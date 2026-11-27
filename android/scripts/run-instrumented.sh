#!/usr/bin/env bash
# Runs the instrumented tests on the connected emulator, then pulls the screenshots they saved.
set -u
cd "$(dirname "$0")/.."
# Keep the app installed afterwards so the screenshots it saved can be pulled.
./gradlew --stacktrace :app:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true
status=$?
mkdir -p ../screens
for f in $(adb shell run-as app.unpaused.debug ls files/screens 2>/dev/null | tr -d '\r'); do
  adb exec-out run-as app.unpaused.debug cat "files/screens/$f" > "../screens/$f"
done
adb logcat -d -s Unpaused UnpausedLibrary AndroidRuntime TestRunner ExoPlayerImpl AudioTrack MediaSessionService > ../screens/logcat.txt 2>/dev/null || true
echo "---- app and test log (last 300 lines) ----"
tail -n 300 ../screens/logcat.txt || true
# Screenshots are also printed as base64 so they can be read from the job log without artifact access.
for f in ../screens/*.jpg; do
  [ -f "$f" ] || continue
  echo "SCREENSHOT_BEGIN $(basename "$f")"
  base64 -w0 "$f"
  echo
  echo "SCREENSHOT_END"
done
exit $status
