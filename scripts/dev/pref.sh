#!/bin/sh
# pref.sh type name value ... : sets preferences of the debug app on the caret_phone emulator directly
# (app stopped, file edited), then selects the keyboard again. type is boolean, int, float, long or string.
export ANDROID_SERIAL=emulator-5570
F=/data/user_de/0/app.caret.debug/shared_prefs/app.caret.debug_preferences.xml
T=$(mktemp)
adb shell am force-stop app.caret.debug
adb shell "run-as app.caret.debug cat $F" > "$T"
while [ $# -ge 3 ]; do
python3 - "$T" "$1" "$2" "$3" <<'P'
import sys, re
p, t, n, v = sys.argv[1:]
s = open(p).read()
if '</map>' not in s: s = "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>" + chr(10) + '<map>' + chr(10) + '</map>' + chr(10)
s = re.sub(r'\s*<(?:boolean|int|string|float|long) name="' + re.escape(n) + r'"(?: value="[^"]*" />|>[^<]*</string>)', '', s)
line = f'    <string name="{n}">{v}</string>\n' if t == 'string' else f'    <{t} name="{n}" value="{v}" />\n'
open(p, 'w').write(s.replace('</map>', line + '</map>'))
P
shift 3; done
adb push "$T" /data/local/tmp/caret-prefs.xml >/dev/null
adb shell "run-as app.caret.debug cp /data/local/tmp/caret-prefs.xml $F"
rm -f "$T"
IME=app.caret.debug/app.caret.latin.LatinIME
sleep 2; adb shell ime enable $IME >/dev/null; adb shell ime set $IME >/dev/null; sleep 1; adb shell ime set $IME >/dev/null
