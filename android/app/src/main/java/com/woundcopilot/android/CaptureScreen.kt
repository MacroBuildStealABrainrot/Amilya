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
        val currentOwner = "${s.user?.str("id")}:${s.encounterId}${if (purpose in listOf("message", "correction")) ":$purpose" else ""}"
        if (photo && submitNewPhoto && newlySelected && currentOwner == ownerKey && !s.loading) {
            s.upload(file, selectedMime, "photo")
        }
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
    if(consentPrompt)AlertDialog(onDismissRequest={consentPrompt=false},title={Text("Consent to record")},text={Text("Do you have consent to record? You will not be asked again during this visit.")},confirmButton={TextButton({consent=true;s.recordingConsent=true;consentPrompt=false;beginWithPermission()}){Text("Yes, record")}},dismissButton={TextButton({consentPrompt=false}){Text("Cancel")}})

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
            if (photo) WcpButton(if (selectedFile == null) "Take photograph" else "Retake", {
                launchExternal(ExternalKind.CAMERA)
            }, Modifier.fillMaxWidth(), enabled = idle)
            WcpButton(if (photo) "Choose an image" else "Choose a document", {
                launchExternal(if(photo)ExternalKind.PHOTO_PICKER else ExternalKind.DOCUMENT_PICKER)
            }, Modifier.fillMaxWidth(), enabled = idle, secondary = photo)
            if (!photo) Text("PDF, text or CSV", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (importBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if(s.externalCapture.canDiscard)TextButton({s.externalCapture.discard()}){Text("Cancel pending selection")}
        if (selectedFile != null && (!audio || !rec.recording)) {
            LocalMediaPreview(selectedFile, kind)
            if (!sizeValid) Text("This file is too large to process. Choose another file.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            // A restored selection waits for an explicit action. New photos start
            // automatically in the selection callback; request failures use Retry.
            if (!photo || (idle && s.error == null)) WcpButton(
                when { purpose == "message" -> "Review message"; purpose == "correction" -> "Review correction"; s.quickMode == "document" && audio -> "Create report"; photo -> "Analyze photo"; s.quickMode == "consultant" -> "Add to conversation"; audio -> "Add recording"; else -> "Add document" },
                {
                    val batch=if(photo)s.pendingPhotoFiles()else emptyList()
                    if(batch.isNotEmpty())s.uploadPhotos(batch)else s.upload(selectedFile,mime,kind)
                },
                Modifier.fillMaxWidth(), enabled = idle && sizeValid && (!audio || consent),
            )
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
