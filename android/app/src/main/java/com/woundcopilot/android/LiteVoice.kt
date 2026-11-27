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

private const val LITE_MAX_SECONDS = 58 * 60

private val transcriptWhitespace = Regex("\\s+")

/** Label only complete, word-for-word segments. Providers can return partial or filtered segments. */
internal fun liteSpeakerText(segments: JSONArray?, plain: String): String {
    if (segments == null || plain.isBlank()) return plain
    val words = mutableListOf<String>()
    val formatted = StringBuilder()
    var previousSpeaker: Int? = null
    var hasLabel = false
    for (i in 0 until segments.length()) {
        val item = segments.optJSONObject(i) ?: continue
        val phrase = item.str("text").trim()
        if (phrase.isEmpty()) continue
        words += phrase
        val speaker = item.optInt("speaker", -1).takeIf { it >= 0 }
        if (formatted.isNotEmpty()) formatted.append(if (speaker == previousSpeaker) " " else "\n\n")
        if (speaker != null && speaker != previousSpeaker) {
            formatted.append("Speaker ").append(speaker + 1).append(": ")
            hasLabel = true
        }
        formatted.append(phrase)
        previousSpeaker = speaker
    }
    val complete = words.joinToString(" ").replace(transcriptWhitespace, " ") == plain.trim().replace(transcriptWhitespace, " ")
    return if (hasLabel && complete) formatted.toString() else plain
}

internal fun liteTranscript(evidence: JSONObject, labels: Boolean): String {
    val metadata = evidence.optJSONObject("metadata")
    val plain = (metadata?.str("original_transcript")?.takeIf { it.isNotBlank() } ?: evidence.str("text")).trim()
    return if (labels) liteSpeakerText(metadata?.optJSONArray("segments"), plain) else plain
}

/** App-level speech heuristics can change a real utterance or suppress a provider guess.
 * Keep the audio whenever they disagree so the listener can decide.
 */
internal fun liteKeepAudio(evidence: JSONObject): Boolean {
    val original = evidence.optJSONObject("metadata")?.str("original_transcript").orEmpty()
    if (original.isNotBlank() && original.trim().replace(transcriptWhitespace, " ") !=
        evidence.str("text").trim().replace(transcriptWhitespace, " ")) return true
    // The backend leaves repeated quantities/negations intact and does not attach original_transcript in that case.
    val warnings = evidence.optJSONArray("warnings") ?: return false
    return (0 until warnings.length()).any { warnings.optString(it).startsWith("A sentence with a number, dose, negation or side was repeated") }
}

/** Text and recording acknowledgements are saved together before removing or retaining the audio. */
internal data class LiteVoiceDraft(val text: String, val applied: Set<String> = emptySet(), val kept: Set<String> = emptySet()) {
    fun append(fileName: String, words: String, keepAudio: Boolean = false): LiteVoiceDraft {
        if (words.isBlank() || fileName in applied) return this
        val joined = if (text.isBlank()) words else text.trimEnd() + "\n\n" + words
        return LiteVoiceDraft(joined, applied + fileName, if (keepAudio) kept + fileName else kept)
    }

    fun encode() = obj("text" to text, "applied" to JSONArray(applied.toList()), "kept" to JSONArray(kept.toList())).toString()

    companion object {
        fun restore(saved: String?, legacyText: String?): LiteVoiceDraft {
            if (saved == null) return LiteVoiceDraft(legacyText.orEmpty())
            val data = JSONObject(saved)
            fun names(key: String): Set<String> {
                val files = data.optJSONArray(key) ?: return emptySet()
                return (0 until files.length()).map { files.getString(it) }.toSet()
            }
            return LiteVoiceDraft(data.getString("text"), names("applied"), names("kept"))
        }
    }
}

