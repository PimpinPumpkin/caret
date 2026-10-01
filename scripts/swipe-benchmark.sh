#!/bin/sh
# Swipe accuracy on real swipes: replays the test split of FUTO's swipe dataset (MIT) through the
# swipe library on a device, with the word list Caret wrote from its own main dictionary.
# Needs scripts/build-swipe.sh and scripts/swipe-models.sh install to have run, and one swipe typed
# in Caret on the device so the word list exists.
#   scripts/swipe-benchmark.sh [swipes]     default 2000; device from ANDROID_SERIAL
# Fails when top-1 accuracy is more than one point under swipe/benchmark-baseline.txt.
set -e
cd "$(dirname "$0")/.."
N=${1:-2000}
PKG=${CARET_PACKAGE:-app.caret.debug}
LIB=${SWIPE_LIBRARY_DIR:-swipe/library}
ABI=$(adb shell getprop ro.product.cpu.abi | tr -d '\r')
DEV=/data/local/tmp/caret-swipe-bench
DATA=swipe/data
mkdir -p "$DATA"

if [ ! -s "$DATA/test_head.jsonl" ]; then
    # the first 40 MB are plenty; the last line is cut off and dropped below
    curl -fsSL -r 0-40000000 -o "$DATA/test_head.jsonl" \
        https://huggingface.co/datasets/futo-org/swipe.futo.org/resolve/main/test.jsonl
fi
LINES=$(($(wc -l < "$DATA/test_head.jsonl")))
[ "$N" -lt "$LINES" ] || N=$((LINES - 1))
head -n "$N" "$DATA/test_head.jsonl" > "$DATA/bench.jsonl"

WORDS=$(adb shell "run-as $PKG sh -c 'ls cache/swipe_words/en-US_*.combined'" | tr -d '\r' | head -1)
[ -n "$WORDS" ] || { echo "no word list on the device: swipe one word in Caret first" >&2; exit 1; }
adb exec-out "run-as $PKG cat $WORDS" > "$DATA/caret-en-US.combined"

adb shell "rm -rf $DEV && mkdir -p $DEV"
adb push "$LIB/build-android-$ABI/infer_jsonl" app/src/main/jniLibs/"$ABI"/libc++_shared.so \
    "$LIB/models/layouts/en_qwerty.json" "$DATA/caret-en-US.combined" "$DATA/bench.jsonl" "$DEV/" >/dev/null 2>&1
adb push swipe/models/honorable_sturgeon swipe/models/magic_macaw swipe/models/hungry_jellyfish "$DEV/" >/dev/null 2>&1
adb shell "cd $DEV && chmod +x infer_jsonl && LD_LIBRARY_PATH=. ./infer_jsonl \
    --encoder honorable_sturgeon/model_fp32.pte --decoder magic_macaw/model_fp32.pte \
    --lm-model hungry_jellyfish/context_lm.pte --lm-vocab hungry_jellyfish/vocab.txt \
    --vocab caret-en-US.combined --layout en_qwerty.json --jsonl bench.jsonl --top-k 4 2>/dev/null" \
    | tr -d '\r' > "$DATA/bench-result.txt"

awk -v base="$(cat swipe/benchmark-baseline.txt 2>/dev/null || echo 0)" '
    $1 == "good" { good++ } $1 == "ok" { ok++ } $1 == "bad" { bad++ } $1 == "oov" { oov++ }
    END {
        n = good + ok + bad
        if (n == 0) { print "no results"; exit 1 }
        top1 = 100 * good / n; top4 = 100 * (good + ok) / n
        printf "swipes %d  top-1 %.1f%%  top-4 %.1f%%  not in dictionary %d\n", n, top1, top4, oov
        printf "%.1f\n", top1 > "swipe/data/bench-top1.txt"
        if (top1 < base - 1) { printf "top-1 fell below the baseline of %.1f%%\n", base; exit 1 }
    }' "$DATA/bench-result.txt"
