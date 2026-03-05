#!/usr/bin/env bash
# ============================================================================
# build_dependencies.sh
# Compiles all native dependencies for CineCamera:
#   - OpenSSL 3.1 (required by libsrt for AES-256 encryption)
#   - libsrt 1.5.3 (Haivision Secure Reliable Transport)
#   - FFmpeg 6.1 (libavformat/avcodec/avutil for crash recovery remux)
#
# Prerequisites:
#   - Android NDK r26b+ at $ANDROID_NDK_HOME
#   - CMake 3.22+, Ninja, nasm (for FFmpeg x86 assembly)
#   - git, wget, tar
#
# Usage: ./scripts/build_dependencies.sh [arm64-v8a|x86_64|all]
# ============================================================================

set -euo pipefail

NDK="${ANDROID_NDK_HOME:-${NDK_HOME:-}}"
[[ -z "$NDK" ]] && { echo "ERROR: ANDROID_NDK_HOME not set"; exit 1; }

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BUILD_DIR="$ROOT/.build_deps"
OUTPUT_BASE="$ROOT/app/src/main/cpp/libs"
INCLUDE_DIR="$ROOT/app/src/main/cpp/include"

API=26
REQUESTED_ABI="${1:-all}"
ALL_ABIS=("arm64-v8a" "x86_64")
ABIS=()

case "$REQUESTED_ABI" in
    all)       ABIS=("${ALL_ABIS[@]}") ;;
    arm64-v8a) ABIS=("arm64-v8a") ;;
    x86_64)    ABIS=("x86_64") ;;
    *)         echo "Unknown ABI: $REQUESTED_ABI. Use arm64-v8a, x86_64, or all"; exit 1 ;;
esac

OPENSSL_VERSION="3.1.5"
SRT_VERSION="v1.5.3"
FFMPEG_VERSION="6.1.1"

OPENSSL_URL="https://www.openssl.org/source/openssl-${OPENSSL_VERSION}.tar.gz"
SRT_URL="https://github.com/Haivision/srt.git"
FFMPEG_URL="https://ffmpeg.org/releases/ffmpeg-${FFMPEG_VERSION}.tar.bz2"

TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64"

mkdir -p "$BUILD_DIR" "$INCLUDE_DIR/srt" "$INCLUDE_DIR/ffmpeg"

# ─── Helper ───────────────────────────────────────────────────────────────────

get_triple() {
    case "$1" in
        arm64-v8a) echo "aarch64-linux-android" ;;
        x86_64)    echo "x86_64-linux-android" ;;
    esac
}

get_ffmpeg_arch() {
    case "$1" in
        arm64-v8a) echo "aarch64" ;;
        x86_64)    echo "x86_64" ;;
    esac
}

# ─── Phase 1: OpenSSL ─────────────────────────────────────────────────────────

build_openssl() {
    local ABI="$1" TRIPLE
    TRIPLE=$(get_triple "$ABI")
    local CC="$TOOLCHAIN/bin/${TRIPLE}${API}-clang"
    local SRC="$BUILD_DIR/openssl-$OPENSSL_VERSION"
    local PREFIX="$BUILD_DIR/openssl_install_$ABI"

    echo "Building OpenSSL $OPENSSL_VERSION for $ABI..."

    if [[ ! -d "$SRC" ]]; then
        wget -q "$OPENSSL_URL" -O "$BUILD_DIR/openssl.tar.gz"
        tar -xf "$BUILD_DIR/openssl.tar.gz" -C "$BUILD_DIR"
    fi

    mkdir -p "$PREFIX"
    (
        cd "$SRC"
        OSSL_ABI="$([ "$ABI" = "arm64-v8a" ] && echo "android-arm64" || echo "android-x86_64")"
        PATH="$TOOLCHAIN/bin:$PATH" \
        ANDROID_NDK_ROOT="$NDK" \
        ./Configure "$OSSL_ABI" \
            -D__ANDROID_API__=$API \
            no-shared no-tests no-ui-console \
            --prefix="$PREFIX"
        make -j"$(nproc)" build_libs
        make install_dev
    )

    local OUT="$OUTPUT_BASE/$ABI"
    mkdir -p "$OUT"
    cp "$PREFIX/lib/libssl.a" "$PREFIX/lib/libcrypto.a" "$OUT/"
    cp -r "$PREFIX/include/openssl" "$INCLUDE_DIR/"
    echo "  ✓ OpenSSL → $OUT"
}

# ─── Phase 2: libsrt ─────────────────────────────────────────────────────────

