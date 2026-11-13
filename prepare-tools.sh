#!/usr/bin/env bash
# 一次性准备 Android 构建链（沙箱内无 JDK / SDK / Gradle 时使用）。
# 全部走国内镜像与 dl.google.com，绕开被拦截的 GitHub 资产域名。
set -euo pipefail

TOOLS="${LIBRETV_TOOLS:-/tmp/libretv-apk-tools}"
SDK="${ANDROID_HOME:-/tmp/androidsdk}"
JDK_URL="https://mirrors.tuna.tsinghua.edu.cn/Adoptium/17/jdk/x64/mac/OpenJDK17U-jdk_x64_mac_hotspot_17.0.21_9.tar.gz"
CT_URL="https://dl.google.com/android/repository/commandlinetools-mac-11076708_latest.zip"
GRADLE_URL="https://mirrors.cloud.tencent.com/gradle/gradle-8.6-bin.zip"

mkdir -p "$TOOLS" "$SDK/cmdline-tools"

# 1) JDK 17
if ! ls -d "$TOOLS"/jdk-*/Contents/Home/bin/java >/dev/null 2>&1; then
  if [ ! -s "$TOOLS/jdk17.tar.gz" ]; then
    echo "[1/4] 下载 JDK 17（清华镜像）..."
    curl -fL --retry 3 --max-time 900 -o "$TOOLS/jdk17.tar.gz" "$JDK_URL"
  fi
  echo "[1/4] 解压 JDK 17 ..."
  tar -xzf "$TOOLS/jdk17.tar.gz" -C "$TOOLS"
fi
export JAVA_HOME="$(ls -d "$TOOLS"/jdk-*/Contents/Home | head -1)"
export PATH="$JAVA_HOME/bin:$PATH"
java -version 2>&1 | head -2

# 2) Android commandlinetools
if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
  if [ ! -s "$TOOLS/ct.zip" ]; then
    echo "[2/4] 下载 Android commandlinetools..."
    curl -fL --retry 3 --max-time 900 -o "$TOOLS/ct.zip" "$CT_URL"
  fi
  echo "[2/4] 解压 Android commandlinetools..."
  rm -rf "$SDK/tmp_extract"
  unzip -q -o "$TOOLS/ct.zip" -d "$SDK/tmp_extract"
  mkdir -p "$SDK/cmdline-tools/latest"
  mv "$SDK/tmp_extract/cmdline-tools/"* "$SDK/cmdline-tools/latest/"
  rm -rf "$SDK/tmp_extract"
fi

# 3) SDK 组件
if [ ! -f "$SDK/platforms/android-34/android.jar" ] || \
   [ ! -f "$SDK/build-tools/34.0.0/aapt2" ]; then
  echo "[3/4] 安装 platform-tools / build-tools;34.0.0 / platforms;android-34 ..."
  # 先单独接受许可：yes 被 sdkmanager 提前关闭会产生 SIGPIPE，
  # 在 set -o pipefail 下会把已成功的安装误判为失败。
  ( yes 2>/dev/null || true ) | "$SDK/cmdline-tools/latest/bin/sdkmanager" \
      --sdk_root="$SDK" --licenses > "$TOOLS/licenses.log" 2>&1 || true
  "$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" \
      "platform-tools" "build-tools;34.0.0" "platforms;android-34" \
      > "$TOOLS/sdkmanager.log" 2>&1
  tail -12 "$TOOLS/sdkmanager.log"
fi

# 4) Gradle
if [ ! -x "$TOOLS/gradle-8.6/bin/gradle" ]; then
  if [ ! -s "$TOOLS/gradle-8.6-bin.zip" ]; then
    echo "[4/4] 下载 Gradle 8.6（腾讯镜像）..."
    curl -fL --retry 3 --max-time 900 -o "$TOOLS/gradle-8.6-bin.zip" "$GRADLE_URL"
  fi
  echo "[4/4] 解压 Gradle 8.6 ..."
  unzip -q -o "$TOOLS/gradle-8.6-bin.zip" -d "$TOOLS"
fi

echo "=== 工具链就绪 ==="
echo "JAVA_HOME=$JAVA_HOME"
echo "ANDROID_HOME=$SDK"
ls -l "$SDK/build-tools/34.0.0/aapt2" "$SDK/platforms/android-34/android.jar" \
      "$TOOLS/gradle-8.6/bin/gradle"
