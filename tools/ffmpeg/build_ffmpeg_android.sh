#!/usr/bin/env bash
# =============================================================================
# build_ffmpeg_android.sh
#
# Reproducible build of a minimal, audio-only, STATIC FFmpeg for Android plus
# its external encoder libraries (LAME, libogg, libvorbis, libopus).
#
# Host:   Windows 10/11 + MSYS2 (MSYS shell) — run from a path WITHOUT spaces.
# Target: Android API 26, ABIs arm64-v8a and x86_64, static libs, -fPIC.
#
# Usage (from an MSYS2 shell):
#   ./build_ffmpeg_android.sh [abi ...]          # default: arm64-v8a x86_64
#   WORK=/c/ffbuild ./build_ffmpeg_android.sh arm64-v8a
#   PROJECT_THIRD_PARTY=/c/path/to/app/src/main/cpp/third_party/ffmpeg ./build_ffmpeg_android.sh
#
# Required MSYS2 packages:  make pkgconf diffutils perl nasm tar xz gzip gnupg
#
# NOTE: Generated build tooling — requires human review before being relied
# on for release builds (license compliance, toolchain hardening flags).
#
# Security / supply-chain decisions:
#   * Every tarball is checked against a pinned SHA-256 (see SHA256 table
#     below) BEFORE extraction; the build aborts on mismatch.
#   * FFmpeg is additionally verified against its detached GPG signature
#     (ffmpeg-devel release key, fingerprint pinned below).
#   * --disable-network / --disable-autodetect / only the "file" protocol:
#     the resulting library cannot open sockets or pull in unexpected system
#     libraries (the app has no INTERNET permission anyway).
#   * No --enable-gpl / --enable-nonfree: result stays LGPL-2.1-or-later.
#   * -fstack-protector-strong and -D_FORTIFY_SOURCE=2 mirror the NDK's own
#     default hardening flags for code compiled outside the NDK CMake toolchain.
# =============================================================================
set -euo pipefail

# ---------------------------------------------------------------- config ----
WORK=${WORK:-/c/ffbuild}                                   # no spaces allowed
NDK=${NDK:-${ANDROID_HOME:?set ANDROID_HOME or NDK}/ndk/27.1.12297006}
SDK_CMAKE_BIN=${SDK_CMAKE_BIN:-${ANDROID_HOME}/cmake/3.22.1/bin}
API=26
ABIS=("${@:-arm64-v8a x86_64}")
# shellcheck disable=SC2206
ABIS=(${ABIS[*]})
PROJECT_THIRD_PARTY=${PROJECT_THIRD_PARTY:-}               # optional copy target
JOBS=${JOBS:-$(nproc)}

FFMPEG_VER=7.1.5
LAME_VER=3.100
OGG_VER=1.3.5
VORBIS_VER=1.3.7
OPUS_VER=1.5.2

declare -A URL=(
  [ffmpeg]="https://ffmpeg.org/releases/ffmpeg-${FFMPEG_VER}.tar.xz"
  [lame]="https://downloads.sourceforge.net/project/lame/lame/${LAME_VER}/lame-${LAME_VER}.tar.gz"
  [ogg]="https://downloads.xiph.org/releases/ogg/libogg-${OGG_VER}.tar.xz"
  [vorbis]="https://downloads.xiph.org/releases/vorbis/libvorbis-${VORBIS_VER}.tar.xz"
  [opus]="https://downloads.xiph.org/releases/opus/opus-${OPUS_VER}.tar.gz"
)
# Pinned SHA-256 of the exact tarballs used (see README.md for provenance).
declare -A SHA256=(
  [ffmpeg]="de668509caf9e35e3cd162473441fdb29538c6d96ed080292b3cf9e6fc5d558f"
  [lame]="ddfe36cab873794038ae2c1210557ad34857a4b6bdc515785d1da9e175b1da1e"
  [ogg]="c4d91be36fc8e54deae7575241e03f4211eb102afb3fc0775fbbc1b740016705"
  [vorbis]="b33cc4934322bcbf6efcbacf49e3ca01aadbea4114ec9589d1b1e9d20f72954b"
  [opus]="65c1d2f78b9f2fb20082c38cbe47c951ad5839345876e46941612ee87f9a7ce1"
)
FFMPEG_KEY_URL="https://ffmpeg.org/ffmpeg-devel.asc"
FFMPEG_KEY_FPR="FCF986EA15E6E293A5644F10B4322F04D67658D8"   # FFmpeg release signing key

