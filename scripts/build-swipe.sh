#!/bin/sh
# Builds the FUTO Swipe library (GPL-3.0-or-later) and copies it into the app.
# Needs cmake 3.29+, python3 and an Android NDK r27 or newer.
#   scripts/build-swipe.sh [abi ...]      default: arm64-v8a
# SWIPE_LIBRARY_DIR points at an existing checkout; otherwise one is cloned into swipe/library.
set -e
cd "$(dirname "$0")/.."
ROOT=$(pwd)
COMMIT=1b13f2c
ABIS=${*:-arm64-v8a}
SDK=${ANDROID_HOME:-$HOME/Library/Android/sdk}
NDK=${ANDROID_NDK_HOME:-$(ls -d "$SDK"/ndk/* | sort -V | tail -1)}
LIB=${SWIPE_LIBRARY_DIR:-$ROOT/swipe/library}

if [ ! -d "$LIB" ]; then
    git clone https://gitlab.futo.org/keyboard/swipe-library.git "$LIB"
    git -C "$LIB" checkout -q "$COMMIT"
    git -C "$LIB" submodule update --init --recursive --depth 1
fi
if ! grep -q nativeLoadTrie "$LIB/jni/swipe_jni.cpp"; then
    git -C "$LIB" apply "$ROOT/swipe/caret.patch"
fi

case "$(uname -s)" in Darwin) HOST=darwin-x86_64 ;; *) HOST=linux-x86_64 ;; esac
for abi in $ABIS; do
    ANDROID_NDK_HOME="$NDK" make -C "$LIB" "android-$abi"
    case "$abi" in
        arm64-v8a) triple=aarch64-linux-android ;;
        armeabi-v7a) triple=arm-linux-androideabi ;;
        x86_64) triple=x86_64-linux-android ;;
        x86) triple=i686-linux-android ;;
    esac
    out="$ROOT/app/src/main/jniLibs/$abi"
    mkdir -p "$out"
    cp "$LIB/build-android-$abi/libswipe_jni.so" "$out/"
    cp "$NDK/toolchains/llvm/prebuilt/$HOST/sysroot/usr/lib/$triple/libc++_shared.so" "$out/"
    echo "copied swipe library for $abi"
done
