"""Cuts declarations out of the REAL main-app files so the stand-in compile checks them against the sandbox code:
   CrashSafeBackend + SealedRecordingHandle (DeviceTools.kt) and Api.multipartBytes (Api.kt)."""
import re, sys
main, out = sys.argv[1], sys.argv[2]
def read(name): return open(f"{main}/{name}", encoding="utf-8").read().replace("\r\n", "\n")
def block(text, start_pattern):
    m = re.search(start_pattern, text); assert m, start_pattern
    i = text.index("{", m.start()); depth = 0; k = i
    while True:
        depth += (text[k] == "{") - (text[k] == "}"); k += 1
        if depth == 0: break
    return text[m.start():k]
def paren_block(text, start_pattern):
    m = re.search(start_pattern, text); assert m, start_pattern
    i = text.index("(", m.start()); depth = 0; k = i
    while True:
        depth += (text[k] == "(") - (text[k] == ")"); k += 1
        if depth == 0: break
    return text[m.start():k]
tools = read("DeviceTools.kt"); api = read("Api.kt")
state = paren_block(tools, r"data class RecordingState\b")
parts = [state, block(tools, r"internal interface CrashSafeBackend\b"), block(tools, r"internal interface SealedRecordingHandle\b")]
multipart = block(api, r"suspend fun multipartBytes\b")
src = ("package com.woundcopilot.android\nimport android.content.Context\nimport java.io.File\nimport org.json.JSONObject\n"
       "import kotlinx.coroutines.Dispatchers\nimport kotlinx.coroutines.withContext\n\n" + "\n\n".join(parts) +
       "\n\nclass ApiExtract {\n    private fun execute(path:String,method:String,data:ByteArray?,type:String,key:String?, renewed:Boolean=false):Any = Any()\n    "
       + multipart + "\n}\n")
open(out, "w", encoding="utf-8").write(src)
