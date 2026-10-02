#!/bin/sh
# End-to-end check on the caret_phone emulator (1080x2340, default keyboard size, English QWERTY):
# installs the debug build, types by tapping and by swiping, and compares what lands in a text field.
# Needs the swipe models (scripts/swipe-models.sh install) for the swipe part.
#   ANDROID_SERIAL=emulator-5570 scripts/smoke.sh
# For the release build: CARET_PKG=app.caret CARET_APK=app/build/outputs/apk/release/Caret_<version>-release.apk
set -e
cd "$(dirname "$0")/.."
PKG=${CARET_PKG:-app.caret.debug}
# the system shortens the class name when it starts with the package name
case "$PKG" in app.caret) IME=app.caret/.latin.LatinIME;; *) IME=$PKG/app.caret.latin.LatinIME;; esac
APK=${CARET_APK:-$(ls app/build/outputs/apk/debug/Caret_*-debug.apk 2>/dev/null | head -1)}
[ -f "$APK" ] || ./gradlew --console=plain assembleDebug >/dev/null
adb wait-for-device
adb install -r "$APK" >/dev/null
# right after boot the keyboard service can take a moment to be listed
for i in 1 2 3 4 5 6 7 8 9 10; do
    adb shell ime list -a -s | grep -q "$IME" && break
    sleep 2
done
adb shell ime enable $IME >/dev/null
adb shell ime set $IME >/dev/null
adb logcat -c

show_keyboard() {
    adb shell am start -a android.settings.SETTINGS >/dev/null 2>&1; sleep 2
    adb shell input tap 540 670; sleep 2
    for i in 1 2 3 4 5 6; do
        adb shell input tap 300 220; sleep 2
        adb shell dumpsys input_method | grep -q "mInputShown=true" && return 0
    done
    echo "FAIL: keyboard did not show" >&2; exit 1
}
clear_field() { adb shell input keycombination 113 29; adb shell input keyevent 67; sleep 1; }
field_text() {
    adb shell uiautomator dump /sdcard/caret-smoke.xml >/dev/null
    # the focused node is the text field, whatever its class
    adb shell cat /sdcard/caret-smoke.xml | tr '>' '\n' | grep 'focused="true"' | sed -n 's/.* text="\([^"]*\)".*/\1/p' | head -1
}
fail=0
check() { # name expected actual
    if [ "$2" = "$3" ]; then echo "ok   $1: $3"; else echo "FAIL $1: expected '$2', got '$3'"; fail=1; fi
}

# the emulator sometimes comes back from an older snapshot without the models
if [ -d swipe/models ] && ! adb shell "run-as $PKG ls files/swipe_models/honorable_sturgeon/model_fp32.pte" >/dev/null 2>&1; then
    scripts/swipe-models.sh install >/dev/null
    adb shell ime set $IME >/dev/null
fi

show_keyboard
sleep 6 # swipe models load in the background when the keyboard first shows

clear_field
python3 scripts/keys.py tap caret
check "tap typing" "caret" "$(field_text | tr 'A-Z' 'a-z')"

if adb shell "run-as $PKG ls files/swipe_models/honorable_sturgeon/model_fp32.pte" >/dev/null 2>&1; then
    clear_field
    # Straight-line swipes with uneven timing, yet the engine reads them. When the letters were
    # once handed to it in the wrong order, the first four words still passed and the last four
    # did not, so the harder words are here on purpose.
    WORDS="hello this is working would love some coffee"
    python3 scripts/keys.py swipe $WORDS
    got=$(field_text | tr 'A-Z' 'a-z')
    right=0
    set -- $got
    for want in $WORDS; do
        [ "$1" = "$want" ] && right=$((right + 1))
        [ $# -gt 0 ] && shift
    done
    if [ "$right" -ge 7 ]; then echo "ok   swipe typing ($right of 8 words): $got"; else echo "FAIL swipe typing ($right of 8 words): $got"; fail=1; fi
else
    echo "skip swipe typing: models are not installed"
fi

if adb logcat -d | grep -q "Fatal signal\|FATAL EXCEPTION.*\|Process: $PKG"; then
    echo "FAIL crash in the log:"; adb logcat -d | grep -m3 "Fatal signal\|FATAL EXCEPTION"; fail=1
else
    echo "ok   no crash in the log"
fi
exit $fail
