package com.libretv.client;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.res.ColorStateList;
import android.media.AudioManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * LibreTV 安卓客户端：按移动端交互重做的 WebView 壳。
 *
 * 移动端设计要点：
 *  - 去掉常驻地址栏，改用底部 6 个大触摸目标导航栏（图标 + 文字），日志独立入口常驻可见
 *  - 深色主题、圆角卡片、底部弹出式设置面板，单手可达
 *  - 首次引导 / 连接失败用同一张卡片，按钮 ≥48dp
 *  - 沉浸模式（隐藏底栏与状态栏）看片，浮动按钮一键恢复
 *  - 文件选择、下载、外部链接、桌面模式、移动页面适配
 *  - 全屏播放：网页全屏 + 常驻原生悬浮控制条、自动横屏、屏幕常亮、返回键优先退出
 *  - 首次打开自动合并 APK 内置的全量源配置
 */
public class MainActivity extends Activity implements LocalServer.Callback {

    private static final String PREF = "libretv_client";
    private static final String KEY_URL = "server_url";
    /** 旧版「全局已导入」标记：跨 origin 会误跳过注入，仅保留用于清除遗留值 */
    private static final String KEY_PRESET_IMPORTED_LEGACY = "preset_imported";
    private static final String KEY_DESKTOP = "desktop_mode";
    private static final String KEY_MOBILE_CSS = "mobile_css";
    private static final String KEY_AUTO_LANDSCAPE = "auto_landscape";
    private static final String KEY_PLAYBACK_BAR = "playback_bar";
    private static final String KEY_CONTROL_AUTOHIDE = "control_autohide";
    /** 全屏控制条无操作后自动隐藏的时长，与主流播放器一致 */
    private static final int CONTROL_HIDE_DELAY_MS = 4000;
    /** 抖音式沉浸播放（默认开）：无大控制条、单击暂停、滑动快进/亮度/音量、底部细进度线 */
    private static final String KEY_IMMERSIVE_PLAYER = "immersive_player";
    /** 画面模式：contain 完整（默认）/ cover 铺满裁切 / fill 拉伸 */
    private static final String KEY_FIT_MODE = "fit_mode";
    /** 高级面板 3 秒自动隐藏；另有 onProgress 时间戳兜底，不依赖 Handler 一定触发 */
    private static final int PANEL_HIDE_DELAY_MS = 3000;
    private static final String KEY_LOCAL_MODE = "local_mode";
    private static final String KEY_LAN = "lan_mode";
    private static final String KEY_PORT = "local_port";
    private static final String KEY_PASSWORD = "local_password";
    private static final int DEFAULT_PORT = 8899;
    /**
     * 本地服务的默认访问密码。
     * 开源版默认为空 —— 使用者不需要知道、也不需要输入任何密码。
     *
     * 注意：这里不能把空串真的传给服务端。上游 LibreTV 要求 PASSWORD 必须非空，
     * 为空时 /api/* 一律回 503「服务器未设置 PASSWORD 环境变量」，表现就是
     * 「服务起来了但搜不到视频」。所以「空」只表示用户没设密码，实际注入的是
     * 首次启动时本机生成的随机密码，由 auto-login.js 自动完成登录，见 resolvePassword()。
     */
    private static final String DEFAULT_PASSWORD = "";
    /** 用户没设密码时，本机随机生成的那个密码（仅本设备有效，不上传、不写日志） */
    private static final String KEY_PASSWORD_AUTO = "local_password_auto";
    /** 手机本机自带服务（默认） */
    private static final String DEFAULT_URL = "http://127.0.0.1:8899";
    /** 回退：电脑上已搭好的 LibreTV 服务 */
    private static final String DEFAULT_EXTERNAL_URL = "http://192.168.1.100:8899";
    private static final String DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    private static final int REQ_FILE = 1001;
    private static final int REQ_IMPORT_FILE = 1002;
    private static final int REQ_EXPORT_FILE = 1003;

    private static final int BG = 0xFF0F1115;
    private static final int SURFACE = 0xFF1B1F27;
    private static final int SURFACE2 = 0xFF252B36;
    private static final int ACCENT = 0xFF4C8BF5;
    private static final int TEXT = 0xFFF2F4F8;
    private static final int TEXT_DIM = 0xFF98A2B3;
    private static final int DANGER = 0xFFFF7B7B;

    /**
     * 移动端页面适配：防横向溢出 + 竖屏播放器满宽（去掉桌面栅格的 px-4 与圆角）。
     * object-fit 不在这里写死，统一由 player-touch.js 的 data-ltv-fit 决定（默认 contain 完整显示）。
     */
    private static final String MOBILE_CSS_JS =
            "(function(){try{if(!document.head||document.getElementById('ltv-mobile'))return;"
                    + "var m=document.querySelector('meta[name=viewport]');"
                    + "if(m){var c=m.getAttribute('content')||'';"
                    + "if(c.indexOf('viewport-fit')<0)m.setAttribute('content',c+', viewport-fit=cover');}"
                    + "var s=document.createElement('style');s.id='ltv-mobile';"
                    + "s.textContent=["
                    + "'html,body{max-width:100%!important;overflow-x:hidden!important}',"
                    + "'::-webkit-scrollbar{width:0!important;height:0!important}',"
                    + "'button,a,[role=button]{touch-action:manipulation}',"
                    + "'video{max-width:100%!important}',"
                    + "'@media (orientation:portrait){',"
                    + "'main.max-w-7xl{padding-left:0!important;padding-right:0!important}',"
                    + "'.aspect-video{border-radius:0!important;margin-left:0!important;margin-right:0!important}',"
                    + "'header.sticky{max-width:100%!important}',"
                    + "'}'"
                    + "].join('');"
                    + "document.head.appendChild(s);}catch(e){}})()";

    private SharedPreferences prefs;
    private FrameLayout root;
    private LinearLayout mainBox;
    private LinearLayout bottomBar;
    private WebView web;
    private ProgressBar progress;
    private View scrim;
    private View sheetHost;
    private TextView fabRestore;
    private EditText settingsEdit;
    private CheckBox desktopBox;
    private CheckBox mobileCssBox;
    private CheckBox landscapeBox;
    private CheckBox playbackBarBox;
    private CheckBox localModeBox;
    private CheckBox lanModeBox;
    private EditText portEdit;
    private EditText passwordEdit;
    private TextView serverStatLine;
    private LinearLayout playbackBar;
    private SeekBar playbackSeek;
    private TextView playbackTime;
    private TextView fabFullscreen;
    private boolean hasVideo = false;
    private boolean seekDragging = false;
    private boolean fsFallbackCancelled = false;
    private final Handler controlHandler = new Handler(Looper.getMainLooper());
    private Runnable controlHideTask;
    private float touchDownX, touchDownY;
    /** true = 用户主动切到网页控制栏（倍速 / 清晰度），此时轻触画面不唤出原生条，避免打断操作 */
    private boolean webControlsMode = false;
    private double lastDuration = 0;
    private double lastCurrent = 0;
    private String fitLabel = "完整（有黑边）";
    private TextView statLine;
    /** 设置面板里「一键恢复所有源」下方的实时状态行（当前被自动停用几个源） */
    private TextView restoreHint;

    // ------------------------------------------------ DLNA 投屏
    private DlnaCast cast;
    private boolean castPanelOpen = false;
    /** 最近一次发起 SSDP 搜索的时间：用于忽略 800ms 内的重复触发 */
    private long castDiscoverAt = 0;
    private TextView fabCast;
    private DlnaCast.Device castDevice;
    private String lastStreamUrl = "";
    private String castTitleText = "LibreTV 投屏";
    private android.app.AlertDialog castDialog;
    private final List<DlnaCast.Device> castDevices = new ArrayList<DlnaCast.Device>();
    /** 面板上记录的电视播放状态：true = 已暂停，按钮该显示「继续」 */
    private boolean castPaused = false;
    /** 投屏中轮询电视进度（只在面板打开时跑，避免白白占用局域网） */
    private final Handler castHandler = new Handler(Looper.getMainLooper());
    private boolean castPolling = false;
    private int castPollFails = 0;

    // ------------------------------------------------ 抖音式沉浸播放层
    private View gestureLayer;
    private View progressLine;
    /** 左上角常驻「退出全屏」：全屏后必须有看得见、点得到的退出口 */
    private TextView exitBtn;
    /**
     * 网页已经自己画好了「✕ 退出全屏」和底部细进度线（player-touch.js 的 #ltv-ui）。
     * 为 true 时原生层不再重复显示这两样，避免两个退出口 / 两条进度线。
     * 之所以让网页来画：原生覆盖层在部分机型（华为等）上根本没上屏，
     * 用户连续两轮反馈「全屏后完全找不到退出口」「全屏下看不到进度条」。
     */
    private boolean domUiActive = false;
    private TextView gestureHint;
    private TextView panelToggle;
    private boolean immersiveLayersOn = false;
    private boolean advancedPanelVisible = false;
    private long lastPanelActionMs = 0;
    private Runnable gestureHintTask;
    private final Paint lineBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePlayedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lineShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lineDotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    // 手势状态：0 无 / 1 横向快进 / 2 左半屏亮度 / 3 右半屏音量
    private float gDownX, gDownY, gLastX, gLastY;
    private long gDownTime;
    private int gMode = 0;
    private double gStartSec, gPreviewSec;
    private float gStartBrightness;
    private int gStartVolume, gMaxVolume;

    // 日志中心：底部导航直达，常驻可见、自动刷新、一键分享
    private android.app.AlertDialog logDialog;
    private TextView logBody;
    private TextView logStatus;
    private String lastCombinedLog = "";
    private int logFilter = 0;              // 0 全部 / 1 仅错误 / 2 Node 原始 / 3 体检
    private boolean logAutoRefresh = true;
    private final Handler logHandler = new Handler(Looper.getMainLooper());
    private Runnable logRefreshTask;

    private LinearLayout overlay;
    private TextView overlayTitle;
    private TextView overlayHint;
    private EditText overlayEdit;
    private Button overlayPrimary;
    private Button overlaySecondary;
    private int overlayMode = 0;   // 1=首次/改地址, 2=连接失败

    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;
    private ValueCallback<Uri[]> filePathCallback;

    private long lastBackPress;
    private String currentUrl = "";
    private String baseUa = "";
    private String presetJson = "";
    private String injectTemplate = "";
    private String autoLoginTemplate = "";
    private String diagnoseTemplate = "";
    private String importTemplate = "";
    private String exportTemplate = "";
    private AppLog appLog;
    /** 等待系统文件选择器写入的文本（源配置或日志） */
    private String pendingExportText = "";
    private String pendingExportName = "";
    private boolean importReplaceMode = false;
    private String playerTouchJs = "";
    /** 服务端 /api/status.defaultSources 条数；-1=尚未探测，>0 时不必再往 localStorage 注入 */
    private volatile int serverEnvSourceCount = -1;
    private volatile String probedUrl = "";
    private int presetCount = 0;
    private int presetLiveCount = 0;
    private boolean injecting = false;
    private boolean immersive = false;
    private boolean playbackMode = false;
    private int brightnessStep = 0;
    /** 当前网页 localStorage 里的点播源数量，-1 表示尚未读到 */
    private int pageSourceCount = -1;

    /* --- onPageFinished 去重：同一次导航会被 WebView 回调多次 --- */
    private String lastFinishedUrl = "";
    private long lastFinishedAt = 0;

    /* --- 主线程卡死自检 ---
       UI 线程每秒打一次心跳；后台看门狗发现 5 秒没有心跳就落一条日志，
       把卡死当时的页面与播放状态记下来 —— 用户下次「卡死」能直接看到证据，
       而不是像现在这样日志在卡死前就断了、什么都没留下。 */
    private volatile long mainHeartbeat = System.currentTimeMillis();
    private volatile boolean mainStuck = false;
    private volatile String heartbeatUrl = "";
    private volatile int heartbeatProgress = 100;
    private volatile String heartbeatMode = "";

