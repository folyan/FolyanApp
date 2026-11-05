#!/usr/bin/env bash
# Builds one shared library per module for Android arm64.
#   libfolyan_pass.so    <- passwordmanager.zig   (password generation)
#   libfolyan_convert.so <- converter_capi.zig    (ffmpeg convert + merge)
# Add libfolyan_stego.so / libfolyan_download.so the same way when those are ported.
#
# Usage: ./build_libs.sh [output_dir]
# Requires zig on PATH. Set ANDROID_TARGET if your target differs.

set -euo pipefail

OUT="${1:-libs/arm64-v8a}"
TARGET="${ANDROID_TARGET:-aarch64-linux-android}"
OPT="${OPTIMIZE:-ReleaseSafe}"

mkdir -p "$OUT"

build() {
    local root="$1" name="$2"
    echo "==> $name from $root"
    zig build-lib "$root" \
        -dynamic \
        -target "$TARGET" \
        -O "$OPT" \
        -fPIC \
        -femit-bin="$OUT/$name" \
        -fno-emit-h
}

build passwordmanager.zig libfolyan_pass.so
build converter_capi.zig libfolyan_convert.so
build stego_capi.zig libfolyan_stego.so
build download_capi.zig libfolyan_download.so

echo "Done: $(ls "$OUT")"
