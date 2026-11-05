#!/usr/bin/env bash
# Собирает libffmpeg.so для Android arm64.
# Это исполняемый PIE-файл: приложение запускает его из nativeLibraryDir.
#
# GPL-сборка: включает libx264 (GPL-2.0-or-later). --enable-version3 НЕ используется,
# чтобы не получить GPLv3 и не конфликтовать с GPLv2 приложения.
#
# Нужно: ANDROID_NDK_HOME (NDK r28c), curl, git, make, tar, xz.
# Использование: ANDROID_NDK_HOME=/path/to/ndk ./ffmpeg/build_ffmpeg.sh
set -euo pipefail

: "${ANDROID_NDK_HOME:?задайте ANDROID_NDK_HOME}"

API="${API:-24}"                       # minSdk из buildozer.spec
FFMPEG_VERSION="${FFMPEG_VERSION:-7.1}"
LAME_VERSION="${LAME_VERSION:-3.100}"
OPUS_VERSION="${OPUS_VERSION:-1.5.2}"
OGG_VERSION="${OGG_VERSION:-1.3.5}"
VORBIS_VERSION="${VORBIS_VERSION:-1.3.7}"

WORK="${WORK:-$PWD/build}"
OUT="${OUT:-$PWD/out}"
PREFIX="$WORK/prefix"
SRC="$WORK/src"
JOBS="$(nproc)"

TC="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64"
BIN="$TC/bin"
HOST=aarch64-linux-android
BUILD=x86_64-pc-linux-gnu

export CC="$BIN/${HOST}${API}-clang"
export CXX="$BIN/${HOST}${API}-clang++"
export AR="$BIN/llvm-ar"
export RANLIB="$BIN/llvm-ranlib"
export STRIP="$BIN/llvm-strip"
export NM="$BIN/llvm-nm"
export CFLAGS="-O2 -fPIC"
export CXXFLAGS="-O2 -fPIC"
# ffmpeg ищет opus/vorbis/x264 через pkg-config. Ищем только в нашем prefix, системные .pc не нужны.
export PKG_CONFIG_LIBDIR="$PREFIX/lib/pkgconfig"
export PKG_CONFIG_PATH="$PREFIX/lib/pkgconfig"

mkdir -p "$PREFIX" "$SRC" "$OUT"

# Скачать и распаковать архив один раз. Имя каталога = имя архива без .tar.*
fetch() {
  local url=$1 name
  name=$(basename "$url" | sed 's/\.tar\..*//')
  if [ ! -d "$SRC/$name" ]; then
    curl -fsSL "$url" -o "$SRC/$name.tar"
    tar -xf "$SRC/$name.tar" -C "$SRC"
  fi
}

# Зависимости собираем один раз; маркер лежит в prefix, поэтому кэш Actions его сохраняет.
autotools_build() {  # name source-dir [configure args...]
  local name=$1 dir=$2
  shift 2
  if [ -f "$PREFIX/.$name.done" ]; then
    echo "== $name: уже собрано"
    return 0
  fi
  echo "== $name"
  (
    cd "$dir"
    ./configure --build="$BUILD" --host="$HOST" --prefix="$PREFIX" \
      --enable-static --disable-shared "$@"
    make -j"$JOBS"
    make install
  )
  touch "$PREFIX/.$name.done"
}

build_x264() {
  if [ -f "$PREFIX/.x264.done" ]; then
    echo "== x264: уже собрано"
    return 0
  fi
  echo "== x264"
  [ -d "$SRC/x264" ] || git clone --depth 1 https://code.videolan.org/videolan/x264.git "$SRC/x264"
  (
    cd "$SRC/x264"
    ./configure --host="$HOST" --prefix="$PREFIX" \
      --enable-static --enable-pic \
      --disable-cli --disable-lavf --disable-ffms --disable-gpac --disable-swscale
    make -j"$JOBS"
    make install
  )
  touch "$PREFIX/.x264.done"
}

# ---- зависимости ffmpeg ----
fetch "https://downloads.sourceforge.net/project/lame/lame/${LAME_VERSION}/lame-${LAME_VERSION}.tar.gz"
fetch "https://github.com/xiph/opus/releases/download/v${OPUS_VERSION}/opus-${OPUS_VERSION}.tar.gz"
fetch "https://github.com/xiph/ogg/releases/download/v1.3.5/libogg-${OGG_VERSION}.tar.gz"
fetch "https://github.com/xiph/vorbis/releases/download/v1.3.7/libvorbis-${VORBIS_VERSION}.tar.gz"

autotools_build lame "$SRC/lame-$LAME_VERSION" --disable-frontend
autotools_build opus "$SRC/opus-$OPUS_VERSION" --disable-doc --disable-extra-programs
autotools_build ogg "$SRC/libogg-$OGG_VERSION"
autotools_build vorbis "$SRC/libvorbis-$VORBIS_VERSION" \
  --with-ogg="$PREFIX" --disable-docs --disable-examples --disable-oggtest
build_x264

# Проверяем, что все .pc на месте, до запуска долгого configure
command -v pkg-config >/dev/null || { echo "нет pkg-config: sudo apt-get install -y pkg-config"; exit 1; }
for lib in opus vorbis ogg x264; do
  pkg-config --exists "$lib" || {
    echo "ERROR: нет $lib.pc в $PREFIX/lib/pkgconfig"
    ls "$PREFIX/lib/pkgconfig" || true
    exit 1
  }
done

# ---- ffmpeg ----
fetch "https://ffmpeg.org/releases/ffmpeg-${FFMPEG_VERSION}.tar.xz"
cd "$SRC/ffmpeg-$FFMPEG_VERSION"

./configure \
  --prefix="$PREFIX" \
  --target-os=android --arch=aarch64 --enable-cross-compile \
  --cc="$CC" --cxx="$CXX" --ar="$AR" --ranlib="$RANLIB" --strip="$STRIP" --nm="$NM" \
  --sysroot="$TC/sysroot" \
  --extra-cflags="-I$PREFIX/include -fPIC" \
  --extra-ldflags="-L$PREFIX/lib" \
  --enable-static --disable-shared --enable-pic \
  --pkg-config="$(command -v pkg-config)" --pkg-config-flags="--static" \
  --enable-gpl \
  --enable-libx264 --enable-libmp3lame --enable-libopus --enable-libvorbis \
  --disable-doc --disable-ffplay --disable-debug

make -j"$JOBS"

cp ffmpeg "$OUT/libffmpeg.so"
"$STRIP" --strip-unneeded "$OUT/libffmpeg.so"

# Проверка: должен быть ELF для AArch64 типа DYN (PIE)
"$BIN/llvm-readelf" -h "$OUT/libffmpeg.so" | grep -E 'Type|Machine'
echo "Готово: $OUT/libffmpeg.so"