    private LocalServer localServer;
    private boolean localMode = true;
    private boolean lanMode = false;
    private int localPort = DEFAULT_PORT;
    private String localPassword = DEFAULT_PASSWORD;
    /** 真正注入给服务端、并用于自动登录的密码（用户没设时为随机值） */
    private String effectivePassword = DEFAULT_PASSWORD;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        appLog = AppLog.get(this);
        appLog.i("app", "应用启动 · LibreTV Android " + versionLine());
        prefs = getSharedPreferences(PREF, MODE_PRIVATE);
        // 覆盖安装会保留旧标记；本地服务换了 origin 后必须允许重新注入
        prefs.edit().remove(KEY_PRESET_IMPORTED_LEGACY).apply();
        localMode = prefs.getBoolean(KEY_LOCAL_MODE, true);
        lanMode = prefs.getBoolean(KEY_LAN, false);
        localPort = prefs.getInt(KEY_PORT, DEFAULT_PORT);
        localPassword = prefs.getString(KEY_PASSWORD, DEFAULT_PASSWORD);
        effectivePassword = resolvePassword();
        localServer = new LocalServer(this, this);
        cast = new DlnaCast(this);
        loadPreset();
        playerTouchJs = readAsset("player-touch.js");
        buildUi();
        configureWeb();
        applyUserAgent();
        // 音量键默认调媒体音量，而不是铃声音量
        setVolumeControlStream(AudioManager.STREAM_MUSIC);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(BG);
            getWindow().setNavigationBarColor(SURFACE);
        }

        if (localMode) {
            startLocalService();
        } else {
            String saved = prefs.getString(KEY_URL, "");
            if (saved.isEmpty()) {
                showSetup("连接 LibreTV 服务",
                        "输入电脑或服务器上 LibreTV 的地址，例如 192.168.1.100:8899。\n\n"
                                + "手机与电脑需在同一 Wi-Fi；服务端要绑定 0.0.0.0，"
                                + "不能用 127.0.0.1（那只代表电脑自己）。",
                        DEFAULT_EXTERNAL_URL);
            } else {
                settingsEdit.setText(saved);
                hideOverlay();
                load(saved);
            }
        }
        updateServerStat();
        startMainThreadWatchdog();
    }

    /**
     * 主线程卡死自检。
     *
     * 卡死时日志往往什么都留不下（写日志也要主线程调度），用户只能看到「界面不动了」。
     * 这里用「主线程心跳 + 后台看门狗」把这个盲区补上：
     *  - 主线程每 1s 刷新一次心跳并顺手记录当前页面 / 播放状态；
     *  - 后台线程每 2s 检查，超过 5s 没心跳就落一条 E 级日志，
     *    恢复时再落一条并写明阻塞时长。
     * 所有对 WebView 的读取都在主线程完成（WebView 非线程安全），后台只读 volatile 字段。
     */
    private void startMainThreadWatchdog() {
        final Handler main = new Handler(Looper.getMainLooper());
        Runnable beat = new Runnable() {
            @Override public void run() {
                mainHeartbeat = System.currentTimeMillis();
                try {
                    if (web != null) {
                        heartbeatUrl = web.getUrl() == null ? "" : web.getUrl();
                        heartbeatProgress = web.getProgress();
                    }
                    heartbeatMode = (playbackMode ? "播放中" : "浏览")
                            + (immersive ? "/沉浸" : "")
                            + (webControlsMode ? "/网页栏" : "")
                            + (advancedPanelVisible ? "/面板" : "");
                } catch (Exception ignored) { }
                main.postDelayed(this, 1000);
            }
        };
        main.post(beat);

        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                while (true) {
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException e) {
                        return;
                    }
                    long idle = System.currentTimeMillis() - mainHeartbeat;
                    if (idle > 5000 && !mainStuck) {
                        mainStuck = true;
                        appLog.e("anr", "主线程疑似卡死 " + (idle / 1000) + "s · 模式="
                                + heartbeatMode + " · 进度=" + heartbeatProgress
                                + " · 页面=" + heartbeatUrl);
                    } else if (idle < 2000 && mainStuck) {
                        mainStuck = false;
                        appLog.i("anr", "主线程已恢复（上次卡死约 " + (idle / 1000) + "s 内结束）");
                    }
                }
            }
        }, "main-watchdog");
        t.setDaemon(true);
        t.start();
    }

    // ---------------------------------------------------------------- 本地服务

    private String localUrl() {
        return "http://127.0.0.1:" + localPort;
    }

    private void startLocalService() {
        String abi = LocalServer.abiProblem();
        if (abi != null) {
            overlayMode = 3;
            showCard("内置服务不可用",
                    abi + "\n\n这台设备无法运行内置的 Node.js 服务，但 APK 仍可作为客户端"
                            + "连接电脑上的 LibreTV。",
                    DEFAULT_EXTERNAL_URL, "改用外部服务", "仍要重试");
            return;
        }
        overlayMode = 4;
        showCard("手机本地服务",
                "正在启动手机上的 LibreTV 服务…\n\n"
                        + "首次启动需解压约 42 MB 运行包（约 10~30 秒），之后通常几秒就绪。\n"
                        + "服务只在手机上运行，不依赖电脑。本版本不含内置源，"
                        + "就绪后请在「设置 → 源管理」里配置源。",
                localUrl(), "重试启动", "改用外部服务");
        localServer.start(localPort, effectivePassword, lanMode);
    }

    private void restartLocal() {
        localServer.stop();
        startLocalService();
    }

    /**
     * 取实际要注入服务端的密码。
     *
     * 用户设了密码就用用户的；没设（默认）就在本机生成一个随机密码并记在 prefs 里，
     * 之后一直复用同一个。这个随机密码只在设备内部流转：注入 Node 进程的 PASSWORD
     * 环境变量、注入网页自动登录，不写日志、不上传、界面上也不展示（密码框仍是空的）。
     *
     * 为什么必须这么做：上游 LibreTV 的 api-guard 把「PASSWORD 为空」当作配置错误，
     * 所有 /api/* 直接 503。空密码不是"免登录"，而是"整个后端不可用"。
     */
    private String resolvePassword() {
        if (localPassword != null && !localPassword.isEmpty()) return localPassword;
        String auto = prefs.getString(KEY_PASSWORD_AUTO, "");
        if (auto == null || auto.isEmpty()) {
            auto = randomPassword();
            prefs.edit().putString(KEY_PASSWORD_AUTO, auto).apply();
            appLog.i("auth", "未设置访问密码，已生成本机随机密码并自动登录（无需手动输入）");
        }
        return auto;
    }

    /** 16 字节随机数转 32 位十六进制串，避免用到弱口令或固定值 */
    private static String randomPassword() {
        byte[] buf = new byte[16];
        new java.security.SecureRandom().nextBytes(buf);
        StringBuilder sb = new StringBuilder(32);
        for (byte b : buf) {
            String h = Integer.toHexString(b & 0xff);
            if (h.length() == 1) sb.append('0');
            sb.append(h);
        }
        return sb.toString();
    }

    private void switchToExternal() {
        localMode = false;
        prefs.edit().putBoolean(KEY_LOCAL_MODE, false).apply();
        localServer.stop();
        if (localModeBox != null) localModeBox.setChecked(false);
        String u = normalize(overlayEdit.getText().toString());
        if (u == null || u.equals(localUrl())) u = DEFAULT_EXTERNAL_URL;
        updateServerStat();
        saveAndLoad(u);
    }

    @Override
    public void onProgress(final String message, final int percent) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                appLog.i("server", message + " (" + percent + "%)");
                if (overlayVisible() && (overlayMode == 4)) {
                    overlayHint.setText(message);
                }
                updateServerStat();
            }
        });
    }

    @Override
    public void onReady(final String url) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                appLog.i("server", "本地服务已就绪 " + url);
                hideOverlay();
                // KEY_URL 始终记录「外部服务地址」，本地模式不覆盖它
                settingsEdit.setText(prefs.getString(KEY_URL, DEFAULT_EXTERNAL_URL));
                load(url);
                updateServerStat();
                toast("手机本地服务已就绪");
            }
        });
    }

    @Override
    public void onError(final String message) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                appLog.e("server", "启动失败：" + message);
                overlayMode = 5;
                showCard("本地服务启动失败",
                        message + "\n\n可尝试：换一个端口、查看服务日志，"
                                + "或暂时切换到电脑上的 LibreTV 服务。",
                        DEFAULT_EXTERNAL_URL, "改用外部服务", "重试本地服务");
                updateServerStat();
            }
        });
    }

    private void updateServerStat() {
        if (serverStatLine == null) return;
        String status = localMode
                ? (localServer != null && localServer.isRunning() ? "运行中" : "未运行")
                : "外部服务";
        String addr = localMode ? localUrl() : currentUrl;
        String ip = LocalServer.lanAddress();
        String extra = (localMode && lanMode && ip != null)
                ? "\n其它设备可访问：http://" + ip + ":" + localPort : "";
        // 把 Node 体检结论直接摆在服务状态里，用户不必导出日志就能看到失败原因
        String probe = "";
        if (localMode && localServer != null) {
            String r = localServer.envProbeText();
            int shown = 0;
            for (String line : r.split("\n")) {
                if (line.startsWith("DNS ") || line.startsWith("HTTPS ")
                        || line.startsWith("fetch ") || line.startsWith("c-ares ")) {
                    probe += "\n" + line;
                    if (++shown >= 4) break;
                }
            }
        }
        serverStatLine.setText("服务：" + status + " · " + addr + extra + probe);
    }

    /** Node 运行环境体检：DNS / CA / 真实出站错误码，一屏可直接复制发回。 */
    private void showEnvProbe() {
        hideSheet();
        final String report = (localServer == null)
                ? "（本地服务未启动）\n\n本体检只对「手机本地服务」有效；连接电脑服务时请在电脑端排查。"
                : localServer.envProbeText();
        appLog.i("probe", "查看 Node 运行环境体检：\n" + report);
        new android.app.AlertDialog.Builder(this)
                .setTitle("Node 运行环境体检")
                .setMessage(report)
                .setPositiveButton("关闭", null)
                .setNeutralButton("复制", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        copyToClipboard(report, "运行环境体检");
                    }
                })
                .setNegativeButton("导出", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        chooseExportTarget(report, "libretv-env-probe-" + fileStamp() + ".txt", "体检报告");
                    }
                })
                .show();
    }

    private void showLog() {
        String s = localServer == null ? "" : localServer.logTail();
        if (s.trim().isEmpty()) s = "（暂无日志，本地服务可能尚未启动）";
        if (s.length() > 6000) s = s.substring(s.length() - 6000);
        new android.app.AlertDialog.Builder(this)
                .setTitle("本地服务日志")
                .setMessage(s)
                .setPositiveButton("关闭", null)
                .show();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private GradientDrawable round(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    // ---------------------------------------------------------------- 源导入 / 导出

    private void showSourceMenu() {
        final CharSequence[] items = {"导出当前源配置", "导入（合并到现有源）",
                "导入（替换现有源）", "取消"};
        new android.app.AlertDialog.Builder(this)
                .setTitle("源管理")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        if (which == 0) exportSources();
                        else if (which == 1) showImportPicker(false);
                        else if (which == 2) showImportPicker(true);
                    }
                }).show();
    }

    /** evaluateJavascript 返回的是 JSON 字符串字面量，解成 Java 字符串。 */
    private String jsString(String value) {
        if (value == null || "null".equals(value)) return null;
        try {
            Object v = new JSONTokener(value).nextValue();
            return v instanceof String ? (String) v : value;
        } catch (Exception e) {
            return value;
        }
    }

    private void exportSources() {
        if (web == null || exportTemplate.isEmpty()) {
            toast("页面尚未加载，稍后再试");
            return;
        }
        hideSheet();
        appLog.i("sources", "开始导出源配置");
        web.evaluateJavascript(exportTemplate, new ValueCallback<String>() {
            @Override public void onReceiveValue(String value) {
                String raw = jsString(value);
                if (raw == null || raw.isEmpty()) {
                    toast("读取源配置失败");
                    return;
                }
                try {
                    JSONObject probe = new JSONObject(raw);
                    if (probe.has("error")) {
                        toast("导出失败：" + probe.optString("error"));
                        appLog.e("export", "源配置导出失败：" + probe.optString("error"));
                        return;
                    }
                } catch (JSONException ignored) { }
                String pretty;
                try {
                    pretty = new JSONObject(raw).toString(2);
                } catch (JSONException e) {
                    pretty = raw;
                }
                int count = 0;
                try {
                    count = new JSONObject(raw).optJSONArray("sources") == null ? 0
                            : new JSONObject(raw).optJSONArray("sources").length();
                } catch (JSONException ignored) { }
                appLog.i("export", "导出源配置 " + count + " 条");
                chooseExportTarget(pretty, "libretv-sources-" + fileStamp() + ".json",
                        "源配置（含源地址，请自行保管）");
            }
        });
    }

    private void showImportPicker(final boolean replace) {
        final CharSequence[] items = {"从文件导入", "从剪贴板导入", "从订阅地址导入", "取消"};
        new android.app.AlertDialog.Builder(this)
                .setTitle(replace ? "导入并替换现有源" : "导入并合并到现有源")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        hideSheet();
                        if (which == 0) openImportFile(replace);
                        else if (which == 1) importFromClipboard(replace);
                        else if (which == 2) showImportUrlDialog(replace);
                    }
                }).show();
    }

    private void openImportFile(boolean replace) {
        importReplaceMode = replace;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES,
                new String[]{"application/json", "text/plain", "text/xml", "application/octet-stream"});
        try {
            startActivityForResult(intent, REQ_IMPORT_FILE);
        } catch (Exception e) {
            appLog.e("import", "无法打开文件选择器：" + e);
            toast("无法打开文件选择器");
        }
    }

    private void importFromClipboard(final boolean replace) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            ClipData clip = cm == null ? null : cm.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) {
                toast("剪贴板为空");
                return;
            }
            CharSequence cs = clip.getItemAt(0).getText();
            if (cs == null || cs.toString().trim().isEmpty()) {
                toast("剪贴板里没有文本");
                return;
            }
            applyImportedSources(cs.toString(), replace);
        } catch (Exception e) {
            appLog.e("import", "读取剪贴板失败：" + e);
            toast("读取剪贴板失败");
        }
    }

    private void showImportUrlDialog(final boolean replace) {
        final EditText input = new EditText(this);
        input.setHint("https://example.com/sources.json");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setSingleLine(true);
        new android.app.AlertDialog.Builder(this)
                .setTitle("从订阅地址导入")
                .setMessage("支持 LibreTV-SourceList、裸数组、TVBOX 配置或完整快照。")
                .setView(input)
                .setPositiveButton("拉取并导入", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        String url = input.getText().toString().trim();
                        if (!url.toLowerCase(Locale.US).startsWith("http")) {
                            toast("地址需以 http:// 或 https:// 开头");
                            return;
                        }
                        fetchImportUrl(url, replace);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void fetchImportUrl(final String url, final boolean replace) {
        toast("正在拉取订阅…");
        appLog.i("import", "拉取订阅 " + url);
        new Thread(new Runnable() {
            @Override public void run() {
                String body = null;
                String err = null;
                HttpURLConnection c = null;
                try {
                    c = (HttpURLConnection) new URL(url).openConnection(Proxy.NO_PROXY);
                    c.setConnectTimeout(8000);
                    c.setReadTimeout(15000);
                    c.setRequestProperty("User-Agent", "LibreTVAndroid/1.0");
                    c.setInstanceFollowRedirects(true);
                    if (c.getResponseCode() != 200) {
                        throw new IOException("HTTP " + c.getResponseCode());
                    }
                    body = readAll(c.getInputStream());
                } catch (Exception e) {
                    err = e.getMessage() == null ? e.toString() : e.getMessage();
                } finally {
                    if (c != null) c.disconnect();
                }
                final String text = body;
                final String error = err;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (text == null || text.trim().isEmpty()) {
                            appLog.e("import", "订阅拉取失败：" + error);
                            toast("拉取失败：" + error);
                        } else {
                            applyImportedSources(text, replace);
                        }
                    }
                });
            }
        }, "ltv-import-url").start();
    }

    private void applyImportedSources(String raw, boolean replace) {
        if (web == null || importTemplate.isEmpty()) {
            toast("页面尚未加载，稍后再试");
            return;
        }
        String js = importTemplate
                .replace("__RAW__", JSONObject.quote(raw))
                .replace("__MODE__", JSONObject.quote(replace ? "replace" : "merge"));
        appLog.i("import", "开始" + (replace ? "替换" : "合并") + "导入，"
                + raw.length() + " 字符");
        web.evaluateJavascript(js, new ValueCallback<String>() {
            @Override public void onReceiveValue(String value) {
                String v = jsString(value);
                if (v == null) v = "";
                if (!v.startsWith("ok:")) {
                    appLog.e("import", "导入失败：" + v);
                    toast("导入失败：" + v);
                    return;
                }
                String[] parts = v.split(":");
                String added = parts.length > 1 ? parts[1] : "0";
                String total = parts.length > 2 ? parts[2] : "?";
                String other = parts.length > 3 ? parts[3] : "0";
                String extra = "0".equals(other) ? "" : ("、" + other + " 条直播/订阅");
                appLog.i("import", "导入成功，新增点播 " + added + " 条" + extra
                        + "，当前点播 " + total + " 条");
                toast("导入成功：新增 " + added + " 个点播源" + extra + "，当前 " + total + " 条");
                if (web != null) {
                    web.reload();
                    web.postDelayed(new Runnable() {
                        @Override public void run() { refreshSourceCount(); }
                    }, 1600);
                }
            }
        });
    }

    // ---------------------------------------------------------------- 日志中心

    /** 旧入口保留，统一转发到日志中心，避免出现两套界面。 */
    private void showLogMenu() { showLogCenter(); }
    private void showAppLog() { showLogCenter(); }

    private String combinedLog() { return combinedLog(256 * 1024, 60000); }

    /** 分享用：控制总长度，保证头部「设备信息 + 体检报告」不会被尾部日志挤掉。 */
    private String shareableLog() { return combinedLog(40000, 20000); }

    /** 应用版本号：从 PackageInfo 读，与 build.gradle 的 versionName 一致。 */
    private String appVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "0.0.0";
        }
    }

    private int appVersionCode() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
        } catch (Exception e) {
            return 1;
        }
    }

    private String versionLine() {
        return "v" + appVersion() + " (build " + appVersionCode() + ")";
    }

    /** 应用分级日志 + Node 体检 + Node 原始 stdout + 设备信息，查看与分享共用一份。 */
    private String combinedLog(int appLimit, int nodeLimit) {
        StringBuilder sb = new StringBuilder();
        sb.append("LibreTV Android ").append(versionLine()).append(" · 运行日志\n");
        sb.append("导出时间：")
                .append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(new Date()))
                .append('\n');
        sb.append("当前服务：").append(currentUrl.isEmpty() ? "未连接" : currentUrl).append('\n');
        sb.append("本地服务：").append(localMode ? "开启" : "关闭")
                .append(" · 端口 ").append(localPort)
                .append(" · 进程 ").append(localServer != null && localServer.isRunning() ? "运行中" : "未运行")
                .append('\n');
        sb.append("设备：Android ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(") · ")
                .append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(" · ").append(android.text.TextUtils.join(",", Build.SUPPORTED_ABIS))
                .append('\n');
        sb.append('\n');
        sb.append("===== Node 运行环境体检 =====\n");
        sb.append(localServer == null ? "（本地服务未启动）" : localServer.envProbeText()).append("\n\n");
        sb.append("===== 应用日志（分级）=====\n");
        String app = appLog.all();
        if (app.length() > appLimit) {
            app = "…（应用日志仅保留最近部分）\n" + app.substring(app.length() - appLimit);
        }
        sb.append(app).append('\n');
        sb.append("===== Node 服务原始日志 =====\n");
        String node = localServer == null ? "（本地服务未启动）" : localServer.nodeLogText();
        if (node == null) node = "";
        if (node.length() > nodeLimit) {
            node = "…（仅保留最近部分）\n" + node.substring(node.length() - nodeLimit);
        }
        sb.append(node).append('\n');
        return sb.toString();
    }

    /** 底部导航「日志」直达：一屏看完、一键分享、自动刷新。 */
    private void showLogCenter() {
        hideSheet();
        appLog.i("log", "打开日志中心");
        lastCombinedLog = combinedLog();
        logFilter = 0;

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), dp(6), dp(12), dp(6));

        logStatus = new TextView(this);
        logStatus.setTextSize(12);
        logStatus.setTextColor(TEXT_DIM);
        logStatus.setPadding(0, 0, 0, dp(8));
        box.addView(logStatus);

        // 主操作：一键分享文本 / 以文件附件分享（微信、邮件等）
        LinearLayout r1 = new LinearLayout(this);
        r1.setOrientation(LinearLayout.HORIZONTAL);
        r1.addView(smallButton("分享日志", true, new View.OnClickListener() {
            @Override public void onClick(View v) {
                appLog.i("log", "一键分享日志文本");
                shareText(shareableLog(), "LibreTV 运行日志");
            }
        }), weighted());
        r1.addView(smallButton("分享文件", false, new View.OnClickListener() {
            @Override public void onClick(View v) { shareLogFile(); }
        }), weighted());
        box.addView(r1, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout r2 = new LinearLayout(this);
        r2.setOrientation(LinearLayout.HORIZONTAL);
        r2.addView(smallButton("复制", false, new View.OnClickListener() {
            @Override public void onClick(View v) { copyToClipboard(shareableLog(), "运行日志"); }
        }), weighted());
        r2.addView(smallButton("保存", false, new View.OnClickListener() {
            @Override public void onClick(View v) {
                appLog.i("log", "保存日志文件");
                saveToDocument(combinedLog(), "libretv-log-" + fileStamp() + ".txt");
            }
        }), weighted());
        r2.addView(smallButton("刷新", false, new View.OnClickListener() {
            @Override public void onClick(View v) {
                lastCombinedLog = combinedLog();
                renderLog();
                toast("日志已刷新");
            }
        }), weighted());
        r2.addView(smallButton("清空", false, new View.OnClickListener() {
            @Override public void onClick(View v) { confirmClearLog(); }
        }), weighted());
        LinearLayout.LayoutParams r2p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        r2p.topMargin = dp(6);
        box.addView(r2, r2p);

        LinearLayout r3 = new LinearLayout(this);
        r3.setOrientation(LinearLayout.HORIZONTAL);
        final Button[] tabs = new Button[4];
        String[] names = {"全部", "仅错误", "Node 原始", "体检"};
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            Button t = smallButton(names[i], false, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    logFilter = idx;
                    for (Button b : tabs) if (b != null) b.setTextColor(TEXT);
                    ((Button) v).setTextColor(ACCENT);
                    renderLog();
                }
            });
            tabs[i] = t;
            r3.addView(t, weighted());
        }
        if (tabs[0] != null) tabs[0].setTextColor(ACCENT);
        LinearLayout.LayoutParams r3p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        r3p.topMargin = dp(6);
        box.addView(r3, r3p);

        CheckBox auto = new CheckBox(this);
        auto.setText("每 2 秒自动刷新");
        auto.setTextSize(12);
        auto.setTextColor(TEXT_DIM);
        auto.setChecked(logAutoRefresh);
        auto.setButtonTintList(ColorStateList.valueOf(ACCENT));
        auto.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean checked) {
                logAutoRefresh = checked;
                if (checked) startLogRefresh(); else stopLogRefresh();
                renderLog();
            }
        });
        box.addView(auto);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        logBody = new TextView(this);
        logBody.setTextSize(11);
        logBody.setTextColor(TEXT);
        logBody.setTypeface(android.graphics.Typeface.MONOSPACE);
        logBody.setPadding(dp(8), dp(8), dp(8), dp(8));
        scroll.addView(logBody, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(340));
        slp.topMargin = dp(8);
        box.addView(scroll, slp);

        logDialog = new android.app.AlertDialog.Builder(this)
                .setTitle("日志中心 · 可分享")
                .setView(box)
                .setPositiveButton("关闭", null)
                .setOnDismissListener(new DialogInterface.OnDismissListener() {
                    @Override public void onDismiss(DialogInterface d) {
                        stopLogRefresh();
                        logDialog = null;
                        logBody = null;
                        logStatus = null;
                    }
                })
                .create();
        logDialog.show();
        android.view.Window w = logDialog.getWindow();
        if (w != null) {
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        renderLog();
        startLogRefresh();
    }

    private void renderLog() {
        if (logBody == null || logStatus == null) return;
        String s = lastCombinedLog;
        if (logFilter == 1) {
            StringBuilder b = new StringBuilder();
            for (String line : s.split("\n")) {
                if (line.contains(" E/") || line.contains(" W/") || line.contains("失败")
                        || line.contains("错误") || line.toLowerCase(Locale.CHINA).contains("error")) {
                    b.append(line).append('\n');
                }
            }
            s = b.length() == 0 ? "（暂无错误或警告）" : b.toString();
        } else if (logFilter == 2) {
            int i = s.indexOf("===== Node 服务原始日志 =====");
            s = i >= 0 ? s.substring(i) : "（暂无 Node 日志）";
        } else if (logFilter == 3) {
            int a = s.indexOf("===== Node 运行环境体检 =====");
            int b = s.indexOf("===== 应用日志");
            s = (a >= 0 && b > a) ? s.substring(a, b) : "（暂无体检报告）";
        }
        if (s.length() > 20000) {
            s = "…（仅显示最后 20000 字符；分享 / 保存为完整内容）\n"
                    + s.substring(s.length() - 20000);
        }
        logBody.setText(s);
        logStatus.setText("共 " + lastCombinedLog.length() + " 字符 · "
                + new SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(new Date())
                + " · " + (logAutoRefresh ? "自动刷新中" : "手动刷新")
                + "\n分享内容含：应用日志 + Node 原始日志 + 运行环境体检 + 设备信息");
    }

    private void startLogRefresh() {
        stopLogRefresh();
        logRefreshTask = new Runnable() {
            @Override public void run() {
                if (logDialog == null || !logDialog.isShowing()) return;
                lastCombinedLog = combinedLog();
                renderLog();
                if (logAutoRefresh) logHandler.postDelayed(this, 2000);
            }
        };
        if (logAutoRefresh) logHandler.postDelayed(logRefreshTask, 2000);
    }

    private void stopLogRefresh() {
        if (logRefreshTask != null) {
            logHandler.removeCallbacks(logRefreshTask);
            logRefreshTask = null;
        }
    }

    /** 小尺寸等宽操作按钮，触摸目标 ≥46dp。 */
    private Button smallButton(String label, boolean primary, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(12);
        b.setAllCaps(false);
        b.setMinHeight(dp(46));
        b.setMinimumHeight(dp(46));
        b.setPadding(dp(3), 0, dp(3), 0);
        if (primary) {
            b.setTextColor(Color.WHITE);
            b.setBackground(round(ACCENT, 8));
        } else {
            b.setTextColor(TEXT);
            b.setBackground(round(SURFACE2, 8));
        }
        b.setOnClickListener(listener);
        return b;
    }

    private LinearLayout.LayoutParams weighted() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        p.setMargins(dp(3), 0, dp(3), 0);
        return p;
    }

    /** 以 content:// 附件分享完整日志，避开 Android 7+ 的 file:// 限制。 */
    private void shareLogFile() {
        try {
            String text = combinedLog();
            File d = new File(getFilesDir(), "share");
            if (!d.exists() && !d.mkdirs()) throw new IOException("无法创建 share 目录");
            File f = new File(d, LogProvider.FILE_NAME);
            FileOutputStream os = new FileOutputStream(f, false);
            os.write(text.getBytes("UTF-8"));
            os.close();
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_STREAM, LogProvider.SHARE_URI);
            i.putExtra(Intent.EXTRA_SUBJECT, "LibreTV 日志 " + fileStamp());
            i.putExtra(Intent.EXTRA_TEXT,
                    "LibreTV 运行日志见附件（应用日志 + Node 原始日志 + 运行环境体检 + 设备信息）。");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            i.setClipData(ClipData.newUri(getContentResolver(), "LibreTV 日志",
                    LogProvider.SHARE_URI));
            startActivity(Intent.createChooser(i, "分享日志文件"));
            appLog.i("log", "已调起日志文件分享（" + text.length() + " 字符）");
        } catch (Exception e) {
            appLog.e("log", "日志文件分享失败：" + e);
            toast("文件分享失败，可改用「分享日志」文本");
        }
    }

    private void exportAppLog() {
        hideSheet();
        String text = combinedLog();
        appLog.i("log", "导出应用日志（" + text.length() + " 字符）");
        chooseExportTarget(text, "libretv-log-" + fileStamp() + ".txt", "日志");
    }

    private void confirmClearLog() {
        new android.app.AlertDialog.Builder(this)
                .setTitle("清空日志")
                .setMessage("将删除已保存的应用日志，此操作不可恢复。Node 服务原始日志与体检报告不受影响。")
                .setPositiveButton("清空", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        appLog.clear();
                        lastCombinedLog = combinedLog();
                        renderLog();
                        toast("应用日志已清空");
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ---------------------------------------------------------------- 输出通道

    private void chooseExportTarget(final String text, final String name, final String label) {
        final CharSequence[] items = {"保存到文件", "复制到剪贴板", "分享文本"};
        new android.app.AlertDialog.Builder(this)
                .setTitle("导出" + label)
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        if (which == 0) saveToDocument(text, name);
                        else if (which == 1) copyToClipboard(text, label);
                        else shareText(text, label);
                    }
                }).show();
    }

    private void saveToDocument(String text, String name) {
        pendingExportText = text;
        pendingExportName = name;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(name.endsWith(".json") ? "application/json" : "text/plain");
        intent.putExtra(Intent.EXTRA_TITLE, name);
        try {
            startActivityForResult(intent, REQ_EXPORT_FILE);
        } catch (Exception e) {
            appLog.e("export", "无法打开文件保存器：" + e);
            toast("无法打开文件保存器，可改用复制或分享");
        }
    }

    private void copyToClipboard(String text, String label) {
        if (text.length() > 100000) {
            toast("内容过长，请改用「保存到文件」");
            return;
        }
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm == null) {
                toast("剪贴板不可用");
                return;
            }
            cm.setPrimaryClip(ClipData.newPlainText(label, text));
            appLog.i("export", label + "已复制到剪贴板（" + text.length() + " 字符）");
            toast(label + "已复制到剪贴板");
        } catch (Exception e) {
            toast("复制失败");
        }
    }

    private void shareText(String text, String label) {
        if (text.length() > 180000) {
            toast("内容过长，请改用「保存到文件」");
            return;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("text/plain");
            intent.putExtra(Intent.EXTRA_TEXT, text);
            intent.putExtra(Intent.EXTRA_SUBJECT, label);
            startActivity(Intent.createChooser(intent, "分享" + label));
            appLog.i("export", label + "已调起分享");
        } catch (Exception e) {
            toast("无法分享");
        }
    }

    private String readUriText(Uri uri) {
        InputStream in = null;
        try {
            in = getContentResolver().openInputStream(uri);
            if (in == null) return null;
            return readAll(in);
        } catch (Exception e) {
            appLog.e("import", "读取文件失败：" + e);
            return null;
        } finally {
            if (in != null) try { in.close(); } catch (Exception ignored) { }
        }
    }

    private void writeUriText(Uri uri, String text) {
        OutputStream out = null;
        try {
            out = getContentResolver().openOutputStream(uri);
            if (out == null) throw new IOException("无法打开输出流");
            out.write(text.getBytes("UTF-8"));
            out.flush();
            appLog.i("export", "已写入 " + pendingExportName + "（" + text.length() + " 字符）");
            toast("已保存 " + pendingExportName);
        } catch (Exception e) {
            appLog.e("export", "保存失败：" + e);
            toast("保存失败：" + e.getMessage());
        } finally {
            if (out != null) try { out.close(); } catch (Exception ignored) { }
            pendingExportText = "";
        }
    }

    private String fileStamp() {
        return new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.CHINA).format(new Date());
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    // ---------------------------------------------------------------- UI

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        setContentView(root);

        mainBox = new LinearLayout(this);
        mainBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(mainBox, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        web = new WebView(this);
        web.setBackgroundColor(BG);
        mainBox.addView(web, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setBackgroundColor(SURFACE);
        bottomBar.setPadding(0, dp(2), 0, dp(2));
        View divider = new View(this);
        divider.setBackgroundColor(SURFACE2);
        mainBox.addView(divider, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1));
        int barHeight = dp(getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_LANDSCAPE ? 50 : 62);
        mainBox.addView(bottomBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, barHeight));

        bottomBar.addView(navButton(R.drawable.ic_home, "首页", new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!currentUrl.isEmpty()) load(currentUrl);
            }
        }));
        bottomBar.addView(navButton(R.drawable.ic_back, "后退", new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (web != null && web.canGoBack()) web.goBack();
                else toast("已经是最早的页面");
            }
        }));
        bottomBar.addView(navButton(R.drawable.ic_refresh, "刷新", new View.OnClickListener() {
            @Override public void onClick(View v) { if (web != null) web.reload(); }
        }));
        bottomBar.addView(navButton(R.drawable.ic_fullscreen, "沉浸", new View.OnClickListener() {
            @Override public void onClick(View v) { applyImmersive(true); }
        }));
        bottomBar.addView(navButton(R.drawable.ic_settings, "设置", new View.OnClickListener() {
            @Override public void onClick(View v) { showSheet(); }
        }));
        bottomBar.addView(navButton(R.drawable.ic_log, "日志", new View.OnClickListener() {
            @Override public void onClick(View v) { showLogCenter(); }
        }));

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setVisibility(View.GONE);
        FrameLayout.LayoutParams progressLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(3), Gravity.TOP);
        root.addView(progress, progressLp);

        buildSheet();
        buildOverlay();

        fabRestore = new TextView(this);
        fabRestore.setText("显示工具栏");
        fabRestore.setTextColor(TEXT);
        fabRestore.setTextSize(13);
        fabRestore.setBackground(round(0xCC2A3140, 18));
        fabRestore.setPadding(dp(16), dp(9), dp(16), dp(9));
        fabRestore.setVisibility(View.GONE);
        fabRestore.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { applyImmersive(false); }
        });
        FrameLayout.LayoutParams fabLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.END);
        fabLp.setMargins(0, 0, dp(16), dp(20));
        root.addView(fabRestore, fabLp);

        // 一键全屏：只在检测到页面里有 <video> 时浮现，避免遮挡浏览页面。
        // 位置比「显示工具栏」高一层，两者不会重叠。
        fabFullscreen = new TextView(this);
        fabFullscreen.setText("全屏播放");
        fabFullscreen.setTextColor(Color.WHITE);
        fabFullscreen.setTextSize(14);
        fabFullscreen.setGravity(Gravity.CENTER);
        fabFullscreen.setMinWidth(dp(104));
        fabFullscreen.setMinHeight(dp(52));
        fabFullscreen.setPadding(dp(14), dp(10), dp(14), dp(10));
        fabFullscreen.setBackground(round(0xDD4C8BF5, 16));
        fabFullscreen.setClickable(true);
        fabFullscreen.setFocusable(true);
        fabFullscreen.setVisibility(View.GONE);
        fabFullscreen.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { enterFullscreen(); }
        });
        FrameLayout.LayoutParams fsLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.END);
        fsLp.setMargins(0, 0, dp(16), dp(84));
        root.addView(fabFullscreen, fsLp);

        // 只要页面里有 <video> 就显示：竖屏播放时也能一键投屏
        fabCast = new TextView(this);
        fabCast.setText("投屏到电视");
        fabCast.setTextColor(Color.WHITE);
        fabCast.setTextSize(14);
        fabCast.setGravity(Gravity.CENTER);
        fabCast.setMinWidth(dp(104));
        fabCast.setMinHeight(dp(52));
        fabCast.setPadding(dp(14), dp(10), dp(14), dp(10));
        fabCast.setBackground(round(0xDD2FA36B, 16));
        fabCast.setClickable(true);
        fabCast.setFocusable(true);
        fabCast.setVisibility(View.GONE);
        fabCast.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showCastDialog(); }
        });
        FrameLayout.LayoutParams castLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.END);
        castLp.setMargins(0, 0, dp(16), dp(148));
        root.addView(fabCast, castLp);

        // 沉浸层必须放在高级面板之下：手势层铺满全屏，但细进度线、右上「⋯」
        // 与高级面板要能优先拿到触摸，addView 顺序固定 gesture → line → toggle → panel。
        buildImmersiveLayer();
        buildPlaybackBar();
    }

    private void updateFabFullscreen() {
        int vis = hasVideo && !playbackMode && !immersive && customView == null
                ? View.VISIBLE : View.GONE;
        if (fabFullscreen != null) fabFullscreen.setVisibility(vis);
        if (fabCast != null) fabCast.setVisibility(vis);
    }

    /** 一键全屏：网页全屏优先，CSS 铺满兜底；JS 上报后切到全屏播放态。 */
    private void enterFullscreen() {
        if (!hasVideo) {
            toast("请先打开影片，再点全屏播放");
            return;
        }
        appLog.i("player", "用户点击一键全屏");
        fsFallbackCancelled = false;
        callJs("window.__ltv && window.__ltv.enter()");
        // 兜底：若页面上报链路失效，也要给出可操作的全屏控制条；
        // 用户若在这 1.2 秒内主动退出，兜底必须作废，不能把他拉回全屏
        if (web != null) {
            web.postDelayed(new Runnable() {
                @Override public void run() {
                    if (!fsFallbackCancelled && hasVideo && !playbackMode) {
                        applyPlaybackMode(true);
                    }
                }
            }, 1200);
        }
    }

    private static String fmtTime(double sec) {
        int s = (int) Math.max(0, sec);
        int h = s / 3600, m = (s % 3600) / 60, x = s % 60;
        return h > 0 ? String.format(Locale.CHINA, "%d:%02d:%02d", h, m, x)
                     : String.format(Locale.CHINA, "%02d:%02d", m, x);
    }

    /**
     * 抖音式沉浸播放层：默认不显示任何大控制条，画面纯净。
     *  gestureLayer 铺满全屏：单击暂停/播放、左右滑快进、左半屏亮度、右半屏音量；
     *  progressLine 底部 28dp 热区 + 3dp 细线：点击任意位置直接跳转、拖动实时跳转；
     *  panelToggle 右上「⋯」：唤出完整高级面板（倍速/清晰度/画面/退出），3 秒自动隐藏。
     */
    private void buildImmersiveLayer() {
        // 之前是 0x66FFFFFF（40% 白）3dp，压在亮画面（雪景、白墙、字幕页）上几乎看不见，
        // 用户反馈「播放时看不到进度条」。改成更实的高光 + 深色底衬 + 加粗。
        lineBgPaint.setColor(0xA6FFFFFF);      // 未播放：65% 白
        linePlayedPaint.setColor(0xFFFF3B5C);  // 已播放：主题红
        lineShadowPaint.setColor(0x73000000);  // 底衬：45% 黑，保证亮画面上也有对比
        lineDotPaint.setColor(0xFFFFFFFF);     // 进度圆点

        gestureLayer = new View(this);
        gestureLayer.setBackgroundColor(Color.TRANSPARENT);
        /* 注意：这里必须 false。true 时 View.dispatchTouchEvent 会在 OnTouchListener
           返回 false 之后由 onTouchEvent 兜住 DOWN，手势层就把事件吃掉了，
           网页里那颗「✕ 退出全屏」和底部进度条永远收不到触摸。
           设为 false 后，只有 handleGesture 明确返回 true 的手势才被消费，
           其余（例如落在网页 UI 上的按下）继续往下分发给 WebView。 */
        gestureLayer.setClickable(false);
        gestureLayer.setVisibility(View.GONE);
        gestureLayer.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) { return handleGesture(v, e); }
        });
        root.addView(gestureLayer, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        // 底部细进度线：整个 28dp 都是触摸热区，线画在热区中间
        progressLine = new View(this) {
            @Override protected void onDraw(Canvas c) {
                super.onDraw(c);
                int w = getWidth(), h = getHeight();
                if (w <= 0) return;
                float y = h / 2f;
                float th = dp(2);                    // 4dp 高的线
                float pad = dp(1);
                float ratio = lastDuration > 0
                        ? (float) Math.max(0, Math.min(1, lastCurrent / lastDuration)) : 0f;
                // 1) 深色底衬：亮画面上也能看清线在哪
                c.drawRect(0, y - th - pad, w, y + th + pad, lineShadowPaint);
                // 2) 未播放段
                c.drawRect(0, y - th, w, y + th, lineBgPaint);
                // 3) 已播放段
                if (ratio > 0) c.drawRect(0, y - th, w * ratio, y + th, linePlayedPaint);
                // 4) 进度圆点：明确指出当前播放位置，也方便判断能不能拖
                if (ratio > 0) {
                    float cx = Math.min(w - dp(5), Math.max(dp(5), w * ratio));
                    c.drawCircle(cx, y, dp(5), lineDotPaint);
                    c.drawCircle(cx, y, dp(3), linePlayedPaint);
                }
            }

            @Override public boolean onTouchEvent(MotionEvent e) {
                if (!playbackMode) return false;
                int a = e.getAction();
                if (a != MotionEvent.ACTION_DOWN && a != MotionEvent.ACTION_MOVE
                        && a != MotionEvent.ACTION_UP && a != MotionEvent.ACTION_CANCEL) return true;
                if (lastDuration <= 0) {
                    if (a == MotionEvent.ACTION_DOWN) toast("时长未知，暂不能跳转");
                    return true;
                }
                float ratio = Math.max(0f, Math.min(1f, e.getX() / Math.max(1f, getWidth())));
                double sec = lastDuration * ratio;
                showGestureHint("快进 " + fmtTime(sec) + " / " + fmtTime(lastDuration));
                // 按下即跳、拖动即跳：不等松手，避免「点不上去」
                callJs("window.__ltv && window.__ltv.seekTo(" + sec + ")");
                if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) scheduleHintHide(700);
                invalidate();
                return true;
            }
        };
        progressLine.setClickable(true);
        progressLine.setVisibility(View.GONE);
        // 抬高 22dp 离底：贴底的触摸会被全面屏系统手势条优先吃掉，
        // setSystemGestureExclusionRects 只能缓解，抬离才是确定可点。
        FrameLayout.LayoutParams lineLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(28), Gravity.BOTTOM);
        lineLp.bottomMargin = dp(22);
        root.addView(progressLine, lineLp);
        // 底部系统手势条热区会吃掉触摸：API 29+ 声明排除区，保证细线可点可拖
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                progressLine.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                    @Override public void onLayoutChange(View v, int l, int t, int r, int b,
                            int ol, int ot, int or, int ob) {
                        try {
                            List<Rect> rects = new ArrayList<Rect>();
                            rects.add(new Rect(0, 0, v.getWidth(), v.getHeight()));
                            v.setSystemGestureExclusionRects(rects);
                        } catch (Exception ignored) { }
                    }
                });
            } catch (Exception ignored) { }
        }

        /* 左上角常驻「退出全屏」。
           之前全屏后唯一的退出口是右上「⋯」面板里的按钮，但面板 3 秒就自动收起，
           而单击画面只切换播放/暂停 —— 用户在全屏里找不到任何看得见的回去的路。
           这里放一个低干扰、始终在场的退出按钮，位置与右上「⋯」对称。 */
        exitBtn = new TextView(this);
        // 文案写全「退出全屏」：只写「退出」容易被理解成退出 App
        exitBtn.setText("✕ 退出全屏");
        exitBtn.setTextColor(0xE6FFFFFF);
        exitBtn.setTextSize(13);
        exitBtn.setGravity(Gravity.CENTER);
        exitBtn.setPadding(dp(14), 0, dp(14), 0);
        exitBtn.setBackground(round(0x80000000, 20));
        exitBtn.setMinHeight(dp(40));
        exitBtn.setClickable(true);
        exitBtn.setFocusable(true);
        exitBtn.setVisibility(View.GONE);
        exitBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { exitPlayback(); }
        });
        FrameLayout.LayoutParams exitLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        exitLp.setMargins(dp(16), dp(14), 0, 0);
        root.addView(exitBtn, exitLp);

        panelToggle = new TextView(this);
        panelToggle.setText("⋯");
        panelToggle.setTextColor(0xDDFFFFFF);
        panelToggle.setTextSize(22);
        panelToggle.setGravity(Gravity.CENTER);
        panelToggle.setBackground(round(0x66000000, 22));
        panelToggle.setMinWidth(dp(44));
        panelToggle.setMinHeight(dp(44));
        panelToggle.setClickable(true);
        panelToggle.setVisibility(View.GONE);
        panelToggle.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleAdvancedPanel(); }
        });
        FrameLayout.LayoutParams tLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        tLp.setMargins(0, dp(14), dp(16), 0);
        root.addView(panelToggle, tLp);

        // 投屏按钮放在「⋯」左侧，全屏播放时一键推送到电视
        //
        // ← 1.2.4 起这里不再创建原生投屏按钮。
        //
        // 原因：播放态里网页 DOM 的按钮行（左上「✕ 退出全屏」+「投屏」）已经被
        // setUiVisible(true) 强制显示，而原生层这颗画在右上「⋯」旁边 ——
        // 同屏出现两个投屏图标（用户截图反馈「出现了2个投屏图标」）。
        //
        // 留哪一颗？留网页那颗：
        //   1) 它和投屏面板在同一层（网页 DOM），点开的面板一定看得到；
        //      原生按钮在部分机型播放时压根不上屏（历史上一串「找不到投屏」都是这个坑）；
        //   2) 它的文案 / 状态由 updateCastToggle() 通过 setCast() 回写，本来就同步；
        //   3) 非全屏的浏览 / 竖屏小窗场景仍由右下角悬浮按钮 fabCast 承担入口，不缺。
        // 所以原生这颗彻底去掉，从根上杜绝「两个投屏图标」。

        gestureHint = new TextView(this);
        gestureHint.setTextColor(Color.WHITE);
        gestureHint.setTextSize(18);
        gestureHint.setGravity(Gravity.CENTER);
        gestureHint.setPadding(dp(18), dp(10), dp(18), dp(10));
        gestureHint.setBackground(round(0xCC11151C, 14));
        gestureHint.setClickable(false);
        gestureHint.setVisibility(View.GONE);
        root.addView(gestureHint, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
    }

    private void setImmersiveLayers(boolean on) {
        immersiveLayersOn = on;
        if (gestureLayer != null) gestureLayer.setVisibility(on ? View.VISIBLE : View.GONE);
        if (panelToggle != null) panelToggle.setVisibility(on ? View.VISIBLE : View.GONE);
        if (gestureHint != null) gestureHint.setVisibility(View.GONE);
        // 退出口不跟着沉浸模式走：只要还在播放态就必须看得见
        syncExitBtn();
        /* 底部细进度线与「沉浸模式」解耦，只跟「是否在播放」走。
           之前它跟着 on 一起收起，一旦关掉沉浸播放（或切到经典控制条），
           网页控制栏已被 ltv-native-bar 压掉、原生细线也不在 —— 播放时完全看不到进度条。
           它是进度可视化的最低保障：只要在播放、又不在网页栏/面板态，就必须一直在。 */
        syncProgressLine();
        if (on || playbackMode) {
            // 顶层优先级：细进度线 / 右上按钮 / 提示 都要在手势层之上
            if (progressLine != null) progressLine.bringToFront();
            if (panelToggle != null) panelToggle.bringToFront();
            if (gestureHint != null) gestureHint.bringToFront();
            if (exitBtn != null) exitBtn.bringToFront();
        }
    }

    /**
     * 「退出全屏」按钮的唯一可见性入口。
     *
     * 之前全屏里唯一的退出口是右上「⋯」面板里的「退出」，而那个面板 3 秒自动收起；
     * 单击画面又只切播放/暂停 —— 用户在全屏里找不到任何看得见、记得住的回去的路。
     * 现在只要处于播放态，左上角那颗「✕ 退出」就一直在，与沉浸/经典/网页控制栏三种
     * 形态无关，也不参与任何自动隐藏计时。
     */
    private void syncExitBtn() {
        if (exitBtn == null) return;
        /* 网页已经画了自己那颗按钮时，原生这颗只在「网页不可见」的 native 全屏
           （custom view 抢占了画面）里补位，否则会出现两个退出口。 */
        boolean show = playbackMode && (customView != null || !domUiActive);
        exitBtn.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) exitBtn.bringToFront();
    }

    /** 细进度线的可见性唯一入口。
     *  注意：正常播放时这条线一直是 GONE —— LibreTV 的播放器自带 miniProgressBar，
     *  控制栏收起后网页自己会在底部留一条细进度条（见 player-touch.js 顶部说明）。
     *  原生这条只在「网页被藏起来」的 native 全屏（custom view）里补位。 */
    private void syncProgressLine() {
        if (progressLine == null) return;
        boolean show = playbackMode && customView != null
                && !webControlsMode && !advancedPanelVisible;
        progressLine.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) {
            progressLine.bringToFront();
            progressLine.invalidate();
        }
    }

    private void showGestureHint(String text) {
        if (gestureHint == null) return;
        gestureHint.setText(text);
        gestureHint.setVisibility(View.VISIBLE);
    }

    private void scheduleHintHide(long delay) {
        if (gestureHintTask != null) controlHandler.removeCallbacks(gestureHintTask);
        gestureHintTask = new Runnable() {
            @Override public void run() {
                if (gestureHint != null) gestureHint.setVisibility(View.GONE);
            }
        };
        controlHandler.postDelayed(gestureHintTask, delay);
    }

    /**
     * 网页自带 UI 的触摸区：左上「✕ 退出全屏」+「投屏」两颗按钮在网页 DOM 里，
     * 底部则是 ArtPlayer 的控制栏 / LibreTV 自带的迷你进度条。手势层在这两块必须
     * 放行（DOWN 返回 false，ViewGroup 会把事件继续分发给 WebView），否则网页里
     * 的按钮和进度条永远点不到。
     */
    private boolean pageUiZone(float x, float y, int h) {
        /* 两颗按钮横排：「✕ 退出全屏」约 110dp + 8dp 间距 + 「投屏」约 76dp，
           取 250dp 留出大字号 / 长文案的余量。 */
        if (x <= dp(250) && y <= dp(76)) return true;   // 左上：退出全屏 / 投屏
        if (y >= h - dp(96)) return true;               // 底部控制栏 / 迷你进度条
        return false;
    }

    /** 全屏手势：单击暂停/播放、左右滑快进、左半屏亮度、右半屏音量。 */
    private boolean handleGesture(View v, MotionEvent e) {
        /* 投屏面板是网页画的，铺满整屏；手势层此时必须完全让开，否则面板里的
           设备行与按钮收不到触摸（表现就是「面板出来了但点不动」）。 */
        if (castPanelOpen) return false;
        if (!playbackMode || !immersiveLayersOn || advancedPanelVisible || webControlsMode) return false;
        switch (e.getAction()) {
            case MotionEvent.ACTION_DOWN:
                /* 落在网页 UI 上就直接放弃这一串手势，让触摸继续下发到 WebView。
                   ViewGroup 的规则：子 View 在 DOWN 上返回 false 就换下一个子 View。 */
                if (pageUiZone(e.getX(), e.getY(), v.getHeight())) return false;
                gDownX = gLastX = e.getX();
                gDownY = gLastY = e.getY();
                gDownTime = System.currentTimeMillis();
                gMode = 0;
                gStartSec = lastCurrent;
                gPreviewSec = gStartSec;
                gStartBrightness = getWindow().getAttributes().screenBrightness;
                if (gStartBrightness < 0f || gStartBrightness > 1f) gStartBrightness = 0.5f;
                AudioManager amDown = (AudioManager) getSystemService(AUDIO_SERVICE);
                if (amDown != null) {
                    gStartVolume = amDown.getStreamVolume(AudioManager.STREAM_MUSIC);
                    gMaxVolume = amDown.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                }
                return true;
            case MotionEvent.ACTION_MOVE: {
                float dx = e.getX() - gDownX, dy = e.getY() - gDownY;
                if (gMode == 0) {
                    float ax = Math.abs(dx), ay = Math.abs(dy);
                    if (ax > dp(18) && ax > ay * 1.1f) gMode = 1;
                    else if (ay > dp(18)) gMode = (gDownX < v.getWidth() / 2f) ? 2 : 3;
                    else return true;
                }
                if (gMode == 1) {
                    if (lastDuration > 0) {
                        double delta = dx / Math.max(1f, v.getWidth()) * lastDuration * 0.8;
                        gPreviewSec = Math.max(0, Math.min(lastDuration, gStartSec + delta));
                        showGestureHint("快进 " + fmtTime(gPreviewSec) + " / " + fmtTime(lastDuration));
                    }
                } else if (gMode == 2) {
                    float b = Math.max(0.05f, Math.min(1f,
                            gStartBrightness - dy / Math.max(1f, v.getHeight()) * 1.4f));
                    WindowManager.LayoutParams lp = getWindow().getAttributes();
                    lp.screenBrightness = b;
                    getWindow().setAttributes(lp);
                    showGestureHint("亮度 " + Math.round(b * 100) + "%");
                } else if (gMode == 3) {
                    AudioManager amMove = (AudioManager) getSystemService(AUDIO_SERVICE);
                    if (amMove != null && gMaxVolume > 0) {
                        int vol = (int) Math.max(0, Math.min(gMaxVolume,
                                gStartVolume - dy / Math.max(1f, v.getHeight()) * gMaxVolume * 1.4));
                        amMove.setStreamVolume(AudioManager.STREAM_MUSIC, vol, 0);
                        showGestureHint("音量 " + vol + " / " + gMaxVolume);
                    }
                }
                gLastX = e.getX();
                gLastY = e.getY();
                return true;
            }
            case MotionEvent.ACTION_UP:
                if (gMode == 1 && lastDuration > 0) {
                    callJs("window.__ltv && window.__ltv.seekTo(" + gPreviewSec + ")");
                    toast("跳到 " + fmtTime(gPreviewSec));
                    scheduleHintHide(700);
                } else if (gMode == 0 && System.currentTimeMillis() - gDownTime < 350) {
                    callJs("window.__ltv && window.__ltv.toggle()");
                    showGestureHint("播放 / 暂停");
                    scheduleHintHide(700);
                } else if (gMode == 0 && System.currentTimeMillis() - gDownTime >= 600) {
                    /* 长按画面（不滑动）＝ 唤出完整控制面板。
                       单击已经给了播放/暂停，长按留作「找按钮」的第二次机会：
                       面板里有 退出 / 网页 / 画面 / 进度条拖拽，比让用户盲点强。 */
                    showAdvancedPanel();
                    showGestureHint("控制面板");
                    scheduleHintHide(600);
                } else if (gMode != 0) {
                    scheduleHintHide(700);
                }
                gMode = 0;
                return true;
            case MotionEvent.ACTION_CANCEL:
                gMode = 0;
                scheduleHintHide(400);
                return true;
        }
        return true;
    }

    /**
     * 全屏播放控制条（两行）：
     *  第一行用 Android 原生 SeekBar 精确拖拽进度，比网页那条几像素高的进度条好用得多；
     *  第二行是等分的大按钮，触摸目标 ≥52dp。
     */
    private void buildPlaybackBar() {
        playbackBar = new LinearLayout(this);
        playbackBar.setOrientation(LinearLayout.VERTICAL);
        playbackBar.setBackground(round(0xE61B1F27, 16));
        playbackBar.setPadding(dp(12), dp(10), dp(12), dp(8));
        playbackBar.setVisibility(View.GONE);
        playbackBar.setClickable(true);

        LinearLayout rowSeek = new LinearLayout(this);
        rowSeek.setOrientation(LinearLayout.HORIZONTAL);
        rowSeek.setGravity(Gravity.CENTER_VERTICAL);

        playbackTime = new TextView(this);
        playbackTime.setText("00:00 / 00:00");
        playbackTime.setTextColor(TEXT);
        playbackTime.setTextSize(12);
        playbackTime.setSingleLine(true);
        playbackTime.setMinWidth(dp(104));
        rowSeek.addView(playbackTime);

        playbackSeek = new SeekBar(this);
        playbackSeek.setMax(1000);
        playbackSeek.setProgress(0);
        playbackSeek.setPadding(dp(10), 0, dp(10), 0);
        rowSeek.addView(playbackSeek, new LinearLayout.LayoutParams(0, dp(46), 1f));
        playbackBar.addView(rowSeek, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        playbackSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (!fromUser || playbackTime == null) return;
                playbackTime.setText(fmtTime(lastDuration * progress / 1000.0)
                        + " / " + fmtTime(lastDuration));
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {
                seekDragging = true;
                // 手指放在进度条上慢慢找位置时，绝不能让面板被自动收起
                lastPanelActionMs = System.currentTimeMillis();
                cancelControlHide();
            }
            @Override public void onStopTrackingTouch(SeekBar sb) {
                seekDragging = false;
                if (lastDuration > 0) {
                    final double sec = lastDuration * sb.getProgress() / 1000.0;
                    callJs("window.__ltv && window.__ltv.seekTo(" + sec + ")");
                    toast("跳到 " + fmtTime(sec));
                } else {
                    toast("时长未知，暂不能拖动进度");
                }
                showPlaybackBarTemporarily();
            }
        });

        LinearLayout rowBtn = new LinearLayout(this);
        rowBtn.setOrientation(LinearLayout.HORIZONTAL);
        rowBtn.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        rowBtn.addView(playbackButton("退出", new View.OnClickListener() {
            @Override public void onClick(View v) { exitPlayback(); }
        }), btnLp);
        rowBtn.addView(playbackButton("-10s", new View.OnClickListener() {
            @Override public void onClick(View v) {
                callJs("window.__ltv && window.__ltv.seek(-10)");
                toast("快退 10 秒");
            }
        }), btnLp);
        rowBtn.addView(playbackButton("播/停", new View.OnClickListener() {
            @Override public void onClick(View v) { callJs("window.__ltv && window.__ltv.toggle()"); }
        }), btnLp);
        rowBtn.addView(playbackButton("+10s", new View.OnClickListener() {
            @Override public void onClick(View v) {
                callJs("window.__ltv && window.__ltv.seek(10)");
                toast("快进 10 秒");
            }
        }), btnLp);
        rowBtn.addView(playbackButton("画面", new View.OnClickListener() {
            @Override public void onClick(View v) { cycleFit(); }
        }), btnLp);
        // 「网页」把倍速 / 清晰度等网页设置控件放出来；原生条立即收起，
        // 按返回键再切回原生条。两层控制栏永远不同时压在画面上。
        rowBtn.addView(playbackButton("网页", new View.OnClickListener() {
            @Override public void onClick(View v) { switchToWebControls(); }
        }, false), btnLp);
        rowBtn.addView(playbackButton("投屏", new View.OnClickListener() {
            @Override public void onClick(View v) { showCastDialog(); }
        }, false), btnLp);
        rowBtn.addView(playbackButton("亮度", new View.OnClickListener() {
            @Override public void onClick(View v) { cycleBrightness(); }
        }), btnLp);
        LinearLayout.LayoutParams rowBtnLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowBtnLp.topMargin = dp(6);
        playbackBar.addView(rowBtn, rowBtnLp);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        // 底边距 56dp：面板底边（H-56）落在细进度线热区（H-50~H-22）之上，
        // 两者即使同时可见也不会互相盖住；同时避开系统手势条。
        lp.setMargins(dp(16), 0, dp(16), dp(56));
        root.addView(playbackBar, lp);
    }

    /** 画面模式由 Java 驱动并记住选择：完整(黑边) → 铺满(裁切) → 拉伸(变形)。
     *  默认 contain：16:9 的视频在 20:9 的横屏上，cover 会按宽度撑满、把画面上下裁掉，
     *  用户反馈「横排时画面上下被裁剪」就是它。铺满改为手动切。
     *  注意：该模式只在横屏生效，竖屏由 player-touch.js 的媒体查询强制 contain，
     *  否则竖屏的窄高容器用 cover 会把画面左右裁掉。 */
    private String fitMode() {
        String m = prefs.getString(KEY_FIT_MODE, "contain");
        return ("cover".equals(m) || "contain".equals(m) || "fill".equals(m)) ? m : "contain";
    }

    private void cycleFit() {
        String[] arr = {"contain", "cover", "fill"};
        String cur = fitMode();
        int i = 0;
        for (int k = 0; k < arr.length; k++) if (arr[k].equals(cur)) i = k;
        final String next = arr[(i + 1) % arr.length];
        prefs.edit().putString(KEY_FIT_MODE, next).apply();
        callJs("window.__ltv && window.__ltv.setFit('" + next + "')");
        fitLabel = "cover".equals(next) ? "铺满（裁上下）"
                : "contain".equals(next) ? "完整（有黑边）" : "拉伸（会变形）";
        toast("画面：" + fitLabel + "（竖屏始终完整）");
        appLog.i("player", "画面模式切换为 " + fitLabel);
    }

    // ------------------------------------------------------------ 控制条显隐

    private void cancelControlHide() {
        if (controlHideTask != null) {
            controlHandler.removeCallbacks(controlHideTask);
            controlHideTask = null;
        }
    }

    /** 经典模式：轻触画面 / 操作按钮时唤出控制条，4 秒后收起。 */
    private void showPlaybackBarTemporarily() {
        if (!playbackMode || playbackBar == null) return;
        if (immersiveLayersOn) { showAdvancedPanel(); return; }
        if (!prefs.getBoolean(KEY_PLAYBACK_BAR, true)) return;
        webControlsMode = false;
        lastPanelActionMs = System.currentTimeMillis();
        cancelControlHide();
        playbackBar.animate().cancel();
        playbackBar.setAlpha(1f);
        playbackBar.setClickable(true);
        playbackBar.setVisibility(View.VISIBLE);
        playbackBar.bringToFront();
        callJs("window.__ltv && window.__ltv.setNativeBar(true)");
        if (!prefs.getBoolean(KEY_CONTROL_AUTOHIDE, true)) return;
        controlHideTask = new Runnable() {
            @Override public void run() { hidePlaybackBar(); }
        };
        controlHandler.postDelayed(controlHideTask, CONTROL_HIDE_DELAY_MS);
    }

    /** 直接 GONE，不用 alpha 淡出：alpha=0 但 VISIBLE 的面板会变成透明遮罩吃掉触摸。 */
    private void hidePlaybackBar() {
        cancelControlHide();
        if (playbackBar == null) return;
        playbackBar.animate().cancel();
        playbackBar.setAlpha(1f);
        playbackBar.setVisibility(View.GONE);
        playbackBar.setClickable(false);
    }

    private void toggleAdvancedPanel() {
        // 网页控制栏模式下，右上「⋯」充当「回到播放画面」而不是再叠一层面板
        if (webControlsMode) { switchToNativeControls(); return; }
        if (advancedPanelVisible) hideAdvancedPanel();
        else showAdvancedPanel();
    }

    /** 沉浸模式：只有用户点右上「⋯」才出现完整面板，3 秒后自动收起。 */
    private void showAdvancedPanel() {
        if (playbackBar == null) return;
        cancelControlHide();
        advancedPanelVisible = true;
        lastPanelActionMs = System.currentTimeMillis();
        // 面板自带 SeekBar，网页控制栏必须让位：否则网页 art-bottom 的进度条
        // 与面板里的 SeekBar 会同时出现在画面底部，两层互相抢触摸。
        callJs("window.__ltv && window.__ltv.hideWebControls()");
        callJs("window.__ltv && window.__ltv.setNativeBar(true)");
        // 网页里那些浮在底部的按钮（跳过片头/片尾）要让开，否则正压在面板按钮行上
        callJs("window.__ltv && window.__ltv.setPanel(true)");
        playbackBar.animate().cancel();
        playbackBar.setAlpha(1f);
        playbackBar.setClickable(true);
        playbackBar.setVisibility(View.VISIBLE);
        playbackBar.bringToFront();
        // 面板自带 SeekBar，必须收起底部细进度线：两者同显会叠在画面底部抢触摸
        syncProgressLine();
        controlHideTask = new Runnable() {
            @Override public void run() { hideAdvancedPanel(); }
        };
        controlHandler.postDelayed(controlHideTask, PANEL_HIDE_DELAY_MS);
    }

    private void hideAdvancedPanel() {
        cancelControlHide();
        advancedPanelVisible = false;
        if (playbackBar == null) { syncProgressLine(); return; }
        playbackBar.animate().cancel();
        playbackBar.setAlpha(1f);
        playbackBar.setVisibility(View.GONE);
        playbackBar.setClickable(false);
        callJs("window.__ltv && window.__ltv.setPanel(false)");
        syncProgressLine();
    }

    /**
     * 自动隐藏的双保险：由网页 timeupdate（约 450ms 一次）驱动。
     * 即使 postDelayed 因任何原因没触发，只要超过时限且用户无新操作，面板也会被收起。
     */
    private void hidePanelIfIdle() {
        if (seekDragging) return;
        if (playbackBar == null || playbackBar.getVisibility() != View.VISIBLE) return;
        if (!prefs.getBoolean(KEY_CONTROL_AUTOHIDE, true)) return;
        long limit = immersiveLayersOn ? PANEL_HIDE_DELAY_MS : CONTROL_HIDE_DELAY_MS;
        if (System.currentTimeMillis() - lastPanelActionMs <= limit) return;
        if (immersiveLayersOn) hideAdvancedPanel();
        else hidePlaybackBar();
    }

    /** 切到网页控制栏（倍速 / 清晰度）。全屏手势层必须让开，否则点不到网页控件。 */
    private void switchToWebControls() {
        if (!playbackMode) return;
        cancelControlHide();
        webControlsMode = true;
        hideAdvancedPanel();
        if (gestureLayer != null) gestureLayer.setVisibility(View.GONE);
        if (progressLine != null) progressLine.setVisibility(View.GONE);
        // 保留右上「⋯」作为回到播放界面的入口；投屏入口由网页 DOM 那颗承担
        if (panelToggle != null) { panelToggle.setVisibility(View.VISIBLE); panelToggle.bringToFront(); }
        // 网页栏形态下手势层已经让开，左上「✕ 退出」必须继续留在最上层
        syncExitBtn();
        callJs("window.__ltv && window.__ltv.showWebControls()");
        toast("网页控制栏：调倍速 / 清晰度；按返回键回到播放画面");
        appLog.i("player", "切换到网页控制栏");
    }

    /** 从网页控制栏切回播放画面：收起网页栏，恢复沉浸手势，不再弹大面板。 */
    private void switchToNativeControls() {
        if (!playbackMode) return;
        webControlsMode = false;
        callJs("window.__ltv && window.__ltv.hideWebControls()");
        callJs("window.__ltv && window.__ltv.setNativeBar(true)");
        if (prefs.getBoolean(KEY_IMMERSIVE_PLAYER, true)) {
            setImmersiveLayers(true);
            toast("已回到沉浸播放：单击暂停 / 左右滑快进");
        } else {
            // 经典模式也要把底部细进度线放回来，否则播放时没有任何进度可视
            setImmersiveLayers(false);
            showPlaybackBarTemporarily();
        }
        appLog.i("player", "切回播放画面");
    }

    private View playbackButton(String label, View.OnClickListener listener) {
        return playbackButton(label, listener, true);
    }

    private View playbackButton(String label, final View.OnClickListener listener,
                                final boolean resetTimer) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(TEXT);
        t.setTextSize(13);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setMinWidth(dp(56));
        t.setMinHeight(dp(52));
        t.setPadding(dp(9), dp(10), dp(9), dp(10));
        t.setBackground(round(SURFACE2, 10));
        t.setClickable(true);
        t.setFocusable(true);
        t.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                listener.onClick(v);
                if (resetTimer) showPlaybackBarTemporarily();
            }
        });
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        p.setMargins(dp(4), 0, dp(4), 0);
        t.setLayoutParams(p);
        return t;
    }

    private void callJs(String js) {
        if (web == null) return;
        try {
            web.evaluateJavascript(js, null);
        } catch (Exception ignored) { }
    }

    // ------------------------------------------------------------ DLNA 投屏

    /** 投屏入口：未投屏时搜索设备；已投屏时给管理面板（遥控器）。 */
    private void showCastDialog() {
        /* 面板统一由网页绘制：播放态下原生覆盖层（含 AlertDialog 里的自定义视图）
           在这台设备上不上屏，弹了也等于没弹 —— 用户只会看到「点了没反应」。
           原生只负责三件事：搜设备、投送、把结果写回网页。 */
        if (web != null) {
            boolean casting = cast != null && cast.current() != null;
            appLog.i("cast", casting ? "打开网页内投屏面板（投屏中 → 遥控器）"
                    : "打开网页内投屏面板（搜索设备）");
            callJs("window.__ltv && window.__ltv.castShow()");
            if (casting) {
                /* 已在投屏：面板是遥控器，重新搜一遍设备会把停止按钮顶掉 ——
                   用户反馈「投屏后无法停止/控制」有一半是这个原因。 */
                startCastPolling();
            } else {
                /* 原生自己也发起一次搜索：即使「网页→原生」这条桥调用出问题，
                   从设置/悬浮按钮进来的用户照样能拿到设备列表（重复触发由 startCastDiscovery 去重）。 */
                startCastDiscovery();
            }
            return;
        }
        showCastDialogNative();
    }

    /**
     * 发起 SSDP 搜索，并把过程与结果全部回写网页面板。
     * 与旧的 showCastScanning 区别：不再依赖任何原生视图，只推 JS。
     */
    private void startCastDiscovery() {
        /* 800ms 内重复触发直接忽略：网页按钮的 bridge('cast') 与原生入口同时发起时会打两遍。 */
        long now = System.currentTimeMillis();
        if (now - castDiscoverAt < 800) return;
        castDiscoverAt = now;
        try {
            if (cast == null) cast = new DlnaCast(this);
            castDevices.clear();
            callJs("window.__ltv && window.__ltv.castState('scanning')");
            appLog.i("cast", "开始 SSDP 搜索投屏设备（网页面板）");
            cast.discover(new DlnaCast.Callback() {
            @Override public void onDevices(List<DlnaCast.Device> ds) {
                castDevices.clear();
                castDevices.addAll(ds);
                appLog.i("cast", "发现 " + ds.size() + " 个 DLNA 渲染器");
                if (ds.isEmpty()) {
                    callJs("window.__ltv && window.__ltv.castStatus("
                            + JSONObject.quote("没发现设备：确认手机与电视在同一个 Wi-Fi，"
                            + "且电视已开启「投屏 / 多屏互动」；也可用「系统屏幕镜像」投整个画面") + ")");
                    return;
                }
                org.json.JSONArray arr = new org.json.JSONArray();
                for (DlnaCast.Device d : ds) {
                    org.json.JSONObject o = new org.json.JSONObject();
                    try { o.put("name", d.name == null || d.name.isEmpty() ? "未命名电视" : d.name); }
                    catch (Exception ignored) { }
                    arr.put(o);
                }
                callJs("window.__ltv && window.__ltv.castDevices("
                        + JSONObject.quote(arr.toString()) + ")");
            }
            @Override public void onStatus(String message) {
                appLog.i("cast", message);
                if (message != null) {
                    callJs("window.__ltv && window.__ltv.castStatus(" + JSONObject.quote(message) + ")");
                }
            }
            @Override public void onResult(boolean ok, String message) { }
            });
        } catch (Exception e) {
            appLog.w("cast", "搜索投屏设备失败：" + e);
            callJs("window.__ltv && window.__ltv.castStatus("
                    + JSONObject.quote("搜索失败：" + e) + ")");
        }
    }

    /** 兜底：网页还没就绪（极少见）时退回原生对话框。 */
    private void showCastDialogNative() {
        if (cast == null) return;
        DlnaCast.Device d = cast.current();
        if (d != null) {
            final CharSequence[] items = {
                    "停止投屏", "把手机当前进度同步到电视",
                    "切换电视 / 重新搜索", "系统屏幕镜像（投整个手机画面）"
            };
            new android.app.AlertDialog.Builder(this)
                    .setTitle("正在投屏到 " + d.name)
                    .setItems(items, new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface g, int which) {
                            if (which == 0) stopCast();
                            else if (which == 1) syncCastProgress();
                            else if (which == 2) showCastScanning();
                            else openSystemCast();
                        }
                    })
                    .setNegativeButton("关闭", null)
                    .show();
            return;
        }
        showCastScanning();
    }

    /** SSDP 搜索期间显示进度，搜到后自动切到设备列表。 */
    private void showCastScanning() {
        if (cast == null) return;
        castDevices.clear();
        final TextView body = new TextView(this);
        body.setText("正在搜索同一 Wi-Fi 下的电视 / 盒子…\n\n"
                + "请确保手机与电视连的是同一个 Wi-Fi，且电视已开启「投屏 / 多屏互动」。\n\n"
                + "若视频走手机本地服务，请在设置里打开「局域网共享」；"
                + "部分电视不支持直接播放 m3u8，没反应时请改用「系统屏幕镜像」。");
        body.setTextColor(TEXT);
        body.setTextSize(14);
        body.setPadding(dp(22), dp(18), dp(22), dp(8));
        body.setLineSpacing(dp(4), 1f);

        castDialog = new android.app.AlertDialog.Builder(this)
                .setTitle("投屏到电视")
                .setView(body)
                .setPositiveButton("重新搜索", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface g, int w) { showCastScanning(); }
                })
                .setNegativeButton("系统屏幕镜像", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface g, int w) { openSystemCast(); }
                })
                .setNeutralButton("关闭", null)
                .show();

        final android.app.AlertDialog ref = castDialog;
        appLog.i("cast", "开始 SSDP 搜索投屏设备");
        cast.discover(new DlnaCast.Callback() {
            @Override public void onDevices(List<DlnaCast.Device> ds) {
                if (ref != castDialog || !ref.isShowing()) return;
                castDevices.clear();
                castDevices.addAll(ds);
                appLog.i("cast", "发现 " + ds.size() + " 个 DLNA 渲染器");
                ref.dismiss();
                showCastDeviceList(new ArrayList<DlnaCast.Device>(ds));
            }
            @Override public void onStatus(String message) {
                if (ref == castDialog && ref.isShowing()) body.setText(message);
                appLog.i("cast", message);
            }
            @Override public void onResult(boolean ok, String message) { }
        });
    }

    private void showCastDeviceList(final List<DlnaCast.Device> ds) {
        final List<String> labels = new ArrayList<String>();
        for (DlnaCast.Device d : ds) labels.add(d.label());
        if (labels.isEmpty()) {
            labels.add("（没发现设备）重新搜索");
        }
        CharSequence[] arr = labels.toArray(new CharSequence[0]);
        castDialog = new android.app.AlertDialog.Builder(this)
                .setTitle(ds.isEmpty() ? "没发现投屏设备" : "选择投屏设备")
                .setItems(arr, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface g, int which) {
                        if (which < ds.size()) castTo(ds.get(which));
                        else showCastScanning();
                    }
                })
                .setPositiveButton("重新搜索", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface g, int w) { showCastScanning(); }
                })
                .setNegativeButton("系统屏幕镜像", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface g, int w) { openSystemCast(); }
                })
                .setNeutralButton("关闭", null)
                .show();
    }

    /** 取当前视频直链 → 转成电视可访问的地址 → SOAP 推送。 */
    private void castTo(final DlnaCast.Device d) {
        if (web == null || cast == null) return;
        appLog.i("cast", "用户选择设备：" + d.name + "  controlURL=" + d.controlUrl);
        web.evaluateJavascript(
                "(function(){try{var L=window.__ltv;"
                        + "return JSON.stringify({u:(L&&L.castUrl())||'',"
                        + "t:(L&&L.castTitle())||'LibreTV 投屏'})}"
                        + "catch(e){return JSON.stringify({u:'',t:'LibreTV 投屏'})}})()",
                new ValueCallback<String>() {
                    @Override public void onReceiveValue(String value) {
                        String u = "", t = "LibreTV 投屏";
                        try {
                            Object outer = new JSONTokener(value == null ? "\"\"" : value).nextValue();
                            JSONObject o = new JSONObject(outer.toString());
                            u = o.optString("u", "");
                            t = o.optString("t", "LibreTV 投屏");
                        } catch (Exception ignored) { }
                        if (u.isEmpty()) u = lastStreamUrl;
                        if (u.isEmpty()) {
                            toast("还没拿到视频直链，请先让影片开始播放；或用「系统屏幕镜像」投整个画面");
                            appLog.w("cast", "未取得视频直链，投屏中止");
                            callJs("window.__ltv && window.__ltv.castStatus("
                                    + JSONObject.quote("还没拿到视频直链：请先让影片开始播放，"
                                    + "或用「系统屏幕镜像」投整个画面") + ")");
                            return;
                        }
                        final String url = toLanUrl(u);
                        final String title = t.isEmpty() ? "LibreTV 投屏" : t;
                        toast("正在投屏到 " + d.name + "…");
                        appLog.i("cast", "推送地址：" + url);
                        cast.play(d, url, title, castCallback());
                    }
                });
    }

    /**
     * 开始投屏（play）专用回调：成功后把面板推到 casting 态。
     *
     * 只有 play() 能用它。历史上 stop / seek 也复用这个回调，于是点「停止投屏」后：
     * 面板先被同步推回 idle，一秒后异步回调又无条件推 castState('casting')，
     * 又变回「正在投屏到 电视」——用户看到的就是「投屏停不掉」。
     */
    private DlnaCast.Callback castCallback() {
        return new DlnaCast.Callback() {
            @Override public void onDevices(List<DlnaCast.Device> devices) { }
            @Override public void onStatus(String message) { appLog.i("cast", message); }
            @Override public void onResult(boolean ok, String message) {
                toast(message);
                appLog.i("cast", (ok ? "成功：" : "失败：") + message);
                DlnaCast.Device d = cast != null ? cast.current() : null;
                if (ok && d != null) {
                    castDevice = d;
                    castPaused = false;
                    updateCastToggle();
                    callJs("window.__ltv && window.__ltv.castPaused(false)");
                    callJs("window.__ltv && window.__ltv.castState('casting', "
                            + JSONObject.quote(d.name) + ")");
                    if (castPanelOpen) startCastPolling();
                } else if (ok) {
                    /* 极端竞态：还没等 current 回写就被停了，别再推 casting */
                    castDevice = null;
                    updateCastToggle();
                    callJs("window.__ltv && window.__ltv.castState('idle', '已停止投屏')");
                } else {
                    callJs("window.__ltv && window.__ltv.castStatus("
                            + JSONObject.quote(message == null ? "投屏失败" : message) + ")");
                }
            }
        };
    }

    /**
     * 投屏过程中「暂停 / 继续 / 拖进度 / 停止」这类控制指令的回调。
     * 与 castCallback() 的关键区别：绝不把面板推回 casting 态，只回写状态文字。
     * pausedAfter 非 null 时，指令失败会把面板上的播放状态回滚，避免按钮停在错误状态。
     */
    private DlnaCast.Callback castControlCallback(final String okMsg, final Boolean pausedAfter) {
        return new DlnaCast.Callback() {
            @Override public void onDevices(List<DlnaCast.Device> devices) { }
            @Override public void onStatus(String message) { appLog.i("cast", message); }
            @Override public void onResult(boolean ok, String message) {
                appLog.i("cast", (ok ? "成功：" : "失败：") + message);
                if (ok) {
                    callJs("window.__ltv && window.__ltv.castStatus("
                            + JSONObject.quote(okMsg) + ")");
                } else {
                    String m = message == null || message.isEmpty() ? "指令发送失败" : message;
                    callJs("window.__ltv && window.__ltv.castStatus(" + JSONObject.quote(m) + ")");
                    toast(m);
                    if (pausedAfter != null) {
                        boolean rollback = !pausedAfter;
                        callJs("window.__ltv && window.__ltv.castPaused(" + rollback + ")");
                    }
                }
            }
        };
    }

    private DlnaCast.Callback castControlCallback(String okMsg) {
        return castControlCallback(okMsg, null);
    }

    /** 面板上的「暂停 / 继续」：一次点击只下发一个确定的动作，不靠状态猜。 */
    private void pauseCast(final boolean pause) {
        if (cast == null || cast.current() == null) {
            callJs("window.__ltv && window.__ltv.castStatus('当前没有投屏设备')");
            return;
        }
        castPaused = pause;
        callJs("window.__ltv && window.__ltv.castPaused(" + pause + ")");
        appLog.i("cast", pause ? "用户暂停投屏" : "用户继续投屏");
        DlnaCast.Callback cb = castControlCallback(pause ? "电视已暂停" : "电视继续播放", pause);
        if (pause) cast.pause(cb); else cast.resume(cb);
    }

    /** 面板进度条松手：按秒数下发 REL_TIME Seek。 */
    private void seekCast(double seconds) {
        if (cast == null || cast.current() == null) {
            callJs("window.__ltv && window.__ltv.castStatus('当前没有投屏设备')");
            return;
        }
        if (seconds < 0) seconds = 0;
        String t = fmtTime((long) seconds);
        appLog.i("cast", "用户把电视进度拖到 " + t);
        callJs("window.__ltv && window.__ltv.castStatus("
                + JSONObject.quote("正在跳到 " + t + "…") + ")");
        cast.seek(seconds, castControlCallback("已跳到 " + t));
    }

    // -------------------------------------------- 投屏进度轮询（面板打开时）

    private void startCastPolling() {
        if (castPolling) return;
        if (cast == null || cast.current() == null) return;
        castPolling = true;
        castPollFails = 0;
        appLog.i("cast", "开始轮询电视端进度（2 秒一次）");
        castHandler.postDelayed(castPollTask, 400);
    }

    private void stopCastPolling() {
        castPolling = false;
        try { castHandler.removeCallbacks(castPollTask); } catch (Exception ignored) { }
    }

    /**
     * 轮询电视端的真实进度与播放状态。
     *
     * 为什么必须读：电视遥控器也能暂停 / 拖动。只靠本地记录的话，用户在电视上按了暂停，
     * 手机面板还显示「暂停」，再点一次等于又发一次 Pause —— 表现就是「控制不生效」。
     */
    private final Runnable castPollTask = new Runnable() {
        @Override public void run() {
            if (!castPolling) return;
            if (cast == null || cast.current() == null) { stopCastPolling(); return; }
            cast.poll(new DlnaCast.Poll() {
                @Override public void onInfo(double position, double duration, String state) {
                    if (!castPolling) return;
                    if (position < 0 && (state == null || state.isEmpty())) {
                        /* 电视不支持 GetPositionInfo 或已断线：连错 5 次就收手，别一直发 */
                        if (++castPollFails >= 5) {
                            appLog.w("cast", "电视不响应进度查询，停止轮询（控制按钮仍可用）");
                            stopCastPolling();
                            return;
                        }
                    } else {
                        castPollFails = 0;
                        if ("PAUSED_PLAYBACK".equals(state) || "PAUSED_RECORDING".equals(state)) {
                            castPaused = true;
                        } else if ("PLAYING".equals(state) || "RECORDING".equals(state)) {
                            castPaused = false;
                        }
                        callJs("window.__ltv && window.__ltv.castProgress(" + position + ","
                                + duration + "," + JSONObject.quote(state == null ? "" : state) + ")");
                    }
                    if (castPolling) castHandler.postDelayed(castPollTask, 2000);
                }
            });
        }
    };

    /**
     * 手机本地服务的 127.0.0.1 / localhost 地址电视访问不到，替换成手机在 Wi-Fi 里的 IP。
     * 公网视频直链原样返回。
     */
    private String toLanUrl(String u) {
        try {
            String lan = LocalServer.lanAddress();
            if (lan == null || lan.isEmpty()) return u;
            return u.replace("://127.0.0.1:", "://" + lan + ":")
                    .replace("://localhost:", "://" + lan + ":");
        } catch (Exception e) {
            return u;
        }
    }

    /** 把手机当前进度推到电视（面板上的「同步进度」）。 */
    private void syncCastProgress() {
        if (cast == null || cast.current() == null) {
            toast("当前没有投屏设备");
            return;
        }
        if (lastDuration <= 0) {
            toast("还没拿到播放时长，请稍后再试");
            return;
        }
        appLog.i("cast", "同步播放进度 " + lastCurrent + "s / " + lastDuration + "s");
        callJs("window.__ltv && window.__ltv.castStatus("
                + JSONObject.quote("正在把手机进度 " + fmtTime(lastCurrent) + " 推给电视…") + ")");
        cast.seek(lastCurrent, castControlCallback("已同步到 " + fmtTime(lastCurrent)));
    }

    private void stopCast() {
        if (cast == null) return;
        appLog.i("cast", "用户停止投屏");
        /* 界面立刻回「已停止」，不等 SOAP 往返 —— 电视没响应时要等满 8 秒读超时，
           点了停止却看不出变化，用户只会认为功能坏了。 */
        castDevice = null;
        castPaused = false;
        stopCastPolling();
        updateCastToggle();
        callJs("window.__ltv && window.__ltv.castPaused(false)");
        callJs("window.__ltv && window.__ltv.castState('idle', '已停止投屏')");
        cast.stop(castControlCallback("已停止投屏"));
    }

    private void updateCastToggle() {
        DlnaCast.Device d = cast != null ? cast.current() : null;
        if (fabCast != null) {
            fabCast.setText(d != null ? "投屏中 · 管理" : "投屏到电视");
        }
        /* 网页 DOM 里那颗投屏按钮是用户真正看得见的那颗（原生层全屏时可能不上屏），
           文案必须跟着投屏状态一起变，否则点了没反应用户会以为功能坏了。 */
        callJs("window.__ltv && window.__ltv.setCast('" + (d != null ? "投屏中" : "投屏") + "')");
    }

    /**
     * 兜底通道：DLNA 依赖「视频直链 + 电视支持」，若任一不满足，用系统无线显示
     * 把整个手机画面镜像出去——任何内容都能投，只是手机不能息屏。
     */
    private void openSystemCast() {
        appLog.i("cast", "打开系统投屏设置（屏幕镜像兜底）");
        try {
            Intent i = new Intent(android.provider.Settings.ACTION_CAST_SETTINGS);
            if (i.resolveActivity(getPackageManager()) != null) { startActivity(i); return; }
        } catch (Exception ignored) { }
        try {
            Intent i = new Intent("android.settings.WIFI_DISPLAY_SETTINGS");
            if (i.resolveActivity(getPackageManager()) != null) { startActivity(i); return; }
        } catch (Exception ignored) { }
        try {
            startActivity(new Intent(android.provider.Settings.ACTION_WIFI_SETTINGS));
            toast("在系统设置里找「投屏 / 无线显示 / 多屏互动」并选择电视");
        } catch (Exception e) {
            toast("本机没有可用的系统投屏入口");
        }
    }

    /** 亮度循环：跟随系统 → 60% → 100%。仅作用于当前 Activity 窗口。 */
    private void cycleBrightness() {
        brightnessStep = (brightnessStep + 1) % 3;
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        if (brightnessStep == 0) {
            lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
            toast("亮度：跟随系统");
        } else if (brightnessStep == 1) {
            lp.screenBrightness = 0.6f;
            toast("亮度：60%");
        } else {
            lp.screenBrightness = 1.0f;
            toast("亮度：100%");
        }
        getWindow().setAttributes(lp);
    }

    /** 进入 / 退出全屏播放态：沉浸模式默认无任何大控制条，视频铺满屏幕。 */
    private void applyPlaybackMode(boolean on) {
        boolean changed = playbackMode != on;
        playbackMode = on;
        appLog.i("player", "播放态 -> " + on + " changed=" + changed
                + " domUi=" + domUiActive + " customView=" + (customView != null)
                + " immersive=" + prefs.getBoolean(KEY_IMMERSIVE_PLAYER, true));
        if (on) {
            if (prefs.getBoolean(KEY_AUTO_LANDSCAPE, true)) {
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
            }
            if (sheetVisible()) hideSheet();
            if (overlayVisible()) hideOverlay();
            bottomBar.setVisibility(View.GONE);
            fabRestore.setVisibility(View.GONE);

            // 网页控制栏全程让位；视频按用户选择的模式铺满
            callJs("window.__ltv && window.__ltv.hideWebControls()");
            callJs("window.__ltv && window.__ltv.setNativeBar(true)");
            callJs("window.__ltv && window.__ltv.setFit('" + fitMode() + "')");
            /* 播放态强制显示网页里的按钮行（退出全屏 / 投屏）。
               默认判定靠 isFullscreen()，但有些机型全屏类名判不出来，
               那时按钮会跟着一起消失 —— 用户就再也找不到投屏了。 */
            callJs("window.__ltv && window.__ltv.setUiVisible(true)");

            boolean immersivePlay = prefs.getBoolean(KEY_IMMERSIVE_PLAYER, true);
            if (changed) {
                webControlsMode = false;
                cancelControlHide();
                hideAdvancedPanel();
                setImmersiveLayers(immersivePlay);
                if (immersivePlay) {
                    if (progressLine != null) progressLine.invalidate();
                    if (!prefs.getBoolean("immersive_hint_shown", false)) {
                        toast("沉浸播放：单击暂停 / 左右滑快进 / 长按出面板 / 左上 ✕ 退出");
                        prefs.edit().putBoolean("immersive_hint_shown", true).apply();
                    }
                } else {
                    setImmersiveLayers(false);
                    showPlaybackBarTemporarily();
                }
            } else {
                setImmersiveLayers(immersivePlay);
            }
        } else {
            // 退出全屏必须撤掉所有覆盖样式与手势层，否则竖屏小播放器会没有网页控制栏
            cancelControlHide();
            webControlsMode = false;
            advancedPanelVisible = false;
            setImmersiveLayers(false);
            callJs("window.__ltv && window.__ltv.hideWebControls()");
            callJs("window.__ltv && window.__ltv.setNativeBar(false)");
            /* 回到网页自己的可见性判定：竖屏小窗里不该再顶着一行按钮 */
            callJs("window.__ltv && window.__ltv.setUiVisible(null)");
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
            bottomBar.setVisibility(immersive ? View.GONE : View.VISIBLE);
            fabRestore.setVisibility(immersive ? View.VISIBLE : View.GONE);
            playbackBar.animate().cancel();
            playbackBar.setAlpha(1f);
            playbackBar.setVisibility(View.GONE);
            playbackBar.setClickable(false);
            brightnessStep = 0;
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
            getWindow().setAttributes(lp);
        }
        syncSystemUi();
        // 退出口的最终状态由 playbackMode 决定，这里做一次兜底同步
        syncExitBtn();
        updateFabFullscreen();
    }

    private void exitPlayback() {
        fsFallbackCancelled = true;
        callJs("window.__ltv && window.__ltv.exit()");
        if (customView != null) hideCustomView();
        applyPlaybackMode(false);
    }

    /** 统一管理系统栏与常亮，避免沉浸模式、全屏播放、custom view 互相清掉对方的标志。 */
    private void syncSystemUi() {
        boolean hideBars = immersive || playbackMode || customView != null;
        if (hideBars) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            // 连导航栏一起隐藏并允许内容布局到系统栏下方，视频才能真正铺满整块屏幕
            if (web != null) {
                web.setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            }
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            if (web != null) web.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        }
        if (playbackMode || customView != null) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    /** 页面脚本用它上报全屏状态；只切 UI，不读取任何本地数据。 */
    private class LtvBridge {
        @JavascriptInterface
        public void onFullscreen(final boolean on) {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    String u = web != null ? web.getUrl() : null;
                    if (u == null || currentUrl.isEmpty() || !u.startsWith(currentUrl)) {
                        /* 之前这里静默 return —— 全屏明明是网页报的，却被丢掉之后
                           原生层永远不进播放态，表现就是「全屏里什么都没有」。
                           现在把丢弃原因写进日志，下次排障不用再猜。 */
                        appLog.w("player", "全屏上报被丢弃 on=" + on + " url=" + u
                                + " base=" + currentUrl);
                        return;
                    }
                    appLog.i("player", "网页上报全屏状态 " + on + " url=" + u);
                    applyPlaybackMode(on);
                }
            });
        }

        /** 网页里那颗「✕ 退出全屏」被点击：走和原生按钮完全相同的退出流程。 */
        @JavascriptInterface
        public void exitFullscreen() {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    appLog.i("player", "网页内「退出全屏」被点击");
                    exitPlayback();
                }
            });
        }

        /**
         * 网页里那颗「投屏」按钮被点击 / 面板里点「重新搜索」。
         * 入口画在网页 DOM 里而不是原生层：全屏时原生覆盖层在部分机型（华为等）不上屏，
         * 用户反馈「软件投屏功能没找到」—— 当时那颗原生按钮就在不上屏的那一层里。
         * （1.2.4 起原生那颗已删除：网页层既然一定可见，两处并存就变成「两个投屏图标」。）
         * 这里只负责发起 SSDP 搜索，进度与设备列表全部回写到网页面板。
         */
        @JavascriptInterface
        public void cast() {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    appLog.i("cast", "网页内投屏入口被点击 / 重新搜索");
                    startCastDiscovery();
                }
            });
        }

        /** cast() 的同义入口：个别 WebView 对短方法名有奇怪处理，留一个别名兜底。 */
        @JavascriptInterface
        public void castStart() {
            cast();
        }

        /** 面板里点了某台设备：索引与 castDevices 一一对应。 */        @JavascriptInterface
        public void castPick(final int index) {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    if (index < 0 || index >= castDevices.size()) {
                        callJs("window.__ltv && window.__ltv.castStatus('设备已失效，请重新搜索')");
                        return;
                    }
                    castTo(castDevices.get(index));
                }
            });
        }

        @JavascriptInterface
        public void castStop() {
            runOnUiThread(new Runnable() {
                @Override public void run() { stopCast(); }
            });
        }

        @JavascriptInterface
        public void castSync() {
            runOnUiThread(new Runnable() {
                @Override public void run() { syncCastProgress(); }
            });
        }

        /** 面板上的「暂停 / 继续」：true = 暂停，false = 继续。 */
        @JavascriptInterface
        public void castPause(final boolean pause) {
            runOnUiThread(new Runnable() {
                @Override public void run() { pauseCast(pause); }
            });
        }

        /** 面板进度条松手：单位秒。JS 侧传字符串（input.value 天生是 string），这里解析。 */
        @JavascriptInterface
        public void castSeek(final String seconds) {
            double s = 0;
            try { s = Double.parseDouble(String.valueOf(seconds).trim()); }
            catch (Exception e) { s = 0; }
            final double sec = s;
            runOnUiThread(new Runnable() {
                @Override public void run() { seekCast(sec); }
            });
        }

        /** 面板打开时轮询电视进度；关掉就停，别白白占用局域网。 */
        @JavascriptInterface
        public void castPoll(final boolean on) {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    if (on) startCastPolling(); else stopCastPolling();
                }
            });
        }

        @JavascriptInterface
        public void castSystem() {
            runOnUiThread(new Runnable() {
                @Override public void run() { openSystemCast(); }
            });
        }

        /**
         * 投屏面板开着的时候，全屏手势层必须让开整屏：
         * 面板里的设备行 / 按钮可能在任意位置，手势层吃掉 DOWN 就点不动。
         * 同时负责起停进度轮询 —— 面板不在屏幕上时不必每 2 秒问一次电视。
         */
        @JavascriptInterface
        public void castPanel(final boolean open) {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    castPanelOpen = open;
                    if (open) startCastPolling(); else stopCastPolling();
                }
            });
        }

        /** 网页已自己绘制退出口与进度线，原生层据此不再重复显示。 */
        @JavascriptInterface
        public void setDomUi(final boolean on) {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    if (domUiActive == on) return;
                    domUiActive = on;
                    appLog.i("player", "网页内 UI " + (on ? "已就绪（原生退出口/进度线让位）"
                                                         : "不可用（回退到原生层）"));
                    syncExitBtn();
                    syncProgressLine();
                }
            });
        }

        /** 网页把关键判定结果写进日志中心，方便真机排障。 */
        @JavascriptInterface
        public void log(final String msg) {
            appLog.i("webview", msg == null ? "null" : msg);
        }

        /** 页面上出现/移除 <video>：决定是否显示常驻「全屏播放」按钮。 */
        @JavascriptInterface
        public void onVideo(final boolean on) {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    hasVideo = on;
                    if (!on) lastDuration = 0;
                    updateFabFullscreen();
                }
            });
        }

        /** 网页 timeupdate 节流上报，驱动原生 SeekBar 与「当前/总时长」。 */
        @JavascriptInterface
        public void onProgress(final double current, final double duration) {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    lastDuration = duration;
                    lastCurrent = current;
                    /* 重绘条件原来是 immersiveLayersOn —— 关掉沉浸播放后这条线虽然可见却
                       永远不重绘，画面里就是一片空白（用户反馈「看不到进度条」）。
                       改成跟着 playbackMode 走，并且顺手自愈一次可见性：
                       任何原因把它收起，下一次进度上报（约 450ms）就会恢复。 */
                    if (progressLine != null && playbackMode) {
                        if (progressLine.getVisibility() != View.VISIBLE
                                && !advancedPanelVisible && !webControlsMode) {
                            syncProgressLine();
                        }
                        progressLine.invalidate();
                    }
                    // 自动隐藏兜底：由播放进度驱动，不依赖 Handler 一定触发
                    hidePanelIfIdle();
                    if (seekDragging || playbackSeek == null) return;
                    if (duration > 0) {
                        int p = (int) Math.max(0, Math.min(1000, current * 1000.0 / duration));
                        playbackSeek.setProgress(p);
                    }
                    if (playbackTime != null) {
                        playbackTime.setText(fmtTime(current) + " / " + fmtTime(duration));
                    }
                }
            });
        }

        /** 投屏：网页拦截 hls.js / video.src 拿到 m3u8、mp4 直链后上报。 */
        @JavascriptInterface
        public void onStream(final String url) {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
                        lastStreamUrl = url;
                    }
                }
            });
        }

        /** 自检脚本把搜索链路的诊断结果回传，只展示文本，不执行任何操作。 */
        @JavascriptInterface
        public void onDiagnosis(final String text) {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    appLog.i("diag", text);
                    new android.app.AlertDialog.Builder(MainActivity.this)
                            .setTitle("搜索链路自检")
                            .setMessage(text)
                            .setPositiveButton("关闭", null)
                            .show();
                }
            });
        }
    }

    private View navButton(int iconRes, String label, View.OnClickListener listener) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(2), dp(6), dp(2), dp(5));
        TypedValue tv = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        box.setBackgroundResource(tv.resourceId);
        box.setClickable(true);
        box.setFocusable(true);
        box.setOnClickListener(listener);

        ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        icon.setColorFilter(TEXT);
        box.addView(icon, new LinearLayout.LayoutParams(dp(22), dp(22)));

        TextView text = new TextView(this);
        text.setText(label);
        text.setTextSize(11);
        text.setTextColor(TEXT_DIM);
        text.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        tp.topMargin = dp(3);
        box.addView(text, tp);

        box.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        return box;
    }

    private EditText styledEdit(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        e.setTextSize(16);
        e.setTextColor(TEXT);
        e.setHintTextColor(TEXT_DIM);
        e.setBackground(round(SURFACE2, 10));
        e.setPadding(dp(14), dp(12), dp(14), dp(12));
        return e;
    }

    private Button primaryButton(String label, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(16);
        b.setTextColor(Color.WHITE);
        b.setAllCaps(false);
        b.setMinHeight(dp(48));
        b.setMinimumHeight(dp(48));
        b.setBackground(round(ACCENT, 10));
        b.setOnClickListener(listener);
        return b;
    }

    private Button textButton(String label, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(14);
        b.setTextColor(ACCENT);
        b.setAllCaps(false);
        b.setMinHeight(dp(44));
        b.setMinimumHeight(dp(44));
        b.setBackgroundColor(Color.TRANSPARENT);
        b.setOnClickListener(listener);
        return b;
    }

    private void buildOverlay() {
        overlay = new LinearLayout(this);
        overlay.setOrientation(LinearLayout.VERTICAL);
        overlay.setGravity(Gravity.CENTER);
        overlay.setBackgroundColor(BG);
        overlay.setClickable(true);
        overlay.setPadding(dp(24), dp(24), dp(24), dp(24));
        root.addView(overlay, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(round(SURFACE, 18));
        card.setPadding(dp(22), dp(26), dp(22), dp(22));
        overlay.addView(card, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView brand = new TextView(this);
        brand.setText("▶  LibreTV");
        brand.setTextSize(22);
        brand.setTextColor(ACCENT);
        brand.setGravity(Gravity.CENTER);
        brand.setPadding(0, 0, 0, dp(14));
        card.addView(brand);

        overlayTitle = new TextView(this);
        overlayTitle.setTextSize(19);
        overlayTitle.setTextColor(TEXT);
        overlayTitle.setGravity(Gravity.CENTER);
        card.addView(overlayTitle, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        overlayHint = new TextView(this);
        overlayHint.setTextSize(14);
        overlayHint.setTextColor(TEXT_DIM);
        overlayHint.setLineSpacing(dp(3), 1f);
        overlayHint.setPadding(0, dp(12), 0, dp(18));
        card.addView(overlayHint, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        overlayEdit = styledEdit(DEFAULT_EXTERNAL_URL);
        card.addView(overlayEdit, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        overlayPrimary = primaryButton("连接", new View.OnClickListener() {
            @Override public void onClick(View v) { onPrimaryClick(); }
        });
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        pp.topMargin = dp(16);
        card.addView(overlayPrimary, pp);

        overlaySecondary = textButton("重新导入内置源", new View.OnClickListener() {
            @Override public void onClick(View v) { onSecondaryClick(); }
        });
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        sp.topMargin = dp(4);
        card.addView(overlaySecondary, sp);
    }

    private void buildSheet() {
        scrim = new View(this);
        scrim.setBackgroundColor(0xAA000000);
        scrim.setClickable(true);
        scrim.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { hideSheet(); }
        });
        scrim.setVisibility(View.GONE);
        root.addView(scrim, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        ScrollView host = new ScrollView(this);
        host.setBackground(round(SURFACE, 18));
        host.setPadding(dp(20), dp(18), dp(20), dp(24));
        host.setVisibility(View.GONE);
        FrameLayout.LayoutParams hostLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        root.addView(host, hostLp);
        sheetHost = host;

        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        host.addView(sheet, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        sheet.addView(header, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        TextView title = new TextView(this);
        title.setText("设置");
        title.setTextSize(19);
        title.setTextColor(TEXT);
        header.addView(title, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView ver = new TextView(this);
        ver.setText("LibreTV " + versionLine());
        ver.setTextSize(12);
        ver.setTextColor(TEXT_DIM);
        header.addView(ver, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        Button close = textButton("关闭", new View.OnClickListener() {
            @Override public void onClick(View v) { hideSheet(); }
        });
        header.addView(close);

        localModeBox = new CheckBox(this);
        localModeBox.setText("使用手机本地服务（不依赖电脑）");
        localModeBox.setTextColor(TEXT);
        localModeBox.setTextSize(14);
        localModeBox.setButtonTintList(ColorStateList.valueOf(ACCENT));
        localModeBox.setChecked(localMode);
        sheet.addView(localModeBox, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        // 投屏入口放在设置面板靠上的位置：用户找功能最先翻这里，
        // 播放界面右下角的悬浮按钮在深色画面或小屏上容易被忽略。
        TextView castEntry = new TextView(this);
        castEntry.setText("投屏到电视（DLNA）");
        castEntry.setTextColor(Color.WHITE);
        castEntry.setTextSize(15);
        castEntry.setGravity(Gravity.CENTER);
        castEntry.setMinHeight(dp(52));
        castEntry.setBackground(round(0xDD2FA36B, 14));
        castEntry.setClickable(true);
        castEntry.setFocusable(true);
        castEntry.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { hideSheet(); showCastDialog(); }
        });
        LinearLayout.LayoutParams castEntryLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        castEntryLp.topMargin = dp(12);
        castEntryLp.bottomMargin = dp(4);
        sheet.addView(castEntry, castEntryLp);

        lanModeBox = new CheckBox(this);
        lanModeBox.setText("允许同一 Wi-Fi 的其它设备访问本机服务");
        lanModeBox.setTextColor(TEXT);
        lanModeBox.setTextSize(13);
        lanModeBox.setButtonTintList(ColorStateList.valueOf(ACCENT));
        lanModeBox.setChecked(lanMode);
        sheet.addView(lanModeBox, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView label = new TextView(this);
        label.setText("外部服务地址（关闭本地服务时填写）");
        label.setTextSize(13);
        label.setTextColor(TEXT_DIM);
        label.setPadding(0, dp(10), 0, dp(6));
        sheet.addView(label);

        settingsEdit = styledEdit(DEFAULT_EXTERNAL_URL);
        sheet.addView(settingsEdit, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        portEdit = new EditText(this);
        portEdit.setHint("本地端口");
        portEdit.setSingleLine(true);
        portEdit.setInputType(InputType.TYPE_CLASS_NUMBER);
        portEdit.setText(String.valueOf(localPort));
        portEdit.setTextSize(15);
        portEdit.setTextColor(TEXT);
        portEdit.setHintTextColor(TEXT_DIM);
        portEdit.setBackground(round(SURFACE2, 10));
        portEdit.setPadding(dp(14), dp(12), dp(14), dp(12));

        passwordEdit = new EditText(this);
        passwordEdit.setHint("访问密码（留空=不校验）");
        passwordEdit.setSingleLine(true);
        passwordEdit.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        passwordEdit.setText(localPassword);
        passwordEdit.setTextSize(15);
        passwordEdit.setTextColor(TEXT);
        passwordEdit.setHintTextColor(TEXT_DIM);
        passwordEdit.setBackground(round(SURFACE2, 10));
        passwordEdit.setPadding(dp(14), dp(12), dp(14), dp(12));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        half.setMargins(0, dp(10), dp(6), 0);
        row.addView(portEdit, half);
        LinearLayout.LayoutParams half2 = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        half2.setMargins(dp(6), dp(10), 0, 0);
        row.addView(passwordEdit, half2);
        sheet.addView(row, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        Button save = primaryButton("保存并应用", new View.OnClickListener() {
            @Override public void onClick(View v) { onSheetSave(); }
        });
        LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        saveLp.topMargin = dp(12);
        sheet.addView(save, saveLp);

        Button diagnose = textButton("网络与搜索自检（查测活失败原因）", new View.OnClickListener() {
            @Override public void onClick(View v) { runDiagnosis(); }
        });
        sheet.addView(diagnose, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        Button envProbe = textButton("运行环境体检（Node 错误码，查测活全失败）", new View.OnClickListener() {
            @Override public void onClick(View v) { showEnvProbe(); }
        });
        sheet.addView(envProbe, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        Button reimport = textButton("重新导入内置源", new View.OnClickListener() {
            @Override public void onClick(View v) { hideSheet(); injectPreset(true); }
        });
        sheet.addView(reimport, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        // 源被「连续失败自动停用」之后，网页端只有切到「已停用」筛选才露出逐个恢复按钮，
        // 手机上基本找不到。这里给一个一键入口（含实时状态行），见 restoreAllSources()。
        Button restoreAll = textButton("一键恢复所有源（解除自动停用）", new View.OnClickListener() {
            @Override public void onClick(View v) { restoreAllSources(); }
        });
        sheet.addView(restoreAll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        restoreHint = new TextView(this);
        restoreHint.setText("正在读取被自动停用的源…");
        restoreHint.setTextSize(12);
        restoreHint.setTextColor(TEXT_DIM);
        restoreHint.setPadding(dp(4), 0, dp(4), dp(8));
        sheet.addView(restoreHint, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        Button sourceMenu = textButton("源管理（导入 / 导出）", new View.OnClickListener() {
            @Override public void onClick(View v) { showSourceMenu(); }
        });
        sheet.addView(sourceMenu, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        Button logMenu = textButton("日志中心（查看 / 一键分享 / 保存）", new View.OnClickListener() {
            @Override public void onClick(View v) { showLogCenter(); }
        });
        sheet.addView(logMenu, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        Button logBtn = textButton("查看本地服务日志", new View.OnClickListener() {
            @Override public void onClick(View v) { showLog(); }
        });
        sheet.addView(logBtn, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        desktopBox = new CheckBox(this);
        desktopBox.setText("桌面模式（以电脑版页面显示）");
        desktopBox.setTextColor(TEXT);
        desktopBox.setTextSize(14);
        desktopBox.setButtonTintList(ColorStateList.valueOf(ACCENT));
        desktopBox.setChecked(prefs.getBoolean(KEY_DESKTOP, false));
        desktopBox.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean checked) {
                prefs.edit().putBoolean(KEY_DESKTOP, checked).apply();
                applyUserAgent();
                if (web != null) web.reload();
            }
        });
        sheet.addView(desktopBox, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        mobileCssBox = new CheckBox(this);
        mobileCssBox.setText("移动端页面适配（播放器满宽、画面等比、防横向溢出）");
        mobileCssBox.setTextColor(TEXT);
        mobileCssBox.setTextSize(14);
        mobileCssBox.setButtonTintList(ColorStateList.valueOf(ACCENT));
        mobileCssBox.setChecked(prefs.getBoolean(KEY_MOBILE_CSS, true));
        mobileCssBox.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean checked) {
                prefs.edit().putBoolean(KEY_MOBILE_CSS, checked).apply();
                if (web != null) web.reload();
            }
        });
        sheet.addView(mobileCssBox, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        landscapeBox = new CheckBox(this);
        landscapeBox.setText("全屏播放时自动横屏");
        landscapeBox.setTextColor(TEXT);
        landscapeBox.setTextSize(14);
        landscapeBox.setButtonTintList(ColorStateList.valueOf(ACCENT));
        landscapeBox.setChecked(prefs.getBoolean(KEY_AUTO_LANDSCAPE, true));
        landscapeBox.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean checked) {
                prefs.edit().putBoolean(KEY_AUTO_LANDSCAPE, checked).apply();
                if (playbackMode) {
                    setRequestedOrientation(checked
                            ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                            : ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
                }
            }
        });
        sheet.addView(landscapeBox, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        playbackBarBox = new CheckBox(this);
        playbackBarBox.setText("全屏时启用悬浮控制条（含可拖拽进度条）");
        playbackBarBox.setTextColor(TEXT);
        playbackBarBox.setTextSize(14);
        playbackBarBox.setButtonTintList(ColorStateList.valueOf(ACCENT));
        playbackBarBox.setChecked(prefs.getBoolean(KEY_PLAYBACK_BAR, true));
        playbackBarBox.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean checked) {
                prefs.edit().putBoolean(KEY_PLAYBACK_BAR, checked).apply();
                if (!playbackMode) return;
                if (checked) {
                    showPlaybackBarTemporarily();
                } else {
                    cancelControlHide();
                    playbackBar.animate().cancel();
                    playbackBar.setAlpha(1f);
                    playbackBar.setVisibility(View.GONE);
                    // 关掉原生条时把网页栏还给页面，避免一个控制入口都没有
                    callJs("window.__ltv && window.__ltv.hideWebControls()");
                }
            }
        });
        sheet.addView(playbackBarBox, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        CheckBox immersiveBox = new CheckBox(this);
        immersiveBox.setText("抖音式沉浸播放（无遮挡 / 单击暂停 / 滑动快进）");
        immersiveBox.setTextColor(TEXT);
        immersiveBox.setTextSize(14);
        immersiveBox.setButtonTintList(ColorStateList.valueOf(ACCENT));
        immersiveBox.setChecked(prefs.getBoolean(KEY_IMMERSIVE_PLAYER, true));
        immersiveBox.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean checked) {
                prefs.edit().putBoolean(KEY_IMMERSIVE_PLAYER, checked).apply();
                appLog.i("player", "沉浸播放开关：" + (checked ? "开" : "关（经典控制条）"));
                if (!playbackMode) return;
                hideAdvancedPanel();
                setImmersiveLayers(checked);
                if (checked) toast("已切到沉浸播放：单击暂停 / 左右滑快进 / 右上 ⋯ 更多");
                else showPlaybackBarTemporarily();
            }
        });
        sheet.addView(immersiveBox, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        CheckBox fitBox = new CheckBox(this);
        /* 默认不勾：完整显示画面。勾上才会在横屏用 cover 铺满，代价是画面上下被裁掉
           （16:9 的视频在 20:9 的屏幕上必然如此）。 */
        fitBox.setText("横屏铺满屏幕（会裁掉上下，竖屏始终完整）");
        fitBox.setTextColor(TEXT);
        fitBox.setTextSize(14);
        fitBox.setButtonTintList(ColorStateList.valueOf(ACCENT));
        fitBox.setChecked(!"contain".equals(fitMode()));
        fitBox.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean checked) {
                String m = checked ? "cover" : "contain";
                prefs.edit().putString(KEY_FIT_MODE, m).apply();
                callJs("window.__ltv && window.__ltv.setFit('" + m + "')");
                toast(checked ? "横屏：铺满（上下会被裁掉）" : "画面：完整显示（有黑边，不裁切）");
                appLog.i("player", "画面铺满开关：" + (checked ? "开" : "关"));
            }
        });
        sheet.addView(fitBox, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        CheckBox autoHideBox = new CheckBox(this);
        autoHideBox.setText("控制条自动隐藏（关闭则常驻）");
        autoHideBox.setTextColor(TEXT);
        autoHideBox.setTextSize(14);
        autoHideBox.setButtonTintList(ColorStateList.valueOf(ACCENT));
        autoHideBox.setChecked(prefs.getBoolean(KEY_CONTROL_AUTOHIDE, true));
        autoHideBox.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean checked) {
                prefs.edit().putBoolean(KEY_CONTROL_AUTOHIDE, checked).apply();
                if (!playbackMode) return;
                cancelControlHide();
                if (checked) {
                    if (immersiveLayersOn) hideAdvancedPanel();
                    else hidePlaybackBar();
                } else {
                    showPlaybackBarTemporarily();
                }
            }
        });
        sheet.addView(autoHideBox, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        serverStatLine = new TextView(this);
        serverStatLine.setTextSize(12);
        serverStatLine.setTextColor(ACCENT);
        serverStatLine.setPadding(0, dp(12), 0, 0);
        serverStatLine.setLineSpacing(dp(3), 1f);
        sheet.addView(serverStatLine);
        updateServerStat();

        statLine = new TextView(this);
        statLine.setTextSize(12);
        statLine.setTextColor(TEXT_DIM);
        statLine.setPadding(0, dp(8), 0, 0);
        statLine.setLineSpacing(dp(3), 1f);
        sheet.addView(statLine);
        updateStatLine();
    }

    /** 设置面板统一提交：模式、端口、密码、局域网共享一次性生效。 */
    private void onSheetSave() {
        boolean useLocal = localModeBox.isChecked();
        int port;
        try {
            port = Integer.parseInt(portEdit.getText().toString().trim());
        } catch (NumberFormatException e) {
            toast("端口必须是数字");
            return;
        }
        if (port < 1024 || port > 65535) {
            toast("端口需在 1024~65535 之间");
            return;
        }
        String pw = passwordEdit.getText().toString();
        boolean lan = lanModeBox.isChecked();

        // 空密码 = 不校验：只监听 127.0.0.1 时没问题，但开启局域网共享就等于把
        // 一个无鉴权的服务暴露给整个网段，这里直接挡住，避免默认配置被误开成公用服务。
        if (lan && pw.trim().isEmpty()) {
            toast("开启局域网共享前，请先设置访问密码（默认的随机密码只有本机知道）");
            return;
        }

        prefs.edit()
                .putBoolean(KEY_LOCAL_MODE, useLocal)
                .putBoolean(KEY_LAN, lan)
                .putInt(KEY_PORT, port)
                .putString(KEY_PASSWORD, pw)
                .apply();
        localMode = useLocal;
        lanMode = lan;
        localPort = port;
        localPassword = pw;
        effectivePassword = resolvePassword();
        hideSheet();
        updateServerStat();

        if (localMode) {
            restartLocal();
        } else {
            localServer.stop();
            String u = normalize(settingsEdit.getText().toString());
            if (u == null) {
                toast("外部地址无效，例如 192.168.1.100:8899");
                return;
            }
            saveAndLoad(u);
        }
    }

    private void updateStatLine() {
        if (statLine == null) return;
        statLine.setText("服务端预置源："
                + (serverEnvSourceCount < 0 ? "探测中…" : serverEnvSourceCount + " 条")
                + "（页面自动勾选，不依赖浏览器存储）\n"
                + "APK 内置源 " + presetCount + " 条 · 直播 " + presetLiveCount
                + " 条 · 网页本地源 " + (pageSourceCount < 0 ? "读取中…" : pageSourceCount + " 条")
                + " · 客户端 " + appVersion() + "\n"
                + "当前服务：" + (currentUrl.isEmpty() ? "未连接" : currentUrl));
    }

    /** 在网页里跑一次真实搜索，把 401 / 无源 / 出网失败 直接显示出来。 */
    private void runDiagnosis() {
        if (web == null || diagnoseTemplate.isEmpty()) {
            toast("自检脚本尚未就绪");
            return;
        }
        hideSheet();
        appLog.i("diag", "开始搜索链路自检");
        toast("正在自检，最多约 20 秒…");
        web.evaluateJavascript(diagnoseTemplate, null);
    }

    // ---------------------------------------------------------------- 面板显隐

    private void showSheet() {
        settingsEdit.setText(prefs.getString(KEY_URL, DEFAULT_EXTERNAL_URL));
        portEdit.setText(String.valueOf(localPort));
        passwordEdit.setText(localPassword);
        localModeBox.setChecked(localMode);
        lanModeBox.setChecked(lanMode);
        updateStatLine();
        updateServerStat();
        refreshSourceCount();
        refreshDisabledSources();
        scrim.setVisibility(View.VISIBLE);
        sheetHost.setVisibility(View.VISIBLE);
        sheetHost.setTranslationY(dp(320));
        sheetHost.animate().translationY(0).setDuration(220).start();
    }

    private void hideSheet() {
        if (sheetHost.getVisibility() != View.VISIBLE) return;
        sheetHost.animate().translationY(dp(320)).setDuration(180)
                .withEndAction(new Runnable() {
                    @Override public void run() {
                        sheetHost.setVisibility(View.GONE);
                        scrim.setVisibility(View.GONE);
                    }
                }).start();
    }

    private boolean sheetVisible() {
        return sheetHost != null && sheetHost.getVisibility() == View.VISIBLE;
    }

    private void applyImmersive(boolean on) {
        immersive = on;
        bottomBar.setVisibility((on || playbackMode) ? View.GONE : View.VISIBLE);
        fabRestore.setVisibility((on && !playbackMode) ? View.VISIBLE : View.GONE);
        if (on) toast("沉浸模式：点右下角或按返回键恢复工具栏");
        syncSystemUi();
        // 退出口的最终状态由 playbackMode 决定，这里做一次兜底同步
        syncExitBtn();
        updateFabFullscreen();
    }

    private void showSetup(String title, String hint, String url) {
        overlayMode = 1;
        showCard(title, hint, url, "连接并保存", "重新导入内置源");
    }

    private void showError(String description, String failingUrl) {
        overlayMode = 2;
        showCard("连接失败",
                "地址：" + failingUrl + "\n错误：" + description + "\n\n"
                        + "请依次检查：\n"
                        + "1  手机与电脑在同一 Wi-Fi\n"
                        + "2  服务端绑定 0.0.0.0（.env.local 的 LIBRETV_HOST）\n"
                        + "3  服务已启动、防火墙未拦截\n"
                        + "4  端口是否正确（默认 8899）",
                currentUrl.isEmpty() ? DEFAULT_URL : currentUrl,
                "重试连接", "用原地址重试");
    }

    private void showCard(String title, String hint, String url,
                          String primary, String secondary) {
        overlayTitle.setText(title);
        overlayHint.setText(hint);
        overlayEdit.setText(url);
        overlayPrimary.setText(primary);
        overlaySecondary.setText(secondary);
        overlay.setVisibility(View.VISIBLE);
        overlay.bringToFront();
        progress.setVisibility(View.GONE);
    }

    private void hideOverlay() {
        overlay.setVisibility(View.GONE);
    }

    private boolean overlayVisible() {
        return overlay != null && overlay.getVisibility() == View.VISIBLE;
    }

    private void onPrimaryClick() {
        if (localMode) {
            if (overlayMode == 3 || overlayMode == 5) switchToExternal();
            else restartLocal();
            return;
        }
        String u = normalize(overlayEdit.getText().toString());
        if (u == null) {
            toast("地址无效，例如 192.168.1.100:8899");
            return;
        }
        saveAndLoad(u);
    }

    private void onSecondaryClick() {
        if (overlayMode == 3 || overlayMode == 5) {
            localMode = true;
            prefs.edit().putBoolean(KEY_LOCAL_MODE, true).apply();
            if (localModeBox != null) localModeBox.setChecked(true);
            startLocalService();
            return;
        }
        if (overlayMode == 4) {
            switchToExternal();
            return;
        }
        if (overlayMode == 2) {
            if (!currentUrl.isEmpty()) {
                hideOverlay();
                load(currentUrl);
            }
            return;
        }
        injectPreset(true);
    }

    // ---------------------------------------------------------------- 内置源

    private void loadPreset() {
        presetJson = readAsset("libretv-settings.json");
        injectTemplate = readAsset("inject.js");
        autoLoginTemplate = readAsset("auto-login.js");
        diagnoseTemplate = readAsset("diagnose.js");
        importTemplate = readAsset("import-sources.js");
        exportTemplate = readAsset("export-sources.js");
        try {
            JSONObject counts = new JSONObject(readAsset("sources.json"))
                    .getJSONObject("counts");
            presetCount = counts.optInt("vod", 0);
            presetLiveCount = counts.optInt("live", 0);
        } catch (Exception e) {
            presetCount = 0;
            presetLiveCount = 0;
        }
    }

    private String readAsset(String name) {
        try {
            InputStream in = getAssets().open(name);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            in.close();
            return out.toString("UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 每次主框架加载完都跑一次「幂等注入」，不再依赖全局已导入标记。
     * 换服务地址 / 本地↔外部模式切换 / 清缓存 / 覆盖安装后，新 origin 的
     * localStorage 是空的，只有这样才能自动把内置源补回来。
     */
    private void maybeInjectPreset(String url, boolean force) {
        if (presetJson.isEmpty() || injectTemplate.isEmpty() || web == null) return;
        if (!force && (currentUrl.isEmpty() || !url.startsWith(currentUrl))) return;
        // 服务端已下发预置源时，页面会自动勾选 env_*；再往 localStorage 注入一份
        // 会变成 47+47 条重复，/api/search 的 slice(0,50) 反而把可用源挤掉。
        if (!force && serverEnvSourceCount > 0) {
            // 点播源由服务端下发即可，但仍要把直播订阅带进去
            web.postDelayed(new Runnable() {
                @Override public void run() { injectLiveOnly(); }
            }, 700);
            return;
        }
        web.postDelayed(new Runnable() {
            @Override public void run() { injectPreset(force); }
        }, 700);
    }

    /** 服务端已有预置点播源时，只合并直播订阅，避免点播源重复。 */
    private void injectLiveOnly() {
        if (web == null || presetJson.isEmpty() || injectTemplate.isEmpty()) return;
        try {
            JSONObject trimmed = new JSONObject(presetJson);
            JSONObject st = trimmed.getJSONObject("state");
            st.put("customAPIs", new org.json.JSONArray());
            st.put("selectedKeys", new org.json.JSONArray());
            web.evaluateJavascript(injectTemplate.replace(
                    "__PRESET__", JSONObject.quote(trimmed.toString())), null);
        } catch (Exception ignored) {
            // 快照结构变化时静默跳过，点播源仍由服务端提供
        }
    }

    /**
     * 探测服务端是否下发了 DEFAULT_SOURCES（/api/status 无需登录）。
     * 按服务地址缓存结果；切换本地/外部模式、改端口会重新探测。
     * 探测失败按「无预置源」处理，自动回落到 localStorage 注入兜底。
     */
    private void probeEnvSources(final String base) {
        if (base == null || base.isEmpty() || base.equals(probedUrl)) return;
        probedUrl = base;
        serverEnvSourceCount = -1;
        new Thread(new Runnable() {
            @Override public void run() {
                int n = 0;
                HttpURLConnection c = null;
                try {
                    c = (HttpURLConnection) new URL(base + "/api/status").openConnection(Proxy.NO_PROXY);
                    c.setConnectTimeout(4000);
                    c.setReadTimeout(6000);
                    c.setRequestProperty("Accept", "application/json");
                    if (c.getResponseCode() == 200) {
                        JSONObject o = new JSONObject(readAll(c.getInputStream()));
                        org.json.JSONArray arr = o.optJSONArray("defaultSources");
                        n = arr == null ? 0 : arr.length();
                    } else {
                        probedUrl = "";   // 服务尚未就绪，允许下次页面加载重试
                    }
                } catch (Exception ignored) {
                    n = 0;
                    probedUrl = "";
                } finally {
                    if (c != null) c.disconnect();
                }
                serverEnvSourceCount = n;
                final int result = n;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        updateStatLine();
                        if (result == 0 && web != null) injectPreset(false);
                    }
                });
            }
        }, "ltv-env-probe").start();
    }

    private String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        return out.toString("UTF-8");
    }

    private void injectPreset(final boolean force) {
        if (injecting) return;
        if (presetJson.isEmpty() || injectTemplate.isEmpty() || web == null) {
            toast("未找到内置源配置");
            return;
        }
        injecting = true;
        String js = injectTemplate.replace("__PRESET__", JSONObject.quote(presetJson));
        web.evaluateJavascript(js, new ValueCallback<String>() {
            @Override public void onReceiveValue(String value) {
                injecting = false;
                String v = value == null ? "" : value.replace("\"", "");
                if (!v.startsWith("ok")) {
                    appLog.w("sources", "内置源注入失败：" + v);
                    toast("内置源导入失败：" + v);
                    return;
                }
                int added = -1;
                try {
                    added = Integer.parseInt(v.substring(3));
                } catch (Exception ignored) { }
                // 只有真的补进了源才 reload，避免每次页面加载都无限刷新
                if (added > 0) {
                    appLog.i("sources", "内置源合并完成，新增 " + added + " 条");
                    toast(force ? "内置源已重新合并（新增 " + added + " 条）"
                                : "已补回 " + added + " 个内置源");
                    if (web != null) {
                        web.post(new Runnable() {
                            @Override public void run() { if (web != null) web.reload(); }
                        });
                    }
                } else if (force) {
                    toast("内置源已是最新（" + presetCount + " 条）");
                }
                if (web != null) {
                    web.postDelayed(new Runnable() {
                        @Override public void run() { refreshSourceCount(); }
                    }, 1500);
                }
            }
        });
    }

    /** 读一次网页 localStorage 里的源数量，让设置面板能直接看到是否导入成功。 */
    private void refreshSourceCount() {
        if (web == null) return;
        web.evaluateJavascript(
                "(function(){try{var s=JSON.parse(localStorage.getItem('libretv-settings')||'null');"
                        + "return s&&s.state&&Array.isArray(s.state.customAPIs)"
                        + "?s.state.customAPIs.length:0;}catch(e){return -1}})()",
                new ValueCallback<String>() {
                    @Override public void onReceiveValue(String value) {
                        String v = value == null ? "" : value.replace("\"", "").trim();
                        try { pageSourceCount = Integer.parseInt(v); }
                        catch (Exception e) { pageSourceCount = -1; }
                        updateStatLine();
                    }
                });
    }

    // ------------------------------------------------ 源健康度：一键恢复

    /**
     * LibreTV 的点播源健康度模型（src/lib/store.ts）：
     * 连续失败/超时 2 次即自动停用，阶梯是 30 分钟 → 24 小时 → 长期停用（只能手动恢复）。
     * 记录落在 localStorage 的 'libretv-settings' 快照里（state.sourceHealth）。
     *
     * standalone 包里的 zustand store 没有暴露到 window，所以这里直接改快照，再派发一个
     * storage 事件 —— LibreTV 自己监听该事件并调用 persist.rehydrate()，内存态随之清空、
     * 界面立即刷新，不需要 reload 页面。
     * 返回 'ok<被停用数>/<健康记录总数>'。
     */
    private static final String RESTORE_ALL_JS =
            "(function(){try{"
            + "var K='libretv-settings',raw=localStorage.getItem(K);"
            + "if(!raw)return'none';"
            + "var s=JSON.parse(raw);"
            + "if(!s||!s.state)return'bad';"
            + "var h=s.state.sourceHealth||{},now=Date.now(),off=0,tot=0,k,e;"
            + "for(k in h){if(!Object.prototype.hasOwnProperty.call(h,k))continue;tot++;e=h[k]||{};"
            + "if(e.permanent===true||(e.disabledUntil&&e.disabledUntil>now))off++;}"
            + "s.state.sourceHealth={};"
            + "localStorage.setItem(K,JSON.stringify(s));"
            + "try{window.dispatchEvent(new StorageEvent('storage',{key:K,storageArea:localStorage,url:location.href}));}catch(x){}"
            + "return'ok'+off+'/'+tot;"
            + "}catch(err){return'err'+(err&&err.message?err.message:err);}})()";

    /** 只读：当前有多少源处于停用期 / 共有多少条健康记录 */
    private static final String COUNT_DISABLED_JS =
            "(function(){try{"
            + "var s=JSON.parse(localStorage.getItem('libretv-settings')||'null');"
            + "if(!s||!s.state)return'ok0/0';"
            + "var h=s.state.sourceHealth||{},now=Date.now(),off=0,tot=0,k,e;"
            + "for(k in h){if(!Object.prototype.hasOwnProperty.call(h,k))continue;tot++;e=h[k]||{};"
            + "if(e.permanent===true||(e.disabledUntil&&e.disabledUntil>now))off++;}"
            + "return'ok'+off+'/'+tot;"
            + "}catch(err){return'err'+(err&&err.message?err.message:err);}})()";

    /** 拼接好的 evaluateJavascript 结果（字符串）剥成裸串；JS 返回 null 时按空串处理 */
    private String jsText(String value) {
        String s = jsString(value);
        return s == null ? "" : s.trim();
    }

    private static int toInt(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }

    /** 一键清空网页里的源健康度：所有被自动停用的源立即恢复参与搜索。 */
    private void restoreAllSources() {
        if (web == null) { toast("网页尚未就绪，稍后再试"); return; }
        appLog.i("sources", "一键恢复所有源：清空 sourceHealth");
        web.evaluateJavascript(RESTORE_ALL_JS, new ValueCallback<String>() {
            @Override public void onReceiveValue(String value) {
                String v = jsText(value);
                if ("none".equals(v) || "bad".equals(v)) {
                    appLog.w("sources", "恢复失败：网页存储不可读（" + v + "）");
                    toast("读取网页配置失败：" + v);
                    return;
                }
                if (!v.startsWith("ok")) {
                    appLog.w("sources", "恢复失败：" + v);
                    toast("恢复失败：" + v);
                    return;
                }
                String[] p = v.substring(2).split("/");
                int off = toInt(p.length > 0 ? p[0] : "", 0);
                int tot = toInt(p.length > 1 ? p[1] : "", 0);
                appLog.i("sources", "已清空健康度：停用 " + off + " 个 / 记录 " + tot + " 条");
                if (tot == 0) {
                    toast("当前没有健康度记录，所有源都在参与搜索");
                } else if (off == 0) {
                    toast("已重置 " + tot + " 条健康记录，所有源重新参与搜索");
                } else {
                    toast("已恢复 " + off + " 个被停用的源（共重置 " + tot + " 条记录）");
                }
                refreshDisabledSources();
                // 节流落盘（800ms 一次）有可能把刚清掉的健康度顺手写回来，
                // 1.2 秒后再清一遍：幂等，并把内存态重新拉回空表。
                if (web != null) {
                    web.postDelayed(new Runnable() {
                        @Override public void run() {
                            if (web != null) web.evaluateJavascript(RESTORE_ALL_JS, null);
                        }
                    }, 1200);
                }
            }
        });
    }

    /** 刷新设置面板里那行「当前被自动停用 N 个」的实时状态。 */
    private void refreshDisabledSources() {
        if (restoreHint == null) return;
        if (web == null) { restoreHint.setText("网页尚未就绪"); return; }
        restoreHint.setText("正在读取被自动停用的源…");
        web.evaluateJavascript(COUNT_DISABLED_JS, new ValueCallback<String>() {
            @Override public void onReceiveValue(String value) {
                if (restoreHint == null) return;
                String v = jsText(value);
                if (!v.startsWith("ok")) {
                    restoreHint.setText("读取失败：" + v);
                    return;
                }
                String[] p = v.substring(2).split("/");
                int off = toInt(p.length > 0 ? p[0] : "", 0);
                int tot = toInt(p.length > 1 ? p[1] : "", 0);
                restoreHint.setText(off > 0
                        ? "当前被自动停用 " + off + " 个（共 " + tot + " 条健康记录，点上方按钮一次性全部恢复）"
                        : "当前没有被自动停用的源（共 " + tot + " 条健康记录）");
            }
        });
    }

    // ---------------------------------------------------------------- WebView

    private void applyUserAgent() {
        if (web == null) return;
        WebSettings ws = web.getSettings();
        boolean desktop = prefs.getBoolean(KEY_DESKTOP, false);
        ws.setUserAgentString(desktop ? DESKTOP_UA : (baseUa + " LibreTVAndroid/1.0"));
        ws.setUseWideViewPort(true);
        // 页面自带 viewport meta(device-width, initialScale=1)；overview=true 会额外
        // 缩放到「整页可见」，在竖屏手机上把播放器与文字一起缩小，是画面不适配的主因
        ws.setLoadWithOverviewMode(false);
    }

    private void configureWeb() {
        WebSettings ws = web.getSettings();
        baseUa = ws.getUserAgentString();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setDatabaseEnabled(true);
        ws.setAllowFileAccess(true);
        ws.setAllowContentAccess(true);
        ws.setUseWideViewPort(true);
        ws.setLoadWithOverviewMode(false);
        ws.setBuiltInZoomControls(true);
        ws.setDisplayZoomControls(false);
        ws.setSupportZoom(true);
        ws.setJavaScriptCanOpenWindowsAutomatically(true);
        ws.setSupportMultipleWindows(false);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setCacheMode(WebSettings.LOAD_DEFAULT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            ws.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }

        // 页面脚本通过它上报全屏状态；方法内会校验当前页面必须属于配置的服务地址
        web.addJavascriptInterface(new LtvBridge(), "LtvBridge");

        // 全屏播放时轻触画面唤出控制条。返回 false 不消费事件，网页自己的
        // 点击（播放/暂停）照常生效。切到「网页控制栏」时不打扰网页栏上的操作，
        // 用户按返回键即可切回原生控制条。
        web.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) {
                if (!playbackMode || webControlsMode || immersiveLayersOn) return false;
                if (e.getAction() == MotionEvent.ACTION_DOWN) {
                    touchDownX = e.getX();
                    touchDownY = e.getY();
                } else if (e.getAction() == MotionEvent.ACTION_UP) {
                    if (Math.abs(e.getX() - touchDownX) < dp(24)
                            && Math.abs(e.getY() - touchDownY) < dp(24)) {
                        showPlaybackBarTemporarily();
                    }
                }
                return false;
            }
        });

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            cookies.setAcceptThirdPartyCookies(web, true);
        }

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleExternal(url);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    return handleExternal(request.getUrl().toString());
                }
                return false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                // 放宽为「同 origin 即可」：密码页 / 重定向后 view.getUrl() 可能与 url 不一致，
                // 严格相等会让注入被跳过，源就补不回来。
                if (url == null || currentUrl.isEmpty() || !url.startsWith(currentUrl)) return;
                /* WebView 对同一次导航会回调多次 onPageFinished（主框架 + 子框架 / 内部重定向），
                   日志里能看到同一 URL 相隔 13ms 连报两三次。每报一次就把整条注入链
                   （自动登录 + 源合并 + 移动端样式 + 播放增强）重跑一遍，其中源合并要
                   读写整个 localStorage、同步阻塞渲染线程 —— 一次导航叠加 2~3 次就是明显卡顿。
                   这里做去重：同一 URL 1.2 秒内只处理一次。 */
                long now = System.currentTimeMillis();
                if (url.equals(lastFinishedUrl) && now - lastFinishedAt < 1200) return;
                lastFinishedUrl = url;
                lastFinishedAt = now;

                appLog.i("page", "页面加载完成 " + url);
                // 未登录时 /api/search 直接 401，界面表现为「搜不到视频」；这里自动完成校验
                if (!autoLoginTemplate.isEmpty() && web != null) {
                    web.evaluateJavascript(autoLoginTemplate.replace(
                            "__PASSWORD__", JSONObject.quote(effectivePassword)), null);
                }
                maybeInjectPreset(url, false);
                if (prefs.getBoolean(KEY_MOBILE_CSS, true) && web != null) {
                    web.evaluateJavascript(MOBILE_CSS_JS, null);
                }
                // 全屏播放增强：重定向原生全屏、放大控件、建立状态上报
                if (!playerTouchJs.isEmpty() && url.startsWith(currentUrl)) {
                    web.evaluateJavascript(playerTouchJs, null);
                    // 不再强制显示网页控制栏：showControls() 会把 art-bottom 钉在画面上，
                    // 也正是「关不掉、点不到进度」的来源。显隐交回 ArtPlayer，全屏态由
                    // setNativeBar(true) 统一压住；这里只同步用户选择的画面模式。
                    callJs("window.__ltv && window.__ltv.setFit('" + fitMode() + "')");
                    // 页面加载会重置 DOM 类名：播放态的「隐藏网页栏 / 沉浸层」必须重放，
                    // 否则网页控制栏与原生细进度线会同时压在底部，两层进度条叠在一起。
                    if (playbackMode) {
                        if (!webControlsMode) {
                            callJs("window.__ltv && window.__ltv.hideWebControls()");
                            callJs("window.__ltv && window.__ltv.setNativeBar(true)");
                        }
                        setImmersiveLayers(!webControlsMode
                                && prefs.getBoolean(KEY_IMMERSIVE_PLAYER, true));
                    }
                }
                updateStatLine();
                if (web != null) {
                    web.postDelayed(new Runnable() {
                        @Override public void run() { refreshSourceCount(); }
                    }, 1600);
                }
            }

            @Override
            public void onReceivedError(WebView view, int errorCode,
                                        String description, String failingUrl) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                    showError(description, failingUrl);
                }
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                        WebResourceError error) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && request.isForMainFrame()) {
                    showError(error.getDescription().toString(), request.getUrl().toString());
                }
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progress.setProgress(newProgress);
                progress.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
            }

            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                showCustomView(view, callback);
            }

            @Override
            public void onHideCustomView() {
                hideCustomView();
            }

            @Override
            public boolean onShowFileChooser(WebView view,
                                             ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = callback;
                try {
                    startActivityForResult(params.createIntent(), REQ_FILE);
                    return true;
                } catch (Exception e) {
                    filePathCallback = null;
                    toast("无法打开文件选择器");
                    return false;
                }
            }
        });

        web.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent,
                                        String contentDisposition, String mimeType,
                                        long contentLength) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                } catch (Exception e) {
                    toast("无法打开下载链接");
                }
            }
        });
    }

    private boolean handleExternal(String url) {
        if (url == null) return false;
        if (url.startsWith("http://") || url.startsWith("https://")
                || url.startsWith("file://") || url.startsWith("about:")) {
            return false;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            toast("无法打开该链接");
        }
        return true;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_FILE) {
            if (filePathCallback == null) return;
            Uri[] results = null;
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                results = new Uri[]{data.getData()};
            }
            filePathCallback.onReceiveValue(results);
            filePathCallback = null;
            return;
        }
        if (requestCode == REQ_IMPORT_FILE) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                String raw = readUriText(data.getData());
                if (raw == null || raw.trim().isEmpty()) {
                    toast("文件为空或无法读取");
                    appLog.w("import", "导入文件为空");
                } else {
                    applyImportedSources(raw, importReplaceMode);
                }
            }
            return;
        }
        if (requestCode == REQ_EXPORT_FILE) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null
                    && !pendingExportText.isEmpty()) {
                writeUriText(data.getData(), pendingExportText);
            }
            return;
        }
    }

    // ---------------------------------------------------------------- 页面控制

    private String normalize(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        if (!s.matches("(?i)^https?://.*")) {
            s = "http://" + s;
        }
        try {
            java.net.URI u = new java.net.URI(s);
            String scheme = u.getScheme();
            String host = u.getHost();
            if (host == null || host.isEmpty()) return null;
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) return null;
            int port = u.getPort();
            if (port < -1 || port > 65535) return null;
            return s.replaceAll("/+$", "");
        } catch (Exception e) {
            return null;
        }
    }

    private void load(String url) {
        currentUrl = url;
        probeEnvSources(url);
        hideOverlay();
        progress.setVisibility(View.VISIBLE);
        web.loadUrl(url);
        updateStatLine();
    }

    private void saveAndLoad(String url) {
        prefs.edit().putString(KEY_URL, url).apply();
        settingsEdit.setText(url);
        load(url);
    }

    private void showCustomView(View view, WebChromeClient.CustomViewCallback callback) {
        if (customView != null) {
            if (callback != null) callback.onCustomViewHidden();
            return;
        }
        customView = view;
        customViewCallback = callback;
        mainBox.setVisibility(View.GONE);
        view.setBackgroundColor(Color.BLACK);
        root.addView(view, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        // custom view 加在最上层会盖住所有 overlay，按优先级重新置顶
        if (gestureLayer != null) gestureLayer.bringToFront();
        if (progressLine != null) progressLine.bringToFront();
        if (panelToggle != null) panelToggle.bringToFront();
        if (gestureHint != null) gestureHint.bringToFront();
        // custom view 是后加进来的，会盖住左上「✕ 退出」——必须重新提回最上层
        if (exitBtn != null) exitBtn.bringToFront();
        playbackBar.bringToFront();
        applyPlaybackMode(true);
        // 沉浸模式不自动弹大面板；经典模式沿用原有控制条
        if (!prefs.getBoolean(KEY_IMMERSIVE_PLAYER, true)) showPlaybackBarTemporarily();
    }

    private void hideCustomView() {
        if (customView == null) return;
        root.removeView(customView);
        customView = null;
        mainBox.setVisibility(View.VISIBLE);
        if (customViewCallback != null) {
            customViewCallback.onCustomViewHidden();
            customViewCallback = null;
        }
        applyPlaybackMode(false);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (customView != null) {
                hideCustomView();
                return true;
            }
            if (sheetVisible()) {
                hideSheet();
                return true;
            }
            // 网页控制栏模式下，返回键先收网页栏、回到播放画面，不直接退出全屏
            if (playbackMode && webControlsMode) {
                switchToNativeControls();
                return true;
            }
            // 沉浸模式下返回键先收起「⋯」高级面板，再按才退出全屏
            if (playbackMode && advancedPanelVisible) {
                hideAdvancedPanel();
                return true;
            }
            if (playbackMode) {
                exitPlayback();
                return true;
            }
            if (immersive) {
                applyImmersive(false);
                return true;
            }
            if (web != null && web.canGoBack()) {
                web.goBack();
                return true;
            }
            long now = System.currentTimeMillis();
            if (now - lastBackPress < 2000) {
                finish();
                return true;
            }
            lastBackPress = now;
            toast("再按一次返回键退出");
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (web != null) web.onPause();
        CookieManager.getInstance().flush();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.onResume();
    }

    @Override
    protected void onDestroy() {
        appLog.i("app", "应用退出，停止本地服务");
        cancelControlHide();
        controlHandler.removeCallbacksAndMessages(null);
        if (castDialog != null && castDialog.isShowing()) castDialog.dismiss();
        if (cast != null) cast.shutdown();
        if (localServer != null) localServer.stop();
        if (filePathCallback != null) {
            filePathCallback.onReceiveValue(null);
            filePathCallback = null;
        }
        if (web != null) {
            ViewGroup parent = (ViewGroup) web.getParent();
            if (parent != null) parent.removeView(web);
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
