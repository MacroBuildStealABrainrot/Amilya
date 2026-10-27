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
check "$(ls $APKS/pilot/*.apk | head -1)" com.woundcopilot.android.pilot false
check "$(ls $APKS/repair/*.apk | head -1)" com.woundcopilot.android.repair false
[ $fail -eq 0 ] && echo "ALL BUILD-FLAG CHECKS PASSED"
exit $fail
