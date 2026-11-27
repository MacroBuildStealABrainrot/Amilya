#!/usr/bin/env bash
# This test uses a disposable offline emulator. No visit, recording, or installer leaves the runner.
set -euo pipefail
cd "$(dirname "$0")/.."
variant=${RECORDER_VARIANT:-notes}
case "$variant" in
    notes|lite) ;;
    *) echo "Unknown recorder variant: $variant" >&2; exit 1 ;;
esac
app="com.woundcopilot.android.$variant"
test_class=com.woundcopilot.android.ScreenOffRecordingTest

adb shell cmd connectivity airplane-mode enable
adb shell svc wifi disable
adb shell svc data disable
adb shell settings put global stay_on_while_plugged_in 0
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard
test "$(adb shell settings get global airplane_mode_on | tr -d '\r')" = '1'
echo "Android API $(adb shell getprop ro.build.version.sdk | tr -d '\r')"
shopt -s nullglob
apps=(android/app/build/outputs/apk/"$variant"/*.apk)
tests=(android/app/build/outputs/apk/androidTest/"$variant"/*.apk)
test "${#apps[@]}" -eq 1
test "${#tests[@]}" -eq 1
adb install -r -g "${apps[0]}"
adb install -r -g "${tests[0]}"

log="$RUNNER_TEMP/$variant-screen-off-result.txt"
run_method() {
    local method="$1"
    adb logcat -c
    timeout 110s adb shell am instrument -w -e class "$test_class#$method" \
        "$app.test/androidx.test.runner.AndroidJUnitRunner" | tee "$log"
    adb logcat -d -s "RecorderLockTest:I" "*:S" > "$log.markers"
    cat "$log.markers"
}

run_method serviceAndEncodedTimelineContinueWhileScreenOff
grep -Eq '^OK \(1 test\)' "$log"
grep -q 'SERVICE_TIMELINE_VERIFIED' "$log.markers"

# Only a qualified 440 Hz microphone source can prove sound survived display-off.
# The hosted guest has no sound source. A physical lab device can run the same
# test with a synthetic tone played throughout recording and locking.
run_method keepsRecordingSoundWhileTheDisplayIsOff
if grep -q 'TONE_FIXTURE_UNAVAILABLE' "$log.markers"; then
    grep -Eq '^OK \([01] tests?\)' "$log"
    if grep -q 'TONE_BASELINE_VERIFIED' "$log.markers"; then exit 1; fi
    echo 'Audible recording: unverified because the 440 Hz foreground sound check did not pass.'
else
    grep -Eq '^OK \(1 test\)' "$log"
    grep -q 'TONE_BASELINE_VERIFIED' "$log.markers"
    grep -q 'TONE_SCREEN_OFF_VERIFIED' "$log.markers"
fi

if [[ "$variant" == lite ]]; then
    test_class=com.woundcopilot.android.VoiceTranscriptScreenTest
    run_method tappingStopOpensTheEditableTranscript
    grep -Eq '^OK \(1 test\)' "$log"
    run_method transcriptStaysAvailableWhenReportOrCaptureRoutesAreRequested
    grep -Eq '^OK \(1 test\)' "$log"
    test_class=com.woundcopilot.android.VoiceSavedRecordingRecoveryTest
    run_method savedRecordingAutomaticallyRecoversWhenTheLegacyServerFinishes
    grep -Eq '^OK \(1 test\)' "$log"
    run_method unconfirmedRecordingStaysPlayableAfterTheLastAutomaticCheck
    grep -Eq '^OK \(1 test\)' "$log"
fi
