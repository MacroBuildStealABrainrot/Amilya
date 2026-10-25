#!/bin/sh
# Type-checks the sandbox recorder files (and the main-app files they touch) with the Kotlin compiler against the stand-in
# Android declarations in verification/android-stubs. NOT an Android build (see android-stubs/README.md).
# Usage: sh run_standin_compile.sh /path/to/gradle-home [extra kotlin files...]
set -e
G="${1:?give the Gradle install folder}"; shift; L="$G/lib"; HERE="$(cd "$(dirname "$0")/.." && pwd)"
KC=$(ls "$L"/kotlin-compiler-embeddable-*.jar); SL=$(ls "$L"/kotlin-stdlib-[0-9]*.jar | head -1)
CP="$KC:$SL:$(ls "$L"/kotlin-script-runtime-*.jar):$(ls "$L"/kotlin-reflect-*.jar):$(ls "$L"/kotlin-daemon-embeddable-*.jar):$(ls "$L"/trove4j-*.jar):$(ls "$L"/annotations-*.jar | head -1):$(ls "$L"/kotlinx-coroutines-core-jvm-*.jar)"
CO=$(ls "$L"/kotlinx-coroutines-core-jvm-*.jar)
W="$(mktemp -d)"; ST="$HERE/verification/android-stubs"
python3 -I "$ST/make_stubs.py" >/dev/null
javac -nowarn -d "$W/stubs" $(find "$ST/java" -name '*.java') 2>&1 | grep -v '^Picked up' || true
M="$HERE/android/app/src/main/java/com/woundcopilot/android"; SB="$HERE/android/app/src/main/java/com/woundcopilot/android"
python3 -I "$ST/extract_real.py" "$M" "$W/RealExtract.kt"
java -cp "$CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -nowarn -jvm-target 17 -cp "$SL:$CO:$W/stubs" -d "$W/out" \
  "$SB"/SafeRecorderService.kt "$SB"/SealedAudio.kt "$SB"/SafeRecording.kt "$SB"/Mp4Aac.kt "$SB"/AndroidKeystoreWrapper.kt "$SB"/SealedPlaceholder.kt "$SB"/VisitContinuation.kt "$SB"/SealedRecording.kt "$SB"/RecordingSafety.kt "$M/RetentionRules.kt" "$M/UnresolvedRetention.kt" "$M/UnresolvedReminder.kt" "$M/LocalRetention.kt" "$M/AudioChunks.kt" "$W/RealExtract.kt" "$ST/MainStandIns.kt" "$@" 2>&1 | grep -v '^Picked up' || true
echo "stand-in compile finished (exit shown above is the compiler's own)"
