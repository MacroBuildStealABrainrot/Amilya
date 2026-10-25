#!/usr/bin/env bash
# Isolated synthetic-data emulator tests. Any required group that fails or does not run makes CI red.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/ci-out"; mkdir -p "$OUT"
TEST_RESULTS="$ROOT/android/app/build/outputs/androidTest-results/connected"
server_pid=""
failed=0

stop_server() {
  if [[ -n "$server_pid" ]]; then
    kill "$server_pid" 2>/dev/null || true
    wait "$server_pid" 2>/dev/null || true
    server_pid=""
  fi
}
trap stop_server EXIT

start_server() { # $1 = Python server script, $2 = label
  local script="$1" label="$2"
  stop_server
  python "$script" >"$OUT/$label-server.log" 2>&1 &
  server_pid=$!
  for _ in $(seq 1 60); do
    if ! kill -0 "$server_pid" 2>/dev/null; then
      echo "$label: server exited before readiness" | tee -a "$OUT/summary.txt"
      return 1
    fi
    if (echo > /dev/tcp/127.0.0.1/18766) 2>/dev/null; then
      echo "$label: server ready" | tee -a "$OUT/summary.txt"
      return 0
    fi
    sleep 1
  done
  echo "$label: server never became ready" | tee -a "$OUT/summary.txt"
  return 1
}

# Require actual Android instrumentation test cases from all requested classes, not just Gradle exit 0.
check_test_cases() { # $1 comma-separated requested fully qualified classes, empty = at least one test
  python3 - "$TEST_RESULTS" "$1" <<'PY_VERIFY'
import pathlib, sys, xml.etree.ElementTree as ET
root=pathlib.Path(sys.argv[1])
expected=[x.strip() for x in sys.argv[2].split(',') if x.strip()]
classes=set(); total=0
for path in root.rglob('*.xml') if root.exists() else []:
    try:
        parsed=ET.parse(path).getroot()
        for case in parsed.iter('testcase'):
            total+=1
            classes.add(case.attrib.get('classname',''))
    except ET.ParseError:
        continue
missing=[x for x in expected if not any(c == x or c.endswith('.'+x.rsplit('.',1)[-1]) for c in classes)]
print(f'Instrumentation test cases executed: {total}; missing classes: {missing}')
sys.exit(0 if total>0 and not missing else 1)
PY_VERIFY
}

run_group() { # $1 label, $2 comma-separated instrumentation classes (empty = all)
  local label="$1" classes="$2" rc=0
  local args=()
  [[ -n "$classes" ]] && args+=("-Pandroid.testInstrumentationRunnerArguments.class=$classes")
  # Eliminate any previous group's results; stale XML must not count as a fresh run.
  rm -rf "$TEST_RESULTS"
  (cd "$ROOT/android" && ./gradlew --no-daemon :app:connectedSandboxAndroidTest "${args[@]}") >"$OUT/$label.log" 2>&1 || rc=$?
  if [[ "$rc" -eq 0 ]]; then check_test_cases "$classes" >>"$OUT/$label.log" 2>&1 || rc=1; fi
  cp -r "$ROOT/android/app/build/reports/androidTests/connected" "$OUT/$label-report" 2>/dev/null || true
  cp -r "$TEST_RESULTS" "$OUT/$label-results" 2>/dev/null || true
  echo "$label: exit $rc" | tee -a "$OUT/summary.txt"
  return "$rc"
}

adb wait-for-device || exit 1
if ! adb reverse tcp:18766 tcp:18766; then
  echo 'adb reverse failed; refusing to run incomplete emulator tests' | tee -a "$OUT/summary.txt"
  exit 1
fi
adb logcat -c || true
P="com.woundcopilot.android"

run_group recorder "$P.CrashSafeRecordingTest,$P.ExistingWorkCompatibilityTest,$P.RecordingRecoveryActionsTest" || failed=1

if start_server "$ROOT/android/tools/local_contract_server.py" level1; then
  run_group level1 "$P.BackendContractTest,$P.CsrfAfterOfflineStartTest,$P.RecordingServiceStartTest,$P.SimpleScreensTest" || failed=1
  stop_server
else
  echo 'level1: SKIPPED — required local server failed' | tee -a "$OUT/summary.txt"
  failed=1; stop_server
fi

if start_server "$ROOT/test-tools/controlled_pipeline_api.py" level2; then
  run_group level2 "$P.ReliabilityPassTest,$P.RecordingWorkflowTest,$P.PhotoRecoveryTest" || failed=1
  stop_server
else
  echo 'level2: SKIPPED — required local server failed' | tee -a "$OUT/summary.txt"
  failed=1; stop_server
fi

if start_server "$ROOT/android/tools/local_contract_server.py" level1-all; then
  run_group all-other-tests '' || echo 'all-other-tests: informational failure' | tee -a "$OUT/summary.txt"
  stop_server
else
  echo 'all-other-tests: informational server unavailable' | tee -a "$OUT/summary.txt"
  stop_server
fi

adb logcat -d >"$OUT/logcat.txt" 2>/dev/null || true
if [[ "$failed" -ne 0 ]]; then echo 'REQUIRED PHONE TEST GROUP FAILED' | tee -a "$OUT/summary.txt"; fi
exit "$failed"
