package com.woundcopilot.android

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.AudioAttributes
import android.media.MediaPlayer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.woundcopilot.android.ui.*
import kotlinx.coroutines.*
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

@Composable
fun CaptureScreen(s: AppState, audio: Boolean, photo: Boolean = false) {
    val context = LocalContext.current
    val rec by RecordingService.state.collectAsState()
    val kind = if (audio) "audio" else if (photo) "photo" else "document"
    val purpose = when { audio && s.voiceMessage -> "message"; audio && s.voiceCorrection -> "correction"; audio -> "audio"; photo -> "photo"; else -> "file" }
    val ownerKey = "${s.user?.str("id")}:${s.encounterId}${if (purpose in listOf("message", "correction")) ":$purpose" else ""}"
    val key = "capture-${s.user?.str("id")}-${s.encounterId}-$purpose"
    var path by rememberSaveable(key) { mutableStateOf(s.vault.read(key) ?: "") }
    var mime by rememberSaveable(key) { mutableStateOf(s.vault.read("$key-mime") ?: captureMimeFromExtension(path, kind)) }
    // Consent is asked once per visit and then remembered for every recording in that visit.
    var consent by rememberSaveable(key) { mutableStateOf(s.recordingConsent) }
    var consentPrompt by remember { mutableStateOf(false) }
    val importBusy=s.externalCapture.busy
    var recordingRequested by rememberSaveable(key) { mutableStateOf(false) }
    var observedRecordingPath by rememberSaveable(key) { mutableStateOf<String?>(null) }
    var pending by remember(ownerKey) { mutableStateOf<List<RecordingState>>(emptyList()) }
    val scope = rememberCoroutineScope()
    val limitMb = when (kind) { "photo" -> 12; "audio" -> 25; else -> 20 }
    val activeOwner = remember(rec.filePath, ownerKey) { rec.filePath?.let { s.vault.read("recording-owner-${File(it).name}") } }
    val belongsHere = activeOwner == ownerKey

    fun selectFile(file: File, selectedMime: String, submitNewPhoto: Boolean = false) {
        val newlySelected = path != file.absolutePath
        // Persist the captured original before any request. Restoring this selection
        // never submits: only a new camera/picker result takes the automatic branch.
        s.vault.write(key, file.absolutePath)
        s.vault.write("$key-mime", selectedMime)
        if (newlySelected) s.vault.remove("$key-review")
        path = file.absolutePath
        mime = selectedMime

        // Photos are never sent on selection: the review screen sends photo, note and details together on "Assess photo".
    }

    LaunchedEffect(key,s.externalCapture.revision) {
        path=s.vault.read(key)?:""
        mime=s.vault.read("$key-mime")?:captureMimeFromExtension(path,kind)
    }
    fun launchExternal(type:ExternalKind){(context as? MainActivity)?.launchCapture(type,kind,purpose)}
    fun startRecording() {
        try{s.prepareRecordingContext(ownerKey);s.recordingConsent=consent;recordingRequested = true; RecordingService.start(context, ownerKey)}
        catch(_:Exception){s.error="Recording could not start because its recovery details could not be saved. Check available device storage and try again."}
    }
    fun microphoneGranted()=ContextCompat.checkSelfPermission(context,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED
    fun notificationsGranted()=android.os.Build.VERSION.SDK_INT<33 || ContextCompat.checkSelfPermission(context,RecordingPermissions.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED
    // Microphone (required) and, once, notifications on Android 13+ (so Stop is in the notification shade). Refusing notifications never blocks recording.
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
        val valid=s.externalCapture.permissionReturned()
        val microphone=microphoneGranted()
        if (RecordingPermissions.mayRecord(microphone) && valid) startRecording() else if(!microphone)s.error = "Microphone permission was denied. You can type a note or add another source."
    }
    fun beginWithPermission(){
        val needed=RecordingPermissions.toRequest(android.os.Build.VERSION.SDK_INT,microphoneGranted(),notificationsGranted(),s.vault.read(RecordingPermissions.NOTIFICATIONS_ASKED_KEY)!=null)
        if(needed.isEmpty())startRecording()
        else if(s.externalCapture.begin(ExternalKind.PERMISSION,kind,purpose))try{
            if(RecordingPermissions.POST_NOTIFICATIONS in needed)runCatching{s.vault.write(RecordingPermissions.NOTIFICATIONS_ASKED_KEY,"1")}
            permission.launch(needed.toTypedArray())
        }catch(_:Exception){s.externalCapture.clear();s.error="Microphone permission could not open."}
    }
    LaunchedEffect(s.autoStartRecording){
        if(audio && s.autoStartRecording){s.autoStartRecording=false;if(consent)beginWithPermission()else consentPrompt=true}
    }
    if(consentPrompt)AlertDialog(onDismissRequest={consentPrompt=false},title={Text("Client consent to record")},text={Text("Ask the client. Tap only if the client agreed to this visit being recorded. A spoken yes cannot confirm consent. You will not be asked again during this visit.")},confirmButton={TextButton({consent=true;s.confirmRecordingConsent("phone_tap");consentPrompt=false;beginWithPermission()}){Text("Client agreed. Record")}},dismissButton={TextButton({consentPrompt=false}){Text("Cancel")}})

    LaunchedEffect(ownerKey, audio) {
        if (audio && path.isNotBlank() && File(path).parentFile?.name == "recordings") {
            if (s.vault.read("recording-owner-${File(path).name}") != ownerKey) {
                path = ""; s.vault.remove(key)
                s.error = "The recovered recording could not be matched to this encounter. Select a recording saved for this encounter below."
            }
        }
    }
    LaunchedEffect(ownerKey, rec.filePath, rec.recording) {
        if (!audio) return@LaunchedEffect
        if (belongsHere && rec.recording) observedRecordingPath = rec.filePath
        if (belongsHere && !rec.recording && rec.filePath != null && rec.filePath == observedRecordingPath) {
            val completed=File(rec.filePath!!)
            observedRecordingPath = null
            try{
                selectFile(completed, "audio/mp4")
                if(!s.loading)s.rememberRecording(completed)
                // A voice message or voice correction is turned into text as soon as recording stops.
                if(purpose in listOf("message","correction") && consent && !s.loading && rec.error==null)s.upload(completed,"audio/mp4","audio")
            }catch(_:Exception){s.error="Audio was captured, but recovery details could not be saved. Keep this screen open and check available device storage."}
        }
        pending = withContext(Dispatchers.IO) {
            RecordingService.pendingRecordings(context).filter { saved ->
                saved.filePath?.let { s.vault.read("recording-owner-${File(it).name}") == ownerKey } == true
            }
        }
    }

    val selectedFile = path.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.isFile }
    val sizeValid = selectedFile == null || selectedFile.length() in 1..(limitMb.toLong() * 1024 * 1024)
    val duration = if (belongsHere && (rec.recording || rec.filePath == path)) rec.elapsedSeconds else pending.firstOrNull { it.filePath == path }?.elapsedSeconds ?: 0
    val idle = !s.loading && !importBusy
    Page {
        InstrumentTitle(when { purpose == "message" -> "Voice message"; purpose == "correction" -> "Fix by Voice"; audio -> "Record a visit"; photo -> "Photo assessment"; else -> "Add a document" })
        if (audio) {
            if (rec.recording && !belongsHere) {
                Text("Another encounter has an active recording. Stop it before starting or playing audio here. The saved file stays with its original encounter.", color = MaterialTheme.colorScheme.error)
                WcpButton("Stop the other recording and save", { RecordingService.stop(context) }, Modifier.fillMaxWidth(), secondary = true)
            }
            Text("${duration / 60}:${(duration % 60).toString().padStart(2, '0')}", fontSize = 56.sp, letterSpacing = (-2).sp, color = MaterialTheme.colorScheme.primary)
            val amplitude = if (belongsHere) rec.amplitude else 0f
            val color = MaterialTheme.colorScheme.primary
            Canvas(Modifier.fillMaxWidth().height(68.dp)) {
                val bars = 45
                for (index in 0 until bars) {
                    val factor = (0.3f + 0.7f * kotlin.math.abs(kotlin.math.sin(index * 1.37))).toFloat()
                    val barHeight = if (belongsHere && rec.recording && !rec.paused) max(3f, size.height * amplitude * factor) else 3f
                    val x = size.width * index / bars
                    drawLine(color, Offset(x, size.height / 2 - barHeight / 2), Offset(x, size.height / 2 + barHeight / 2), 5f)
                }
            }
            StatusTag(when { belongsHere && rec.paused -> "Paused"; belongsHere && rec.recording -> "Recording"; selectedFile != null -> "Saved on this device"; else -> "Ready" })
            if (consent) Text("Consent confirmed for this visit", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            RecorderControl(
                enabled=(belongsHere && rec.recording) || (idle && !rec.recording),
                recording=belongsHere && rec.recording, paused=rec.paused,
                onRecord={ if(consent)beginWithPermission() else consentPrompt=true }, onStop={RecordingService.stop(context)}
            )
            if (rec.recording && belongsHere) TextButton(onClick={if(rec.paused)RecordingService.resume(context)else RecordingService.pause(context)},modifier=Modifier.align(Alignment.CenterHorizontally)) { Text(if(rec.paused)"Resume" else "Pause") }
            if (belongsHere || recordingRequested) rec.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (belongsHere || recordingRequested) rec.notice?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            if (belongsHere && rec.recording) RecordingPermissions.notificationHint(android.os.Build.VERSION.SDK_INT, notificationsGranted())?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            TextButton(onClick={launchExternal(ExternalKind.AUDIO_PICKER)},modifier=Modifier.align(Alignment.CenterHorizontally),enabled=idle&&!rec.recording){Text("Import audio") }
            // Only recordings other than the one in use are listed, e.g. one left over after an interruption.
            val others = pending.filter { it.filePath != path && !(belongsHere && rec.recording && it.filePath == rec.filePath) }
            if (others.isNotEmpty() && !s.loading) {
                SectionLabel("Earlier recordings for this visit")
                others.forEachIndexed { index, saved ->
                    val storedFile = File(saved.filePath!!)
                    val time = localTime(java.time.Instant.ofEpochMilli(storedFile.lastModified()).toString())
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Recording ${index + 1} · $time", style = MaterialTheme.typography.titleSmall)
                        Text("${saved.elapsedSeconds / 60}:${(saved.elapsedSeconds % 60).toString().padStart(2, '0')}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        WcpButton(if (path == saved.filePath) "Selected recording" else "Use this recording", { selectFile(storedFile, "audio/mp4") }, Modifier.fillMaxWidth(), enabled = idle && !rec.recording, secondary = path != saved.filePath)
                        // Sandbox only: the one way to dispose of a recording that is not finished with. Nothing is ever deleted automatically.
                        if (crashSafeBackend() != null) {
                            var confirmDiscard by remember(saved.filePath) { mutableStateOf(false) }
                            TextButton({ confirmDiscard = true }, enabled = idle && !rec.recording) { Text("Discard this recording…") }
                            if (confirmDiscard) AlertDialog(
                                onDismissRequest = { confirmDiscard = false },
                                title = { Text("Discard this recording?") },
                                text = { Text("This recording has not been confirmed as turned into a report. If you discard it, the audio is deleted from this phone and cannot be recovered.") },
                                confirmButton = {
                                    TextButton({
                                        confirmDiscard = false
                                        scope.launch {
                                            val removed = withContext(Dispatchers.IO) { runCatching { RecordingService.clearPending(context, storedFile.absolutePath, true) }.isSuccess }
                                            if (removed) {
                                                s.vault.remove("recording-job-${storedFile.name}"); s.vault.remove("recording-owner-${storedFile.name}")
                                                pending = pending.filter { it.filePath != saved.filePath }
                                                if (path == saved.filePath) { path = ""; s.vault.remove(key) }
                                            } else s.error = "The recording could not be discarded right now. It was kept."
                                        }
                                    }) { Text("Discard permanently") }
                                },
                                dismissButton = { TextButton({ confirmDiscard = false }) { Text("Keep it") } },
                            )
                        }
                    }
                }
            }
        } else {
            if (photo && selectedFile==null) GlassPanel(Modifier.fillMaxWidth()){
                Box(Modifier.fillMaxWidth().height(150.dp),contentAlignment=Alignment.Center){
                    val tint=MaterialTheme.colorScheme.primary
                    Canvas(Modifier.fillMaxSize()){
                        val inset=14.dp.toPx();val arm=24.dp.toPx();val stroke=2.dp.toPx()
                        listOf(Offset(inset,inset) to Offset(1f,1f),Offset(size.width-inset,inset) to Offset(-1f,1f),Offset(inset,size.height-inset) to Offset(1f,-1f),Offset(size.width-inset,size.height-inset) to Offset(-1f,-1f)).forEach{(p,d)->drawLine(tint,p,p+Offset(arm*d.x,0f),stroke);drawLine(tint,p,p+Offset(0f,arm*d.y),stroke)}
                    }
                    InstrumentIcon("camera",Modifier.size(48.dp),tint)
                }
            }
            if (photo && selectedFile == null) WcpButton("Take photograph", {
                launchExternal(ExternalKind.CAMERA)
            }, Modifier.fillMaxWidth(), enabled = idle)
            if (!(photo && selectedFile != null)) {
            WcpButton(if (photo) "Choose an image" else "Choose a document", {
                launchExternal(if(photo)ExternalKind.PHOTO_PICKER else ExternalKind.DOCUMENT_PICKER)
            }, Modifier.fillMaxWidth(), enabled = idle, secondary = photo)
            }
            if (!photo) Text("PDF, text or CSV", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (importBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if(s.externalCapture.canDiscard)TextButton({s.externalCapture.discard()}){Text("Cancel pending selection")}
        if (photo && selectedFile != null) {
            PhotoReviewPanel(s, selectedFile, idle, sizeValid,
                onRetake = { launchExternal(ExternalKind.CAMERA) },
                onAssess = {
                    val batch = s.pendingPhotoFiles()
                    if (batch.isNotEmpty()) s.uploadPhotos(batch) else s.upload(selectedFile, mime, kind)
                })
        } else if (selectedFile != null && (!audio || !rec.recording)) {
            LocalMediaPreview(selectedFile, kind)
            if (kind != "document" && selectedFile.isFile && !(audio && rec.recording)) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val shareMime = if (photo) "image/jpeg" else "audio/mp4"
                TextButton({ s.notice = PhoneSave.save(context, selectedFile, shareMime) }, Modifier.weight(1f), enabled = idle) { Text("Save to phone") }
                TextButton({ PhoneSave.share(context, selectedFile, shareMime, "Share")?.let { s.notice = it } }, Modifier.weight(1f), enabled = idle) { Text("Share") }
            }
            if (!sizeValid) Text("This file is too large to process. Choose another file.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            // A restored selection waits for an explicit action. New photos start
            // automatically in the selection callback; request failures use Retry.
            if (!photo || (idle && s.error == null)) WcpButton(
                when { purpose == "message" -> "Review message"; purpose == "correction" -> "Review correction"; s.quickMode == "document" && audio -> "Create report"; photo -> "Use this photo"; s.quickMode == "consultant" -> "Add to conversation"; audio -> "Add recording"; else -> "Add document" },
                {
                    val batch=if(photo)s.pendingPhotoFiles()else emptyList()
                    if(batch.isNotEmpty())s.uploadPhotos(batch)else s.upload(selectedFile,mime,kind)
                },
                Modifier.fillMaxWidth(), enabled = idle && sizeValid && (!audio || consent),
            )
            if (photo) TextButton({
                s.vault.remove(key); s.vault.remove("$key-mime"); s.vault.remove("$key-review"); path = ""
                s.finishPhotoAssessment()
            }, Modifier.fillMaxWidth(), enabled = idle) { Text("Cancel") }
        }
        if (purpose == "message") TextButton(onClick = { s.cancelVoiceMessage() }, enabled = idle && !rec.recording, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Back to conversation") }
        if (purpose == "correction") TextButton(onClick = { s.cancelVoiceCorrection() }, enabled = idle && !rec.recording, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Keep original report") }
    }
}

@Composable
fun LocalMediaPreview(file: File, kind: String) {
    var expanded by remember(file.absolutePath) { mutableStateOf(false) }
    if (kind == "photo") {
        var previewError by remember(file.absolutePath) { mutableStateOf(false) }
        val bitmap by produceState<ImageBitmap?>(null, file.absolutePath) {
            value = withContext(Dispatchers.IO) { runCatching { displayBitmap(file, 1800) }.getOrNull() }
            previewError = value == null
        }
        if (bitmap != null) {
            Image(bitmap!!, "Captured wound photograph", Modifier.fillMaxWidth().heightIn(max = 320.dp).clickable { expanded = true })
            TextButton(onClick = { expanded = true }) { Text("View photo") }
            if (expanded) androidx.compose.ui.window.Dialog(onDismissRequest = { expanded = false }, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
                val inspection by produceState<ImageBitmap?>(bitmap, file.absolutePath) {
                    value = withContext(Dispatchers.IO) { runCatching { displayBitmap(file, 4096) }.getOrDefault(bitmap) }
                }
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(Modifier.systemBarsPadding()) {
                        var scale by remember { mutableFloatStateOf(1f) }
                        var offset by remember { mutableStateOf(Offset.Zero) }
                        Row(Modifier.horizontalScroll(rememberScrollState())) {
                            TextButton(onClick = { expanded = false }) { Text("Close") }
                            TextButton(onClick = { scale = (scale + .5f).coerceAtMost(5f) }) { Text("Zoom in") }
                            TextButton(onClick = { scale = (scale - .5f).coerceAtLeast(1f); offset = Offset.Zero }) { Text("Zoom out") }
                            TextButton(onClick = { scale = 1f; offset = Offset.Zero }) { Text("Reset") }
                        }
                        Box(Modifier.fillMaxSize().pointerInput(file.absolutePath) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(1f, 5f)
                                val next = offset + pan
                                val horizontal = size.width * (scale - 1) / 2
                                val vertical = size.height * (scale - 1) / 2
                                offset = Offset(next.x.coerceIn(-horizontal, horizontal), next.y.coerceIn(-vertical, vertical))
                            }
                        }) {
                            inspection?.let { Image(it, "Wound photograph with adjustable zoom", Modifier.fillMaxSize().graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y)) }
                        }
                    }
                }
            }
        } else Text(if (previewError) "The preview could not be opened. The original remains saved on this device." else "Opening image preview…", style = MaterialTheme.typography.bodySmall)
    } else if (kind == "audio") {
        var playing by remember(file.absolutePath) { mutableStateOf(false) }
        var preparing by remember(file.absolutePath) { mutableStateOf(false) }
        var playbackError by remember(file.absolutePath) { mutableStateOf<String?>(null) }
        var player by remember(file.absolutePath) { mutableStateOf<MediaPlayer?>(null) }
        val recording by RecordingService.state.collectAsState()
        fun stop() { runCatching { player?.release() }; player = null; playing = false; preparing = false }
        DisposableEffect(file.absolutePath) { onDispose { runCatching { player?.release() } } }
        WcpButton(if (preparing) "Cancel playback" else if (playing) "Stop playback" else "Listen to recording", {
            if (playing || preparing) stop() else {
                try {
                    playbackError = null; preparing = true
                    val created = MediaPlayer(); player = created
                    created.setAudioAttributes(AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).setUsage(AudioAttributes.USAGE_MEDIA).build())
                    val sealed = crashSafeBackend()?.sealedRecording(file) // sandbox: decrypt in memory, nothing is written to disk
                    if (sealed != null) created.setDataSource(sealed.use { it.playbackSource() }) else created.setDataSource(file.absolutePath)
                    created.setOnCompletionListener { stop() }
                    created.setOnErrorListener { _, _, _ -> stop(); playbackError = "Audio playback is unavailable. The original file is retained."; true }
                    created.setOnPreparedListener {
                        if (player === created) runCatching { created.start(); playing = true; preparing = false }
                            .onFailure { stop(); playbackError = "Audio playback was interrupted. Try again." }
                    }
                    created.prepareAsync()
                } catch (_: Exception) { stop(); playbackError = "Audio playback is unavailable. The original file is retained." }
            }
        }, secondary = true, enabled = !recording.recording || playing || preparing)
        if (recording.recording) Text("Finish the active recording before playing audio.", style = MaterialTheme.typography.bodySmall)
        playbackError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    } else Text("Document ready", style = MaterialTheme.typography.bodySmall)
}

private fun displayBitmap(file: File, longestEdge: Int): ImageBitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    val ratio = minOf(1f, longestEdge.toFloat() / max(info.size.width, info.size.height))
    decoder.setTargetSize(max(1, (info.size.width * ratio).toInt()), max(1, (info.size.height * ratio).toInt()))
}.asImageBitmap()

