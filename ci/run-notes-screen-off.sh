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

# Android Emulator ships the RPC schema for its virtual microphone. Generate the client
# from the running emulator's version, and refuse a controller exposed beyond loopback.
bindings="$RUNNER_TEMP/notes-grpc-bindings"
python="$RUNNER_TEMP/notes-grpc/bin/python"
mkdir -p "$bindings"
proto="$ANDROID_HOME/emulator/lib/emulator_controller.proto"
"$python" -m grpc_tools.protoc -I "$(dirname "$proto")" --python_out="$bindings" --grpc_python_out="$bindings" "$proto"
socket=$(ss -H -ltn 'sport = :8554' | awk '{print $4}')
case "$socket" in 127.0.0.1:8554|'[::1]:8554') ;; *) echo 'Expected an emulator loopback listener'; exit 1 ;; esac

log="$RUNNER_TEMP/notes-screen-off-result.txt"
adb logcat -c
timeout 110s adb shell am instrument -w -e class "$test_class" \
  "$app.test/androidx.test.runner.AndroidJUnitRunner" > "$log" 2>&1 &
test_pid=$!
injector=''
cleanup() {
    if [[ -n "$injector" ]]; then kill "$injector" 2>/dev/null || true; fi
    kill "$test_pid" 2>/dev/null || true
    cat "$log"
    adb logcat -d -s "NotesLockTest:I" "*:S"
}
trap cleanup EXIT
for attempt in {1..100}; do
    if adb logcat -d -s "NotesLockTest:I" "*:S" | grep -q 'Recorder ready for synthetic tone'; then break; fi
    kill -0 "$test_pid"
    sleep 0.2
done
adb logcat -d -s "NotesLockTest:I" "*:S" | grep -q 'Recorder ready for synthetic tone'
PYTHONPATH="$bindings" "$python" -u ci/inject-notes-test-tone.py &
injector=$!
wait "$test_pid"
grep -Eq '^OK \(1 test\)' "$log"