build_srt() {
    local ABI="$1"
    local SRC="$BUILD_DIR/srt"
    local BDIR="$BUILD_DIR/srt_build_$ABI"
    local OPENSSL_PREFIX="$BUILD_DIR/openssl_install_$ABI"

    echo "Building libsrt $SRT_VERSION for $ABI..."

    [[ ! -d "$SRC" ]] && git clone --depth 1 --branch "$SRT_VERSION" "$SRT_URL" "$SRC"
    mkdir -p "$BDIR"

    cmake -S "$SRC" -B "$BDIR" \
        -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI="$ABI" \
        -DANDROID_PLATFORM="android-$API" \
        -DCMAKE_BUILD_TYPE=Release \
        -DENABLE_SHARED=OFF \
        -DENABLE_STATIC=ON \
        -DENABLE_APPS=OFF \
        -DENABLE_ENCRYPTION=ON \
        -DUSE_OPENSSL=ON \
        -DOPENSSL_ROOT_DIR="$OPENSSL_PREFIX" \
        -DOPENSSL_INCLUDE_DIR="$OPENSSL_PREFIX/include" \
        -DOPENSSL_CRYPTO_LIBRARY="$OPENSSL_PREFIX/lib/libcrypto.a" \
        -DOPENSSL_SSL_LIBRARY="$OPENSSL_PREFIX/lib/libssl.a" \
        -DENABLE_CXX11=ON \
        -Wno-dev

    cmake --build "$BDIR" -j"$(nproc)"

    cp "$BDIR/libsrt.a" "$OUTPUT_BASE/$ABI/"
    cp "$SRC/srtcore/"*.h "$INCLUDE_DIR/srt/" 2>/dev/null || true
    echo "  ✓ libsrt → $OUTPUT_BASE/$ABI/libsrt.a"
}

# ─── Phase 3: FFmpeg ─────────────────────────────────────────────────────────

build_ffmpeg() {
    local ABI="$1" TRIPLE ARCH
    TRIPLE=$(get_triple "$ABI")
    ARCH=$(get_ffmpeg_arch "$ABI")
    local CC="$TOOLCHAIN/bin/${TRIPLE}${API}-clang"
    local CXX="$TOOLCHAIN/bin/${TRIPLE}${API}-clang++"
    local SRC="$BUILD_DIR/ffmpeg-$FFMPEG_VERSION"
    local PREFIX="$BUILD_DIR/ffmpeg_install_$ABI"

    echo "Building FFmpeg $FFMPEG_VERSION for $ABI (remux libs only)..."

    if [[ ! -d "$SRC" ]]; then
        wget -q "$FFMPEG_URL" -O "$BUILD_DIR/ffmpeg.tar.bz2"
        tar -xf "$BUILD_DIR/ffmpeg.tar.bz2" -C "$BUILD_DIR"
    fi

    mkdir -p "$PREFIX"
    (
        cd "$SRC"
        PATH="$TOOLCHAIN/bin:$PATH"
        ./configure \
            --prefix="$PREFIX" \
            --target-os=android \
            --arch="$ARCH" \
            --cc="$CC" \
            --cxx="$CXX" \
            --cross-prefix="${TRIPLE}-" \
            --sysroot="$TOOLCHAIN/sysroot" \
            --extra-cflags="-O3 -ffast-math -D__ANDROID_API__=$API" \
            --enable-static \
            --disable-shared \
            --disable-programs \
            --disable-doc \
            --disable-debug \
            --disable-avdevice \
            --disable-swscale \
            --disable-postproc \
            --disable-network \
            --disable-encoders \
            --disable-muxers \
            --disable-filters \
            --disable-bsfs \
            --disable-protocols \
            --enable-protocol=file \
            --enable-demuxer=mov,mp4,h264,aac \
            --enable-muxer=mp4,mov \
            --enable-decoder=h264,hevc,aac \
            --enable-parser=h264,hevc,aac
        make -j"$(nproc)"
        make install
    )

    local OUT="$OUTPUT_BASE/$ABI"
    cp "$PREFIX/lib/libavformat.a" "$PREFIX/lib/libavcodec.a" \
       "$PREFIX/lib/libavutil.a" "$PREFIX/lib/libswresample.a" "$OUT/"
    cp -r "$PREFIX/include/libavformat" "$PREFIX/include/libavcodec" \
          "$PREFIX/include/libavutil" "$PREFIX/include/libswresample" "$INCLUDE_DIR/ffmpeg/"
    echo "  ✓ FFmpeg → $OUT"
}

# ─── Main ─────────────────────────────────────────────────────────────────────

echo "═══════════════════════════════════════════════════════════"
echo "  CineCamera Native Dependencies Build"
echo "  NDK: $NDK"
echo "  ABIs: ${ABIS[*]}"
echo "═══════════════════════════════════════════════════════════"

for ABI in "${ABIS[@]}"; do
    echo ""
    echo "────────────────────────────────────────────────────────"
    echo "  Processing: $ABI"
    echo "────────────────────────────────────────────────────────"
    mkdir -p "$OUTPUT_BASE/$ABI"
    build_openssl "$ABI"
    build_srt "$ABI"
    build_ffmpeg "$ABI"
done

echo ""
echo "═══════════════════════════════════════════════════════════"
echo "  ✓ All native dependencies built successfully"
echo "═══════════════════════════════════════════════════════════"