internal fun identifyCapture(file: File, kind: String, reportedMime: String, displayName: String): String {
    val header = file.inputStream().use { input -> ByteArray(16).let { buffer -> val count = input.read(buffer); buffer.copyOf(max(0, count)) } }
    fun starts(vararg bytes: Int) = header.size >= bytes.size && bytes.indices.all { (header[it].toInt() and 255) == bytes[it] }
    fun ascii(from: Int, to: Int) = if (header.size >= to) String(header.copyOfRange(from, to), Charsets.US_ASCII) else ""
    if (kind == "photo") {
        val mime = when {
            starts(255, 216, 255) -> "image/jpeg"
            starts(137, 80, 78, 71, 13, 10, 26, 10) -> "image/png"
            ascii(0, 4) == "RIFF" && ascii(8, 12) == "WEBP" -> "image/webp"
            reportedMime.contains("hei") || displayName.endsWith(".heic", true) || displayName.endsWith(".heif", true) -> error("HEIC and HEIF are not supported yet. Choose an original JPEG, PNG or WebP image; no conversion was made.")
            else -> error("Choose a JPEG, PNG or WebP image. Other formats are not supported.")
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "The image could not be decoded. Choose another original file." }
        require(bounds.outWidth.toLong() * bounds.outHeight <= 25_000_000) { "Choose a photograph of 25 megapixels or fewer. The selected original has not been resized." }
        return mime
    }
    if (kind == "audio") return when {
        starts(26, 69, 223, 163) -> "audio/webm"
        ascii(0, 4) == "OggS" -> "audio/ogg"
        ascii(0, 4) == "RIFF" && ascii(8, 12) == "WAVE" -> "audio/wav"
        ascii(4, 8) == "ftyp" -> "audio/mp4"
        ascii(0, 3) == "ID3" || (header.size >= 2 && (header[0].toInt() and 255) == 255 && (header[1].toInt() and 224) == 224) -> "audio/mpeg"
        else -> error("Choose original M4A, MP3, WAV, Ogg or WebM audio.")
    }
    return when {
        ascii(0, 5) == "%PDF-" -> "application/pdf"
        reportedMime in setOf("text/csv", "application/csv") || displayName.endsWith(".csv", true) -> "text/csv"
        reportedMime == "text/plain" || displayName.endsWith(".txt", true) -> "text/plain"
        else -> error("Choose a PDF, plain-text or CSV document.")
    }
}

