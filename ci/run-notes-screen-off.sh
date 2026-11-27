#!/usr/bin/env bash
# The emulator receives only a generated tone. The visit, recording and installer never leave the runner.
set -euo pipefail
cd "$(dirname "$0")/.."
app=com.woundcopilot.android.notes
test_class=com.woundcopilot.android.NotesScreenOffRecordingTest

adb shell cmd connectivity airplane-mode enable
adb shell svc wifi disable
adb shell svc data disable
adb shell settings put global stay_on_while_plugged_in 0
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard
test "$(adb shell settings get global airplane_mode_on | tr -d '\r')" = '1'
echo "Android API $(adb shell getprop ro.build.version.sdk | tr -d '\r')"
shopt -s nullglob
apps=(android/app/build/outputs/apk/notes/*.apk)
tests=(android/app/build/outputs/apk/androidTest/notes/*.apk)
test "${#apps[@]}" -eq 1
test "${#tests[@]}" -eq 1
adb install -r -g "${apps[0]}"
adb install -r -g "${tests[0]}"

# All sound input comes from a virtual sink on the runner. No host soundcard is needed.
test "$(pactl get-default-source)" = 'notes_fixture.monitor'
adb emu avd hostmicon | grep -q '^OK'
python3 - "$RUNNER_TEMP/notes-tone.wav" <<'PY'
import array
import math
import sys
import wave

frequency = 440
sample_rate = 48000
second = array.array("h", (
    round(18000 * math.sin(2 * math.pi * frequency * i / sample_rate))
    for i in range(sample_rate)
))
with wave.open(sys.argv[1], "wb") as tone:
    tone.setparams((1, 2, sample_rate, 0, "NONE", "not compressed"))
    for _ in range(120):
        tone.writeframesraw(second.tobytes())
PY
paplay --device=notes_fixture "$RUNNER_TEMP/notes-tone.wav" &
playback=$!
trap 'kill "$playback" 2>/dev/null || true; adb logcat -d -s "NotesLockTest:I" "*:S"' EXIT

log="$RUNNER_TEMP/notes-screen-off-result.txt"
timeout 110s adb shell am instrument -w -e class "$test_class" \
  "$app.test/androidx.test.runner.AndroidJUnitRunner" | tee "$log"
grep -Eq '^OK \(1 test\)' "$log"
