#!/usr/bin/env bash
# Headless test emulator on nixos-dell. Run inside `nix develop .#emulator`.
#   scripts/emulator.sh            start (creates the AVD on first use)
#   adb install -r app/build/outputs/apk/debug/app-debug.apk
set -euo pipefail
avd=deck-todo-36
image=$(ls -d "$ANDROID_HOME"/system-images/android-36/google_apis/x86_64 | head -1)
if ! avdmanager list avd -c | grep -qx "$avd"; then
  echo no | avdmanager create avd -n "$avd" -k "system-images;android-36;google_apis;x86_64" -d pixel_7 >/dev/null
fi
[ -d "$image" ] || { echo "no system image in $ANDROID_HOME" >&2; exit 1; }
exec emulator -avd "$avd" -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect -no-snapshot-save
