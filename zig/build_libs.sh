#!/usr/bin/env bash
# Собирает Zig-библиотеки для Android arm64 (загрузчик больше не входит: его делает Python).
#   libfolyan_pass.so    <- passwordmanager.zig
#   libfolyan_convert.so <- converter_capi.zig
#   libfolyan_stego.so   <- stego_capi.zig
#
# -fstrip: символы выкидываются на этапе линковки (меньше .so, без лишнего дебаг-мусора).
# Использование: ./build_libs.sh [output_dir]   (запускать из каталога zig/)
# Нужен zig в PATH. ANDROID_TARGET и OPTIMIZE можно переопределить.

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
        -fstrip \
        -femit-bin="$OUT/$name" \
        -fno-emit-h
}

build passwordmanager.zig libfolyan_pass.so
build converter_capi.zig libfolyan_convert.so
build stego_capi.zig libfolyan_stego.so

echo "Done: $(ls "$OUT")"
