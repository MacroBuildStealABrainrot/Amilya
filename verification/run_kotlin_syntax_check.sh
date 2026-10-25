#!/bin/sh
# Parses every Kotlin file with the compiler bundled in Gradle. Android libraries are missing, so 'unresolved reference' etc. are expected and ignored.
# THIS IS NOT AN ANDROID BUILD. It only proves the files parse. Usage: sh run_kotlin_syntax_check.sh <gradle-home>
G="${1:?give the Gradle install folder}"; L="$G/lib"; HERE="$(cd "$(dirname "$0")/.." && pwd)"
KC=$(ls "$L"/kotlin-compiler-embeddable-*.jar); SL=$(ls "$L"/kotlin-stdlib-[0-9]*.jar | head -1)
CP="$KC:$SL:$(ls "$L"/kotlin-script-runtime-*.jar):$(ls "$L"/kotlin-reflect-*.jar):$(ls "$L"/kotlin-daemon-embeddable-*.jar):$(ls "$L"/trove4j-*.jar):$(ls "$L"/annotations-*.jar | head -1):$(ls "$L"/kotlinx-coroutines-core-jvm-*.jar)"
FILES=$(find "$HERE/android" -name '*.kt' -not -path '*/build/*'); N=$(echo "$FILES" | wc -l)
OUT=$(java -cp "$CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -nowarn -d "$(mktemp -d)" $FILES 2>&1 | grep -v '^Picked up')
TOTAL=$(echo "$OUT" | grep -c 'error:'); SYN=$(echo "$OUT" | grep -ci 'syntax error\|expecting \|unexpected tokens\|unclosed\|expected \(a \)\?\(declaration\|expression\|name\)')
echo "Kotlin parser check of $N Kotlin files using the compiler bundled with Gradle."
echo "The Android libraries are not available here, so 'unresolved reference' style errors are expected and ignored."
echo "Total compiler messages: $TOTAL. Messages that are SYNTAX errors: $SYN."
echo "THIS IS NOT AN ANDROID BUILD. It only proves the files parse."
