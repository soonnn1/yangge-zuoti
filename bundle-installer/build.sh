#!/bin/bash
set -euo pipefail

TASK_PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TASK_MODULE_ROOT="$(cd "$(dirname "$0")" && pwd)"
TASK_ANDROID_SDK="${WATCHGATE_ANDROID_SDK:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
TASK_JAVA_ROOT="${WATCHGATE_JAVA_ROOT:-$(/usr/libexec/java_home 2>/dev/null || true)}"
TASK_BUILD="$TASK_MODULE_ROOT/build"
TASK_TOOLS="$TASK_ANDROID_SDK/build-tools/34.0.0"
TASK_PLATFORM="$TASK_ANDROID_SDK/platforms/android-34/android.jar"
TASK_SOURCE="$TASK_MODULE_ROOT/src/main"
TASK_ASSETS="$TASK_SOURCE/assets"

if [[ ! -x "$TASK_JAVA_ROOT/bin/javac" || ! -f "$TASK_PLATFORM" || ! -x "$TASK_TOOLS/aapt2" ]]; then
    echo "需要 JDK 17～21、Android SDK platform 34 和 build-tools 34.0.0。"
    exit 1
fi

# 总安装器只打包本次构建的两个独立 APK。
cp "$TASK_PROJECT_ROOT/releases/洋哥做题了-1.0.0.apk" "$TASK_ASSETS/watchgate.apk"
cp "$TASK_PROJECT_ROOT/releases/洋哥拍题-1.0.0.apk" "$TASK_ASSETS/yanggephoto.apk"
rm -rf "$TASK_BUILD/classes"
mkdir -p "$TASK_BUILD/generated" "$TASK_BUILD/classes" "$TASK_BUILD/dex" "$TASK_PROJECT_ROOT/releases"
"$TASK_TOOLS/aapt2" compile --dir "$TASK_SOURCE/res" -o "$TASK_BUILD/resources.zip"
"$TASK_TOOLS/aapt2" link -I "$TASK_PLATFORM" --manifest "$TASK_SOURCE/AndroidManifest.xml" \
    --java "$TASK_BUILD/generated" --min-sdk-version 29 --target-sdk-version 34 \
    -A "$TASK_ASSETS" -o "$TASK_BUILD/base.apk" "$TASK_BUILD/resources.zip"
"$TASK_JAVA_ROOT/bin/javac" -encoding UTF-8 --release 8 \
    -classpath "$TASK_PLATFORM" -d "$TASK_BUILD/classes" \
    "$TASK_SOURCE/java/cn/tan/watchgateinstaller/"*.java "$TASK_BUILD/generated/cn/tan/watchgateinstaller/R.java"
"$TASK_JAVA_ROOT/bin/jar" cf "$TASK_BUILD/compiled.jar" -C "$TASK_BUILD/classes" .
"$TASK_JAVA_ROOT/bin/java" -cp "$TASK_TOOLS/lib/d8.jar" com.android.tools.r8.D8 \
    --min-api 29 --lib "$TASK_PLATFORM" --output "$TASK_BUILD/dex" "$TASK_BUILD/compiled.jar"
cp "$TASK_BUILD/base.apk" "$TASK_BUILD/unsigned.apk"
zip -q -j "$TASK_BUILD/unsigned.apk" "$TASK_BUILD/dex/classes.dex"
"$TASK_TOOLS/zipalign" -f -p 4 "$TASK_BUILD/unsigned.apk" "$TASK_BUILD/aligned.apk"
"$TASK_JAVA_ROOT/bin/java" -jar "$TASK_TOOLS/lib/apksigner.jar" sign \
    --ks "$TASK_PROJECT_ROOT/.signing/watchgate.p12" --ks-key-alias watchgate \
    --ks-pass "file:$TASK_PROJECT_ROOT/.signing/password" \
    --out "$TASK_PROJECT_ROOT/releases/洋哥做题了-一键安装-1.0.0.apk" "$TASK_BUILD/aligned.apk"
echo "安装器已生成：$TASK_PROJECT_ROOT/releases/洋哥做题了-一键安装-1.0.0.apk"
