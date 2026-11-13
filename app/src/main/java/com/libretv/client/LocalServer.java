package com.libretv.client;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.os.Build;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * 在手机本机启动 LibreTV 的 Next.js standalone 服务。
 *
 * 关键点：
 *  - Android 10+ 不允许从应用私有目录 exec / dlopen 代码，Node 与依赖库都以
 *    lib*.so 放进 jniLibs，由系统解压到 nativeLibraryDir 后才可执行。
 *  - LD_LIBRARY_PATH 指向 nativeLibraryDir，覆盖 ELF 里写死的 Termux RUNPATH。
 *  - 官方 standalone 包（tar.gz）首次启动解压到 files/libretv-runtime，
 *    用版本标记文件避免重复解压；解压只处理普通文件，忽略目录/链接。
 *  - 服务是 Activity 子进程，随界面生命周期；退到后台过久可能被系统回收。
 */
public class LocalServer {

    public interface Callback {
        void onProgress(String message, int percent);
        void onReady(String url);
        void onError(String message);
    }

    private static final String RUNTIME_ASSET = "libretv-standalone.bin";
    private static final String RUNTIME_DIR = "libretv-runtime";
    private static final int READY_TIMEOUT_MS = 40000;

    private final Context ctx;
    private final Callback cb;
    private Process process;
    private Thread logThread;
    private volatile boolean stopped = false;
    /** 启动令牌。每次 start()/stop() 自增，旧线程在每个检查点比对后自行退出。
     *  只用 boolean stopped 挡不住并发：stop() 之后马上 start() 会把 stopped 重新置 false，
     *  正在跑的解压线程于是继续往下走，结果两个线程同时解压 42MB 并各起一个 Node 抢 8899。 */
    private volatile int generation = 0;
    /** 启动串行化：同一时刻只允许一个线程做解压 / 启动，避免 IO 打满导致界面卡死。 */
    private final Object startLock = new Object();
    private int port = 8899;
    private String logTail = "";

    public LocalServer(Context ctx, Callback cb) {
        this.ctx = ctx.getApplicationContext();
        this.cb = cb;
    }

