#!/bin/sh
# Builds the library that runs Caret's typing language model (lm/native, on llama.cpp, MIT) and copies
# it into the app. Needs cmake 3.22+ and an Android NDK.
#   scripts/build-lm.sh [abi ...]      default: arm64-v8a
#   scripts/build-lm.sh host           the lm_test tool for this computer, in lm/build/host
set -e
cd "$(dirname "$0")/.."
ROOT=$(pwd)
TAG=v0.5.0
ABIS=${*:-arm64-v8a}
SDK=${ANDROID_HOME:-$HOME/Library/Android/sdk}
NDK=${ANDROID_NDK_HOME:-$(ls -d "$SDK"/ndk/* | sort -V | tail -1)}
SRC=${LLAMA_SRC:-$ROOT/lm/llama.cpp}
[ -d "$SRC" ] || git clone -q --depth 1 --branch "$TAG" https://github.com/ggml-org/llama.cpp.git "$SRC"

for abi in $ABIS; do
    if [ "$abi" = host ]; then
        cmake -S lm/native -B lm/build/host -DLLAMA_SRC="$SRC" >/dev/null
        cmake --build lm/build/host -j 8 --target lm_test
        continue
    fi
    cmake -S lm/native -B "lm/build/$abi" -DLLAMA_SRC="$SRC" \
        -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI="$abi" -DANDROID_PLATFORM=android-23 -DANDROID_STL=c++_static >/dev/null
    cmake --build "lm/build/$abi" -j 8 --target caret_lm
    mkdir -p "app/src/main/jniLibs/$abi"
    "$NDK"/toolchains/llvm/prebuilt/*/bin/llvm-strip -o "app/src/main/jniLibs/$abi/libcaret_lm.so" "lm/build/$abi/libcaret_lm.so"
    echo "copied typing model library for $abi: $(wc -c < "app/src/main/jniLibs/$abi/libcaret_lm.so") bytes"
done