# ------------------------------------------------------------- toolchain ----
TC=$NDK/toolchains/llvm/prebuilt/windows-x86_64
SYSROOT=$TC/sysroot
CC_BIN=$TC/bin/clang.exe
CXX_BIN=$TC/bin/clang++.exe
m() { cygpath -m "$1"; }            # MSYS path -> C:/mixed/path for native tools
CMAKE="$SDK_CMAKE_BIN/cmake.exe"
NINJA="$(m "$SDK_CMAKE_BIN/ninja.exe")"
TOOLCHAIN_FILE="$(m "$NDK/build/cmake/android.toolchain.cmake")"

DL=$WORK/dl
SRC=$WORK/src
BLD=$WORK/build
OUT=$WORK/out
mkdir -p "$DL" "$SRC" "$BLD" "$OUT"

log() { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }

# -------------------------------------------------------------- download ----
fetch_and_verify() {
  local k=$1 f
  f=$DL/$(basename "${URL[$k]}")
  [ -s "$f" ] || curl -fL --retry 3 -o "$f" "${URL[$k]}"
  echo "${SHA256[$k]}  $f" | sha256sum -c - || { echo "SHA-256 mismatch for $f" >&2; exit 1; }
}

verify_ffmpeg_gpg() {
  local f=$DL/ffmpeg-${FFMPEG_VER}.tar.xz gh=$WORK/gnupg
  [ -s "$f.asc" ] || curl -fL -o "$f.asc" "${URL[ffmpeg]}.asc"
  [ -s "$DL/ffmpeg-devel.asc" ] || curl -fL -o "$DL/ffmpeg-devel.asc" "$FFMPEG_KEY_URL"
  mkdir -p "$gh" && chmod 700 "$gh"
  gpg --homedir "$gh" --batch --quiet --import "$DL/ffmpeg-devel.asc"
  # Refuse to trust the downloaded key unless its fingerprint matches the pin.
  gpg --homedir "$gh" --batch --with-colons --fingerprint | grep -q "fpr:::::::::${FFMPEG_KEY_FPR}:" \
    || { echo "FFmpeg signing key fingerprint mismatch" >&2; exit 1; }
  gpg --homedir "$gh" --batch --status-fd 1 --verify "$f.asc" "$f" | grep -q "VALIDSIG ${FFMPEG_KEY_FPR}" \
    || { echo "FFmpeg GPG signature verification FAILED" >&2; exit 1; }
  gpgconf --homedir "$gh" --kill gpg-agent 2>/dev/null || true   # don't leave an agent running
  echo "FFmpeg tarball GPG signature OK (key $FFMPEG_KEY_FPR)"
}

# Extract a tarball into a fresh, empty directory: $SRC/<abi>/<name>
extract() {
  local k=$1 abi=$2 dst=$SRC/$2/$1
  rm -rf "$dst" && mkdir -p "$dst"
  tar -xf "$DL/$(basename "${URL[$k]}")" -C "$dst" --strip-components=1
}

# ------------------------------------------------------------ per-ABI vars ----
setup_abi() {
  ABI=$1
  case $ABI in
    arm64-v8a) TRIPLE=aarch64-linux-android; FF_ARCH=aarch64; FF_ASM=() ;;
    # x86_64: nasm from MSYS2 emits ELF64 objects; x86inc marks asm globals
    # hidden and -fvisibility=hidden (see build_ffmpeg) does the same for the
    # C-side constant tables, so the PC-relative relocs are .so-safe.
    x86_64)    TRIPLE=x86_64-linux-android;  FF_ARCH=x86_64;  FF_ASM=(--x86asmexe=nasm) ;;
    *) echo "unsupported ABI $ABI" >&2; exit 1 ;;
  esac
  PREFIX=$OUT/$ABI
  PREFIX_M=$(m "$PREFIX")
  TARGET="--target=${TRIPLE}${API}"
  HARDEN="-fstack-protector-strong -D_FORTIFY_SOURCE=2"
  BASE_CFLAGS="$TARGET --sysroot=$(m "$SYSROOT") -fPIC -O2 -DANDROID $HARDEN"
  mkdir -p "$PREFIX"
}

