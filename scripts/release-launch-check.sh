#!/usr/bin/env bash
# Installs the minified (R8) "qa" build on the connected emulator, launches it, and fails if it
# crashes or is not running after start-up. Catches missing R8 keep rules before a real release.
set -euo pipefail

apk="$(ls app/build/outputs/apk/qa/*.apk | head -n 1)"
pkg="dev.gcode.app.qa"

adb install -r "${apk}"
adb logcat -c
adb shell am start -W -n "${pkg}/dev.gcode.app.MainActivity"
sleep 15

if adb logcat -d -b crash | grep -q "${pkg}"; then
  echo "::error title=Minified build crashed::${pkg} crashed after launch"
  adb logcat -d -b crash
  exit 1
fi
if ! adb shell pidof "${pkg}" > /dev/null; then
  echo "::error title=Minified build not running::${pkg} is not running 15 s after launch"
  adb logcat -d | tail -n 200
  exit 1
fi
echo "The minified build launched and is running."
adb uninstall "${pkg}" > /dev/null || true
