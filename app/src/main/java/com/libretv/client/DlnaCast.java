package com.libretv.client;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.net.DatagramPacket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * DLNA / UPnP AVTransport 投屏控制器（纯 Android framework，无 androidx / 无 Google Cast SDK）。
 *
 * 国内智能电视、电视盒子、智慧屏基本都实现了 UPnP MediaRenderer：
 *  1) SSDP M-SEARCH 组播发现局域网设备；
 *  2) GET LOCATION 指向的设备描述 XML，取出 AVTransport 的 controlURL；
 *  3) SOAP 调 SetAVTransportURI 把视频直链推给电视，再调 Play / Pause / Stop / Seek。
 *
 * 与「系统屏幕镜像」的区别：这是真正的推流投屏，手机可以继续干别的、可以息屏，
 * 电视直接用自己的播放器拉流，画质与耗电都更好。
 *
 * 所有网络操作都在后台线程；Callback 一律回主线程。
 */
public class DlnaCast {

    private static final String SSDP_ADDR = "239.255.255.250";
    private static final int SSDP_PORT = 1900;
    private static final String AVT = "urn:schemas-upnp-org:service:AVTransport:1";

    /** 发现到的渲染器 */
    public static class Device {
        public final String name;
        public final String location;
        public final String controlUrl;
        public final String host;

        Device(String name, String location, String controlUrl) {
            this.name = name;
            this.location = location;
            this.controlUrl = controlUrl;
            String h = "";
            try { h = new URL(controlUrl).getHost(); } catch (Exception ignored) { }
            this.host = h;
        }

        public String label() {
            return name + (host.isEmpty() ? "" : "  (" + host + ")");
        }
    }

    public interface Callback {
        void onDevices(List<Device> devices);
        void onStatus(String message);
        void onResult(boolean ok, String message);
    }

    private final Context app;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final List<Device> devices = new ArrayList<Device>();
    private volatile Device current;

    public DlnaCast(Context ctx) {
        this.app = ctx.getApplicationContext();
    }

    public Device current() { return current; }

    public void shutdown() {
        try { pool.shutdownNow(); } catch (Exception ignored) { }
    }

    // ---------------------------------------------------------------- 设备发现

    public void discover(final Callback cb) {
        pool.execute(new Runnable() {
            @Override public void run() {
                status(cb, "正在搜索同一 Wi-Fi 下的电视 / 盒子…");
                WifiManager.MulticastLock lock = null;
                try {
                    WifiManager wm = (WifiManager) app.getSystemService(Context.WIFI_SERVICE);
                    if (wm != null) {
                        lock = wm.createMulticastLock("libretv-dlna");
                        lock.setReferenceCounted(true);
                        lock.acquire();
                    }
                } catch (Exception ignored) { }

                final List<String> locations = new ArrayList<String>();
                MulticastSocket sock = null;
                try {
                    sock = new MulticastSocket(0);
                    sock.setTimeToLive(4);
                    sock.setSoTimeout(1100);
                    InetAddress group = InetAddress.getByName(SSDP_ADDR);
                    try { sock.joinGroup(group); } catch (Exception ignored) { }

                    // 三种 ST 都发：不同电视对 M-SEARCH 的匹配规则不一样
                    String[] sts = {
                            "urn:schemas-upnp-org:device:MediaRenderer:1",
                            "ssdp:all",
                            "upnp:rootdevice"
                    };
                    for (String st : sts) {
                        String req = "M-SEARCH * HTTP/1.1\r\n"
                                + "HOST: " + SSDP_ADDR + ":" + SSDP_PORT + "\r\n"
                                + "MAN: \"ssdp:discover\"\r\n"
                                + "MX: 2\r\n"
                                + "ST: " + st + "\r\n\r\n";
                        byte[] b = req.getBytes("UTF-8");
                        sock.send(new DatagramPacket(b, b.length, group, SSDP_PORT));
                    }

                    long deadline = System.currentTimeMillis() + 4200;
                    byte[] buf = new byte[4096];
                    while (System.currentTimeMillis() < deadline) {
                        try {
                            DatagramPacket p = new DatagramPacket(buf, buf.length);
                            sock.receive(p);
                            String s = new String(p.getData(), 0, p.getLength(), "UTF-8");
                            String loc = header(s, "LOCATION");
                            if (loc != null && !locations.contains(loc)) locations.add(loc);
                        } catch (SocketTimeoutException ignored) {
                            // 继续等到总时限
                        } catch (Exception ignored) {
                            break;
                        }
                    }
                } catch (Exception e) {
                    status(cb, "组播搜索失败：" + shortMsg(e));
                } finally {
                    if (sock != null) try { sock.close(); } catch (Exception ignored) { }
                }

                List<Device> found = new ArrayList<Device>();
                for (String loc : locations) {
                    Device d = describe(loc);
                    if (d != null) found.add(d);
                }
                synchronized (devices) {
                    devices.clear();
                    devices.addAll(found);
                }
                final List<Device> out = new ArrayList<Device>(found);
                ui.post(new Runnable() {
                    @Override public void run() { cb.onDevices(out); }
                });
                if (lock != null) try { lock.release(); } catch (Exception ignored) { }
            }
        });
    }

