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
[ $fail -eq 0 ] && echo "ALL BUILD-FLAG CHECKS PASSED"
exit $fail
