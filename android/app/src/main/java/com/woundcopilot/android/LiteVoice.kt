package com.woundcopilot.android

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.woundcopilot.android.ui.WcpButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * "Amilya Voice": the full-strength voice-to-text app. It does one job: record the whole visit (nurse and client), turn it
 * into text, and let you share that text. No patient charts, photos, reports or consultant.
 *
 * Recording uses the main app's own recorder service: it keeps recording with the screen locked or while another app is
 * open, can be paused, warns if the phone silences the microphone, and keeps the recording if the app is closed.
 * Transcription uses the server's existing full-visit steps (medical speech model, speaker detection, 2-minute pieces
 * that can be retried without paying twice). No new server code and no report step.
 */
private const val LITE_MAX_SECONDS = 58 * 60

/**
 * Turns the transcript's pieces into "Speaker 1: ... Speaker 2: ..." paragraphs. Speaker numbers come from the speech
 * service and are only reliable inside each 2-minute piece, so the app says so next to the switch. Returns [plain]
 * unchanged when the pieces carry no speaker information.
 */
internal fun liteSpeakerText(segments: JSONArray?, plain: String): String {
    if (segments == null || segments.length() == 0) return plain
    val out = StringBuilder()
    var last = Int.MIN_VALUE
    var any = false
    for (i in 0 until segments.length()) {
        val item = segments.optJSONObject(i) ?: continue
        val words = item.optString("text").trim()
        if (words.isEmpty()) continue
        val speaker = if (item.has("speaker") && !item.isNull("speaker")) item.optInt("speaker", -1) else -1
        if (speaker < 0) { if (out.isNotEmpty()) out.append(' '); out.append(words); continue }
        any = true
        if (speaker != last) { if (out.isNotEmpty()) out.append("\n\n"); out.append("Speaker ").append(speaker + 1).append(": "); last = speaker }
        else out.append(' ')
        out.append(words)
    }
    return if (any) out.toString().trim() else plain
}

private suspend fun liteVisit(s: AppState, owner: String): String {
    val cache = "lite-visit-$owner"
    s.vault.read(cache)?.takeIf { it.isNotBlank() }?.let { return it }
    val request = UUID.randomUUID().toString()
    val response = s.api.call("/quick/start", "POST", obj("mode" to "document", "request_id" to request), request) as JSONObject
    val id = response.getJSONObject("encounter").str("id")
    s.vault.write(cache, id)
    return id
}

/** Sends the whole recording (you and the client) in short pieces and returns the finished transcript. No report is made. */
private suspend fun liteTranscribe(s: AppState, owner: String, file: File, stage: (String) -> Unit): JSONObject {
    if (s.demo) return obj("text" to "Sample only. No transcription was performed.")
    val key = "lite-$owner-${file.name}"
    suspend fun once(): JSONObject {
        val visit = liteVisit(s, owner)
        val result = AudioChunks(s.api, s.vault).transcribe(visit, file, key, null) { stage(it) }
        return result.optJSONObject("evidence") ?: JSONObject()
    }
    return try { once() } catch (e: CancellationException) { throw e } catch (e: Exception) {
        // The temporary holder visit can expire on the server. Make a fresh one once and start this recording again.
        if (e.message.orEmpty().contains("not found", ignoreCase = true)) {
            s.vault.remove("lite-visit-$owner"); s.vault.remove("audio-batch-$key"); once()
        } else throw e
    }
}

