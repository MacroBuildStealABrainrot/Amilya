#!/bin/sh
# Type-check app Kotlin sources against the real Android 36 API jar (no AndroidX/Compose available).
# Usage: GRADLE_HOME=... ANDROID_JAR=.../android.jar STUBS="$(ls verification/androidx-standins/*.kt)" sh run_android_api_typecheck.sh <project>/android <out.log>
# Type-checks the real app Kotlin against the real Android 36 API plus small AndroidX stand-ins. NOT an Android build.
# Prints only errors that are NOT caused by missing androidx/compose/play-services libraries.
set -e
SRC="$1"; OUT="$2"; L="${GRADLE_HOME:?set GRADLE_HOME to a Gradle install}/lib"
KC=$(ls $L/kotlin-compiler-embeddable-*.jar); SL=$(ls $L/kotlin-stdlib-[0-9]*.jar | head -1)
CP="$KC:$SL:$(ls $L/kotlin-script-runtime-*.jar):$(ls $L/kotlin-reflect-*.jar):$(ls $L/kotlin-daemon-embeddable-*.jar):$(ls $L/trove4j-*.jar):$(ls $L/annotations-*.jar | head -1):$(ls $L/kotlinx-coroutines-core-jvm-*.jar)"
CO=$(ls $L/kotlinx-coroutines-core-jvm-*.jar)
W=$(mktemp -d)
cat > $W/BuildConfig.kt <<'K'
package com.woundcopilot.android
object BuildConfig { const val DEBUG=false; const val APPLICATION_ID="com.woundcopilot.android"; const val API_BASE_URL="https://woundcopilot-1039151887397.us-central1.run.app/api/v1"; const val SANDBOX=false; const val CRASH_SAFE_RECORDING=false; const val RETAIN_UNRESOLVED=false; const val WEAR_REMOTE_CONTROL=true; const val ALLOW_SCREEN_CAPTURE=false; const val VERSION_NAME="x"; const val VERSION_CODE=1 }
K
java -cp "$CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -nowarn -jvm-target 17 \
  -cp "$SL:$CO:${ANDROID_JAR:?set ANDROID_JAR to an android-36 android.jar}:$SRC/../wear-contract-classes" -d $W/out \
  $W/BuildConfig.kt ${STUBS:-} $(find "$SRC/app/src/main/java" $( [ -d "$SRC/app/src/sandbox/java" ] && echo "$SRC/app/src/sandbox/java" ) "$SRC/wear-contract/src/main" -name "*.kt") > "$OUT" 2>&1 || true
rm -rf $W
