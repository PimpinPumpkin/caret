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
if ! grep -q nativeScoreWords "$LIB/jni/swipe_jni.cpp"; then
    git -C "$LIB" apply "$ROOT/swipe/caret.patch"
fi

# The library is built with only the operators its models use, read from models/*/ops.txt. Caret's own
# swipe encoder (training/swipe_model.py) needs a few more: its list goes in next to FUTO's.
mkdir -p "$LIB/models/caret_swipe_encoder"
if ! cmp -s "$ROOT/swipe/caret-encoder-ops.txt" "$LIB/models/caret_swipe_encoder/ops.built"; then
    # the list changed: ExecuTorch is only built anew when its library is missing
    rm -f "$LIB"/third_party/executorch/cmake-out-*/libexecutorch.a
fi
cp "$ROOT/swipe/caret-encoder-ops.txt" "$LIB/models/caret_swipe_encoder/ops.txt"

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
cp "$ROOT/swipe/caret-encoder-ops.txt" "$LIB/models/caret_swipe_encoder/ops.built"

# Caret's own encoder ships in the app when it is there (training/swipe_export.py writes it)
if [ -f "$ROOT/swipe/models/caret_swipe_encoder/model_fp32.pte" ]; then
    mkdir -p "$ROOT/app/src/main/assets/swipe_models/caret_swipe_encoder"
    cp "$ROOT/swipe/models/caret_swipe_encoder/model_fp32.pte" "$ROOT/swipe/models/caret_swipe_encoder/metadata.json" \
        "$ROOT/app/src/main/assets/swipe_models/caret_swipe_encoder/"
    echo "swipe encoder for the app: $(wc -c < "$ROOT/swipe/models/caret_swipe_encoder/model_fp32.pte") bytes"
fi
