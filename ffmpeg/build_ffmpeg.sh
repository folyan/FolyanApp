#!/usr/bin/env bash
set -euo pipefail

: "${ANDROID_NDK_HOME:?задайте ANDROID_NDK_HOME}"

API="${API:-24}"
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
export PKG_CONFIG_LIBDIR="$PREFIX/lib/pkgconfig"
export PKG_CONFIG_PATH="$PREFIX/lib/pkgconfig"

mkdir -p "$PREFIX" "$SRC" "$OUT"

# Умная функция загрузки с ретраями
curl_download() {
  local url=$1 dest=$2
  curl -fsSL --connect-timeout 15 --retry 5 --retry-delay 3 --retry-all-errors "$url" -o "$dest"
}

fetch() {
  local url=$1 name
  name=$(basename "$url" | sed 's/\.tar\..*//')
  if [ ! -d "$SRC/$name" ]; then
    echo "== Скачивание $name..."
    curl_download "$url" "$SRC/$name.tar"
    mkdir -p "$SRC/$name"
    tar -xf "$SRC/$name.tar" --strip-components=1 -C "$SRC/$name"
  fi
}

fetch_ffmpeg() {
  local target_dir="$SRC/ffmpeg-$FFMPEG_VERSION"
  if [ -d "$target_dir" ] && [ -f "$target_dir/configure" ]; then
    return 0
  fi

  mkdir -p "$target_dir"
  local primary_url="https://ffmpeg.org/releases/ffmpeg-${FFMPEG_VERSION}.tar.xz"
  local fallback_url="https://github.com/FFmpeg/FFmpeg/archive/refs/tags/n${FFMPEG_VERSION}.tar.gz"

  echo "== Скачивание FFmpeg: попытка с ffmpeg.org..."
  if ! curl_download "$primary_url" "$SRC/ffmpeg.tar"; then
    echo "==! Ошибка загрузки с ffmpeg.org. Переключаемся на GitHub..."
    curl_download "$fallback_url" "$SRC/ffmpeg.tar"
  fi

  tar -xf "$SRC/ffmpeg.tar" --strip-components=1 -C "$target_dir"
}

autotools_build() {
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

# ---- Зависимости ----
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

command -v pkg-config >/dev/null || { echo "нет pkg-config: sudo apt-get install -y pkg-config"; exit 1; }
for lib in opus vorbis ogg x264; do
  pkg-config --exists "$lib" || {
    echo "ERROR: нет $lib.pc в $PREFIX/lib/pkgconfig"
    ls "$PREFIX/lib/pkgconfig" || true
    exit 1
  }
done

# ---- FFmpeg ----
fetch_ffmpeg
cd "$SRC/ffmpeg-$FFMPEG_VERSION"

./configure \
  --prefix="$PREFIX" \
  --target-os=android --arch=aarch64 --enable-cross-compile \
  --cc="$CC" --cxx="$CXX" --ar="$AR" --ranlib="$RANLIB" --strip="$STRIP" --nm="$NM" \
  --sysroot="$TC/sysroot" \
  --extra-cflags="-I$PREFIX/include -fPIC -Os" \
  --extra-ldflags="-L$PREFIX/lib" \
  --enable-static --disable-shared --enable-pic \
  --pkg-config="$(command -v pkg-config)" --pkg-config-flags="--static" \
  --enable-gpl \
  --disable-everything \
  --disable-network --disable-doc --disable-debug --disable-avdevice \
  --disable-ffplay --disable-ffprobe --enable-small \
  --enable-libx264 --enable-libmp3lame --enable-libopus --enable-libvorbis \
  --enable-protocol=file,pipe \
  --enable-demuxer=mov,matroska,ogg,mp3,wav,flac,aac,concat \
  --enable-muxer=mp4,ipod,matroska,ogg,mp3,wav,flac,adts \
  --enable-decoder=h264,hevc,vp9,aac,mp3,opus,vorbis,flac,pcm_s16le \
  --enable-encoder=libx264,libmp3lame,libopus,libvorbis,aac,flac,pcm_s16le \
  --enable-parser=h264,hevc,vp9,aac,mpegaudio,opus,vorbis,flac \
  --enable-bsf=aac_adtstoasc,h264_mp4toannexb,hevc_mp4toannexb,extract_extradata \
  --enable-filter=aresample,aformat,anull,format,null \
  --enable-swresample

make -j"$JOBS"

cp ffmpeg "$OUT/libffmpeg.so"
"$STRIP" --strip-unneeded "$OUT/libffmpeg.so"

"$BIN/llvm-readelf" -h "$OUT/libffmpeg.so" | grep -E 'Type|Machine'
echo "Готово: $OUT/libffmpeg.so"
