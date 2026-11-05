#!/usr/bin/env bash
set -euo pipefail

OUT="${1:-libs/arm64-v8a}"
TARGET="aarch64-linux-android"
OPT="${OPTIMIZE:-ReleaseSafe}"

mkdir -p "$OUT"

build() {
    local root="$1" name="$2"
    echo "==> $name from $root"
    zig build-lib "$root" \
        -dynamic \
        -target "$TARGET" \
        -mcpu=generic \
        -O "$OPT" \
        -fPIC \
        -fstrip \
        -femit-bin="$OUT/$name" \
        -fno-emit-h \
        -fsingle-threaded \
        -fno-stack-check
}

build passwordmanager.zig libfolyan_pass.so
build stego_capi.zig libfolyan_stego.so
build converter_capi.zig libfolyan_convert.so

echo "Done: $(ls "$OUT")"
