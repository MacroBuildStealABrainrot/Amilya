package com.woundcopilot.android
// Stand-ins for the main-app symbols the sandbox files call. Signatures copied from DeviceTools.kt / Api.kt / RecordingRecovery.kt /
// DeveloperDiagnostics.kt. The real bodies are NOT compiled here.
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.io.File

// RecordingState is cut out of the REAL DeviceTools.kt by run_standin_compile.sh.

// CrashSafeBackend and SealedRecordingHandle are cut out of the REAL DeviceTools.kt by run_standin_compile.sh.

class RecordingService {
    companion object {
        const val ACTION_START = "start"; const val ACTION_PAUSE = "pause"; const val ACTION_RESUME = "resume"; const val ACTION_STOP = "stop"
        const val EXTRA_OWNER = "owner"
        val state = MutableStateFlow(RecordingState())
        internal fun recordingsDirectory(context: Context): File = File("")
        internal fun writeMetadata(file: File, status: String, elapsed: Long, error: String?) {}
        internal fun readableAudio(file: File): Boolean = true
    }
}
class Vault(context: Context) {
    fun remove(name: String) {}
    fun names(): List<String> = emptyList()
    fun modified(name: String): Long = 0L
    fun expireRecovery(cutoff: Long, keep: (String) -> Boolean = { false }) {}
    fun read(name: String): String? = null
    fun write(name: String, value: String) {}
}
fun JSONObject.str(key: String, fallback: String = ""): String = fallback
class RecordingRecovery(private val context: Context) {
    companion object { internal val storageLock = Any() }
    fun remember(file: File, user: String, case: JSONObject, template: String, mime: String = "audio/mp4"): JSONObject = JSONObject()
    fun stage(file: File, phase: String, case: JSONObject? = null, report: String? = null): JSONObject? = null
}
object DeveloperDiagnostics { fun fileRemoved(vault: Vault, owner: String?, file: File, deleted: Boolean) {}; fun bind(vault: Vault, owner: String, encounter: String, file: File? = null): String = "" }

// ---- used only when AudioChunks.kt (real file) is compiled as well ----
internal fun crashSafeBackend(): CrashSafeBackend? = null
fun obj(vararg values: Pair<String, Any?>): JSONObject = JSONObject()
object Performance { fun now(): Long = 0L; fun record(name: String, start: Long) {} }
class Api {
    suspend fun multipart(path: String, file: File, mime: String, fields: Map<String, String>, key: String): JSONObject = JSONObject()
    suspend fun multipartBytes(path: String, name: String, data: ByteArray, mime: String, fields: Map<String, String>, key: String): JSONObject = JSONObject()
    suspend fun call(path: String, method: String = "GET", body: JSONObject? = null, key: String? = null): Any = JSONObject()
}

fun org.json.JSONArray.objects(): List<JSONObject> = emptyList()
