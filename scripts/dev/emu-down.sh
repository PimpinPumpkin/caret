#!/bin/sh
# Stops the caret_phone test emulator and waits for its process to go, so it can be started again.
ANDROID_SERIAL=emulator-5570 adb emu kill >/dev/null 2>&1
while pgrep -f -- "-avd caret_phone" >/dev/null; do sleep 1; done
echo down
