#!/bin/sh
# Usage: sh run_handoff_check.sh <gradle-home> [fixture.aac] [frames-per-part]   (needs java, ffmpeg, python3 with numpy). PC only, not Android.
set -e
G="${1:?give the Gradle install folder}"; L="$G/lib"; HERE="$(cd "$(dirname "$0")/.." && pwd)"
KC=$(ls "$L"/kotlin-compiler-embeddable-*.jar); SL=$(ls "$L"/kotlin-stdlib-[0-9]*.jar | head -1)
CP="$KC:$SL:$(ls "$L"/kotlin-script-runtime-*.jar):$(ls "$L"/kotlin-reflect-*.jar):$(ls "$L"/kotlin-daemon-embeddable-*.jar):$(ls "$L"/trove4j-*.jar):$(ls "$L"/annotations-*.jar | head -1):$(ls "$L"/kotlinx-coroutines-core-jvm-*.jar)"
W="$(mktemp -d)"; SB="$HERE/android/app/src/sandbox/java/com/woundcopilot/android"; TS="$HERE/android/app/src/testSandbox/java/com/woundcopilot/android"
FIX="${2:-$HERE/android/app/src/androidTestSandbox/assets/fixtures/speech-flite-synthetic.aac}"
java -cp "$CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -cp "$SL" -d "$W/out" "$SB/SafeRecording.kt" "$SB/SealedAudio.kt" "$SB/Mp4Aac.kt" "$SB/SealedRecording.kt" "$SB/VisitContinuation.kt" "$TS/TestSupport.kt" "$HERE/verification/prototype/HandoffPipeline.kt" 2>&1 | grep -v "^warning\|^Picked" || true
java -cp "$W/out:$SL" com.woundcopilot.android.HandoffPipelineKt "$FIX" "$W/data" "${3:-1500}" 2>&1 | grep -v "^Picked"
python3 -I "$HERE/verification/prototype/handoff_check.py" "$W/data" "$HERE/backend/server"
