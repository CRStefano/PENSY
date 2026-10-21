#!/bin/sh
# Build the offline text reader for Android: Leptonica + Tesseract + the
# PENSY bridge (tools/ocr/pensyocr.cpp), as one libpensyocr.so per CPU type,
# copied into android/app/src/main/jniLibs/<abi>/ where both builds find it.
#
# Only needed when the OCR engine itself changes: the .so files are kept in
# the project, so neither Android Studio nor tools/build-apk.sh need the NDK.
#
# Needs clang + lld (any recent version), cmake, and from an Android NDK:
#   NDK_SYSROOT   .../toolchains/llvm/prebuilt/<host>/sysroot
#   NDK_RT        .../toolchains/llvm/prebuilt/<host>/lib/clang/<v>/lib/linux
# Sources: LEPTONICA_SRC (1.85), TESSERACT_SRC (5.5).
# ABIS defaults to "arm64-v8a" (every phone of the last years).
set -e
cd "$(dirname "$0")/.."
ROOT=$(pwd)
: "${NDK_SYSROOT:?}" "${NDK_RT:?}" "${LEPTONICA_SRC:?}" "${TESSERACT_SRC:?}"
ABIS=${ABIS:-arm64-v8a}
API=26
CLANG_INC=$(clang -print-resource-dir)/include
JOBS=$(nproc 2>/dev/null || echo 2)

for ABI in $ABIS; do
    case $ABI in
        arm64-v8a)   CPU=aarch64; TRIPLE=aarch64-linux-android$API;     RT=aarch64 ;;
        armeabi-v7a) CPU=armv7-a; TRIPLE=armv7a-linux-androideabi$API; RT=arm ;;
        x86_64)      CPU=x86_64;  TRIPLE=x86_64-linux-android$API;      RT=x86_64 ;;
        *) echo "unknown ABI $ABI"; exit 1 ;;
    esac
    B=$ROOT/build/ocr/$ABI
    RD=$B/resource-dir   # clang's own headers + the NDK's runtime libraries
    mkdir -p "$RD/lib/linux"
    ln -sfn "$CLANG_INC" "$RD/include"
    cp "$NDK_RT/libclang_rt.builtins-$RT-android.a" "$RD/lib/linux/"
    rm -rf "$RD/lib/linux/$RT" && cp -r "$NDK_RT/$RT" "$RD/lib/linux/$RT"
    # Bionic has pthread inside libc; an empty libpthread.a satisfies builds that ask for -lpthread.
    mkdir -p "$B/stubs" && rm -f "$B/stubs/libpthread.a" && ar rcs "$B/stubs/libpthread.a"
    export NDK_SYSROOT NDK_RESOURCE_DIR="$RD" ANDROID_TRIPLE="$TRIPLE" ANDROID_CPU="$CPU" NDK_STUBS="$B/stubs"
    TC="-DCMAKE_TOOLCHAIN_FILE=$ROOT/tools/ocr/android.toolchain.cmake -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=OFF -DCMAKE_INSTALL_PREFIX=$B/prefix"

    # Leptonica and Tesseract are rebuilt only if missing (delete build/ocr to force).
    if [ ! -f "$B/prefix/lib/cmake/tesseract/TesseractConfig.cmake" ]; then
    # Leptonica without any image format: PENSY hands over raw pixels.
    cmake -S "$LEPTONICA_SRC" -B "$B/lept" $TC -DBUILD_PROG=OFF -DSW_BUILD=OFF \
        -DENABLE_ZLIB=OFF -DENABLE_PNG=OFF -DENABLE_GIF=OFF -DENABLE_JPEG=OFF \
        -DENABLE_TIFF=OFF -DENABLE_WEBP=OFF -DENABLE_OPENJPEG=OFF >/dev/null
    cmake --build "$B/lept" -j"$JOBS" >/dev/null
    cmake --install "$B/lept" >/dev/null

    # Tesseract, LSTM engine only, no training tools, no network, no archives.
    cmake -S "$TESSERACT_SRC" -B "$B/tess" $TC -DLeptonica_DIR="$B/prefix/lib/cmake/leptonica" \
        -DBUILD_TRAINING_TOOLS=OFF -DBUILD_TESTS=OFF -DGRAPHICS_DISABLED=ON -DDISABLED_LEGACY_ENGINE=ON \
        -DOPENMP_BUILD=OFF -DDISABLE_TIFF=ON -DDISABLE_ARCHIVE=ON -DDISABLE_CURL=ON -DSW_BUILD=OFF \
        -DINSTALL_CONFIGS=OFF -DENABLE_NATIVE=OFF -DLEPT_TIFF_RESULT=1 >/dev/null
    cmake --build "$B/tess" -j"$JOBS" >/dev/null
    cmake --install "$B/tess" >/dev/null
    fi

    LIBS="$(ls "$B"/prefix/lib/libtesseract*.a | head -1) $(ls "$B"/prefix/lib/libleptonica*.a | head -1)"
    CXX="clang++ --target=$TRIPLE --sysroot=$NDK_SYSROOT -resource-dir=$RD"
    LINK="-fuse-ld=lld -rtlib=compiler-rt -unwindlib=libunwind -static-libstdc++"
    $CXX -std=c++17 -O2 -fPIC -fvisibility=hidden -I"$B/prefix/include" -c tools/ocr/pensyocr.cpp -o "$B/pensyocr.o"
    $CXX -shared -o "$B/libpensyocr.so" "$B/pensyocr.o" $LIBS $LINK \
        -Wl,--gc-sections -Wl,--exclude-libs,ALL -Wl,-z,max-page-size=16384 -s -lm
    # Static command line test (runs under qemu on a PC).
    $CXX -std=c++17 -O2 -static -o "$B/pensyocr-test" tools/ocr/test_main.cpp "$B/pensyocr.o" $LIBS $LINK -lm

    mkdir -p "android/app/src/main/jniLibs/$ABI"
    cp "$B/libpensyocr.so" "android/app/src/main/jniLibs/$ABI/"
    echo "OK: $ABI $(wc -c < "$B/libpensyocr.so") bytes"
done
