#!/usr/bin/env bash
set -euo pipefail

API="${1:?Android SDK version required}"
case "$API" in 35|37.0) ;; *) exit 2 ;; esac
export ANDROID_AVD_HOME="$HOME/.android/avd"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
mkdir -p "$ANDROID_AVD_HOME" .validation
(yes | sdkmanager --licenses >/dev/null) || true
sdkmanager --install "platforms;android-$API" "build-tools;37.0.0" platform-tools emulator "system-images;android-$API;google_apis;x86_64" >/dev/null
echo no | avdmanager create avd --name casting-phone --package "system-images;android-$API;google_apis;x86_64" --device pixel_6
echo 'vm.heapSize=512' >> "$ANDROID_AVD_HOME/casting-phone.avd/config.ini"
adb start-server
emulator -avd casting-phone -port 5554 -no-window -gpu swangle -feature -HardwareDecoder \
  -memory 4096 -cores 4 -no-audio -no-snapshot -no-boot-anim -camera-back none -no-metrics \
  > .validation/emulator.log 2>&1 &
emulator_pid=$!
trap 'kill "$emulator_pid" 2>/dev/null || true' EXIT

# Android 17 can report boot completion before its input/package services are ready.
ready=false
for attempt in $(seq 1 180); do
  if ! kill -0 "$emulator_pid" 2>/dev/null; then cat .validation/emulator.log; exit 1; fi
  if [ "$(timeout 10s adb -s emulator-5554 shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] &&
     timeout 10s adb -s emulator-5554 shell input keyevent 82 >/dev/null 2>&1 &&
     timeout 10s adb -s emulator-5554 shell pm path android >/dev/null 2>&1; then
    ready=true; break
  fi
  sleep 5
done
if [ "$ready" != true ]; then cat .validation/emulator.log; exit 1; fi
for setting in window_animation_scale transition_animation_scale animator_duration_scale; do
  adb -s emulator-5554 shell settings put global "$setting" 0
done
echo "Android phone API $API ready; running decoded-frame and notification regressions."
./gradlew connectedMobileDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.aliflix.app.player.NativeBackgroundPlaybackTest,com.aliflix.app.player.WebStreamHandoffTest \
  --no-daemon --console=plain
