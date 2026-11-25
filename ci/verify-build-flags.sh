#!/usr/bin/env bash
# Proves, from the BUILT apps, that only the sandbox app turns on the crash-resistant recorder, and that the
# release app keeps its application ID. Fails the job if any check is wrong.
set -euo pipefail
AAPT2="$(ls -d "$ANDROID_HOME"/build-tools/*/ | sort -V | tail -1)aapt2"
APKS=android/app/build/outputs/apk
fail=0
check() { # $1 apk, $2 expected package, $3 expected SafeRecorderService enabled (true/false)
  local dump; dump="$("$AAPT2" dump xmltree --file AndroidManifest.xml "$1")"
  local pkg; pkg="$("$AAPT2" dump packagename "$1")"
  local svc; svc="$(echo "$dump" | grep -A8 'SafeRecorderService' | grep -m1 'android:enabled' || true)"
  echo "== $1"; echo "package: $pkg"; echo "SafeRecorderService: ${svc:-<enabled attribute missing>}"
  [ "$pkg" = "$2" ] || { echo "FAIL: package should be $2"; fail=1; }
  if [ "$3" = "false" ]; then echo "$svc" | grep -Eq '=false|0x0$|\(0x0\)' || { echo "FAIL: recorder service must be disabled"; fail=1; }
  else echo "$svc" | grep -Eq '=true|0xffffffff' || { echo "FAIL: recorder service must be enabled"; fail=1; }; fi
}
check "$(ls $APKS/release/*.apk | head -1)" com.woundcopilot.android.release false
check "$(ls $APKS/debug/*.apk | head -1)" com.woundcopilot.android false
check "$(ls $APKS/sandbox/*.apk | head -1)" com.woundcopilot.android.sandbox true
# Pilot keeps the original recorder until clinical and device compatibility approval.
check "$(ls $APKS/pilot/*.apk | head -1)" com.woundcopilot.android.pilot2 false
check "$(ls $APKS/repair/*.apk | head -1)" com.woundcopilot.android.repair2 false
check "$(ls $APKS/renderTest/*.apk | head -1)" com.woundcopilot.android.rendertest false
check "$(ls $APKS/isolatedTest/*.apk | head -1)" com.woundcopilot.android.isolated2 false
check "$(ls $APKS/live/*.apk | head -1)" com.woundcopilot.android.live false
check "$(ls $APKS/liveShots/*.apk | head -1)" com.woundcopilot.android.liveshots false
check "$(ls $APKS/notes/*.apk | head -1)" com.woundcopilot.android.notes false
check "$(ls $APKS/lite/*.apk | head -1)" com.woundcopilot.android.lite false
# The live app must be compiled for the real service only, never as an isolated/test build, with the clean owner screens.
LBC="$(find android/app/build/generated -path '*/live/*' -name BuildConfig.java | head -1)"
[ -n "$LBC" ] || { echo "FAIL: live BuildConfig not found"; fail=1; }
if [ -n "$LBC" ]; then
  grep -q 'API_BASE_URL = "https://woundcopilot-1039151887397.us-central1.run.app/api/v1"' "$LBC" || { echo "FAIL: live app has the wrong server address"; fail=1; }
  grep -q 'ISOLATED_TEST = false' "$LBC" && grep -q 'OWNER_CLEAN_UI = true' "$LBC" && grep -q 'SANDBOX = false' "$LBC" && grep -q 'DEBUG = false' "$LBC" || { echo "FAIL: unexpected live app flags"; fail=1; }
fi
# The isolated test app must be COMPILED to use only the isolated test server (read from the generated settings file).
BC="$(find android/app/build/generated -path '*isolatedTest*' -name BuildConfig.java | head -1)"
[ -n "$BC" ] || { echo "FAIL: isolatedTest BuildConfig not found"; fail=1; }
if [ -n "$BC" ]; then
  grep -q 'API_BASE_URL = "https://amilya-repair-test.onrender.com/api/v1"' "$BC" || { echo "FAIL: isolated app is not built for the isolated test server address"; fail=1; }
  grep -q 'ISOLATED_TEST = true' "$BC" || { echo "FAIL: ISOLATED_TEST must be true"; fail=1; }
  grep -q 'SANDBOX = false' "$BC" && grep -q 'REPAIR_SERVER = false' "$BC" || { echo "FAIL: unexpected server flags"; fail=1; }
  echo "isolated app compiled address: $(grep -o 'API_BASE_URL = "[^"]*"' "$BC")"
fi
APKSIGNER="${AAPT2%aapt2}apksigner"
"$APKSIGNER" verify --verbose --print-certs "$(ls $APKS/renderTest/*.apk | head -1)"

# Check screenshot policy and destination separately for all live variants.
for variant in live liveShots notes lite; do
  config=$(find android/app/build/generated -path "*/$variant/*" -name BuildConfig.java | head -1)
  [ -n "$config" ] || exit 1
  capture=false; [ "$variant" != liveShots ] || capture=true
  grep -q "ALLOW_SCREEN_CAPTURE = $capture" "$config" || exit 1
  grep -q 'API_BASE_URL = "https://woundcopilot-1039151887397.us-central1.run.app/api/v1"' "$config" || exit 1
  grep -q 'ISOLATED_TEST = false' "$config" || exit 1
  grep -q 'OWNER_CLEAN_UI = true' "$config" || exit 1
  grep -q 'SANDBOX = false' "$config" || exit 1
  grep -q 'DEBUG = false' "$config" || exit 1
  grep -q 'WEAR_REMOTE_CONTROL = false' "$config" || exit 1
done

# Only the reporting companion may remove the other workflows. Verify every built app.
for variant in debug release pilot repair renderTest isolatedTest sandbox live liveShots notes lite; do
  config=$(find android/app/build/generated -path "*/$variant/*" -name BuildConfig.java | head -1)
  [ -n "$config" ] || { echo "FAIL: $variant BuildConfig not found"; exit 1; }
  notes=false; [ "$variant" != notes ] || notes=true
  lite=false; [ "$variant" != lite ] || lite=true
  grep -q "LITE_VOICE = $lite" "$config" || { echo "FAIL: unexpected light-app flag in $variant"; exit 1; }
  grep -q "NOTES_ONLY = $notes" "$config" || { echo "FAIL: unexpected reporting-only flag in $variant"; exit 1; }
done

[ $fail -eq 0 ] && echo "ALL BUILD-FLAG CHECKS PASSED"
exit $fail