cmake_build() {   # cmake_build <name> <srcdir> [extra -D args...]
  local name=$1 src=$2; shift 2
  local b=$BLD/$ABI/$name
  rm -rf "$b" && mkdir -p "$b"
  "$CMAKE" -S "$(m "$src")" -B "$(m "$b")" -G Ninja \
    -DCMAKE_MAKE_PROGRAM="$NINJA" \
    -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN_FILE" \
    -DANDROID_ABI="$ABI" -DANDROID_PLATFORM=android-$API \
    -DCMAKE_BUILD_TYPE=Release \
    -DBUILD_SHARED_LIBS=OFF -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
    -DBUILD_TESTING=OFF \
    -DCMAKE_INSTALL_PREFIX="$PREFIX_M" -DCMAKE_INSTALL_LIBDIR=lib \
    "$@"
  "$CMAKE" --build "$(m "$b")" -j "$JOBS"
  "$CMAKE" --install "$(m "$b")"
}

build_ogg() {
  log "[$ABI] libogg $OGG_VER"
  extract ogg "$ABI"
  cmake_build ogg "$SRC/$ABI/ogg" -DINSTALL_DOCS=OFF
}

build_vorbis() {
  log "[$ABI] libvorbis $VORBIS_VER"
  extract vorbis "$ABI"
  # Point FindOgg at our freshly built static libogg (bypasses find_* which
  # the NDK toolchain restricts to the sysroot).
  cmake_build vorbis "$SRC/$ABI/vorbis" \
    -DOGG_INCLUDE_DIR="$PREFIX_M/include" -DOGG_LIBRARY="$PREFIX_M/lib/libogg.a"
  # libvorbis 1.3.7's CMake leaves "Libs.private:" empty, so a static link
  # via pkg-config misses libm (sin/cos/exp...). Fix the installed .pc file.
  sed -i 's/^Libs.private: *$/Libs.private: -lm/' "$PREFIX/lib/pkgconfig/vorbis.pc"
}

build_opus() {
  log "[$ABI] libopus $OPUS_VER"
  extract opus "$ABI"
  # Neural-network extras (Deep PLC; DRED/OSCE are autotools-only in 1.5.2
  # and off by default) are not needed by FFmpeg's libopus wrapper.
  cmake_build opus "$SRC/$ABI/opus" \
    -DOPUS_BUILD_PROGRAMS=OFF -DOPUS_BUILD_TESTING=OFF -DOPUS_BUILD_SHARED_LIBRARY=OFF \
    -DOPUS_DEEP_PLC=OFF \
    -DOPUS_INSTALL_PKG_CONFIG_MODULE=ON -DOPUS_INSTALL_CMAKE_CONFIG_MODULE=OFF
}

build_lame() {
  log "[$ABI] LAME $LAME_VER"
  extract lame "$ABI"
  local s=$SRC/$ABI/lame
  # In-tree autotools build (source tree is per-ABI and freshly extracted).
  ( cd "$s"
    ./configure --host="$TRIPLE" --prefix="$PREFIX" \
      --enable-static --disable-shared --with-pic \
      --disable-frontend --disable-decoder --disable-gtktest --disable-analyzer-hooks \
      --disable-dependency-tracking \
      CC="$(m "$CC_BIN") $TARGET" \
      CFLAGS="$BASE_CFLAGS" \
      AR="$(m "$TC/bin/llvm-ar.exe")" RANLIB="$(m "$TC/bin/llvm-ranlib.exe")" \
      NM="$(m "$TC/bin/llvm-nm.exe")" STRIP="$(m "$TC/bin/llvm-strip.exe")"
    make -j"$JOBS"
    make install )
}