internal fun captureExtension(mime: String) = when (mime) {
    "image/jpeg" -> "jpg"; "image/png" -> "png"; "image/webp" -> "webp"
    "audio/webm" -> "webm"; "audio/ogg" -> "ogg"; "audio/wav" -> "wav"; "audio/mpeg" -> "mp3"; "audio/mp4" -> "m4a"
    "application/pdf" -> "pdf"; "text/csv" -> "csv"; else -> "txt"
}

private fun captureMimeFromExtension(path: String, kind: String) = when (File(path).extension.lowercase(Locale.ROOT)) {
    "jpg", "jpeg" -> "image/jpeg"; "png" -> "image/png"; "webp" -> "image/webp"
    "webm" -> "audio/webm"; "ogg", "opus" -> "audio/ogg"; "wav" -> "audio/wav"; "mp3" -> "audio/mpeg"; "m4a", "mp4" -> "audio/mp4"
    "csv" -> "text/csv"; "txt" -> "text/plain"; "pdf" -> "application/pdf"
    else -> if (kind == "audio") "audio/mp4" else if (kind == "photo") "image/jpeg" else "application/pdf"
}


/**
 * After a photo: one simple review screen. Photo with a small Retake, an optional note (type or dictate), optional
 * wound details folded away, and one large "Assess photo". Nothing is sent until that button is tapped.
 */
