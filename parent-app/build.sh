#!/bin/bash
set -euo pipefail
TASK_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TASK_SDK="${WATCHGATE_ANDROID_SDK:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
TASK_JAVA="${WATCHGATE_JAVA_ROOT:-$(/usr/libexec/java_home)}"
TASK_TOOLS="$TASK_SDK/build-tools/34.0.0"
TASK_ANDROID="$TASK_SDK/platforms/android-34/android.jar"
TASK_TMP="$TASK_ROOT/parent-app/build"
TASK_SRC="$TASK_ROOT/parent-app/src/main"
mkdir -p "$TASK_TMP/classes" "$TASK_TMP/dex" "$TASK_ROOT/releases"
"$TASK_TOOLS/aapt2" compile --dir "$TASK_SRC/res" -o "$TASK_TMP/res.zip"
"$TASK_TOOLS/aapt2" link -I "$TASK_ANDROID" --manifest "$TASK_SRC/AndroidManifest.xml" --min-sdk-version 26 --target-sdk-version 34 -o "$TASK_TMP/base.apk" "$TASK_TMP/res.zip"
"$TASK_JAVA/bin/javac" -encoding UTF-8 --release 8 -classpath "$TASK_ANDROID" -d "$TASK_TMP/classes" "$TASK_SRC/java/cn/tan/watchgateparent/MainActivity.java"
"$TASK_JAVA/bin/jar" cf "$TASK_TMP/classes.jar" -C "$TASK_TMP/classes" .
"$TASK_JAVA/bin/java" -cp "$TASK_TOOLS/lib/d8.jar" com.android.tools.r8.D8 --min-api 26 --lib "$TASK_ANDROID" --output "$TASK_TMP/dex" "$TASK_TMP/classes.jar"
cp "$TASK_TMP/base.apk" "$TASK_TMP/unsigned.apk"
zip -q -j "$TASK_TMP/unsigned.apk" "$TASK_TMP/dex/classes.dex"
"$TASK_TOOLS/zipalign" -f -p 4 "$TASK_TMP/unsigned.apk" "$TASK_TMP/aligned.apk"
"$TASK_JAVA/bin/java" -jar "$TASK_TOOLS/lib/apksigner.jar" sign --ks "$TASK_ROOT/.signing/watchgate.p12" --ks-key-alias watchgate --ks-pass "file:$TASK_ROOT/.signing/password" --out "$TASK_ROOT/releases/WatchGate-Parent-1.4.apk" "$TASK_TMP/aligned.apk"
# 儿童端的下载二维码同步提供本次构建的家长端安装包。
cp "$TASK_ROOT/releases/WatchGate-Parent-1.4.apk" "$TASK_ROOT/app/src/main/assets/parent.apk"
