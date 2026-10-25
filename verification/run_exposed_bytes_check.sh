#!/bin/sh
# Regression check for the compile error an independent review found in SafeRecorderService.kt:
#   "size hides member of supertype ByteArrayOutputStream and needs an override modifier"  (a class extending
#   ByteArrayOutputStream re-declared  fun size(): Int = count ).
# 1) The Kotlin compiler must still REJECT that pattern (so the check can fail) and must accept the corrected form.
# 2) No sandbox, main or test source may extend ByteArrayOutputStream and re-declare one of its methods.
# (The sandbox recorder no longer uses such a class at all; FrameBuffer replaced it. The full stand-in compile of every sandbox file is
#  run by run_standin_compile.sh.)
# Usage: sh run_exposed_bytes_check.sh /path/to/gradle-home
set -e
G="${1:?give the Gradle install folder}"; L="$G/lib"; HERE="$(cd "$(dirname "$0")/.." && pwd)"
KC=$(ls "$L"/kotlin-compiler-embeddable-*.jar); SL=$(ls "$L"/kotlin-stdlib-[0-9]*.jar | head -1)
CP="$KC:$SL:$(ls "$L"/kotlin-script-runtime-*.jar):$(ls "$L"/kotlin-reflect-*.jar):$(ls "$L"/kotlin-daemon-embeddable-*.jar):$(ls "$L"/trove4j-*.jar):$(ls "$L"/annotations-*.jar | head -1):$(ls "$L"/kotlinx-coroutines-core-jvm-*.jar)"
W="$(mktemp -d)"
cat > "$W/Bad.kt" <<'KT'
package check
class ExposedBytes : java.io.ByteArrayOutputStream(1 shl 20) {
    fun raw(): ByteArray = buf
    fun size(): Int = count
    fun wipe() { java.util.Arrays.fill(buf, 0.toByte()); reset() }
}
KT
cat > "$W/Good.kt" <<'KT'
package check
class ExposedBytes : java.io.ByteArrayOutputStream(1 shl 20) {
    fun raw(): ByteArray = buf
    fun wipe() { java.util.Arrays.fill(buf, 0.toByte()); reset() }
}
fun use(b: ExposedBytes): Int { b.write(1); return b.size() }
KT
run() { java -cp "$CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -cp "$SL" -d "$W/out$2" "$1" 2>&1 | grep -v '^Picked up'; }
echo "--- corrected form (uses the inherited size()) must compile:"
if run "$W/Good.kt" good | grep -q "error"; then echo "FAIL: corrected form rejected"; exit 1; else echo "PASS"; fi
echo "--- the old form must be rejected by the compiler:"
run "$W/Bad.kt" bad > "$W/bad.log" || true
if grep -q "hides member of supertype" "$W/bad.log"; then grep "error" "$W/bad.log" | head -2; echo "PASS: rejected with the reviewer's error"; else echo "FAIL: the old form was accepted, so this check proves nothing"; cat "$W/bad.log"; exit 1; fi
echo "--- no source may extend ByteArrayOutputStream and re-declare one of its methods:"
python3 -I - "$HERE/android" <<'PY'
import re, sys, pathlib
bad = []
for f in pathlib.Path(sys.argv[1]).rglob("*.kt"):
    text = f.read_text(encoding="utf-8", errors="replace")
    for m in re.finditer(r"class\s+\w+[^{]*:\s*(?:java\.io\.)?ByteArrayOutputStream\b[^{]*\{", text):
        depth, i = 1, m.end()
        while depth and i < len(text):
            depth += (text[i] == "{") - (text[i] == "}"); i += 1
        body = text[m.end():i]
        for method in ("size", "reset", "toByteArray", "write", "toString", "writeTo", "close", "flush"):
            if re.search(r"fun\s+" + method + r"\s*\(", body) and not re.search(r"override\s+fun\s+" + method + r"\s*\(", body):
                bad.append(f"{f}: re-declares {method}() without override")
print("\n".join(bad) if bad else "PASS: none found")
sys.exit(1 if bad else 0)
PY
