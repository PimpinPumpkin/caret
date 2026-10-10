#!/bin/sh
# Swipe accuracy on real swipes: replays the test split of FUTO's swipe dataset (MIT) through the
# swipe library on a device, with the word list Caret wrote from its own main dictionary.
# Needs scripts/build-swipe.sh and scripts/swipe-models.sh install to have run, and one swipe typed
# in Caret on the device so the word list exists.
#   scripts/swipe-benchmark.sh [swipes]     default 2000; device from ANDROID_SERIAL
#   SWIPES_FILE=x.jsonl replays those swipes instead, LAYOUT_FILE=y.json uses those key positions
#   CONTEXT_WORDS=3 limits the words given as context in front of each swipe
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

LAYOUT=${LAYOUT_FILE:-$LIB/models/layouts/en_qwerty.json}
if [ -n "$SWIPES_FILE" ]; then
    # swipes from somewhere else, such as a recording read by scripts/swipe-replay.py
    cp "$SWIPES_FILE" "$DATA/bench.jsonl"
else
if [ ! -s "$DATA/test_head.jsonl" ]; then
    # the first 40 MB are plenty; the last line is cut off and dropped below
    curl -fsSL -r 0-40000000 -o "$DATA/test_head.jsonl" \
        https://huggingface.co/datasets/futo-org/swipe.futo.org/resolve/main/test.jsonl
fi
LINES=$(($(wc -l < "$DATA/test_head.jsonl")))
[ "$N" -lt "$LINES" ] || N=$((LINES - 1))
head -n "$N" "$DATA/test_head.jsonl" > "$DATA/bench.jsonl"
if [ -n "$CONTEXT_WORDS" ]; then
    # keep only that many words in front of each swiped word, to see what the context is worth
    python3 - "$DATA/bench.jsonl" "$CONTEXT_WORDS" <<'P'
import json, sys
path, keep = sys.argv[1], int(sys.argv[2])
out = []
for line in open(path):
    j = json.loads(line)
    if "sentence" in j and "word_idx" in j:
        words = j["sentence"].split()
        before = words[max(0, j["word_idx"] - keep):j["word_idx"]] if keep > 0 else []
        j["sentence"] = " ".join(before + words[j["word_idx"]:])
        j["word_idx"] = len(before)
    out.append(json.dumps(j))
open(path, "w").write("\n".join(out) + "\n")
P
fi
fi
WORDS=$(adb shell "run-as $PKG sh -c 'ls cache/swipe_words/en-US_*.combined'" | tr -d '\r' | head -1)
[ -n "$WORDS" ] || { echo "no word list on the device: swipe one word in Caret first" >&2; exit 1; }
adb exec-out "run-as $PKG cat $WORDS" > "$DATA/caret-en-US.combined"

adb shell "rm -rf $DEV && mkdir -p $DEV"
adb push "$LIB/build-android-$ABI/infer_jsonl" app/src/main/jniLibs/"$ABI"/libc++_shared.so \
    "$DATA/caret-en-US.combined" "$DATA/bench.jsonl" "$DEV/" >/dev/null 2>&1
adb push "$LAYOUT" "$DEV/layout.json" >/dev/null 2>&1
# which models: ENCODER=<folder under swipe/models>, DECODER and LM the same or "none" (both are optional
# to the library); the default is FUTO's three
ENCODER=${ENCODER:-honorable_sturgeon}
DECODER=${DECODER:-magic_macaw}
LM=${LM:-hungry_jellyfish}
MODELS="--encoder $ENCODER/model_fp32.pte"
adb push "swipe/models/$ENCODER" "$DEV/" >/dev/null 2>&1
if [ "$DECODER" != none ]; then
    adb push "swipe/models/$DECODER" "$DEV/" >/dev/null 2>&1
    MODELS="$MODELS --decoder $DECODER/model_fp32.pte"
fi
if [ "$LM" != none ]; then
    adb push "swipe/models/$LM" "$DEV/" >/dev/null 2>&1
    MODELS="$MODELS --lm-model $LM/context_lm.pte --lm-vocab $LM/vocab.txt"
fi
adb shell "cd $DEV && chmod +x infer_jsonl && LD_LIBRARY_PATH=. ./infer_jsonl $MODELS \
    --vocab caret-en-US.combined --layout layout.json --jsonl bench.jsonl --top-k 4 2>$DEV/errors.txt" \
    | tr -d '\r' > "$DATA/bench-result.txt"
adb shell "head -5 $DEV/errors.txt" 2>/dev/null | grep -iE "error|fail|missing|not" | head -3 || true

awk -v base="$(cat swipe/benchmark-baseline.txt 2>/dev/null || echo 0)" '
    $1 == "good" { good++ } $1 == "ok" { ok++ } $1 == "bad" { bad++ } $1 == "oov" { oov++ }
    END {
        n = good + ok + bad
        if (n == 0) { print "no results"; exit 1 }
        top1 = 100 * good / n; top4 = 100 * (good + ok) / n
        printf "swipes %d  top-1 %.1f%%  top-4 %.1f%%  not in dictionary %d\n", n, top1, top4, oov
        if (ENVIRON["SWIPES_FILE"] != "" || ENVIRON["ENCODER"] != "" || ENVIRON["DECODER"] != "" || ENVIRON["LM"] != "") exit 0
        printf "%.1f\n", top1 > "swipe/data/bench-top1.txt"
        if (top1 < base - 1) { printf "top-1 fell below the baseline of %.1f%%\n", base; exit 1 }
    }' "$DATA/bench-result.txt"
