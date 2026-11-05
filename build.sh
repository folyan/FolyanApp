#!/usr/bin/env bash
# Полная сборка APK: Zig (strip) -> ffmpeg -> jniLibs -> gradle.
# Нужно: zig 0.17 в PATH, ANDROID_NDK_HOME (NDK r28c), JDK 17, Android SDK, gradle (или ./gradlew).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
JNI="$ROOT/app/src/main/jniLibs/arm64-v8a"
mkdir -p "$JNI"

echo "== Zig-библиотеки (strip)"
(cd "$ROOT/zig" && ./build_libs.sh "$JNI")

echo "== ffmpeg"
: "${ANDROID_NDK_HOME:?задайте ANDROID_NDK_HOME}"
(cd "$ROOT" && ./ffmpeg/build_ffmpeg.sh)
cp "$ROOT/out/libffmpeg.so" "$JNI/"

echo "== APK"
cd "$ROOT"
if [ -x ./gradlew ]; then GRADLE=./gradlew; else GRADLE=gradle; fi
$GRADLE assembleDebug

echo "Готово: $ROOT/app/build/outputs/apk/debug/app-debug.apk"
ls -la "$JNI"
