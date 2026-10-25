#!/bin/sh
# Real SIGKILL experiment (JVM only, NOT Android). Usage: sh run_kill_experiment.sh <gradle-home> [trials]
set -e
G="${1:?give the Gradle install folder}"; L="$G/lib"; HERE="$(cd "$(dirname "$0")/.." && pwd)"
KC=$(ls "$L"/kotlin-compiler-embeddable-*.jar); SL=$(ls "$L"/kotlin-stdlib-[0-9]*.jar | head -1)
CP="$KC:$SL:$(ls "$L"/kotlin-script-runtime-*.jar):$(ls "$L"/kotlin-reflect-*.jar):$(ls "$L"/kotlin-daemon-embeddable-*.jar):$(ls "$L"/trove4j-*.jar):$(ls "$L"/annotations-*.jar | head -1):$(ls "$L"/kotlinx-coroutines-core-jvm-*.jar)"
W="$(mktemp -d)"; SB="$HERE/android/app/src/sandbox/java/com/woundcopilot/android"
java -cp "$CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -cp "$SL" -d "$W/out" "$SB/SafeRecording.kt" "$SB/SealedAudio.kt" "$SB/Mp4Aac.kt" "$SB/VisitContinuation.kt" "$HERE/verification/prototype/KillExperiment.kt" 2>&1 | grep -v "^warning" || true
echo "Real SIGKILL experiment (JVM only, NOT Android): $(date -u +%FT%TZ)"
java -cp "$W/out:$SL" com.woundcopilot.android.KillExperimentKt "${2:-300}"