private suspend fun liteVisit(s: AppState, owner: String, checkSession: () -> Unit): String {
    checkSession()
    val cache = "lite-visit-$owner"
    s.vault.read(cache)?.takeIf { it.isNotBlank() }?.let { return it }
    val request = UUID.randomUUID().toString()
    val response = s.api.call("/quick/start", "POST", obj("mode" to "document", "request_id" to request), request) as JSONObject
    checkSession()
    val id = response.getJSONObject("encounter").getString("id")
    s.vault.write(cache, id)
    return id
}

/** Keep one recording on its original visit and job while any section has an uncertain outcome. */
private suspend fun liteTranscribe(
    s: AppState, owner: String, file: File, manual: Boolean, checkSession: () -> Unit,
    onPending: (VoicePendingPart) -> Unit, stage: (String) -> Unit,
): JSONObject {
    if (s.demo) return obj("text" to "Sample only. No transcription was performed.")
    val key = "lite-$owner-${file.name}"
    val checkpoint = "audio-batch-$key"
    val pendingKey = "lite-pending-$owner-${file.name}"
    var pending = s.vault.read(pendingKey)?.let(VoicePendingPart::read)
    var allowManualReplay = manual && pending != null
    val visitKey = "lite-recording-visit-$owner-${file.name}"
    val newBatch = s.vault.read(checkpoint) == null
    // Older builds stored the visit per account. Bind that value to the existing recording on upgrade.
    val recordingVisit = s.vault.read(visitKey)
    val cachedVisit = voiceRecordingVisit(pending?.visit, recordingVisit, s.vault.read("lite-visit-$owner"), newBatch)
    var visit = cachedVisit ?: liteVisit(s, owner, checkSession)
    s.vault.write(visitKey, visit)
    var mayReplaceExpiredVisit = newBatch && pending == null
    while (true) {
        checkSession()
        val part = pending
        if (part != null) {
            val batch = s.vault.read(checkpoint)?.let(::JSONObject)
            check(batch != null && batch.str("job_id") == part.job &&
                batch.optJSONArray("files")?.length() == part.count) { "Could not find the original transcription request." }
            batch.optJSONObject("result")?.let { return it.getJSONObject("evidence") }
            if (batch.optInt("completed", -1) == part.index) {
                stage("Checking the saved transcription")
                val outcome = waitForVoicePart(part, allowManualReplay) {
                    checkSession()
                    checkVoicePart(part) { path, method, body, requestKey ->
                        checkSession()
                        s.api.call(path, method, body, requestKey) as JSONObject
                    }
                }
                checkSession()
                when (outcome) {
                    VoicePartCheck.Ready -> {
                        // The server has durably accepted these bytes; finish any remaining sections.
                        check(part.matches(batch))
                        batch.put("completed", part.index + 1)
                    }
                    is VoicePartCheck.Complete -> batch.put("result", outcome.result)
                    VoicePartCheck.LegacyIncomplete -> {
                        // A deliberate retry on a legacy server reuses the same visit, job, index and bytes.
                        // If the service still cannot confirm that section, display the saved status again.
                        allowManualReplay = false
                        mayReplaceExpiredVisit = false
                    }
                    VoicePartCheck.Waiting -> throw VoiceStillPending()
                }
                s.vault.write(checkpoint, batch.toString())
            } else {
                // The app may have closed between advancing the checkpoint and clearing its pending marker.
                check(batch.getInt("completed") in part.index + 1..part.count)
            }
            // Keep the receipt until the next section is acknowledged or a full result is durably saved.
            // A network failure during a manual replay must not erase the original request identity.
            pending = null
            batch.optJSONObject("result")?.let { result ->
                // Partial M4A copies created by AudioChunks normally disappear after assembly.
                val segments = File(file.parentFile, "segments").canonicalFile
                val files = batch.getJSONArray("files")
                for (index in 0 until files.length()) {
                    val segment = File(files.getString(index)).canonicalFile
                    if (segment.parentFile == segments) segment.delete()
                }
                return result.getJSONObject("evidence")
            }
        }
        try {
            val result = AudioChunks(s.api, s.vault).transcribe(visit, file, key, null, stage)
            return result.getJSONObject("evidence")
        } catch (e: ApiException) {
            checkSession()
            if (e.voicePending()) {
                val batch = JSONObject(checkNotNull(s.vault.read(checkpoint)))
                val saved = VoicePendingPart.from(visit, batch, e)
                s.vault.write(pendingKey, saved.encode())
                pending = saved
                onPending(saved)
                mayReplaceExpiredVisit = false
            } else if (e.status == 404 && mayReplaceExpiredVisit) {
                mayReplaceExpiredVisit = false
                val batch = s.vault.read(checkpoint)?.let(::JSONObject)
                if (batch != null && batch.optInt("completed", -1) != 0) throw e
                s.vault.remove("lite-visit-$owner")
                s.vault.remove(checkpoint)
                visit = liteVisit(s, owner, checkSession)
                s.vault.write(visitKey, visit)
            } else throw e
        }
    }
}

