#!/usr/bin/env bash
# Disposable emulator only. Never runs against the user's physical phone or Oracle.
set -u
mkdir -p verification/emulator
bash gradlew :app:connectedDebugAndroidTest --no-daemon --max-workers=2
result=$?
adb pull /sdcard/Download/osrs-recovery-screens verification/emulator/ || true
# Gradle cleans up installed test packages. Reinstall this CI-only debug build
# on the disposable emulator, not an existing user installation.
adb install -r app/build/outputs/apk/debug/app-debug.apk || result=1
adb shell am force-stop com.example.waspmobile >/dev/null 2>&1 || true
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 0
adb shell am start -W -n com.example.waspmobile/.MainActivity || result=1
sleep 2
adb exec-out screencap -p > verification/emulator/home-portrait.png || result=1
adb shell settings put system user_rotation 1
sleep 2
adb exec-out screencap -p > verification/emulator/home-landscape.png || result=1
adb logcat -d -t 1200 > verification/emulator/logcat.txt || true
exit "$result"
