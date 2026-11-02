#!/bin/bash
# Builds the optional LGPL-only FFmpeg audio decoders (AC-3, E-AC-3, DTS, TrueHD/MLP, MPEG
# audio) as shared libraries for the four Android ABIs.
#
# FFmpeg is configured without --enable-gpl, --enable-version3 or --enable-nonfree, so the
# result is LGPL v2.1 or later. The script stops if FFmpeg's own configure output says
# anything else. Shared libraries (not static) keep the LGPL relinking right simple: the
# libraries in the APK can be replaced with any compatible build.
#
#   scripts/build_ffmpeg.sh <ndk-dir> [work-dir]
#
# The work dir must have no spaces (FFmpeg's configure does not support them); default
# $TEMP/ffmpeg-android. Output (git-ignored) in app/src/main/ffmpeg/: jniLibs/<abi>/*.so
# (packaged by Gradle), include/<abi>/ (for the JNI glue) and configuration-<abi>.txt.
# The app builds without it; it then just has no AC-3/DTS fallback decoder.
# Runs in Git Bash on Windows (NDK's make.exe) or any Linux/macOS shell.
set -euo pipefail

VERSION=7.1.5
SHA256=de668509caf9e35e3cd162473441fdb29538c6d96ed080292b3cf9e6fc5d558f
URL="https://ffmpeg.org/releases/ffmpeg-$VERSION.tar.xz"
DECODERS="ac3 eac3 dca truehd mlp mp3"
API=26

NDK="${1:?usage: build_ffmpeg.sh <ndk-dir> [work-dir]}"
WORK="${2:-${TEMP:-/tmp}/ffmpeg-android}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/app/src/main/ffmpeg"
case "$WORK" in *" "*) echo "work dir must not contain spaces: $WORK"; exit 1;; esac

case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) HOST=windows-x86_64; MAKE="$NDK/prebuilt/windows-x86_64/bin/make.exe";;
  Darwin) HOST=darwin-x86_64; MAKE=make;;
  *) HOST=linux-x86_64; MAKE=make;;
esac
BIN="$NDK/toolchains/llvm/prebuilt/$HOST/bin"
JOBS="$(nproc 2>/dev/null || echo 4)"

mkdir -p "$WORK/tmp"
# configure creates test files under $TMPDIR; Windows' TEMP has backslashes it cannot use.
TMPDIR="$WORK/tmp"
command -v cygpath >/dev/null && TMPDIR="$(cygpath -u "$TMPDIR")"
export TMPDIR TEMP="$TMPDIR" TMP="$TMPDIR"

# The host compiler is only used by configure checks (no host tools are built without
# --enable-hardcoded-tables), so the NDK's x86_64 clang stands in where gcc is missing.
build() { # abi arch cpu triple extra-configure-args...
  local abi=$1 arch=$2 cpu=$3 triple=$4; shift 4
  local enable=""
  for d in $DECODERS; do enable="$enable --enable-decoder=$d"; done
  echo "=== $abi"
  (
    cd "$SRC"
    "$MAKE" distclean >/dev/null 2>&1 || true
    ./configure \
      --prefix="$WORK/out/$abi" \
      --target-os=android --enable-cross-compile --arch="$arch" --cpu="$cpu" \
      --cc="$BIN/$triple$API-clang" --cxx="$BIN/$triple$API-clang++" \
      --host-cc="$BIN/x86_64-linux-android$API-clang" --host-ld="$BIN/x86_64-linux-android$API-clang" \
      --nm="$BIN/llvm-nm" --ar="$BIN/llvm-ar" --ranlib="$BIN/llvm-ranlib" --strip="$BIN/llvm-strip" \
      --enable-shared --disable-static --disable-symver \
      --disable-doc --disable-programs --disable-everything --disable-autodetect --disable-network \
      --disable-avdevice --disable-avformat --disable-swscale --disable-postproc --disable-avfilter \
      --enable-swresample $enable \
      --extra-ldflags="-Wl,-z,max-page-size=16384" \
      "$@" > "$WORK/configure-$abi.log"
    # Licence check: FFmpeg reports the licence that its configuration implies.
    grep -q '^#define FFMPEG_LICENSE "LGPL version 2.1 or later"' config.h || { echo "not LGPL 2.1+:"; grep FFMPEG_LICENSE config.h; exit 1; }
    for flag in GPL VERSION3 NONFREE; do
      grep -q "^#define CONFIG_$flag 0" config.h || { echo "CONFIG_$flag is enabled"; exit 1; }
    done
    "$MAKE" -j"$JOBS" > "$WORK/make-$abi.log"
    "$MAKE" install-libs install-headers > /dev/null
    grep '^#define FFMPEG_CONFIGURATION' config.h > "$WORK/out/$abi/configuration.txt"
  )
  install_abi "$abi"
}

# Copies one ABI's build into the app (also usable on its own after a manual build).
install_abi() {
  local abi=$1
  rm -rf "$OUT/jniLibs/$abi" "$OUT/include/$abi" && mkdir -p "$OUT/jniLibs/$abi" "$OUT/include"
  cp -r "$WORK/out/$abi/include" "$OUT/include/$abi"
  cp "$WORK/out/$abi/lib/"lib{avcodec,avutil,swresample}.so "$OUT/jniLibs/$abi/"
  # Android loads libraries by file name, so each SONAME must be the plain name.
  for lib in "$OUT/jniLibs/$abi/"*.so; do
    "$BIN/llvm-readelf" -d "$lib" | grep -q "SONAME.*\[$(basename "$lib")\]" || { echo "bad SONAME in $lib"; exit 1; }
  done
  # The configure line, with this machine's paths replaced.
  sed -e "s#$WORK#<work>#g" -e "s#$NDK#<ndk>#g" "$WORK/out/$abi/configuration.txt" > "$OUT/configuration-$abi.txt"
}

if [ "${INSTALL_ONLY:-}" = 1 ]; then  # re-copy finished builds without rebuilding
  for abi in arm64-v8a armeabi-v7a x86_64 x86; do install_abi "$abi"; done
  exit 0
fi

SRC="$WORK/src/ffmpeg-$VERSION"
TARBALL="$WORK/ffmpeg-$VERSION.tar.xz"
[ -f "$TARBALL" ] || curl -fL "$URL" -o "$TARBALL"
echo "$SHA256  $TARBALL" | sha256sum -c -
rm -rf "$WORK/src" && mkdir -p "$WORK/src"
# Relative paths: GNU tar on Windows would read "C:" as a remote host.
(cd "$WORK" && tar -xJf "ffmpeg-$VERSION.tar.xz" -C src)

build arm64-v8a aarch64 armv8-a aarch64-linux-android
build armeabi-v7a arm armv7-a armv7a-linux-androideabi --extra-cflags="-march=armv7-a -mfloat-abi=softfp"
build x86_64 x86_64 x86-64 x86_64-linux-android --disable-x86asm
build x86 x86 i686 i686-linux-android --disable-asm
# The release build packs this tarball into the APK (LGPL source next to the libraries).
cp "$TARBALL" "$ROOT/"
echo "FFmpeg $VERSION ($DECODERS) built into $OUT; source tarball copied to $ROOT"