@Composable
fun LiteVoiceScreen(s: AppState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val owner = s.user?.str("id") ?: "sample"
    val rec by RecordingService.state.collectAsState()
    var working by remember { mutableStateOf(false) }
    var stageLabel by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var text by remember { mutableStateOf(s.vault.read("lite-text-$owner").orEmpty()) }
    var labels by remember { mutableStateOf(s.vault.read("lite-labels-$owner") != "off") }
    var pending by remember { mutableStateOf<File?>(null) }
    var watched by remember { mutableStateOf<String?>(null) }

    fun saveText(value: String) { text = value; if (value.isBlank()) s.vault.remove("lite-text-$owner") else s.vault.write("lite-text-$owner", value) }

    suspend fun refreshPending() {
        pending = withContext(Dispatchers.IO) {
            runCatching { RecordingService.pendingRecordings(context).firstNotNullOfOrNull { it.filePath }?.let { File(it) } }.getOrNull()
        }
    }
    // Saved recordings that were never turned into text (an error, or the app was closed) are offered again here.
    LaunchedEffect(Unit) { refreshPending() }
    LaunchedEffect(rec.recording, rec.filePath) {
        if (rec.recording) watched = rec.filePath
        else if (watched != null && rec.filePath == watched) { watched = null; refreshPending() }
    }
    LaunchedEffect(rec.elapsedSeconds, rec.recording) {
        if (rec.recording && rec.elapsedSeconds >= LITE_MAX_SECONDS) {
            RecordingService.stop(context)
            message = "Recording stopped after about an hour, which is the longest one recording can be. Tap Turn into text, then record more if the visit is not finished."
        }
    }

    fun turnIntoText(file: File) {
        if (working || rec.recording) return
        scope.launch {
            working = true; message = null; stageLabel = "Starting"
            try {
                val evidence = liteTranscribe(s, owner, file) { stageLabel = it }
                val plain = evidence.str("text").trim()
                val heard = if (labels) liteSpeakerText(evidence.optJSONObject("metadata")?.optJSONArray("segments"), plain).trim() else plain
                if (heard.isBlank()) message = "No speech was found in this recording. Move the phone closer to both people and record again."
                else saveText(if (text.isBlank()) heard else text.trimEnd() + "\n\n" + heard)
                s.vault.remove("audio-batch-lite-$owner-${file.name}")
                runCatching { RecordingService.clearPending(context, file.absolutePath, deleteFile = true) }
                refreshPending()
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                message = (e.message?.takeIf { it.isNotBlank() } ?: "The text could not be made.") + " Your recording is saved on this phone. Tap Turn into text to try again."
            } finally { working = false }
        }
    }

    fun micGranted() = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    fun notificationsGranted() = android.os.Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, RecordingPermissions.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    fun begin() { message = null; RecordingService.start(context, "$owner:lite") }

    // Microphone (required) and, once, notifications on Android 13+ so Stop is in the notification shade. Refusing notifications never blocks recording.
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
        if (RecordingPermissions.mayRecord(micGranted())) begin() else message = "Amilya Voice needs the microphone. Allow it in your phone's Settings."
    }
    fun startRecording() {
        if (working) return
        val needed = RecordingPermissions.toRequest(android.os.Build.VERSION.SDK_INT, micGranted(), notificationsGranted(),
            s.vault.read(RecordingPermissions.NOTIFICATIONS_ASKED_KEY) != null)
        if (needed.isEmpty()) begin()
        else { runCatching { s.vault.write(RecordingPermissions.NOTIFICATIONS_ASKED_KEY, "1") }; permission.launch(needed.toTypedArray()) }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Amilya Voice", style = MaterialTheme.typography.headlineMedium)
        Text("Record the visit: you and the client. Get the text. Share it. No report is made.", style = MaterialTheme.typography.bodyLarge)
        if (s.demo) Text("Sample mode: nothing is sent and no real text is made.", color = MaterialTheme.colorScheme.error)

        if (rec.recording) {
            val label = if (rec.paused) "Paused" else "Recording"
            Text("$label  %d:%02d".format(rec.elapsedSeconds / 60, rec.elapsedSeconds % 60), style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.error)
            Text("It keeps recording with the screen locked or while you use other apps. Stop it here or in the notification.",
                style = MaterialTheme.typography.bodyMedium)
            rec.notice?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            WcpButton("Stop", { RecordingService.stop(context) }, Modifier.fillMaxWidth())
            WcpButton(if (rec.paused) "Resume" else "Pause", { if (rec.paused) RecordingService.resume(context) else RecordingService.pause(context) },
                Modifier.fillMaxWidth(), secondary = true)
        } else {
            WcpButton(if (text.isBlank() && pending == null) "Start recording" else "Record more", { startRecording() }, Modifier.fillMaxWidth(), enabled = !working)
            Text("Press record only when the client knows and agrees to be recorded.", style = MaterialTheme.typography.bodySmall)
            rec.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }

        if (!rec.recording) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Speaker labels", style = MaterialTheme.typography.titleSmall)
                    Text("Marks who is talking. Check them: the numbers can swap every 2 minutes.", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = labels, onCheckedChange = { labels = it; s.vault.write("lite-labels-$owner", if (it) "on" else "off") }, enabled = !working)
            }
        }

        pending?.let { file ->
            if (!rec.recording) {
                WcpButton(if (working) "Making text... $stageLabel" else "Turn into text", { turnIntoText(file) }, Modifier.fillMaxWidth(), enabled = !working)
                if (!working) WcpButton("Delete this recording", {
                    runCatching { RecordingService.clearPending(context, file.absolutePath, deleteFile = true) }
                    scope.launch { refreshPending() }
                }, Modifier.fillMaxWidth(), secondary = true)
            }
        }
        if (working) LinearProgressIndicator(Modifier.fillMaxWidth())
        message?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        if (!rec.recording && (text.isNotBlank() || pending == null)) {
            OutlinedTextField(value = text, onValueChange = { saveText(it) }, modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp),
                label = { Text("Your text (you can edit it)") })
        }
        if (!rec.recording && text.isNotBlank()) {
            WcpButton("Share text", {
                val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }
                runCatching { context.startActivity(Intent.createChooser(send, "Share text")) }
                    .onFailure { message = "No app was found to share with." }
            }, Modifier.fillMaxWidth())
            WcpButton("Copy text", {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Amilya Voice", text))
                message = null
            }, Modifier.fillMaxWidth(), secondary = true)
            WcpButton("Clear text", { saveText("") }, Modifier.fillMaxWidth(), secondary = true)
            Text("Check the text before you share it. Do not send patient details to apps you do not trust.", style = MaterialTheme.typography.bodySmall)
        }
        TextButton({ s.screen = "account" }) { Text("Account and sign out") }
    }
}