@Composable
fun LiteVoiceScreen(s: AppState) {
    val owner = s.user?.str("id") ?: if (s.demo) "sample" else return
    key(owner) { VoiceRecordingScreen(s, owner) }
}

@Composable
private fun VoiceRecordingScreen(s: AppState, owner: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val rec by RecordingService.state.collectAsState()
    val ownerKey = "$owner:lite"
    val activeOwner = remember(rec.filePath) { rec.filePath?.let { s.vault.read("recording-owner-${File(it).name}") } }
    val draftKey = "lite-draft-$owner"
    val legacyKey = "lite-text-$owner"
    var draft by remember { mutableStateOf(LiteVoiceDraft.restore(s.vault.read(draftKey), s.vault.read(legacyKey))) }
    val text = draft.text
    var working by remember { mutableStateOf(false) }
    var stageLabel by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var labels by remember { mutableStateOf(s.vault.read("lite-labels-$owner") == "on") }
    var pendingFiles by remember { mutableStateOf(emptyList<File>()) }
    var selectedName by remember { mutableStateOf<String?>(null) }
    val pending = pendingFiles.firstOrNull { it.name == selectedName } ?: pendingFiles.firstOrNull()
    var emptyFiles by remember { mutableStateOf(emptySet<String>()) }
    var awaiting by remember { mutableStateOf(emptyMap<String, VoicePendingPart>()) }
    fun pendingKey(file: File) = "lite-pending-$owner-${file.name}"
    fun stopKey(file: File) = "lite-transcribe-$owner-${file.name}"

    fun sessionActive() = (s.user?.str("id") ?: if (s.demo) "sample" else null) == owner && !s.locked && !s.legalRequired
    fun checkSession() {
        if (!sessionActive()) throw CancellationException("Voice session changed")
    }
    fun owns(file: File) = s.vault.read("recording-owner-${file.name}") == ownerKey
    fun saveDraft(value: LiteVoiceDraft) {
        s.vault.write(draftKey, value.encode())
        draft = value
        s.vault.remove(legacyKey)
    }
    fun saveText(value: String) = saveDraft(draft.copy(text = value))

    suspend fun refreshPending() {
        val (files, empty) = withContext(Dispatchers.IO) {
            val files = RecordingService.pendingRecordings(context).mapNotNull { it.filePath?.let(::File) }.filter(::owns)
            files to files.map { it.name }.filter { s.vault.read("lite-empty-$owner-$it") != null }.toSet()
        }
        awaiting = files.mapNotNull { file ->
            s.vault.read(pendingKey(file))?.let { file.name to VoicePendingPart.read(it) }
        }.toMap()
        pendingFiles = files
        emptyFiles = empty
    }

    fun removeRecording(file: File) {
        RecordingService.clearPending(context, file.absolutePath, deleteFile = true)
        s.vault.remove("audio-batch-lite-$owner-${file.name}")
        s.vault.remove(stopKey(file))
        s.vault.remove(pendingKey(file))
        s.vault.remove("lite-recording-visit-$owner-${file.name}")
        s.vault.remove("lite-empty-$owner-${file.name}")
        s.vault.remove("recording-owner-${file.name}")
        if (file.name in draft.applied) saveDraft(draft.copy(applied = draft.applied - file.name, kept = draft.kept - file.name))
    }

    fun turnIntoText(file: File, manual: Boolean = false) {
        if (working || rec.recording || !sessionActive() || !owns(file)) return
        working = true
        selectedName = file.name
        scope.launch {
            message = null
            stageLabel = "Starting"
            try {
                if (file.name !in draft.applied) {
                    val evidence = liteTranscribe(s, owner, file, manual, ::checkSession, { part ->
                        awaiting = awaiting + (file.name to part)
                    }) {
                        checkSession()
                        stageLabel = it
                    }
                    if (!sessionActive()) return@launch
                    s.vault.remove(pendingKey(file))
                    awaiting = awaiting - file.name
                    val heard = liteTranscript(evidence, labels)
                    if (heard.isBlank()) {
                        s.vault.write("lite-empty-$owner-${file.name}", "1")
                        emptyFiles = emptyFiles + file.name
                        s.vault.remove(stopKey(file))
                        return@launch
                    }
                    saveDraft(draft.append(file.name, heard, keepAudio = liteKeepAudio(evidence)))
                }
                if (file.name in draft.kept) {
                    s.vault.remove(stopKey(file))
                    s.vault.remove("audio-batch-lite-$owner-${file.name}")
                } else {
                    removeRecording(file)
                    refreshPending()
                }
            } catch (e: VoiceStillPending) {
                s.vault.remove(stopKey(file))
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                s.vault.remove(stopKey(file))
                message = if (awaiting.containsKey(file.name)) "Couldn’t check the transcript. Try Check again when you’re connected."
                    else if (file.name in draft.applied) "Your text is saved. Tap Finish to clear the recording."
                    else (e.message?.takeIf { it.isNotBlank() } ?: "The recording could not be transcribed.") + " Your recording is saved on this phone."
            } finally { working = false }
        }
    }

    LaunchedEffect(rec.recording, rec.filePath) {
        if (!rec.recording) refreshPending()
    }
    // The marker is written only when the user taps Stop here. Resume it after rotation or unlocking.
    LaunchedEffect(pendingFiles, working, rec.recording, s.locked, s.legalRequired) {
        if (!working && !rec.recording && sessionActive()) {
            rec.filePath?.takeIf { rec.error != null }?.let { s.vault.remove(stopKey(File(it))) }
            pendingFiles.firstOrNull { s.vault.read(stopKey(it)) != null }?.let { turnIntoText(it) }
        }
    }
    LaunchedEffect(rec.elapsedSeconds, rec.recording) {
        if (rec.recording && activeOwner == ownerKey && rec.elapsedSeconds >= LITE_MAX_SECONDS) {
            RecordingService.stop(context)
            message = "Recording reached the time limit. Tap Turn into text."
        }
    }

    fun micGranted() = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    fun notificationsGranted() = android.os.Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, RecordingPermissions.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    fun begin() {
        if (!sessionActive() || working || RecordingService.state.value.recording) return
        message = null
        RecordingService.start(context, ownerKey)
    }

    // Microphone (required) and, once, notifications on Android 13+ so Stop is in the notification shade. Refusing notifications never blocks recording.
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
        if (RecordingPermissions.mayRecord(micGranted())) begin() else message = "Amilya Voice needs the microphone. Allow it in your phone's Settings."
    }
    fun startRecording() {
        if (working || !sessionActive()) return
        val needed = RecordingPermissions.toRequest(android.os.Build.VERSION.SDK_INT, micGranted(), notificationsGranted(),
            s.vault.read(RecordingPermissions.NOTIFICATIONS_ASKED_KEY) != null)
        if (needed.isEmpty()) begin()
        else { runCatching { s.vault.write(RecordingPermissions.NOTIFICATIONS_ASKED_KEY, "1") }; permission.launch(needed.toTypedArray()) }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Amilya Voice", style = MaterialTheme.typography.headlineMedium)
        Text("Record the conversation. Tap Stop to turn speech into text.", style = MaterialTheme.typography.bodyLarge)
        if (s.demo) Text("Sample mode: nothing is sent and no real text is made.", color = MaterialTheme.colorScheme.error)

        if (rec.recording && activeOwner != ownerKey) {
            Text("Another account has an active recording.")
        } else if (rec.recording) {
            val label = if (rec.paused) "Paused" else "Recording"
            Text("$label  %d:%02d".format(rec.elapsedSeconds / 60, rec.elapsedSeconds % 60), style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.error)
            Text("It keeps recording with the screen locked or while you use other apps. If you stop from the notification, open Voice and tap Turn into text.",
                style = MaterialTheme.typography.bodyMedium)
            rec.notice?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            WcpButton("Stop", {
                val path = rec.filePath
                if (path != null && sessionActive()) {
                    runCatching { s.vault.write(stopKey(File(path)), "1") }
                        .onFailure { message = "Tap Turn into text when the recording stops." }
                }
                RecordingService.stop(context)
            }, Modifier.fillMaxWidth())
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
                    Text("Adds speaker numbers when available. The numbers can change between sections.", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = labels, onCheckedChange = { labels = it; s.vault.write("lite-labels-$owner", if (it) "on" else "off") }, enabled = !working)
            }
        }

        pending?.let { file ->
            if (!rec.recording) {
                if (pendingFiles.size > 1) {
                    var choosing by remember { mutableStateOf(false) }
                    Box {
                        TextButton(onClick = { choosing = true }, enabled = !working) {
                            Text("Recording ${pendingFiles.indexOf(file) + 1} of ${pendingFiles.size}")
                        }
                        DropdownMenu(expanded = choosing, onDismissRequest = { choosing = false }) {
                            pendingFiles.forEachIndexed { index, item ->
                                DropdownMenuItem(text = { Text("Recording ${index + 1} · ${localTime(java.time.Instant.ofEpochMilli(item.lastModified()).toString())}") },
                                    onClick = { selectedName = item.name; choosing = false })
                            }
                        }
                    }
                }
                val waiting = awaiting[file.name]
                if (waiting != null && !working) {
                    Text(if (waiting.review) "The service could not confirm the transcription. Your recording is saved on this phone."
                        else "Your transcript isn’t ready yet. Your recording is saved on this phone.")
                    waiting.reference?.let { Text("Support reference: $it", style = MaterialTheme.typography.bodySmall) }
                    LocalMediaPreview(file, "audio")
                }
                if (file.name in emptyFiles || file.name in draft.kept) {
                    if (file.name in emptyFiles) Text("The speech service returned no text. Your recording is still on this phone.")
                    LocalMediaPreview(file, "audio")
                } else {
                    val action = when {
                        working && waiting != null -> "Checking the saved transcription…"
                        working -> "Transcribing… $stageLabel"
                        waiting != null -> "Check again"
                        file.name in draft.applied -> "Finish"
                        else -> "Turn into text"
                    }
                    WcpButton(action, { turnIntoText(file, manual = true) }, Modifier.fillMaxWidth(), enabled = !working)
                }
                if (!working) WcpButton("Delete this recording", {
                    if (sessionActive() && owns(file)) {
                        runCatching { removeRecording(file) }.onFailure { message = "Could not delete the recording." }
                        scope.launch { refreshPending() }
                    }
                }, Modifier.fillMaxWidth(), secondary = true)
            }
        }
        if (working) LinearProgressIndicator(Modifier.fillMaxWidth())
        message?.let { Text(it, color = if (pending?.let { file -> awaiting.containsKey(file.name) } == true) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error) }

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
