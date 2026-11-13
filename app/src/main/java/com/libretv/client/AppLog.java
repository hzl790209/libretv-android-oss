package com.libretv.client;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 应用级日志：分级、内存环形缓冲 + 按天落盘、自动滚动删除。
 *
 * 与 LocalServer 的 libretv-node.log（Node 进程原始 stdout）互补：
 * 这里记录的是客户端行为与关键结论（服务阶段、页面加载、登录、源注入、
 * 自检、导入导出、异常），导出时可以把两份合在一起。
 *
 * 只写应用私有目录，不需要任何存储权限。
 */
public class AppLog {

    private static final String PREFIX = "app-";
    private static final String SUFFIX = ".log";
    private static final int MAX_LINES = 2000;      // 内存缓冲，避免长时间运行无界增长
    private static final int KEEP_DAYS = 7;
    private static final int MAX_EXPORT = 256 * 1024;

    private static AppLog instance;

    private final File dir;
    private final ArrayDeque<String> ring = new ArrayDeque<String>();
    private final SimpleDateFormat stamp = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.CHINA);
    private final SimpleDateFormat day = new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA);

    /** 待落盘队列。主线程只做 offer，实际 open/write/close 全部由 writer 线程批量完成。
     *  原来每条日志都在调用线程上开一次 FileOutputStream —— 页面加载、进度上报这类
     *  主线程高频路径上会累积成秒级卡顿，严重时整页无响应。 */
    private final java.util.concurrent.ArrayBlockingQueue<String> pending =
            new java.util.concurrent.ArrayBlockingQueue<String>(4096);
    private final Thread writer;

    public static synchronized AppLog get(Context ctx) {
        if (instance == null) instance = new AppLog(ctx.getApplicationContext());
        return instance;
    }

    private AppLog(Context ctx) {
        dir = new File(ctx.getFilesDir(), "logs");
        if (!dir.exists()) dir.mkdirs();
        cleanOld();
        writer = new Thread(new Runnable() {
            @Override public void run() {
                StringBuilder batch = new StringBuilder(16384);
                while (true) {
                    try {
                        String first = pending.take();
                        batch.setLength(0);
                        batch.append(first).append('\n');
                        // 攒一小批再一次写盘，减少系统调用次数
                        String more;
                        while (batch.length() < 16384 && (more = pending.poll()) != null) {
                            batch.append(more).append('\n');
                        }
                        appendToFile(batch.toString());
                    } catch (InterruptedException e) {
                        return;
                    } catch (Exception ignored) {
                        // 写盘失败不能影响主流程
                    }
                }
            }
        }, "applog-writer");
        writer.setDaemon(true);
        writer.start();
    }

    public synchronized void i(String tag, String msg) { write("I", tag, msg); }
    public synchronized void w(String tag, String msg) { write("W", tag, msg); }
    public synchronized void e(String tag, String msg) { write("E", tag, msg); }

    private void write(String level, String tag, String msg) {
        String clean = msg == null ? "" : msg.replace('\n', ' ').replace('\r', ' ');
        if (clean.length() > 600) clean = clean.substring(0, 600) + "…";
        String line = stamp.format(new Date()) + " " + level + "/" + tag + ": " + clean;
        ring.addLast(line);
        while (ring.size() > MAX_LINES) ring.removeFirst();
        // 队列满就丢弃：日志是排障辅助，绝不能反过来拖慢界面
        pending.offer(line);
    }

    private void appendToFile(String chunk) {
        File f = new File(dir, PREFIX + day.format(new Date()) + SUFFIX);
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(f, true);
            out.write(chunk.getBytes("UTF-8"));
        } catch (Exception ignored) {
        } finally {
            if (out != null) try { out.close(); } catch (Exception ignored) { }
        }
    }

    /** 最近 n 行，倒序不需要，按时间正序返回。 */
    public synchronized String tail(int n) {
        List<String> all = new ArrayList<String>(ring);
        int from = Math.max(0, all.size() - n);
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < all.size(); i++) sb.append(all.get(i)).append('\n');
        return sb.toString();
    }

    /** 全部落盘日志（按天文件顺序），用于导出。 */
    public synchronized String all() {
        StringBuilder sb = new StringBuilder();
        for (File f : logFiles()) {
            sb.append("===== ").append(f.getName()).append(" =====\n");
            sb.append(readText(f)).append('\n');
            if (sb.length() > MAX_EXPORT) break;
        }
        return sb.toString();
    }

    public synchronized void clear() {
        ring.clear();
        for (File f : logFiles()) f.delete();
        i("log", "日志已清空");
    }

    private List<File> logFiles() {
        File[] fs = dir.listFiles();
        if (fs == null) return Collections.emptyList();
        List<File> out = new ArrayList<File>();
        for (File f : fs) {
            if (f.isFile() && f.getName().startsWith(PREFIX) && f.getName().endsWith(SUFFIX)) out.add(f);
        }
        Collections.sort(out);
        return out;
    }

    private String readText(File f) {
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(f, "r");
            long len = raf.length();
            long start = Math.max(0, len - MAX_EXPORT);
            raf.seek(start);
            byte[] buf = new byte[(int) (len - start)];
            raf.readFully(buf);
            return new String(buf, "UTF-8");
        } catch (Exception e) {
            return "（读取失败：" + e.getMessage() + "）";
        } finally {
            if (raf != null) try { raf.close(); } catch (Exception ignored) { }
        }
    }

    /** 删除 KEEP_DAYS 之前的日志文件；yyyy-MM-dd 文件名可直接按字典序比较。 */
    private void cleanOld() {
        String cutoff = day.format(new Date(System.currentTimeMillis() - KEEP_DAYS * 86400000L));
        for (File f : logFiles()) {
            String name = f.getName();
            String d = name.substring(PREFIX.length(), name.length() - SUFFIX.length());
            if (d.compareTo(cutoff) < 0) f.delete();
        }
    }

    public File directory() { return dir; }
}