build_ffmpeg() {
  log "[$ABI] FFmpeg $FFMPEG_VER"
  extract ffmpeg "$ABI"
  local b=$BLD/$ABI/ffmpeg
  rm -rf "$b" && mkdir -p "$b"
  # -fvisibility=hidden: FFmpeg's hand-written aarch64/x86 asm addresses C data
  # tables (ff_tx_tab_*, ff_pw_*) PC-relatively. With default visibility those
  # symbols are preemptible once the archive is linked into the app's JNI .so
  # and ld.lld fails with "relocation R_AARCH64_ADR_PREL_PG_HI21 cannot be used
  # against symbol ...; recompile with -fPIC". Hidden visibility makes them
  # link-unit local (also keeps FFmpeg's API out of the app's dynamic symbol
  # table, reducing exported attack surface). App code linking the .a files
  # in the same .so is unaffected.
  # Only our own .pc files are visible: no host libraries can leak in.
  export PKG_CONFIG_LIBDIR=$PREFIX/lib/pkgconfig
  unset PKG_CONFIG_PATH
  ( cd "$b"
    "$SRC/$ABI/ffmpeg/configure" \
      --prefix="$PREFIX" \
      --target-os=android --arch="$FF_ARCH" --enable-cross-compile \
      --sysroot="$(m "$SYSROOT")" \
      --cc="$(m "$CC_BIN")" --cxx="$(m "$CXX_BIN")" \
      --nm="$(m "$TC/bin/llvm-nm.exe")" --ar="$(m "$TC/bin/llvm-ar.exe")" \
      --ranlib="$(m "$TC/bin/llvm-ranlib.exe")" --strip="$(m "$TC/bin/llvm-strip.exe")" \
      --host-cc=gcc \
      --pkg-config=pkg-config --pkg-config-flags=--static \
      --extra-cflags="$TARGET -fPIC -fvisibility=hidden -DANDROID $HARDEN -I$PREFIX_M/include" \
      --extra-ldflags="$TARGET -L$PREFIX_M/lib" \
      --enable-pic --enable-static --disable-shared \
      --disable-everything --disable-autodetect --disable-programs --disable-doc \
      --disable-network --disable-avdevice --disable-swscale --disable-postproc \
      --disable-avfilter --disable-debug \
      --enable-avcodec --enable-avformat --enable-avutil --enable-swresample \
      --enable-zlib \
      --enable-libmp3lame --enable-libopus --enable-libvorbis \
      --enable-decoder=mp3,mp3float,mp2,aac,aac_latm,flac,alac,vorbis,opus,ac3,eac3,wmav1,wmav2,amrnb,amrwb,pcm_s16le,pcm_s16be,pcm_s24le,pcm_s32le,pcm_f32le,pcm_f64le,pcm_u8,pcm_alaw,pcm_mulaw,adpcm_ms,adpcm_ima_wav \
      --enable-encoder=aac,flac,alac,pcm_s16le,pcm_s24le,pcm_s32le,pcm_f32le,libmp3lame,libopus,libvorbis \
      --enable-demuxer=mp3,aac,mov,matroska,ogg,flac,wav,w64,avi,amr,aiff,asf,ac3,eac3,caf \
      --enable-muxer=mp3,ipod,mp4,mov,flac,wav,ogg,opus,adts,matroska,webm \
      --enable-parser=aac,aac_latm,mpegaudio,flac,opus,vorbis,ac3 \
      --enable-bsf=aac_adtstoasc \
      --enable-protocol=file \
      "${FF_ASM[@]}" \
      || { tail -n 60 ffbuild/config.log; exit 1; }
    make -j"$JOBS"
    make install )
}

