# LibreTV 安卓客户端（开源版）

把 [LibreTV](https://github.com/LibreSpark/LibreTV) 装进手机的一个壳：内置 Node 运行时，App 自己在本机 `127.0.0.1` 上跑起官方 standalone 服务，再套一层为触屏重写过的播放器交互。

**这个分支（`opensource`）只有一个差别：不带任何内置源。** APK 里没有任何点播源、直播源清单，也没有任何人的私人设置。源由你自己配置。

- 版本：`1.2.4-oss`（versionCode 16）
- 包名：`com.libretv.client`
- 最低系统：Android 5.0（API 21），目标 34
- 许可：[AGPL-3.0-or-later](LICENSE)

## 下载

**[LibreTV-1.2.4-oss-debug.apk](https://github.com/hzl790209/libretv-android-oss/releases/download/v1.2.4-oss/LibreTV-1.2.4-oss-debug.apk)**　（45 MB，见 [Releases](https://github.com/hzl790209/libretv-android-oss/releases)）

debug 签名，装的时候系统会提示「未知来源」，允许即可。想自己编见第七节。

---

## 一、为什么开源版不带源

私有版是把一台电脑上整理好的源清单在构建期打进 APK，装上就能搜。开源版反过来：

| | 私有版 | 开源版（本分支） |
|:---|:---|:---|
| 内置源 | 构建机上有多少就打多少 | **0 条** |
| 访问密码 | 固定私人密码 | **默认为空（用户无需输入）**，需要时自己设 |
| 源导出 / 同步脚本 | 有 | 已删除 |
| 首次可用性 | 打开即可搜索 | 需要在网页里配置一次源 |

原因很直接：第三方采集站的可用性、授权状态与这个项目无关，把它们写死进仓库或 APK，既不可维护，也不合适。**源属于使用者自己的配置。**

---

## 二、安装与首次启动

```bash
adb install -r dist/LibreTV-1.2.4-oss-debug.apk
```

也可以直接把 APK 拷到手机点击安装（debug 签名，首次需在系统设置里允许「安装未知来源应用」）。

首次启动流程：

1. App 解压内置的 Node 运行时与官方 standalone 包到私有目录（约 45 MB，首次十几秒）
2. 启动 Node 进程，服务监听 `127.0.0.1:8899`
3. 自动打开网页，**不需要输密码，直接进主界面**
4. 此时是**零源状态**，搜索会返回「请至少选择一个点播源」，属正常。按下一节配置源即可

> **默认密码为空，是怎么实现的？**
>
> 不能把空串直接传给服务端。上游 LibreTV 的 `api-guard` 把「PASSWORD 为空」当配置错误，
> 所有 `/api/*` 一律返回 `503 {"error":"服务器未设置 PASSWORD 环境变量"}` ——
> 表现就是「服务起来了，但搜不到任何东西」，比设个密码还糟。
>
> 所以这里的「空」只表示**用户不用管密码**：首次启动时客户端用 `SecureRandom` 生成一个
> 32 位随机串，作为 `PASSWORD` 注入 Node 进程，同时由 `auto-login.js` 自动完成登录。
> 密码只在本机内存与 SharedPreferences 内流转，不进日志、不上传，设置面板的密码框也一直是空的。
>
> 想自己控制密码：设置 → 访问密码，填一个，重启服务生效，之后自动登录就用你填的。
>
> ⚠️ **要让其他设备连手机上的服务（局域网共享），必须先自己设密码** ——
> 默认那个随机密码只有本机知道，别的设备无法登录，客户端会直接拦下这种组合并提示。

---

## 三、配置你的源（三种方式，选一种）

### 3.1 网页里手动添加（最简单）

登录后进入 **设置 → 源管理 → 添加源**，填名称和 API 地址，勾选启用。格式就是标准的苹果 CMS / MacCMS 采集接口：

```
https://你的采集站/api.php/provide/vod
```

加完点 **测活**，能通的会变绿。源的健康度由 LibreTV 自己维护：连续失败 2 次会按 30 分钟 → 24 小时两级降权，仍失败则永久停用；成功一次即清零。

### 3.2 导入配置文件（批量）

**设置 → 源管理 → 导入**，选一个 JSON 文件。格式是数组，每条三个字段：

```json
[
  { "name": "示例点播源", "url": "https://example.com/api.php/provide/vod", "isAdult": false },
  { "name": "示例直播源", "url": "https://example.com/live.m3u", "isAdult": false }
]
```

`isAdult` 为 `true` 的条目会被归到成人分组，默认不展示（可在设置里关掉这个开关）。

导出的文件也是同样的格式，方便在几台手机之间搬运。

### 3.3 构建时预置（做自己的发行版）

把上面那份 JSON 存成 `app/src/main/assets/default-sources.json`，再执行 `./build-apk.sh`。服务端会读它并通过 `DEFAULT_SOURCES` 环境变量注入，首次启动即自动勾选这些源。

注意：这个文件在 `.gitignore` 里，**不要提交**。构建脚本还会在打包期间把它临时移开，确保开源版 APK 一定不含它 —— 你自己构建时如果确实想打进去，删掉 `build-apk.sh` 里那段「构建期移开源资产」即可。

---

## 四、手机本地服务是怎么跑起来的

```
APK
├─ lib/arm64-v8a/libnode.so        ← Termux 的 nodejs-lts aarch64 ELF，改名放进 jniLibs（否则无法执行）
├─ assets/libretv-standalone.bin   ← 官方 standalone 包（.next + public + node_modules），gzip
├─ assets/node-boot.js             ← 进程内引导脚本
└─ MainActivity                     ← 解压 → 起进程 → 打开 WebView
```

几个绕不开的点：

- **APK 内的 `.so` 才能被执行**。Node 二进制必须放进 `jniLibs/<abi>/` 并命名为 `lib*.so`，Android 10+ 才会允许从应用私有目录执行它。
- **standalone 包要解压到可写目录**。`.next` 需要写缓存，不能留在只读的 assets 里。
- **`extractNativeLibs=true`**。否则 `.so` 不会落到磁盘上，`exec` 直接失败。
- **端口固定 8899**，被占用时自动往后找空闲端口，实际端口写进日志。

为什么非要内置 Node：LibreTV 的搜索/播放链路是 Next.js 的 Server Action 加 route handler，纯静态托管跑不起来。把服务端塞进手机，才能做到「不依赖任何外部服务器、断网也能进界面」。

---

## 五、外部服务模式（有电脑或 NAS 时更省电）

设置里可以关掉本地服务，改填一个远程地址，例如 `http://192.168.1.100:8899`。此时 App 只当浏览器用，手机不跑 Node，发热和耗电都低很多。

自检：

```bash
curl http://192.168.1.100:8899/api/status
# 应返回 {"version":"2.22.0", ...}
```

---

## 六、客户端做了哪些移动端改造

上游网页是给桌面鼠标写的，直接塞进手机很难用。这一层是重写过的：

**播放**

- 单击唤出 / 收起悬浮控制条，无操作 4 秒自动淡出，不挡画面
- 双击全屏、真全屏（隐藏状态栏与导航栏，`WindowInsets` 全吃掉）
- 竖屏强制 `object-fit: contain`（默认的 `cover` 会把 16:9 裁掉两边），横屏铺满
- 进度条加高到手指能点中的高度，拖动时显示时间气泡

**投屏（DLNA）**

- 播放页一键投屏：SSDP 搜同网段设备 → 选电视 → `SetAVTransportURI` + `Play`
- 投屏中面板变遥控器：进度条、−30s / 暂停 / +30s、停止投屏
- 每 2 秒从电视端拉一次 `GetPositionInfo` / `GetTransportInfo`，用遥控器改的状态也会同步回手机
- 停止时会先清掉电视端的 URI，避免电视卡在最后一帧

**其它**

- 运行日志面板：分级、可搜索、可一键分享（排查「为什么搜不到」时非常有用）
- 源导入 / 导出、批量测活
- 长按视频下载（走服务端代理，绕开 m3u8 的 CORS）

---

## 七、重新构建

需要 JDK 17、Android SDK 34、Gradle 8.6。

```bash
./prepare-tools.sh     # 首次：下载 JDK / SDK / Gradle 到 /tmp，不污染系统
./build-apk.sh         # 产出 dist/LibreTV-<版本>-debug.apk
```

`build-apk.sh` 会做三件事：

1. 构建期把 `libretv-settings.json` / `sources.json` / `default-sources.json` 临时移到 `.oss-bak-*`，并用 `trap` 保证无论成功失败都还原
2. 检查 Node 运行时与 standalone 包，缺失则下载（`LIBRETV_NODE_REFRESH=1` 强制刷新）
3. `gradle assembleDebug`，按 `versionName` 重命名产物

沙箱 / CI 里只想验证能编过，可以直接 `gradle assembleDebug`。

---

## 八、目录

```
libretv-android/
├─ app/src/main/
│  ├─ java/com/libretv/client/
│  │  ├─ MainActivity.java      # WebView 宿主、JS 桥、投屏遥控、日志
│  │  ├─ LocalServer.java       # 解压运行时、拉 Node 进程、健康检查
│  │  ├─ DlnaCast.java          # SSDP 发现 + AVTransport SOAP
│  │  └─ AppLog.java            # 环形日志缓冲
│  ├─ assets/
│  │  ├─ inject.js              # 页面桥接（window.__ltv）
│  │  ├─ player-touch.js        # 悬浮控制条 / 投屏面板 / 手势
│  │  ├─ import-sources.js      # 源导入
│  │  ├─ export-sources.js      # 源导出
│  │  ├─ diagnose.js            # 环境自检
│  │  └─ auto-login.js          # 本地服务免登录
│  └─ jniLibs/arm64-v8a/        # Node ELF（.gitignore，构建期下载）
├─ tools/prepare-node.py        # 下载并裁剪 Node 运行时与 standalone 包
├─ prepare-tools.sh             # 下载 JDK / SDK / Gradle
├─ build-apk.sh
└─ LICENSE                      # AGPL-3.0
```

---

## 九、已知限制

- **只支持 arm64-v8a**。Node 运行时是 aarch64 ELF，32 位与 x86 设备装不了（会装成功但起不来）。
- **首次启动慢**。解压 45 MB 需要十几秒，之后走缓存。
- **需要一个能出网的采集站**。零源状态下搜索无结果不是 bug。
- **DLNA 依赖同网段组播**。很多企业 Wi-Fi 开了 AP 隔离，SSDP 搜不到设备；此时用「手动输入 IP」。
- **后台会被杀**。国产 ROM 需要手动允许「后台运行 / 自启动」，否则切到后台 Node 进程会被回收。
- **debug 签名**。自己用没问题，要发布请换正式签名。

---

## 十、许可证与合规

本项目以 **AGPL-3.0-or-later** 发布，与上游 LibreTV 保持一致。

这意味着：

- 你可以自由使用、修改、分发
- 如果你修改后**通过网络向他人提供服务**（包括把这个 App 或它内置的服务端部署出去给别人用），你必须以 AGPL-3.0 公开你修改后的完整源码
- 分发 APK 时必须附带许可证与源码获取方式

上游署名：

- LibreTV — <https://github.com/LibreSpark/LibreTV>（AGPL-3.0）
- Node.js — <https://nodejs.org>（MIT），运行时取自 Termux 构建

本项目只提供客户端壳与交互改造，**不包含、不托管、不分发任何视频内容或采集接口**。源由使用者自行配置，内容合法性由使用者与对应站点负责。
