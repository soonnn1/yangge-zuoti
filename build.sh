#!/bin/bash
set -euo pipefail

# 无第三方运行库，直接使用本机 Android SDK 离线构建。
TASK_PROJECT_ROOT="$(cd "$(dirname "$0")" && pwd)"
TASK_ANDROID_SDK="${WATCHGATE_ANDROID_SDK:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
TASK_JAVA_ROOT="${WATCHGATE_JAVA_ROOT:-$(/usr/libexec/java_home 2>/dev/null || true)}"
TASK_BUILD="$TASK_PROJECT_ROOT/build"
TASK_TOOLS="$TASK_ANDROID_SDK/build-tools/34.0.0"
TASK_PLATFORM="$TASK_ANDROID_SDK/platforms/android-34/android.jar"
TASK_SOURCE="$TASK_PROJECT_ROOT/app/src/main"
TASK_OUTPUT="${WATCHGATE_OUTPUT:-$TASK_PROJECT_ROOT/releases}"
TASK_SIGN="$TASK_PROJECT_ROOT/.signing"

if [[ ! -x "$TASK_JAVA_ROOT/bin/javac" || ! -f "$TASK_PLATFORM" || ! -x "$TASK_TOOLS/aapt2" ]]; then
    echo "需要 JDK 17～21、Android SDK platform 34 和 build-tools 34.0.0。"
    echo "可通过 WATCHGATE_JAVA_ROOT、WATCHGATE_ANDROID_SDK 指定位置。"
    exit 1
fi

# 清理旧的编译类，避免已删除的功能残留在新安装包中。
rm -rf "$TASK_BUILD/classes"
mkdir -p "$TASK_BUILD/generated" "$TASK_BUILD/classes" "$TASK_BUILD/dex" "$TASK_OUTPUT" "$TASK_SIGN"
"$TASK_TOOLS/aapt2" compile --dir "$TASK_SOURCE/res" -o "$TASK_BUILD/resources.zip"
"$TASK_TOOLS/aapt2" link -I "$TASK_PLATFORM" --manifest "$TASK_SOURCE/AndroidManifest.xml" \
    --java "$TASK_BUILD/generated" --min-sdk-version 26 --target-sdk-version 34 \
    -A "$TASK_SOURCE/assets" -o "$TASK_BUILD/base.apk" "$TASK_BUILD/resources.zip"
"$TASK_JAVA_ROOT/bin/javac" -encoding UTF-8 --release 8 \
    -classpath "$TASK_PLATFORM" -d "$TASK_BUILD/classes" \
    "$TASK_SOURCE/java/cn/tan/watchgate/"*.java "$TASK_SOURCE/java/io/nayuki/qrcodegen/"*.java "$TASK_BUILD/generated/cn/tan/watchgate/R.java"
"$TASK_JAVA_ROOT/bin/jar" cf "$TASK_BUILD/compiled.jar" -C "$TASK_BUILD/classes" .
"$TASK_JAVA_ROOT/bin/java" -cp "$TASK_TOOLS/lib/d8.jar" com.android.tools.r8.D8 \
    --min-api 26 --lib "$TASK_PLATFORM" --output "$TASK_BUILD/dex" "$TASK_BUILD/compiled.jar"
cp "$TASK_BUILD/base.apk" "$TASK_BUILD/unsigned.apk"
zip -q -j "$TASK_BUILD/unsigned.apk" "$TASK_BUILD/dex/classes.dex"
"$TASK_TOOLS/zipalign" -f -p 4 "$TASK_BUILD/unsigned.apk" "$TASK_BUILD/aligned.apk"

if [[ ! -f "$TASK_SIGN/watchgate.p12" ]]; then
    umask 077
    python3 -c 'import secrets,sys; print(secrets.token_urlsafe(32), file=open(sys.argv[1], "w"))' "$TASK_SIGN/password"
    "$TASK_JAVA_ROOT/bin/keytool" -genkeypair -alias watchgate -keyalg RSA -keysize 3072 \
        -validity 10000 -storetype PKCS12 -keystore "$TASK_SIGN/watchgate.p12" \
        -storepass:file "$TASK_SIGN/password" -keypass:file "$TASK_SIGN/password" \
        -dname "CN=WatchGate Local Build, O=Personal, C=CN"
fi
"$TASK_JAVA_ROOT/bin/java" -jar "$TASK_TOOLS/lib/apksigner.jar" sign \
    --ks "$TASK_SIGN/watchgate.p12" --ks-key-alias watchgate \
    --ks-pass "file:$TASK_SIGN/password" \
    --out "$TASK_OUTPUT/洋哥做题了-1.0.0.apk" "$TASK_BUILD/aligned.apk"
echo "APK 已生成：$TASK_OUTPUT/洋哥做题了-1.0.0.apk"
