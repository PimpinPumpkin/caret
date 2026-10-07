#!/bin/sh
# typing-benchmark-3.sh TAG [OUT_DIR] : the typing benchmark on three seeds plus the clean rare words, for
# the debug app as it is on the caret_phone emulator. Results go to OUT_DIR/b3-TAG-*.txt.
cd "$(dirname "$0")/../.."
export ANDROID_SERIAL=emulator-5570
tag=$1; out=${2:-/tmp}
IME=app.caret.debug/app.caret.latin.LatinIME
adb shell am force-stop app.caret.debug; sleep 2
# start from nothing learned: the sentences repeat from run to run, and what the keyboard learned from
# the last run would flatter this one
adb shell "run-as app.caret.debug sh -c 'rm -rf files/UserHistoryDictionary.* files/personal_corrections.json files/typing_corrections.jsonl'"
# the system falls back to another keyboard when ours is stopped; pick it again, twice to be sure
adb shell ime enable $IME >/dev/null; adb shell ime set $IME >/dev/null; sleep 1; adb shell ime set $IME >/dev/null
python3 scripts/dev/focus-message.py >/dev/null; sleep 8; adb logcat -c
for seed in 7 11 23; do
    python3 scripts/typing-benchmark.py app.caret.debug --seed $seed > "$out/b3-$tag-$seed.txt" 2>&1
    echo "$tag seed $seed: $(head -1 "$out/b3-$tag-$seed.txt")"
done
python3 scripts/typing-benchmark.py app.caret.debug --rare --sigma 0 > "$out/b3-$tag-rare.txt" 2>&1
echo "$tag rare: $(head -1 "$out/b3-$tag-rare.txt")"
adb logcat -d | grep -E "TypingModel: (typing model|scored|read)" | awk '/us$/ {print $(NF-1)}' | sort -n | awk '{a[NR]=$1} END {if (NR) print "score calls", NR, "median us", a[int(NR/2)], "p95 us", a[int(NR*0.95)]}'
adb logcat -d | grep -m1 "TypingModel: typing model"
