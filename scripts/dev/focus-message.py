#!/usr/bin/env python3
"""Opens a text message draft on the emulator (ANDROID_SERIAL) and taps its text field, so the keyboard shows."""
import re, subprocess, time
A = ["adb", "shell"]
def sh(c): return subprocess.run(A + [c], capture_output=True, text=True).stdout
sh("am force-stop com.google.android.apps.messaging; am force-stop com.android.messaging")
sh("am start -a android.intent.action.SENDTO -d sms:5550100"); time.sleep(3)
for i in range(4):
    sh("uiautomator dump /sdcard/u.xml"); x = sh("cat /sdcard/u.xml")
    m = [n for n in x.split('>') if 'EditText' in n]
    if m:
        b = list(map(int, re.findall(r'\d+', re.search(r'bounds="([^"]*)"', m[0])[1])))
        sh(f"input tap {(b[0]+b[2])//2} {(b[1]+b[3])//2}"); time.sleep(2)
    if 'mInputShown=true' in sh("dumpsys input_method"): print("keyboard up"); break
