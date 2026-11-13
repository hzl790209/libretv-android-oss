#!/usr/bin/env bash
# 构建 LibreTV 安卓客户端 APK（debug 签名，可直接侧载）。
set -euo pipefail

ROOT="$(cd -P "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TOOLS="${LIBRETV_TOOLS:-/tmp/libretv-apk-tools}"
SDK="${ANDROID_HOME:-/tmp/androidsdk}"

JAVA_HOME="$(ls -d "$TOOLS"/jdk-*/Contents/Home 2>/dev/null | head -1 || true)"
if [ -z "$JAVA_HOME" ] || [ ! -x "$JAVA_HOME/bin/java" ]; then
  echo "缺少 JDK 17，请先执行 ./prepare-tools.sh" >&2; exit 1
fi
if [ ! -f "$SDK/platforms/android-34/android.jar" ]; then
  echo "缺少 Android SDK 34，请先执行 ./prepare-tools.sh" >&2; exit 1
fi
if [ ! -x "$TOOLS/gradle-8.6/bin/gradle" ]; then
  echo "缺少 Gradle 8.6，请先执行 ./prepare-tools.sh" >&2; exit 1
fi

export JAVA_HOME ANDROID_HOME="$SDK"
export PATH="$JAVA_HOME/bin:$PATH"
printf 'sdk.dir=%s\n' "$SDK" > "$ROOT/local.properties"

# ---------------------------------------------------------------- 开源版：不打包任何内置源
#
# 本分支（opensource）刻意不携带任何点播 / 直播源：源清单属于使用者自己的配置，
# 且第三方采集站的可用性、授权状态都与本项目无关。
#
# 三个源资产文件在 .gitignore 里，正常 clone 下来本来就没有；这里防的是
# 在「已有源的机器」上构建时残留 —— gradle 会把 app/src/main/assets 下的文件
# 原样打进 APK，不清掉的话开源包装完就带着别人的源清单了。
# 做法是构建期间临时移开、脚本退出时无论成功失败都还原（私有分支的资产不受影响）。
SOURCE_ASSETS="libretv-settings.json sources.json default-sources.json"
MOVED_ASSETS=""
restore_source_assets() {
  for f in $MOVED_ASSETS; do
    if [ -f "$ROOT/.oss-bak-$f" ]; then
      mv "$ROOT/.oss-bak-$f" "$ROOT/app/src/main/assets/$f" || true
    fi
  done
}
trap restore_source_assets EXIT INT TERM

for f in $SOURCE_ASSETS; do
  if [ -f "$ROOT/app/src/main/assets/$f" ]; then
    mv "$ROOT/app/src/main/assets/$f" "$ROOT/.oss-bak-$f"
    MOVED_ASSETS="$MOVED_ASSETS $f"
    echo "[源] 构建期移开 ${f}（开源版不含内置源）"
  fi
done

echo "[源] 开源版：APK 内不含任何预置源，首次启动后请在「设置 → 源管理 / 导入源」里自行配置"

# 手机本地服务运行时：Node ELF + 官方 standalone 包（构建期下载，带缓存）
PY="${PY:-python3}"
NODE_LIB="$ROOT/app/src/main/jniLibs/arm64-v8a/libnode.so"
NODE_TAR="$ROOT/app/src/main/assets/libretv-standalone.bin"
if [ "${LIBRETV_NODE_REFRESH:-0}" = "1" ] || [ ! -f "$NODE_LIB" ] || [ ! -f "$NODE_TAR" ]; then
  echo "[Node] 准备手机本地服务运行时 ..."
  "$PY" "$ROOT/tools/prepare-node.py" || { echo "[Node] 运行时准备失败" >&2; exit 1; }
else
  echo "[Node] 运行时已缓存，跳过（LIBRETV_NODE_REFRESH=1 可强制刷新）"
fi

cd "$ROOT"
"$TOOLS/gradle-8.6/bin/gradle" assembleDebug --no-daemon --console=plain

APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
if [ ! -f "$APK" ]; then
  echo "构建结束但未找到 APK：$APK" >&2; exit 1
fi
# 产物名带版本号：版本升了旧文件不会被覆盖，用户侧也不会装错包
# 注意：macOS BSD sed 不支持 \s，须用 POSIX [[:space:]]
VER="$(sed -n "s/^[[:space:]]*versionName[[:space:]]*'\([^']*\)'.*/\1/p" "$ROOT/app/build.gradle" | head -1)"
[ -n "$VER" ] || VER="unknown"
mkdir -p "$ROOT/dist"
OUT="$ROOT/dist/LibreTV-${VER}-debug.apk"
cp "$APK" "$OUT"
echo "=== 产物 ==="
ls -lh "$OUT"
shasum -a 256 "$OUT"
