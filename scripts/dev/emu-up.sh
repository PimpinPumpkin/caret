#!/bin/sh
# Starts the caret_phone test emulator on port 5570 without a window and waits until it has booted.
export ANDROID_SERIAL=emulator-5570
SDK=${ANDROID_HOME:-$HOME/Library/Android/sdk}
if ! adb get-state >/dev/null 2>&1; then
    nohup "$SDK/emulator/emulator" -avd caret_phone -port 5570 -no-window -no-audio -no-boot-anim >/tmp/caret_phone.log 2>&1 &
    adb wait-for-device
fi
until [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; do sleep 2; done
adb shell settings put secure enabled_accessibility_services com.android.systemui.accessibility.accessibilitymenu/.AccessibilityMenuService >/dev/null 2>&1
echo up
