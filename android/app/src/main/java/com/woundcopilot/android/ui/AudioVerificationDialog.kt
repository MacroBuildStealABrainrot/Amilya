package com.woundcopilot.android.ui

import android.media.AudioAttributes
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.woundcopilot.android.ApiException
import com.woundcopilot.android.AppState
import com.woundcopilot.android.RecordingService
import com.woundcopilot.android.items
import com.woundcopilot.android.obj
import com.woundcopilot.android.str
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

/** Re-verifies an original audio window and keeps acceptance as an explicit, separate clinical action. */
@Composable
fun AudioVerificationDialog(state: AppState, evidence: JSONObject, file: File, onDismiss: () -> Unit) {
    val sourceId = if (evidence.str("kind") == "audio_verification") {
        evidence.optJSONObject("metadata")?.str("source_evidence_id") ?: ""
    } else evidence.str("id")
    val original = state.case?.items("evidence")?.firstOrNull { it.str("id") == sourceId } ?: evidence
    val encounterId = state.encounterId
    val storageKey = "audio-review-${state.user?.str("id") ?: "sample"}-$sourceId"
    val recovered = remember(storageKey) {
        state.vault.read(storageKey)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
    }
    val explicitProposalId = evidence.str("id").takeIf { evidence.str("kind") == "audio_verification" }
    val storedResult = recovered.optJSONObject("result")?.takeIf {
        it.str("source_evidence_id") == sourceId && (explicitProposalId == null || it.str("verification_id") == explicitProposalId)
    }
    var start by rememberSaveable(storageKey) { mutableStateOf(recovered.str("start", "0")) }
    var end by rememberSaveable(storageKey) { mutableStateOf(recovered.str("end", "15")) }
    var term by rememberSaveable(storageKey) { mutableStateOf(recovered.str("term")) }
    var clinicalContext by rememberSaveable(storageKey) { mutableStateOf(recovered.str("context")) }
    var verifier by rememberSaveable(storageKey) { mutableStateOf(recovered.str("verifier", "stronger")) }
    // Consent and source-review acknowledgement must be made again on each opening.
    var consent by remember { mutableStateOf(false) }
    var acknowledged by remember { mutableStateOf(false) }
    var result by remember(storageKey) { mutableStateOf(storedResult) }
    var candidate by rememberSaveable(storageKey) { mutableStateOf(if (storedResult != null) recovered.str("candidate", storedResult.str("candidate_text")) else "") }
    var acceptedText by remember(storageKey) { mutableStateOf(recovered.str("accepted_text")) }
    var commandFingerprint by rememberSaveable(storageKey) { mutableStateOf(recovered.str("fingerprint")) }
    var commandKey by rememberSaveable(storageKey) { mutableStateOf(recovered.str("request_key")) }
    var freshResult by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var storageError by remember { mutableStateOf<String?>(null) }
    var fileError by remember { mutableStateOf<String?>(null) }
    var checkingFile by remember { mutableStateOf(true) }
    var duration by remember { mutableStateOf<Double?>(null) }
    var mediaHash by remember { mutableStateOf("") }
    var playing by remember { mutableStateOf(false) }
    var preparing by remember { mutableStateOf(false) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    val handler = remember { Handler(Looper.getMainLooper()) }
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme

    fun snapshot() = obj(
        "start" to start, "end" to end, "term" to term, "context" to clinicalContext,
        "verifier" to verifier, "result" to result, "candidate" to candidate,
        "accepted_text" to acceptedText, "fingerprint" to commandFingerprint, "request_key" to commandKey,
    ).toString()

    fun persist(): Boolean = runCatching { state.vault.write(storageKey, snapshot()) }
        .onSuccess { storageError = null }
        .onFailure { storageError = "This review could not be saved on the device. The server keeps completed candidates, but keep this dialog open to preserve unsaved edits." }
        .isSuccess

    val savedSnapshot = snapshot()
    val latestSnapshot by rememberUpdatedState(savedSnapshot)
    LaunchedEffect(savedSnapshot) { delay(250); persist() }
    DisposableEffect(storageKey) {
        onDispose {
            runCatching { state.vault.write(storageKey, latestSnapshot) }
            handler.removeCallbacksAndMessages(null)
            runCatching { player?.release() }
        }
    }

    fun stopPlayback() {
        handler.removeCallbacksAndMessages(null)
        runCatching { player?.release() }
        player = null
        playing = false
        preparing = false
    }

    fun showError(problem: Exception) {
        error = if (problem is ApiException) problem.message else "The connection was interrupted. Your request identity and edits are kept; retry when connected."
        if (problem is ApiException && problem.status == 409) {
            freshResult = false
            acknowledged = false
            error = "The encounter changed or this candidate was already reviewed. Refresh the review context, compare the text again, then retry."
        }
        if (problem is ApiException && problem.status == 428) state.legalRequired = true
        if (problem is ApiException && problem.status == 401) {
            persist()
            state.error = "Your session expired. Sign in again to continue the saved audio review."
            state.screen = "login"
            onDismiss()
        }
    }

    fun runOperation(operation: String, work: suspend () -> Unit) {
        if (busy.isNotBlank()) return
        busy = operation
        error = null
        scope.launch {
            try { work() }
            catch (problem: Exception) {
                if (problem is CancellationException) throw problem
                showError(problem)
            } finally { busy = "" }
        }
    }

    suspend fun refreshProposal(id: String) {
        freshResult = false
        val next = state.api.call("/audio-verifications/$id") as JSONObject
        require(next.str("source_evidence_id") == sourceId) { "Candidate does not belong to this audio source" }
        val preserveEdits = result?.str("verification_id") == id
        result = next
        if (!preserveEdits) candidate = next.str("candidate_text")
        start = next.optJSONObject("window")?.optDouble("start_seconds")?.toString() ?: start
        end = next.optJSONObject("window")?.optDouble("end_seconds")?.toString() ?: end
        term = next.str("uncertain_term")
        clinicalContext = next.str("context")
        acknowledged = false
        persist()
        state.reloadCase()
        freshResult = true
        if (next.str("status") == "accepted") {
            val acceptedId = next.str("accepted_evidence_id")
            acceptedText = state.case?.items("evidence")?.firstOrNull { it.str("id") == acceptedId }?.str("text") ?: ""
            persist()
        }
    }

    LaunchedEffect(storageKey) {
        val proposalId = explicitProposalId ?: result?.str("verification_id")?.ifBlank { null }
            ?: if (!recovered.has("result")) state.case?.items("evidence")?.lastOrNull {
                it.str("kind") == "audio_verification" && it.optJSONObject("metadata")?.str("source_evidence_id") == sourceId
            }?.str("id") else null
        if (proposalId != null && !state.demo) {
            busy = "load"
            try { refreshProposal(proposalId) }
            catch (problem: Exception) { if (problem is CancellationException) throw problem; showError(problem) }
            finally { busy = "" }
        }
    }

    LaunchedEffect(file.absolutePath, sourceId) {
        checkingFile = true
        fileError = null
        try {
            val checked = withContext(Dispatchers.IO) {
                require(file.isFile && file.length() > 0) { "The original recording is no longer available on this device." }
                require(file.length() <= 25L * 1024L * 1024L) { "The original recording exceeds the 25 MB upload limit." }
                val digest = MessageDigest.getInstance("SHA-256")
                file.inputStream().use { input ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                }
                val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
                val expected = original.optJSONObject("metadata")?.str("media_hash").orEmpty()
                    .ifBlank { evidence.optJSONObject("metadata")?.str("source_media_hash").orEmpty() }
                require(expected.isBlank() || expected.equals(hash, ignoreCase = true)) { "This file does not match the original recording for the selected source." }
                val reader = MediaMetadataRetriever()
                val seconds = try {
                    reader.setDataSource(file.absolutePath)
                    reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toDoubleOrNull()?.div(1000)
                } catch (_: Exception) { null } finally { runCatching { reader.release() } }
                hash to seconds?.takeIf { it.isFinite() && it > 0 }
            }
            mediaHash = checked.first
            duration = checked.second
            if (!recovered.has("end") && result == null && duration != null) end = minOf(15.0, duration!!).toString()
        } catch (problem: Exception) {
            if (problem is CancellationException) throw problem
            fileError = problem.message ?: "The original recording could not be read."
        } finally { checkingFile = false }
    }

    val begin = start.trim().toDoubleOrNull()
    val finish = end.trim().toDoubleOrNull()
    val validWindow = begin != null && finish != null && begin.isFinite() && finish.isFinite() &&
        begin >= 0 && finish > begin && finish - begin <= 30 && (duration == null || finish <= duration!! + .025)
    val capabilities = state.config.optJSONObject("capabilities")
    val audioCapability = capabilities?.optJSONObject("audio")
    val medicalCapability = capabilities?.optJSONObject("audio_medical")
    val audioEnabled = !state.demo && audioCapability?.optBoolean("enabled") == true
    val medicalEnabled = !state.demo && medicalCapability?.optBoolean("enabled") == true
    val available = audioEnabled && (verifier != "medical" || medicalEnabled)
    val unavailableReason = when {
        state.demo -> "Speech verification is unavailable in the synthetic preview. No provider request will be sent."
        !audioEnabled -> audioCapability?.str("reason")?.ifBlank { null } ?: "Speech verification is not available in the current server configuration."
        verifier == "medical" && !medicalEnabled -> medicalCapability?.str("reason")?.ifBlank { null } ?: "Optional medical transcription is not configured."
        else -> null
    }
    val accepted = result?.str("status") == "accepted"
    val idle = busy.isBlank()
    val canListen = validWindow && fileError == null && !checkingFile

    fun playWindow() {
        if (playing || preparing) { stopPlayback(); return }
        if (!canListen) { error = "Choose a valid window within the original recording before playing it."; return }
        if (RecordingService.state.value.recording) { error = "Stop the active recording before listening to this excerpt."; return }
        stopPlayback()
        preparing = true
        val selectedStart = begin!!
        val selectedEnd = finish!!
        try {
            val created = MediaPlayer()
            player = created
            created.setAudioAttributes(AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).setUsage(AudioAttributes.USAGE_MEDIA).build())
            created.setDataSource(file.absolutePath)
            created.setOnErrorListener { _, _, _ ->
                stopPlayback()
                error = "The recording could not play on this device. Its original bytes are still retained."
                true
            }
            created.setOnCompletionListener { stopPlayback() }
            created.setOnSeekCompleteListener {
                if (player !== created) return@setOnSeekCompleteListener
                try { created.start() } catch (_: Exception) {
                    stopPlayback()
                    error = "Playback was interrupted. Try listening again."
                    return@setOnSeekCompleteListener
                }
                preparing = false
                playing = true
                handler.post(object : Runnable {
                    override fun run() {
                        if (player !== created) return
                        val reachedEnd = runCatching { !created.isPlaying || created.currentPosition >= selectedEnd * 1000 }.getOrDefault(true)
                        if (reachedEnd) stopPlayback() else handler.postDelayed(this, 60)
                    }
                })
            }
            created.setOnPreparedListener {
                if (player === created) runCatching { created.seekTo((selectedStart * 1000).toLong(), MediaPlayer.SEEK_CLOSEST) }
                    .onFailure { stopPlayback(); error = "The selected playback window could not be opened." }
            }
            created.prepareAsync()
        } catch (_: Exception) {
            stopPlayback()
            error = "The recording could not play on this device. Its original bytes are still retained."
        }
    }

    Dialog(
        onDismissRequest = { if (idle) { persist(); onDismiss() } },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false, dismissOnBackPress = idle),
    ) {
        Surface(Modifier.fillMaxSize().systemBarsPadding(), color = colors.background) {
            Column(Modifier.imePadding()) {
                Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Audio review", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { persist(); onDismiss() }, enabled = idle, modifier = Modifier.heightIn(min = 48.dp)) { Text("Close") }
                }
                HorizontalDivider(color = colors.outlineVariant)
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    Eyebrow("Source verification")
                    PageHeading("Listen. Compare. Review.", "Check up to 30 seconds of original audio, then decide whether to add a separate reviewed transcript.")
                    Text(
                        listOf(state.case?.optJSONObject("patient")?.str("display_name"), state.case?.optJSONObject("wound")?.str("label")).filterNotNull().filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.titleSmall, color = colors.primary,
                    )
                    Text("The original transcript is preserved. Accepting an excerpt does not confirm its clinical findings.", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                    if (error != null) ErrorBanner(error!!, { error = null })
                    if (storageError != null) ErrorBanner(storageError!!, { storageError = null })
                    if (fileError != null) VerificationNote(fileError!!, warning = true)
                    if (unavailableReason != null) VerificationNote(unavailableReason, warning = true)
                    if (checkingFile) Text("Checking original audio…", style = MaterialTheme.typography.bodySmall, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    if (duration != null) Text("Original recording · ${String.format(Locale.ROOT, "%.1f", duration)} seconds", color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)

                    SectionLabel("Selected window")
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = start, onValueChange = { start = it.take(12); acknowledged = false; stopPlayback() },
                            label = { Text("Start · seconds") }, modifier = Modifier.weight(1f),
                            enabled = idle && result == null, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
                        )
                        OutlinedTextField(
                            value = end, onValueChange = { end = it.take(12); acknowledged = false; stopPlayback() },
                            label = { Text("End · seconds") }, modifier = Modifier.weight(1f),
                            enabled = idle && result == null, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
                        )
                    }
                    if (!validWindow) Text("Choose a window longer than zero and no more than 30 seconds, within the recording.", style = MaterialTheme.typography.bodySmall, color = colors.error)
                    WcpButton(
                        when { preparing -> "Cancel playback"; playing -> "Stop playback"; else -> "Listen to this window" },
                        { playWindow() }, Modifier.fillMaxWidth(), enabled = canListen || playing || preparing, secondary = true,
                    )

                    if (result == null) {
                        OutlinedTextField(
                            term, { term = it.take(200) }, Modifier.fillMaxWidth(), label = { Text("Uncertain phrase or term") },
                            placeholder = { Text("For example, a unit or left/right reference") }, enabled = idle, singleLine = true,
                        )
                        OutlinedTextField(
                            clinicalContext, { clinicalContext = it.take(2000) }, Modifier.fillMaxWidth(),
                            label = { Text("Context · optional") }, enabled = idle, minLines = 3,
                            supportingText = { Text("${clinicalContext.length}/2000") },
                        )
                        SectionLabel("Speech verification")
                        WcpButton("Stronger general transcription", { verifier = "stronger" }, Modifier.fillMaxWidth(), enabled = idle, secondary = verifier != "stronger")
                        WcpButton("Medical transcription · optional", { verifier = "medical" }, Modifier.fillMaxWidth(), enabled = idle && medicalEnabled, secondary = verifier != "medical")
                        if (!medicalEnabled) Text(medicalCapability?.str("reason")?.ifBlank { null } ?: "Optional medical transcription is unavailable.", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        Text("This requests another transcription from the configured speech service. It is not an independent medical assessment.", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        VerificationCheck(consent, { consent = it }, "Recording consent covers verification of this excerpt.", enabled = idle)
                        WcpButton(
                            if (busy == "verify") "Verifying excerpt…" else "Verify excerpt",
                            onClick = {
                                if (!validWindow || term.isBlank() || !consent) {
                                    error = "Choose a valid window, describe the uncertain phrase, and confirm recording consent."
                                } else runOperation("verify") {
                                    val fields = linkedMapOf(
                                        "source_evidence_id" to sourceId, "start_seconds" to begin!!.toString(),
                                        "end_seconds" to finish!!.toString(), "uncertain_term" to term.trim(),
                                        "context" to clinicalContext.trim(), "consent" to "true", "verifier" to verifier,
                                    )
                                    val fingerprint = obj("media_hash" to mediaHash, "fields" to JSONObject(fields)).toString()
                                    if (fingerprint != commandFingerprint || commandKey.isBlank()) {
                                        commandFingerprint = fingerprint
                                        commandKey = UUID.randomUUID().toString()
                                    }
                                    check(persist()) { "Request recovery could not be saved. Free device storage and retry." }
                                    val next = state.api.multipart("/encounters/$encounterId/audio-verifications", file, originalAudioMime(file), fields, commandKey)
                                    require(next.str("source_evidence_id") == sourceId) { "Unexpected source returned by verification" }
                                    result = next
                                    candidate = next.str("candidate_text")
                                    acceptedText = ""
                                    acknowledged = false
                                    freshResult = false
                                    persist()
                                    state.reloadCase()
                                    freshResult = true
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = idle && available && !checkingFile && fileError == null,
                        )
                    } else {
                        val proposal = result!!
                        StatusTag(if (accepted) "Reviewed transcript added" else "Candidate saved · review required", if (accepted) "confirmed" else "review")
                        Text("Closing keeps this candidate with the encounter. Your unsaved text corrections are kept on this device.", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        SectionLabel("Original transcript")
                        SelectionContainer { Text(proposal.str("original_text"), style = MaterialTheme.typography.bodyMedium) }
                        if (proposal.str("original_text_scope") != "overlapping_original_segments") {
                            VerificationNote("The original text may cover more than this window because aligned segment timing is unavailable.")
                        }
                        val notices = proposal.optJSONObject("comparison")?.optJSONArray("notices")
                        if (notices != null) for (index in 0 until notices.length()) {
                            notices.optString(index).takeIf { it.isNotBlank() }?.let { VerificationNote(it) }
                        }
                        if (accepted) {
                            SectionLabel(if (acceptedText.isNotBlank()) "Accepted transcript evidence" else "Original proposed text")
                            SelectionContainer { Text(acceptedText.ifBlank { proposal.str("candidate_text") }, style = MaterialTheme.typography.bodyLarge) }
                            VerificationNote("This proposal was already accepted. The reviewed transcript is separate evidence; review its findings for later corrections.")
                        } else {
                            OutlinedTextField(
                                candidate, { candidate = it.take(8000); acknowledged = false }, Modifier.fillMaxWidth(),
                                label = { Text("Candidate excerpt · review or correct") }, enabled = idle, minLines = 6,
                                supportingText = { Text("${candidate.length}/8000") },
                            )
                            if (!freshResult) VerificationNote("Refresh the review context before accepting this saved candidate.", warning = true)
                            VerificationCheck(acknowledged, { acknowledged = it }, "I reviewed this text against the source audio and want to add it as separate transcript evidence.", idle)
                            WcpButton(
                                if (busy == "accept") "Adding reviewed evidence…" else "Add reviewed transcript evidence",
                                onClick = {
                                    if (!acknowledged || candidate.isBlank()) {
                                        error = "Review the excerpt against the audio, enter the text to keep, and acknowledge your review."
                                    } else runOperation("accept") {
                                        persist()
                                        val response = state.api.call(
                                            "/audio-verifications/${proposal.str("verification_id")}/accept", "POST",
                                            obj("expected_case_revision" to proposal.optInt("case_revision"), "accepted_text" to candidate),
                                        ) as JSONObject
                                        acceptedText = response.optJSONObject("evidence")?.str("text") ?: candidate
                                        result = JSONObject(proposal.toString()).apply {
                                            put("status", "accepted")
                                            put("accepted_evidence_id", response.optJSONObject("evidence")?.str("id"))
                                            put("case_revision", response.optInt("case_revision", proposal.optInt("case_revision")))
                                        }
                                        acknowledged = false
                                        persist()
                                        state.reloadCase()
                                        state.notice = "Reviewed transcript added as separate evidence. Its extracted findings still need review."
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(), enabled = idle && freshResult && !state.demo,
                            )
                        }
                        WcpButton("Refresh review context", { runOperation("refresh") { refreshProposal(proposal.str("verification_id")) } }, Modifier.fillMaxWidth(), enabled = idle && !state.demo, secondary = true)
                        WcpButton("Verify another window", {
                            result = null; candidate = ""; acceptedText = ""; acknowledged = false; consent = false
                            commandFingerprint = ""; commandKey = ""; freshResult = false; error = null; persist()
                        }, Modifier.fillMaxWidth(), enabled = idle, secondary = true)
                    }
                    if (busy.isNotBlank()) Text(
                        when (busy) { "load" -> "Opening the saved candidate…"; "refresh" -> "Refreshing the candidate and encounter…"; "accept" -> "Saving the reviewed excerpt…"; else -> "Processing the selected audio window…" },
                        color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}

@Composable
private fun VerificationNote(text: String, warning: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    Surface(Modifier.fillMaxWidth(), color = if (warning) colors.tertiaryContainer else colors.surfaceContainer, shape = MaterialTheme.shapes.medium) {
        Text(text, modifier = Modifier.padding(14.dp), style = MaterialTheme.typography.bodySmall,
            color = if (warning) colors.onTertiaryContainer else colors.onSurfaceVariant)
    }
}

@Composable
private fun VerificationCheck(value: Boolean, onChange: (Boolean) -> Unit, text: String, enabled: Boolean) {
    Row(
        Modifier.fillMaxWidth().toggleable(value, enabled = enabled, role = Role.Checkbox, onValueChange = onChange).heightIn(min = 56.dp).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Checkbox(value, null, enabled = enabled)
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

private fun originalAudioMime(file: File): String = when (file.extension.lowercase(Locale.ROOT)) {
    "webm" -> "audio/webm"
    "ogg", "opus" -> "audio/ogg"
    "wav" -> "audio/wav"
    "mp3", "mpeg" -> "audio/mpeg"
    "m4a", "mp4" -> "audio/mp4"
    else -> "audio/mp4"
}
