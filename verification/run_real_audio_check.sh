#!/bin/sh
# Usage: sh run_real_audio_check.sh <gradle-home>     (needs java, ffmpeg, ffprobe, python3 with numpy)
# Feeds genuine AAC through the recorder's storage/recovery code, then checks the result with real audio tools and with
# the backend's own audio-checking functions. NOT an Android test.
set -e
G="${1:?give the Gradle install folder}"; L="$G/lib"; HERE="$(cd "$(dirname "$0")/.." && pwd)"
KC=$(ls "$L"/kotlin-compiler-embeddable-*.jar); SL=$(ls "$L"/kotlin-stdlib-[0-9]*.jar | head -1)
CP="$KC:$SL:$(ls "$L"/kotlin-script-runtime-*.jar):$(ls "$L"/kotlin-reflect-*.jar):$(ls "$L"/kotlin-daemon-embeddable-*.jar):$(ls "$L"/trove4j-*.jar):$(ls "$L"/annotations-*.jar | head -1):$(ls "$L"/kotlinx-coroutines-core-jvm-*.jar)"
W="$(mktemp -d)"; SB="$HERE/android/app/src/sandbox/java/com/woundcopilot/android"; TS="$HERE/android/app/src/testSandbox/java/com/woundcopilot/android"
java -cp "$CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -cp "$SL" -d "$W/out" "$SB/SafeRecording.kt" "$SB/SealedAudio.kt" "$SB/Mp4Aac.kt" "$TS/TestSupport.kt" "$HERE/verification/prototype/RealAudioPipeline.kt"
java -cp "$W/out:$SL" com.woundcopilot.android.RealAudioPipelineKt "$HERE/android/app/src/androidTestSandbox/assets/fixtures/tone-visit-60s.aac" "$W/data" 300
cp "$HERE/verification/make_fixtures.sh" "$W/data/"
mkdir -p "$W/work"
python3 -I "$HERE/verification/prototype/real_audio_check.py" "$W/data" "$HERE/backend/server" "$W/work"
