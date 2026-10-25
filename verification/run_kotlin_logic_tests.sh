#!/bin/sh
# Re-runs the pure-logic Kotlin tests WITHOUT Android, using the Kotlin compiler that ships inside a Gradle install.
# Usage: sh run_kotlin_logic_tests.sh /path/to/gradle-home   (the folder that contains lib/kotlin-compiler-embeddable-*.jar)
# Not a replacement for the real Android build. It only proves the pure logic (CSRF rules, retry rules, sandbox address guard, watch protocol).
set -e
G="${1:?give the Gradle install folder}"; L="$G/lib"; HERE="$(cd "$(dirname "$0")/.." && pwd)"
KC=$(ls "$L"/kotlin-compiler-embeddable-*.jar); SL=$(ls "$L"/kotlin-stdlib-[0-9]*.jar | head -1)
CP="$KC:$SL:$(ls "$L"/kotlin-script-runtime-*.jar):$(ls "$L"/kotlin-reflect-*.jar):$(ls "$L"/kotlin-daemon-embeddable-*.jar):$(ls "$L"/trove4j-*.jar):$(ls "$L"/annotations-*.jar | head -1):$(ls "$L"/kotlinx-coroutines-core-jvm-*.jar)"
JU="$SL:$(ls "$L"/junit-4*.jar):$(ls "$L"/hamcrest-core-*.jar)"
W="$(mktemp -d)"; python3 -I "$HERE/verification/extract_pure_kotlin.py" "$HERE" "$W/Extracted.kt"
java -cp "$CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -cp "$JU" -d "$W/out" "$W/Extracted.kt" "$HERE/android/app/src/main/java/com/woundcopilot/android/LockPolicy.kt" "$HERE/android/app/src/main/java/com/woundcopilot/android/RecordingSafety.kt" "$HERE"/android/app/src/test/java/com/woundcopilot/android/*.kt
java -cp "$W/out:$JU" org.junit.runner.JUnitCore com.woundcopilot.android.CsrfBootstrapTest com.woundcopilot.android.CsrfRecoveryTest com.woundcopilot.android.ApiOriginGuardTest com.woundcopilot.android.LockPolicyTest com.woundcopilot.android.RecordingSafetyTest
java -cp "$CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -cp "$JU" -d "$W/outw" $(find "$HERE/android/wear-contract/src" -name '*.kt')
java -cp "$W/outw:$JU" org.junit.runner.JUnitCore $(grep -h '^class ' -r "$HERE/android/wear-contract/src/test" | sed 's/class \([A-Za-z]*\).*/com.woundcopilot.wear.contract.\1/')
# Crash-resistant recording prototype: pure file-writing/recovery/encryption logic only (no microphone, no encoder, no Android).
SB="$HERE/android/app/src/main/java/com/woundcopilot/android"; TS="$HERE/android/app/src/testSandbox/java/com/woundcopilot/android"
java -cp "$CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -cp "$JU" -d "$W/outp" "$SB/SafeRecording.kt" "$SB/SealedAudio.kt" "$SB/Mp4Aac.kt" "$SB/SealedRecording.kt" "$SB/SealedPlaceholder.kt" "$SB/VisitContinuation.kt" "$HERE/android/app/src/main/java/com/woundcopilot/android/RetentionRules.kt" "$HERE/android/app/src/main/java/com/woundcopilot/android/UnresolvedRetention.kt" $(ls "$TS"/*.kt | grep -v SafetyFlagsTest)  # flag tests need Gradle BuildConfig; CI runs them
java ${FULL_DIR:+-Dfull.dir="$FULL_DIR"} -Dsandbox.src="$SB/SafeRecorderService.kt" -Dfixture.dir="$HERE/android/app/src/androidTestSandbox/assets/fixtures" -cp "$W/outp:$JU" org.junit.runner.JUnitCore com.woundcopilot.android.SafeRecordingTest com.woundcopilot.android.SealedAudioTest com.woundcopilot.android.RealFixtureTest com.woundcopilot.android.RetentionRulesTest com.woundcopilot.android.BackendInstanceTest com.woundcopilot.android.UnresolvedRetentionTest com.woundcopilot.android.InMemoryRecordingTest com.woundcopilot.android.KeyHandlingTest com.woundcopilot.android.VisitContinuationTest com.woundcopilot.android.VisitPartsOrderTest com.woundcopilot.android.StorageFullTest
