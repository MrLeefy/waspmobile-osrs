#!/usr/bin/env bash
# Disposable emulator only. No physical phone, account, live Oracle connection, or game actions.
set -u
mkdir -p verification/emulator
bash gradlew :app:connectedDebugAndroidTest --no-daemon --max-workers=2
result=$?
adb pull /sdcard/Android/data/com.example.waspmobile/files/recovery-screens verification/emulator/ 2>/dev/null || true
adb shell am force-stop com.example.waspmobile >/dev/null 2>&1 || true
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 0
adb shell am start -n com.example.waspmobile/.MainActivity >/dev/null 2>&1 || true
sleep 2
adb exec-out screencap -p > verification/emulator/home-portrait.png || true
adb shell settings put system user_rotation 1
sleep 2
adb exec-out screencap -p > verification/emulator/home-landscape.png || true
adb logcat -d -t 1200 > verification/emulator/logcat.txt || true
exit "$result"