    private static String header(String raw, String key) {
        String[] lines = raw.split("\\r?\\n");
        String prefix = key.toLowerCase() + ":";
        for (String line : lines) {
            if (line.toLowerCase().startsWith(prefix)) {
                return line.substring(prefix.length()).trim();
            }
        }
        return null;
    }

    /** 解析设备描述 XML，只取 friendlyName、URLBase 与 AVTransport 的 controlURL。 */
    private static Device describe(String location) {
        try {
            String xml = httpGet(location, 6000);
            XmlPullParser p = Xml.newPullParser();
            p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
            p.setInput(new StringReader(xml));

            String friendly = null, urlBase = null, chosen = null;
            boolean inService = false, avt = false, serviceControl = false;
            String control = null;
            String tag = null;
            StringBuilder text = new StringBuilder();
            int ev;
            while ((ev = p.next()) != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    tag = local(p.getName());
                    text.setLength(0);
                    if ("service".equals(tag)) {
                        inService = true;
                        avt = false;
                        control = null;
                    }
                } else if (ev == XmlPullParser.TEXT && tag != null) {
                    text.append(p.getText());
                } else if (ev == XmlPullParser.END_TAG) {
                    String end = local(p.getName());
                    String val = text.toString().trim();
                    if ("friendlyName".equals(end) && friendly == null) friendly = val;
                    else if ("URLBase".equals(end) && urlBase == null) urlBase = val;
                    else if ("serviceType".equals(end) && inService
                            && val.contains("AVTransport")) avt = true;
                    else if ("controlURL".equals(end) && inService) {
                        control = val;
                        serviceControl = true;
                    } else if ("service".equals(end) && inService) {
                        if (avt && serviceControl && control != null && chosen == null) {
                            chosen = control;
                        }
                        inService = false;
                    }
                    tag = null;
                    text.setLength(0);
                }
            }
            if (chosen == null) return null;
            String base = urlBase != null && urlBase.length() > 0 ? urlBase : location;
            String ctl = absolute(base, chosen);
            if (ctl == null) ctl = absolute(location, chosen);
            if (ctl == null) return null;
            String name = friendly == null || friendly.isEmpty() ? "电视 / 盒子" : friendly;
            return new Device(name, location, ctl);
        } catch (Exception e) {
            return null;
        }
    }

    private static String local(String n) {
        if (n == null) return "";
        int i = n.indexOf(':');
        return i >= 0 ? n.substring(i + 1) : n;
    }

    private static String absolute(String base, String path) {
        if (path == null) return null;
        path = path.trim();
        String low = path.toLowerCase();
        if (low.startsWith("http://") || low.startsWith("https://")) return path;
        try {
            URL u = new URL(base);
            if (path.startsWith("/")) {
                return u.getProtocol() + "://" + u.getAuthority() + path;
            }
            String p = u.getPath();
            String dir = p.endsWith("/") ? p : p.substring(0, p.lastIndexOf('/') + 1);
            return u.getProtocol() + "://" + u.getAuthority() + dir + path;
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- 播放控制

    public void play(final Device d, final String url, final String title, final Callback cb) {
        pool.execute(new Runnable() {
            @Override public void run() {
                try {
                    status(cb, "正在把视频送到 " + d.name + "…");
                    soap(d, "SetAVTransportURI", setUriBody(url, title));
                    try { Thread.sleep(350); } catch (InterruptedException ignored) { }
                    soap(d, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>");
                    current = d;
                    result(cb, true, "已投屏到 " + d.name);
                } catch (final Exception e) {
                    result(cb, false, "投屏失败：" + shortMsg(e));
                }
            }
        });
    }

    public void pause(final Callback cb) {
        final Device d = current;
        if (d == null) { result(cb, false, "当前没有投屏设备"); return; }
        pool.execute(new Runnable() {
            @Override public void run() {
                try {
                    soap(d, "Pause", "<InstanceID>0</InstanceID>");
                    result(cb, true, "电视已暂停");
                } catch (Exception e) { result(cb, false, "暂停失败：" + shortMsg(e)); }
            }
        });
    }

    /** 从暂停恢复播放：与 play 的唯一区别是不重设 URI。 */
    public void resume(final Callback cb) {
        final Device d = current;
        if (d == null) { result(cb, false, "当前没有投屏设备"); return; }
        pool.execute(new Runnable() {
            @Override public void run() {
                try {
                    soap(d, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>");
                    result(cb, true, "电视继续播放");
                } catch (Exception e) { result(cb, false, "继续播放失败：" + shortMsg(e)); }
            }
        });
    }

    public void stop(final Callback cb) {
        final Device d = current;
        pool.execute(new Runnable() {
            @Override public void run() {
                if (d == null) { current = null; result(cb, true, "已停止投屏"); return; }
                boolean ok = true;
                String msg = "已停止投屏";
                try {
                    soap(d, "Stop", "<InstanceID>0</InstanceID>");
                } catch (Exception e) {
                    /* 停不掉的最常见原因：电视已经断了 / 换了 IP / 退出了投屏界面。
                       这时本地状态照样要清，否则手机端永远显示「投屏中」。 */
                    ok = false;
                    msg = "停止指令发送失败（电视可能已断开）：" + shortMsg(e);
                }
                /* Stop 之后小米 / 华为智慧屏等机型仍会停在最后一帧画面，
                   必须再推一次空 URI 才会真正退出投屏界面。失败不计较 ——
                   有些电视对空 URI 直接返回 402，属于正常。 */
                try {
                    soap(d, "SetAVTransportURI", "<InstanceID>0</InstanceID>"
                            + "<CurrentURI></CurrentURI><CurrentURIMetaData></CurrentURIMetaData>");
                } catch (Exception ignored) { }
                current = null;
                result(cb, ok, msg);
            }
        });
    }

    /** 把进度同步到电视（REL_TIME 格式 HH:MM:SS）。 */
    public void seek(final double seconds, final Callback cb) {
        final Device d = current;
        if (d == null) { result(cb, false, "当前没有投屏设备"); return; }
        pool.execute(new Runnable() {
            @Override public void run() {
                long s = (long) Math.max(0, seconds);
                String t = String.format(java.util.Locale.CHINA, "%02d:%02d:%02d",
                        s / 3600, (s % 3600) / 60, s % 60);
                try {
                    soap(d, "Seek", "<InstanceID>0</InstanceID><Unit>REL_TIME</Unit>"
                            + "<Target>" + t + "</Target>");
                    result(cb, true, "已跳到 " + t);
                } catch (Exception e) {
                    /* 部分电视只支持 TRACK_NR 模式，对 REL_TIME 直接返 710/711。
                       如实报出来，用户才知道是电视不支持而不是没点中。 */
                    result(cb, false, "电视不支持跳到指定时间：" + shortMsg(e));
                }
            }
        });
    }

    /** 电视端状态快照：position / duration 为秒（拿不到给 -1），state 为 UPnP 的 CurrentTransportState。 */
    public interface Poll {
        void onInfo(double position, double duration, String state);
    }

    /**
     * 读电视端的真实进度与播放状态。
     * 面板上的进度条与「暂停 / 继续」按钮都靠它 —— 不读的话，用户在电视遥控器上按了暂停，
     * 手机上还是显示「暂停」，按下去等于再发一次 Pause，看起来就是「控制不生效」。
     *
     * 两条请求都失败时回调 onInfo(-1, -1, null)，由调用方决定是否停止轮询。
     */
    public void poll(final Poll cb) {
        final Device d = current;
        if (d == null) return;
        pool.execute(new Runnable() {
            @Override public void run() {
                double pos = -1, dur = -1;
                String state = null;
                try {
                    String r = soap(d, "GetPositionInfo",
                            "<InstanceID>0</InstanceID><Channel>Master</Channel>");
                    pos = hms(tag(r, "RelTime"));
                    dur = hms(tag(r, "TrackDuration"));
                } catch (Exception ignored) { }
                try {
                    String r2 = soap(d, "GetTransportInfo",
                            "<InstanceID>0</InstanceID><Channel>Master</Channel>");
                    state = tag(r2, "CurrentTransportState");
                } catch (Exception ignored) { }
                final double p = pos, du = dur;
                final String st = state;
                ui.post(new Runnable() {
                    @Override public void run() { cb.onInfo(p, du, st); }
                });
            }
        });
    }

    /** 取某个 XML 标签的文本内容（不区分命名空间前缀）。 */
    private static String tag(String xml, String name) {
        if (xml == null) return null;
        try {
            int i = xml.indexOf("<" + name + ">");
            if (i < 0) {
                /* 有些电视会带命名空间前缀：<u:RelTime> */
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("<(?:[\\w.-]+:)?" + name + ">([^<]*)</")
                        .matcher(xml);
                return m.find() ? m.group(1).trim() : null;
            }
            int j = xml.indexOf("</" + name + ">", i);
            if (j < 0) return null;
            return xml.substring(i + name.length() + 2, j).trim();
        } catch (Exception e) {
            return null;
        }
    }

    /** UPnP 的 H+:MM:SS / H:MM:SS(.f0) 时长转秒；NOT_IMPLEMENTED 之类返回 -1。 */
    private static double hms(String t) {
        if (t == null || t.isEmpty()) return -1;
        try {
            String[] p = t.split(":");
            if (p.length != 3) return -1;
            double h = Double.parseDouble(p[0].trim());
            double m = Double.parseDouble(p[1].trim());
            double s = Double.parseDouble(p[2].trim());
            if (h < 0 || m < 0 || s < 0) return -1;
            return h * 3600 + m * 60 + s;
        } catch (Exception e) {
            return -1;
        }
    }

    private static String setUriBody(String url, String title) {
        String meta = "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" "
                + "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" "
                + "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">"
                + "<item id=\"0\" parentID=\"-1\" restricted=\"1\">"
                + "<dc:title>" + esc(title) + "</dc:title>"
                + "<upnp:class>object.item.videoItem.movie</upnp:class>"
                + "<res protocolInfo=\"http-get:*:*/*:DLNA.ORG_OP=01;DLNA.ORG_CI=0\">"
                + esc(url) + "</res>"
                + "</item></DIDL-Lite>";
        // CurrentURIMetaData 是 SOAP 里的文本内容，整段 XML 需要再转义一层
        return "<InstanceID>0</InstanceID><CurrentURI>" + esc(url) + "</CurrentURI>"
                + "<CurrentURIMetaData>" + esc(meta) + "</CurrentURIMetaData>";
    }

    private static String envelope(String action, String inner) {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" "
                + "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">"
                + "<s:Body><u:" + action + " xmlns:u=\"" + AVT + "\">"
                + inner + "</u:" + action + "></s:Body></s:Envelope>";
    }

    private static String soap(Device d, String action, String inner) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(d.controlUrl).openConnection();
        try {
            c.setRequestMethod("POST");
            c.setConnectTimeout(5000);
            c.setReadTimeout(8000);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"");
            c.setRequestProperty("SOAPACTION", "\"" + AVT + "#" + action + "\"");
            c.setRequestProperty("User-Agent", "LibreTV Android DLNA/1.0");
            byte[] b = envelope(action, inner).getBytes("UTF-8");
            c.setFixedLengthStreamingMode(b.length);
            OutputStream os = c.getOutputStream();
            os.write(b);
            os.close();
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String resp = in == null ? "" : readAll(in);
            if (code >= 400) throw new IOException("HTTP " + code + " " + brief(resp));
            if (resp.contains("<Fault>") || resp.contains("UPnPError")) {
                throw new IOException("设备拒绝：" + brief(resp));
            }
            return resp;
        } finally {
            c.disconnect();
        }
    }

    private static String brief(String s) {
        String t = s.replaceAll("\\s+", " ").trim();
        int i = t.toLowerCase().indexOf("<errorcode>");
        if (i >= 0) return t.substring(i, Math.min(t.length(), i + 80));
        return t.length() > 160 ? t.substring(0, 160) : t;
    }

    private static String shortMsg(Exception e) {
        String m = e.getMessage();
        if (m == null || m.isEmpty()) m = e.getClass().getSimpleName();
        return m.length() > 160 ? m.substring(0, 160) : m;
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    private static String httpGet(String url, int timeout) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(timeout);
            c.setReadTimeout(timeout);
            c.setRequestProperty("User-Agent", "LibreTV Android DLNA/1.0");
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            return in == null ? "" : readAll(in);
        } finally {
            c.disconnect();
        }
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        try { in.close(); } catch (Exception ignored) { }
        return new String(out.toByteArray(), "UTF-8");
    }

    private void status(final Callback cb, final String msg) {
        ui.post(new Runnable() {
            @Override public void run() { cb.onStatus(msg); }
        });
    }

    private void result(final Callback cb, final boolean ok, final String msg) {
        ui.post(new Runnable() {
            @Override public void run() { cb.onResult(ok, msg); }
        });
    }
}