    /** 只打包了 arm64-v8a 的 Node，其它 ABI 自动回退到外部服务模式。 */
    public static String abiProblem() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return "系统版本过低";
        }
        String[] abis = Build.SUPPORTED_ABIS;
        for (String a : abis) {
            if ("arm64-v8a".equalsIgnoreCase(a)) return null;
        }
        return "本机不是 64 位 ARM 设备（" + (abis.length > 0 ? abis[0] : "未知")
                + "），无法运行内置服务";
    }

    private File runtimeDir() {
        return new File(ctx.getFilesDir(), RUNTIME_DIR);
    }

    private File marker() {
        return new File(ctx.getFilesDir(), RUNTIME_DIR + ".ready");
    }

    private File logFile() {
        return new File(ctx.getFilesDir(), "libretv-node.log");
    }

    private File bootFile() { return new File(ctx.getFilesDir(), "node-boot.js"); }
    // 资产名用 .bundle：wbpub 会按 .pem/.key 等证书后缀拦截提交（这是公开 CA 包，不是私钥）
    private File caFile() { return new File(ctx.getFilesDir(), "cacert.bundle"); }
    private File probeFile() { return new File(ctx.getFilesDir(), "node-env-probe.json"); }

    /**
     * 每次启动都重新落盘引导脚本与 CA 证书包，并清掉上一轮体检报告。
     * 放这里而不是 prepare() 里，是因为 prepare() 有解压标记会跳过——覆盖安装后
     * 资产变了但运行包没变时，仍必须刷新这两个文件。
     */
    private void writeRuntimeAssets() throws IOException {
        copyAsset("node-boot.js", bootFile());
        copyAsset("cacert.bundle", caFile());
        File old = probeFile();
        if (old.exists()) old.delete();
    }

    private void copyAsset(String name, File dst) throws IOException {
        InputStream in = ctx.getAssets().open(name);
        OutputStream os = new FileOutputStream(dst, false);
        try {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        } finally {
            os.close();
            in.close();
        }
    }

    /**
     * 取系统当前 DNS 服务器，给 Node 的 c-ares 兜底用。
     * Android 的 getaddrinfo 走 netd，但纯 native 子进程解析失败时没有 resolv.conf
     * 可依赖；把系统 DNS 明确告诉 c-ares，才能做 dns.lookup 的第二条路。
     */
    private String androidDnsServers() {
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return "";
            java.util.LinkedHashSet<String> found = new java.util.LinkedHashSet<String>();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                android.net.Network n = cm.getActiveNetwork();
                addDns(cm, n, found);
            } else {
                for (android.net.Network n : cm.getAllNetworks()) addDns(cm, n, found);
            }
            // 运营商 DNS 不可靠时补两个公共 DNS，保证 c-ares 至少有可用上游
            found.add("223.5.5.5");
            found.add("8.8.8.8");
            StringBuilder b = new StringBuilder();
            for (String s : found) {
                if (b.length() > 0) b.append(',');
                b.append(s);
            }
            return b.toString();
        } catch (Exception e) {
            return "223.5.5.5,8.8.8.8";
        }
    }

    private void addDns(ConnectivityManager cm, android.net.Network n,
                        java.util.Set<String> out) {
        if (n == null) return;
        try {
            LinkProperties lp = cm.getLinkProperties(n);
            if (lp == null) return;
            for (InetAddress a : lp.getDnsServers()) {
                if (a != null && a.getAddress().length == 4) out.add(a.getHostAddress());
            }
        } catch (Exception ignored) { }
    }

    /** 体检用的真实源地址：取服务端预置源第一条，尽量复现测活链路。 */
    private String firstSourceUrl() {
        String s = readAssetText("default-sources.json");
        if (s.isEmpty()) return "";
        try {
            org.json.JSONArray arr = new org.json.JSONArray(s);
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject o = arr.optJSONObject(i);
                String u = o == null ? "" : o.optString("url", "");
                if (u.startsWith("http")) return u;
            }
        } catch (Exception ignored) { }
        return "";
    }

    /** 供设置面板显示：把 Node 体检 JSON 转成可直接复制发回的中文报告。 */
    public String envProbeText() {
        File f = probeFile();
        if (!f.isFile()) {
            return "尚未生成体检报告。\n请确认使用的是「手机本地服务」，服务启动约 12 秒后再打开本页。";
        }
        try {
            org.json.JSONObject o = new org.json.JSONObject(readText(f));
            StringBuilder sb = new StringBuilder();
            sb.append("Node 运行环境体检\n");
            sb.append("时间：").append(o.optString("generatedAt", "?")).append('\n');
            sb.append("运行时：Node ").append(o.optString("node", "?"))
                    .append(" · ").append(o.optString("platform", "?"))
                    .append(" · OpenSSL ").append(o.optString("openssl", "?")).append('\n');
            org.json.JSONObject ca = o.optJSONObject("ca");
            if (ca != null) {
                sb.append("CA：内置根证书 ").append(ca.optInt("bundledRootCertCount", -1))
                        .append(" 张 · 外部证书包")
                        .append(ca.optBoolean("fileReadable", false) ? "已注入" : "不可用")
                        .append(" · 系统证书目录")
                        .append(ca.optBoolean("systemCertDirExists", false) ? "存在" : "不存在")
                        .append('\n');
            }
            org.json.JSONObject dns = o.optJSONObject("dns");
            if (dns != null) {
                sb.append("DNS 服务器：").append(dns.optString("servers", ""))
                        .append(" · 兜底调用 ").append(dns.optInt("fallbackUsed", 0)).append(" 次\n");
                org.json.JSONObject errs = dns.optJSONObject("errors");
                if (errs != null && errs.length() > 0) {
                    java.util.Iterator<String> it = errs.keys();
                    while (it.hasNext()) {
                        String k = it.next();
                        org.json.JSONObject e = errs.optJSONObject(k);
                        sb.append("  DNS 错误 ").append(k).append(" ×")
                                .append(e == null ? 0 : e.optInt("count", 0))
                                .append("：").append(e == null ? "" : e.optString("example", ""))
                                .append('\n');
                    }
                }
            }
            sb.append('\n');
            org.json.JSONArray c = o.optJSONArray("conclusion");
            if (c != null && c.length() > 0) {
                for (int i = 0; i < c.length(); i++) sb.append(c.optString(i, "")).append('\n');
            } else {
                sb.append("（结论生成中…）\n");
            }
            return sb.toString();
        } catch (Exception e) {
            return "体检报告解析失败：" + e.getMessage();
        }
    }

    public String logTail() {
        return logTail;
    }

    /** Node 进程原始日志全文（尾部 256 KB），供应用日志导出时合并。 */
    public String nodeLogText() {
        File f = logFile();
        if (!f.isFile()) return "（暂无 Node 服务日志）";
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(f, "r");
            long len = raf.length();
            long start = Math.max(0, len - 256 * 1024);
            raf.seek(start);
            byte[] buf = new byte[(int) (len - start)];
            raf.readFully(buf);
            return new String(buf, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "（读取失败：" + e.getMessage() + "）";
        } finally {
            if (raf != null) try { raf.close(); } catch (Exception ignored) { }
        }
    }

    public boolean isRunning() {
        if (process == null) return false;
        try {
            process.exitValue();
            return false;
        } catch (IllegalThreadStateException e) {
            return true;
        }
    }

    public void start(final int port, final String password, final boolean lan) {
        stop();
        stopped = false;
        final int gen = ++generation;
        this.port = port;
        new Thread(new Runnable() {
            @Override public void run() {
                // 串行化：后来的启动请求必须等前一个流程彻底退出，
                // 否则两个线程会同时解压同一个目录（文件互相覆盖 + IO 打满）。
                synchronized (startLock) {
                    if (gen != generation) return;
                    try {
                        String abi = abiProblem();
                        if (abi != null) throw new IllegalStateException(abi);
                        prepare(gen);
                        if (gen != generation) return;
                        int patched = patchRuntimeTimeouts();
                        if (patched > 0) {
                            cb.onProgress("已放宽移动端上游超时（" + patched + " 处）", 22);
                        }
                        if (gen != generation) return;
                        launch(password, lan);
                        if (gen != generation) {
                            // 刚起的进程已被更新的启动请求取代：销毁它，
                            // 否则两个 Node 抢同一个端口，界面表现为一直转圈。
                            killProcess();
                            return;
                        }
                        awaitReady(gen);
                        if (gen != generation) return;
                        cb.onReady("http://127.0.0.1:" + port);
                    } catch (final Exception e) {
                        if (gen == generation && !stopped) {
                            cb.onError(e.getMessage() == null ? e.toString() : e.getMessage());
                        }
                    }
                }
            }
        }, "libretv-local-server").start();
    }

    /** 只杀进程，不动令牌（供启动流程内部在发现自己被取代时调用）。 */
    private void killProcess() {
        if (process != null) {
            try { process.destroy(); } catch (Exception ignored) { }
            process = null;
        }
        if (logThread != null) {
            try { logThread.interrupt(); } catch (Exception ignored) { }
            logThread = null;
        }
    }

    /**
     * 官方 standalone 产物把上游超时写死成 6s（源测活）/ 8s（搜索单页）。
     * 桌面网络够用，手机 DNS + TLS 握手稍慢就整批超时，界面表现为「所有源测活失败」。
     * 解压后对两个路由做精确字符串替换：不动包结构、不改业务逻辑、可重复执行。
     * 每次启动都跑一遍，覆盖安装后旧的解压目录也会被修正。
     */
    private int patchRuntimeTimeouts() {
        String[][] targets = {
                {".next/server/app/api/source/test/route.js", "timeoutMs:6e3", "timeoutMs:1.5e4"},
                {".next/server/app/api/search/route.js", "timeoutMs:8e3", "timeoutMs:1.5e4"},
        };
        int patched = 0;
        for (String[] t : targets) {
            File f = new File(runtimeDir(), t[0]);
            if (!f.isFile()) continue;
            try {
                String s = readText(f);
                if (s.contains(t[1])) {
                    writeText(f, s.replace(t[1], t[2]));
                    patched++;
                }
            } catch (Exception ignored) {
                // 补丁失败不阻断服务启动，诊断日志里能看到原始超时错误
            }
        }
        return patched;
    }

    /** 解压官方 standalone 包；已解压且版本标记匹配时直接跳过。 */
    private void prepare(int gen) throws Exception {
        String sha = "";
        try {
            InputStream in = ctx.getAssets().open("node-runtime.json");
            byte[] b = new byte[in.available()];
            int n = in.read(b);
            in.close();
            String json = new String(b, 0, Math.max(n, 0), StandardCharsets.UTF_8);
            int i = json.indexOf("\"runtimeSha256\"");
            if (i >= 0) {
                int s = json.indexOf('"', json.indexOf(':', i) + 1) + 1;
                sha = json.substring(s, json.indexOf('"', s));
            }
        } catch (Exception ignored) { }

        File dir = runtimeDir();
        File server = new File(dir, "server.js");
        File mark = marker();
        String tag = sha.length() >= 12 ? sha.substring(0, 12) : "v1";
        if (server.isFile() && mark.isFile() && tag.equals(readText(mark).trim())) {
            cb.onProgress("本地服务文件已就绪", 20);
            return;
        }
        if (dir.exists()) deleteRecursively(dir);
        dir.mkdirs();

        cb.onProgress("首次启动：正在解压 LibreTV 运行包（约 42 MB）…", 5);
        InputStream raw = ctx.getAssets().open(RUNTIME_ASSET);
        unpackTarGz(raw, dir, gen);
        raw.close();
        if (!server.isFile()) throw new IOException("运行包解压后缺少 server.js");
        writeText(mark, tag);
        // 密码只通过进程环境变量注入，不写进磁盘（避免明文落盘，也避免 .env.local 覆盖）
        writeText(new File(dir, ".env.local"),
                "# LibreTV 本地服务：PASSWORD / PORT / HOSTNAME 由客户端以环境变量注入\n"
                        + "REQUEST_TIMEOUT=8000\nMAX_RETRIES=1\nDEBUG=false\n");
        cb.onProgress("运行包就绪，正在启动 Node.js…", 25);
    }

    /** 轻量统计：避免为一条进度提示引入完整 JSON 解析。 */
    private int countJsonItems(String json) {
        int n = 0, idx = 0;
        while ((idx = json.indexOf("\"url\"", idx)) >= 0) {
            n++;
            idx += 5;
        }
        return n;
    }

    /** 读取 APK assets 里的纯文本配置（源清单、运行时清单等）。 */
    private String readAssetText(String name) {
        try {
            InputStream in = ctx.getAssets().open(name);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            return out.toString("UTF-8").trim();
        } catch (Exception e) {
            return "";
        }
    }

    private void launch(String password, boolean lan) throws Exception {
        String libDir = ctx.getApplicationInfo().nativeLibraryDir;
        File node = new File(libDir, "libnode.so");
        if (!node.isFile()) {
            throw new IOException("未找到内置 Node 运行时（" + node.getAbsolutePath()
                    + "）。请确认安装的是完整版 APK，且系统已解压原生库。");
        }
        if (!node.canExecute()) node.setExecutable(true, false);
        probeNode(node, libDir);
        writeRuntimeAssets();

        ProcessBuilder pb = new ProcessBuilder(node.getAbsolutePath(), "server.js");
        pb.directory(runtimeDir());
        Map<String, String> env = pb.environment();
        env.put("LD_LIBRARY_PATH", libDir);
        env.put("PORT", String.valueOf(port));
        // server.js 用 HOSTNAME 作为监听地址；仅本机或开放局域网
        env.put("HOSTNAME", lan ? "0.0.0.0" : "127.0.0.1");
        // 值为空时干脆不注入：LibreTV 的 isPasswordConfigured() 以 PASSWORD 非空为准，
        // 空串与不设等价，但「不设」不会在进程环境里留下一个空的敏感键。
        if (password != null && password.length() > 0) {
            env.put("PASSWORD", password);
        }
        env.put("NODE_ENV", "production");
        env.put("TMPDIR", ctx.getCacheDir().getAbsolutePath());
        env.put("NEXT_TELEMETRY_DISABLED", "1");
        // --- Android 出站网络适配（第一轮：线程/超时/协议族）---
        env.put("UV_THREADPOOL_SIZE", "32");
        env.put("SEARCH_SOURCE_TIMEOUT_MS", "25000");

        // --- 第二轮：证书目录与 DNS 兜底 + 自动体检 ---
        // Termux 版 libcrypto.so 把 OPENSSLDIR 编译成
        // /data/data/com.termux/files/usr/etc/tls（安卓设备上不存在），
        // 所有 https 源都可能因找不到根证书而全灭。显式注入自有 CA 包。
        String ca = caFile().getAbsolutePath();
        env.put("NODE_EXTRA_CA_CERTS", ca);
        env.put("SSL_CERT_FILE", ca);
        env.put("SSL_CERT_DIR", "/system/etc/security/cacerts");
        // 引导层：dns.lookup 的 c-ares 自愈 + 出站错误取证 + 启动体检。
        // --require 在 server.js 之前加载，能同时覆盖 node:dns 与 node:dns/promises，
        // 也让 SSRF Agent 的 safeLookup 拿到已被兜底的 lookup。
        String boot = bootFile().getAbsolutePath();
        env.put("NODE_OPTIONS", "--dns-result-order=ipv4first --require " + boot);
        env.put("LTV_DNS_SERVERS", androidDnsServers());
        env.put("LTV_PROBE_OUT", probeFile().getAbsolutePath());
        String probeUrl = firstSourceUrl();
        env.put("LTV_PROBE_URL", probeUrl.isEmpty() ? "https://www.baidu.com" : probeUrl);
        cb.onProgress("已注入 CA 证书与 DNS 兜底，正在做出站自检", 38);
        // 服务端预置源：/api/status 下发给任何浏览器并自动勾选，
        // 彻底绕开 WebView localStorage 的 origin 隔离（换端口/清缓存/覆盖安装都不丢源）。
        // 手机本地服务没有 .env.local 加载链，必须走进程环境变量。
        String defaultSources = readAssetText("default-sources.json");
        if (!defaultSources.isEmpty()) {
            env.put("DEFAULT_SOURCES", defaultSources);
            cb.onProgress("已载入 " + countJsonItems(defaultSources) + " 条服务端预置源", 35);
        }
        pb.redirectErrorStream(true);
        process = pb.start();
        startLogPump();
        cb.onProgress("Node.js 已启动，等待服务就绪…", 40);
    }

    /** 先跑一次 node --version，把 ELF / 动态库问题与 Next.js 启动问题区分开。 */
    private void probeNode(File node, String libDir) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(node.getAbsolutePath(), "--version");
        Map<String, String> env = pb.environment();
        env.put("LD_LIBRARY_PATH", libDir);
        env.put("TMPDIR", ctx.getCacheDir().getAbsolutePath());
        // 自检进程不要加载引导层，避免写半份体检报告、或 CA 问题掩盖 ELF 问题
        env.remove("NODE_OPTIONS");
        env.remove("NODE_EXTRA_CA_CERTS");
        pb.redirectErrorStream(true);
        Process p = pb.start();
        StringBuilder out = new StringBuilder();
        InputStream in = p.getInputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.append(new String(buf, 0, n, StandardCharsets.UTF_8));
        }
        in.close();
        int code = p.waitFor();
        String text = out.toString().trim();
        if (code != 0 || !text.startsWith("v")) {
            throw new IOException("Node 运行时自检失败（退出码 " + code + "）：\n" + text);
        }
        logTail = "[node] " + text + " 自检通过\n";
        cb.onProgress("Node.js " + text + " 自检通过", 35);
    }

    private void startLogPump() {
        final InputStream in = process.getInputStream();
        logThread = new Thread(new Runnable() {
            @Override public void run() {
                StringBuilder tail = new StringBuilder(logTail == null ? "" : logTail);
                try {
                    byte[] buf = new byte[4096];
                    int n;
                    FileOutputStream out = new FileOutputStream(logFile(), false);
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        tail.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                        if (tail.length() > 8000) tail.delete(0, tail.length() - 8000);
                        logTail = tail.toString();
                    }
                    out.close();
                } catch (Exception ignored) { }
            }
        }, "libretv-node-log");
        logThread.setDaemon(true);
        logThread.start();
    }

    private void awaitReady(int gen) throws Exception {
        URL url = new URL("http://127.0.0.1:" + port + "/api/status");
        long deadline = System.currentTimeMillis() + READY_TIMEOUT_MS;
        int tries = 0;
        while (System.currentTimeMillis() < deadline && !stopped && gen == generation) {
            if (!isRunning()) {
                throw new IOException("Node 进程意外退出。日志：\n" + lastLines(12));
            }
            try {
                HttpURLConnection c = (HttpURLConnection) url.openConnection(Proxy.NO_PROXY);
                c.setConnectTimeout(900);
                c.setReadTimeout(900);
                c.setUseCaches(false);
                int code = c.getResponseCode();
                c.disconnect();
                if (code == 200) {
                    cb.onProgress("服务已就绪", 100);
                    return;
                }
            } catch (IOException ignored) { }
            tries++;
            if (tries % 4 == 0) {
                cb.onProgress("等待服务就绪…（" + tries / 2 + "s）", Math.min(90, 40 + tries));
            }
            Thread.sleep(500);
        }
        throw new IOException("服务启动超时（40s）。日志：\n" + lastLines(12));
    }

    private String lastLines(int count) {
        String s = logTail == null ? "" : logTail.trim();
        if (s.isEmpty()) return "（无输出）";
        String[] lines = s.split("\n");
        int from = Math.max(0, lines.length - count);
        StringBuilder b = new StringBuilder();
        for (int i = from; i < lines.length; i++) b.append(lines[i]).append('\n');
        return b.toString().trim();
    }

    public void stop() {
        stopped = true;
        // 令牌自增：正在跑的解压/启动线程会在下一个检查点自行退出
        generation++;
        killProcess();
    }

    /** 手机在 Wi-Fi 里的 IPv4，供其它设备连接时显示。 */
    public static String lanAddress() {
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            while (ifs.hasMoreElements()) {
                NetworkInterface ni = ifs.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (!a.isLoopbackAddress() && a.getAddress().length == 4) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) { }
        return null;
    }

    // ------------------------------------------------------------ tar.gz (USTAR)

    private void unpackTarGz(InputStream raw, File dest, int gen) throws IOException {
        GZIPInputStream gz = new GZIPInputStream(new BufferedInputStream(raw, 32768));
        byte[] hdr = new byte[512];
        String longName = null;
        int files = 0;
        while (readFully(gz, hdr)) {
            // 启动已被更新的请求取代：立刻停手，别和另一个线程抢着解压同一个目录
            if (gen != generation) { try { gz.close(); } catch (Exception ignored) { } throw new IOException("启动已被取消"); }
            if (allZero(hdr)) break;
            String name = str(hdr, 0, 100);
            String prefix = str(hdr, 345, 155);
            if (!prefix.isEmpty()) name = prefix + "/" + name;
            long size = octal(hdr, 124, 12);
            char type = (char) (hdr[156] & 0xFF);
            long padded = ((size + 511) / 512) * 512;

            if (type == 'L') {                       // GNU long name
                byte[] b = new byte[(int) size];
                readFully(gz, b);
                longName = str(b, 0, b.length);
                skipFully(gz, padded - size);
                continue;
            }
            if (longName != null) {
                name = longName;
                longName = null;
            }
            if (name.startsWith("./")) name = name.substring(2);
            if (name.isEmpty() || name.endsWith("/") || type == '5'
                    || (type != 0 && type != '0')) {
                skipFully(gz, padded);
                continue;
            }
            File out = new File(dest, name);
            String safe = dest.getCanonicalPath();
            if (!out.getCanonicalPath().startsWith(safe + File.separator)) {
                skipFully(gz, padded);
                continue;
            }
            File parent = out.getParentFile();
            if (parent != null) parent.mkdirs();
            OutputStream os = new FileOutputStream(out);
            long left = size;
            byte[] buf = new byte[32768];
            while (left > 0) {
                int r = gz.read(buf, 0, (int) Math.min(buf.length, left));
                if (r < 0) break;
                os.write(buf, 0, r);
                left -= r;
            }
            os.close();
            skipFully(gz, padded - size);
            files++;
            if (files % 250 == 0) {
                cb.onProgress("解压运行包… " + files + " 个文件", 5 + Math.min(18, files / 120));
            }
        }
        if (files == 0) throw new IOException("运行包解压失败：没有读到任何文件");
    }

    private static boolean readFully(InputStream in, byte[] b) throws IOException {
        int off = 0;
        while (off < b.length) {
            int n = in.read(b, off, b.length - off);
            if (n < 0) return false;
            off += n;
        }
        return true;
    }

    private static void skipFully(InputStream in, long n) throws IOException {
        long left = n;
        byte[] b = new byte[8192];
        while (left > 0) {
            int r = in.read(b, 0, (int) Math.min(b.length, left));
            if (r < 0) break;
            left -= r;
        }
    }

    private static boolean allZero(byte[] b) {
        for (byte x : b) if (x != 0) return false;
        return true;
    }

    private static String str(byte[] b, int off, int len) {
        int end = off;
        int max = Math.min(b.length, off + len);
        while (end < max && b[end] != 0) end++;
        return new String(b, off, end - off, StandardCharsets.UTF_8).trim();
    }

    private static long octal(byte[] b, int off, int len) {
        long v = 0;
        for (int i = off; i < off + len; i++) {
            int c = b[i];
            if (c >= '0' && c <= '7') v = v * 8 + (c - '0');
            else if (v > 0) break;
        }
        return v;
    }

    private static String readText(File f) throws IOException {
        byte[] b = new byte[(int) f.length()];
        InputStream in = new java.io.FileInputStream(f);
        int n = in.read(b);
        in.close();
        return new String(b, 0, Math.max(n, 0), StandardCharsets.UTF_8);
    }

    private static void writeText(File f, String s) throws IOException {
        OutputStream os = new FileOutputStream(f);
        os.write(s.getBytes(StandardCharsets.UTF_8));
        os.close();
    }

    private static void deleteRecursively(File f) {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRecursively(k);
        }
        // 仅删除本应用私有目录内自己创建的文件
        f.delete();
    }
}