@Composable
private fun PhotoReviewPanel(s: AppState, file: File, idle: Boolean, sizeValid: Boolean, onRetake: () -> Unit, onAssess: () -> Unit) {
    LaunchedEffect(s.encounterId) { s.loadPhotoNote() }
    var open by rememberSaveable(s.encounterId) { mutableStateOf(false) }
    val details = s.photoDetails
    Box(Modifier.fillMaxWidth()) {
        LocalMediaPreview(file, "photo")
        OutlinedButton(onRetake, Modifier.align(Alignment.BottomEnd).padding(12.dp), enabled = idle,
            colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.Black.copy(alpha = 0.45f), contentColor = Color.White)) {
            Text("↻  Retake")
        }
    }
    if (!sizeValid) Text("This file is too large to process. Choose another file.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text("Add a note", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    OutlinedTextField(s.photoNote, { s.updatePhotoNote(it) }, Modifier.fillMaxWidth(), enabled = idle, minLines = 3,
        placeholder = { Text("Type or speak a quick note…") },
        trailingIcon = {
            FilledIconButton({ s.recordPhotoNote() }, enabled = idle, modifier = Modifier.size(52.dp)) {
                InstrumentIcon("voice", Modifier.size(26.dp), MaterialTheme.colorScheme.onPrimary)
            }
        })
    OutlinedButton({ open = !open }, Modifier.fillMaxWidth().heightIn(min = 56.dp), enabled = idle) {
        Text("+", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.width(12.dp))
        Text(if (details.selectedCount() > 0) "Wound details · ${details.selectedCount()}" else "Add wound details",
            Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        Text(if (open) "⌃" else "⌄", style = MaterialTheme.typography.titleLarge)
    }
    if (!open) Text("Wound bed, surrounding skin, drainage, size", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp))
    if (open) WoundDetailsChoices(details, idle) { s.updatePhotoDetails(it) }
    WcpButton("Assess photo", onAssess, Modifier.fillMaxWidth().heightIn(min = 60.dp), enabled = idle && sizeValid && !details.hasInvalidMeasurement())
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WoundDetailsChoices(details: PhotoDetails, enabled: Boolean, onChange: (PhotoDetails) -> Unit) {
    @Composable fun Group(title: String, content: @Composable () -> Unit) {
        Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
    Group("Wound type") {
        PhotoDetails.WOUND_TYPE.forEach { (key, label) ->
            FilterChip(details.woundType == key, { onChange(details.copy(woundType = if (details.woundType == key) null else key,
                pressureStage = if (key == "pressure_injury" && details.woundType != key) details.pressureStage else null)) }, { Text(label) }, enabled = enabled)
        }
    }
    if (details.woundType == "pressure_injury") Group("Stage") {
        PhotoDetails.PRESSURE_STAGE.forEach { (key, label) ->
            FilterChip(details.pressureStage == key, { onChange(details.copy(pressureStage = if (details.pressureStage == key) null else key)) }, { Text(label) }, enabled = enabled)
        }
    }
    Group("Wound bed") {
        PhotoDetails.WOUND_BED.forEach { (key, label) ->
            FilterChip(key in details.woundBed, { onChange(details.copy(woundBed = details.woundBed.toggle(key))) }, { Text(label) }, enabled = enabled)
        }
    }
    Group("Wound edges") {
        PhotoDetails.WOUND_EDGES.forEach { (key, label) ->
            FilterChip(key in details.woundEdges, { onChange(details.copy(woundEdges = PhotoDetails.toggleEdge(details.woundEdges, key))) }, { Text(label) }, enabled = enabled)
        }
    }
    Group("Periwound") {
        PhotoDetails.SURROUNDING_SKIN.forEach { (key, label) ->
            FilterChip(key in details.surroundingSkin, { onChange(details.copy(surroundingSkin = details.surroundingSkin.toggle(key))) }, { Text(label) }, enabled = enabled)
        }
    }
    Group("Drainage amount") {
        PhotoDetails.DRAINAGE_AMOUNT.forEach { (key, label) ->
            FilterChip(details.drainageAmount == key, { onChange(details.copy(drainageAmount = if (details.drainageAmount == key) null else key)) }, { Text(label) }, enabled = enabled)
        }
    }
    Group("Drainage type") {
        PhotoDetails.DRAINAGE_TYPE.forEach { (key, label) ->
            FilterChip(details.drainageType == key, { onChange(details.copy(drainageType = if (details.drainageType == key) null else key)) }, { Text(label) }, enabled = enabled)
        }
    }
    Group("Local signs of infection") {
        PhotoDetails.INFECTION_SIGNS.forEach { (key, label) ->
            FilterChip(key in details.infectionSigns, { onChange(details.copy(infectionSigns = PhotoDetails.toggleInfection(details.infectionSigns, key))) }, { Text(label) }, enabled = enabled)
        }
    }
    Group("Pain (0–10)") {
        (0..10).forEach { score ->
            FilterChip(details.painScore == score, { onChange(details.copy(painScore = if (details.painScore == score) null else score)) }, { Text("$score") }, enabled = enabled)
        }
    }
    Text("Size (cm)", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(Triple("Length", details.lengthCm) { v: String -> details.copy(lengthCm = v) },
               Triple("Width", details.widthCm) { v: String -> details.copy(widthCm = v) },
               Triple("Depth", details.depthCm) { v: String -> details.copy(depthCm = v) }).forEach { (label, value, update) ->
            OutlinedTextField(value, { onChange(update(it.take(6))) }, Modifier.weight(1f), enabled = enabled, singleLine = true,
                label = { Text(label) }, isError = details.invalidMeasurement(value),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        }
    }
    if (listOf(details.lengthCm, details.widthCm, details.depthCm).any { details.invalidMeasurement(it) })
        Text("Enter a size above 0 and up to 100 cm, or leave it blank.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    // Optional healing progress: earlier length x width compared with today's. Calculated in the app, never by the AI.
    Text("Healing progress", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(Triple("Earlier length", details.previousLengthCm) { v: String -> details.copy(previousLengthCm = v.take(6)) },
               Triple("Earlier width", details.previousWidthCm) { v: String -> details.copy(previousWidthCm = v.take(6)) },
               Triple("Weeks ago", details.previousWeeksAgo) { v: String -> details.copy(previousWeeksAgo = v.filter { it.isDigit() }.take(3)) }).forEach { (label, value, update) ->
            OutlinedTextField(value, { onChange(update(it)) }, Modifier.weight(1f), enabled = enabled, singleLine = true,
                label = { Text(label) }, isError = if (label == "Weeks ago") details.invalidWeeks() else details.invalidMeasurement(value),
                keyboardOptions = KeyboardOptions(keyboardType = if (label == "Weeks ago") KeyboardType.Number else KeyboardType.Decimal))
        }
    }
    val change = details.areaChangePercent()
    when {
        change != null -> Text(when {
            change > 0 -> "$change% smaller in area"
            change < 0 -> "${-change}% larger in area"
            else -> "No change in area"
        }, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
        details.progressIncomplete() -> Text("Enter today's and the earlier length and width.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}

private fun Set<String>.toggle(key: String) = if (key in this) this - key else this + key