verify_and_test() {
  log "[$ABI] verifying symbols + link test"
  local nm=$TC/bin/llvm-nm.exe L=$PREFIX/lib
  for s in avformat_open_input:libavformat avcodec_send_packet:libavcodec swr_convert:libswresample \
           av_frame_alloc:libavutil lame_init:libmp3lame opus_encoder_create:libopus \
           vorbis_encode_init:libvorbisenc vorbis_info_init:libvorbis ogg_stream_init:libogg; do
    "$nm" -g --defined-only "$(m "$L/${s#*:}.a")" 2>/dev/null | grep -qE " T ${s%%:*}$" \
      && echo "  OK  ${s%%:*} defined in ${s#*:}.a" || { echo "  MISSING ${s%%:*}" >&2; exit 1; }
  done
  local t=$BLD/$ABI/linktest; mkdir -p "$t"
  cat > "$t/linktest.c" <<'EOF'
#include <stdio.h>
#include <libavformat/avformat.h>
#include <libavcodec/avcodec.h>
#include <libswresample/swresample.h>
#include <libavutil/avutil.h>
#include <lame/lame.h>
#include <opus/opus.h>
int main(int argc, char **argv) {
    AVFormatContext *fmt = NULL;
    if (argc > 1 && avformat_open_input(&fmt, argv[1], NULL, NULL) == 0) avformat_close_input(&fmt);
    const AVCodec *enc[] = { avcodec_find_encoder_by_name("libmp3lame"), avcodec_find_encoder_by_name("libopus"),
                             avcodec_find_encoder_by_name("libvorbis"), avcodec_find_encoder_by_name("aac"),
                             avcodec_find_encoder_by_name("flac") };
    for (unsigned i = 0; i < sizeof enc / sizeof *enc; i++) printf("enc[%u]=%s\n", i, enc[i] ? enc[i]->name : "(null)");
    AVCodecContext *c = avcodec_alloc_context3(NULL); avcodec_send_packet(c, NULL); avcodec_free_context(&c);
    SwrContext *swr = swr_alloc(); swr_convert(swr, NULL, 0, NULL, 0); swr_free(&swr);
    lame_global_flags *l = lame_init(); lame_close(l);
    int err; OpusEncoder *o = opus_encoder_create(48000, 2, OPUS_APPLICATION_AUDIO, &err); opus_encoder_destroy(o);
    printf("%s\n", av_version_info());
    return 0;
}
EOF
  "$CC_BIN" $TARGET --sysroot="$(m "$SYSROOT")" -fPIE -pie \
    -I"$PREFIX_M/include" "$(m "$t/linktest.c")" -o "$(m "$t/linktest")" \
    -L"$PREFIX_M/lib" \
    -lavformat -lavcodec -lswresample -lavutil \
    -lmp3lame -lopus -lvorbisenc -lvorbis -logg \
    -lz -lm
  echo "  link test OK -> $t/linktest"
  # Shared-object test mirroring how the app consumes the archives: every
  # FFmpeg object pulled in, no undefined symbols, and no text relocations.
  "$CC_BIN" $TARGET --sysroot="$(m "$SYSROOT")" -fPIC -shared \
    -Wl,--no-undefined -Wl,-z,text \
    -Wl,--whole-archive \
      "$PREFIX_M/lib/libavformat.a" "$PREFIX_M/lib/libavcodec.a" \
      "$PREFIX_M/lib/libswresample.a" "$PREFIX_M/lib/libavutil.a" \
    -Wl,--no-whole-archive \
    -L"$PREFIX_M/lib" -lmp3lame -lopus -lvorbisenc -lvorbis -logg -lz -lm \
    -o "$(m "$t/libsotest.so")"
  echo "  shared-object link test OK (-z text, --no-undefined) -> $t/libsotest.so"
}

copy_to_project() {
  [ -n "$PROJECT_THIRD_PARTY" ] || return 0
  log "[$ABI] copying to $PROJECT_THIRD_PARTY/$ABI"
  local d=$PROJECT_THIRD_PARTY/$ABI
  rm -rf "$d" && mkdir -p "$d/include" "$d/lib"
  cp -r "$PREFIX"/include/{libavcodec,libavformat,libavutil,libswresample} "$d/include/"
  # Also ship the encoder-lib headers (harmless, handy for direct use).
  cp -r "$PREFIX"/include/{lame,opus,ogg,vorbis} "$d/include/"
  for a in libavformat libavcodec libswresample libavutil libmp3lame libopus libvorbisenc libvorbis libogg; do
    cp "$PREFIX/lib/$a.a" "$d/lib/"
  done
}

# ------------------------------------------------------------------- main ----
# STEPS lets a human re-run part of the pipeline, e.g. STEPS="ffmpeg test".
STEPS=${STEPS:-"deps ffmpeg test copy"}
has() { [[ " $STEPS " == *" $1 "* ]]; }

for k in lame ogg vorbis opus; do fetch_and_verify "$k"; done
if has ffmpeg; then fetch_and_verify ffmpeg; verify_ffmpeg_gpg; fi

for abi in "${ABIS[@]}"; do
  setup_abi "$abi"
  if has deps; then
    rm -rf "$PREFIX" && mkdir -p "$PREFIX"
    build_ogg
    build_vorbis
    build_opus
    build_lame
  fi
  has ffmpeg && build_ffmpeg
  has test && verify_and_test
  has copy && copy_to_project
  du -ch "$PREFIX"/lib/*.a | tail -n1
done
log "done"
