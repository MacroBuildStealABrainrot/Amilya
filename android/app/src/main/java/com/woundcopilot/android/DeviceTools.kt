package com.woundcopilot.android

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AtomicFile
import androidx.core.content.FileProvider
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.sqrt

/**
 * Seam for the experimental crash-resistant recorder. Only the sandbox build contains an implementation.
 * In debug and release builds the flag is false, the lookup returns null and the existing recorder is used unchanged.
 */
internal interface CrashSafeBackend {
    fun start(context: Context, ownerKey: String)
    fun control(context: Context, action: String)
    fun isActive(): Boolean
    fun activeFileName(): String?
    /** Turn any unfinished safe sessions (app killed mid-visit) into normal .m4a recordings with sidecars. */
    fun recoverOrphans(context: Context)
    /** Remove the raw segment folder that belongs to this finished file. Never touches the file itself. */
    fun discard(context: Context, finalFile: File)
    /** Sandbox only: true when [finalFile] is a placeholder for an encrypted recording that can be opened (no plain .m4a exists). */
    fun isSealedRecording(finalFile: File): Boolean
    /** Sandbox only: opens the encrypted recording behind a placeholder, or null for an ordinary file. The caller must close it. */
    fun sealedRecording(finalFile: File): SealedRecordingHandle?
    /**
     * Every recording of the same visit, in recording order, by file name (a visit longer than about an hour is recorded in parts).
     * An ordinary recording is a visit of one part.
     */
    fun visitParts(finalFile: File): List<String> = listOf(finalFile.name)
}

/**
 * Sandbox only. An encrypted recording. Sound is decrypted in memory when asked for and overwritten by [close]; no plain audio file
 * is ever written. Parts are the same 2-minute .m4a pieces the app has always uploaded, and the same recording always gives the
 * same bytes for the same part, so a retried upload is recognised by the server.
 */
internal interface SealedRecordingHandle : java.io.Closeable {
    val partCount: Int
    fun part(index: Int): ByteArray
    /** Milliseconds at the start of part [index] that repeat the end of the part before (sandbox 3-second overlap). */
    fun overlapMillis(index: Int): Long = 0L
    fun playbackSource(): android.media.MediaDataSource
}

internal fun crashSafeBackend(): CrashSafeBackend? =
    if (BuildConfig.CRASH_SAFE_RECORDING) {
        runCatching { Class.forName("com.woundcopilot.android.CrashSafeBackendImpl").getField("INSTANCE").get(null) as CrashSafeBackend }.getOrNull()
    } else null

data class RecordingState(
    val recording: Boolean = false,
    val paused: Boolean = false,
    val elapsedSeconds: Int = 0,
    val amplitude: Float = 0f,
    val filePath: String? = null,
    val error: String? = null,
    /** Sandbox: a calm, non-error message such as "recording will continue in a second recording". Null everywhere else. */
    val notice: String? = null,
)

/** Owns the microphone independently of the activity, and never restarts recording on process death. */
class RecordingService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var completedMillis = 0L
    private var activeSince = 0L
    private var lastPersistedSecond = -1
    private var finishing = false
    private var micInterruption = MicInterruption()

    private val ticker = object : Runnable {
        override fun run() {
            if (recorder == null || finishing) return
            val current = state.value
            val elapsed = elapsedMillis()
            val level = if (current.paused) 0f else {
                runCatching { sqrt(recorder!!.maxAmplitude.coerceIn(0, 32767) / 32767f) }
                    .getOrDefault(0f)
            }
            state.value = current.copy(elapsedSeconds = (elapsed / 1000).toInt(), amplitude = level)
            if (state.value.elapsedSeconds != lastPersistedSecond) {
                lastPersistedSecond = state.value.elapsedSeconds
                // Android 10+: a phone call or another app can silence this recording without any error. Say so on screen.
                val silenced = !current.paused && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                    runCatching { recorder?.activeRecordingConfiguration?.isClientSilenced == true }.getOrDefault(false)
                val micNotice = micInterruption.update(silenced, elapsed)
                if (micNotice != state.value.notice) state.value = state.value.copy(notice = micNotice)
                persist(if (current.paused) "paused" else "recording", elapsed)
                updateNotification()
            }
            handler.postDelayed(this, 100)
        }
    }

    override fun onCreate() {
        super.onCreate()
        liveService = this
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Recording", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Microphone recording controls"
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            },
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> beginRecording(intent.getStringExtra(EXTRA_OWNER).orEmpty())
            ACTION_PAUSE -> pauseRecording()
            ACTION_RESUME -> resumeRecording()
            ACTION_STOP -> finishRecording()
            else -> if (recorder == null) stopSelf()
        }
        // Restarting a killed microphone service must never silently resume capture.
        return START_NOT_STICKY
    }

    private fun beginRecording(ownerKey: String) {
        if (recorder != null) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            state.value = RecordingState(error = "Microphone permission is required to record.")
            stopSelf()
            return
        }
        // Android requires startForeground() within seconds of startForegroundService(). Do it first, before any
        // storage or vault work that could fail, so every later error path is a legal foreground stop.
        try {
            val first = notification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(NOTIFICATION_ID, first, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIFICATION_ID, first)
            }
        } catch (error: Exception) {
            state.value = RecordingState(error = "The microphone could not start. Keep WoundCoPilot open and try again.")
            stopSelf()
            return
        }
        finishing = false
        completedMillis = 0L
        activeSince = 0L
        lastPersistedSecond = -1
        micInterruption = MicInterruption()
        try {
            val directory = recordingsDirectory(this)
            outputFile = File(directory, "recording-${System.currentTimeMillis()}-${UUID.randomUUID()}.m4a")
            // Ownership is committed before any state exposes this file, never inferred from a later screen.
            if (ownerKey.isNotBlank()) Vault(this).write("recording-owner-${outputFile!!.name}", ownerKey)
            ownerKey.split(':',limit=2).takeIf{it.size==2}?.let{DeveloperDiagnostics.bind(Vault(this),it[0],it[1],outputFile)}
            Vault(this).read("recording-context-$ownerKey")?.let{raw->
                val capture=JSONObject(raw)
                RecordingRecovery(this).remember(outputFile!!,capture.str("owner"),capture.getJSONObject("case"),capture.str("template","nursing"))
                RecordingRecovery(this).stage(outputFile!!,"RECORDING")
            }
            state.value = RecordingState(filePath = outputFile!!.absolutePath)
            updateNotification()
            @Suppress("DEPRECATION")
            val created = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else MediaRecorder()
            recorder = created
            created.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(32_000)
                setAudioSamplingRate(24_000)
                setAudioChannels(1)
                setOutputFile(outputFile!!.absolutePath)
                setOnErrorListener { _, _, _ ->
                    handler.post { finishRecording("Recording was interrupted by the microphone. Review the saved audio before uploading.") }
                }
                // The current backend accepts 25 MiB and 3,600 seconds; leave room for MP4 finalization.
                setMaxFileSize(24L * 1024L * 1024L)
                setMaxDuration(3_600_000)
                setOnInfoListener { _, what, _ ->
                    if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED ||
                        what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED
                    ) {
                        handler.post { finishRecording("The recording limit was reached. The saved audio is ready; start another recording to continue.") }
                    }
                }
                prepare()
                persist("starting", 0L)
                start()
            }
            activeSince = SystemClock.elapsedRealtime()
            state.value = state.value.copy(recording = true, paused = false, error = null)
            DeveloperDiagnostics.emit("recording_captured")
            persist("recording", 0L)
            handler.post(ticker)
        } catch (error: Exception) {
            val message = when (error) {
                is SecurityException -> "Microphone access was denied. Open the app and allow microphone access before recording."
                else -> "The microphone could not start. Check that another app is not using it and try again."
            }
            finishRecording(message)
        }
    }

    private fun pauseRecording() {
        val active = recorder ?: return stopSelf()
        if (state.value.paused || finishing) return
        try {
            active.pause()
            completedMillis += SystemClock.elapsedRealtime() - activeSince
            activeSince = 0L
            state.value = state.value.copy(paused = true, amplitude = 0f, elapsedSeconds = (completedMillis / 1000).toInt())
            persist("paused", completedMillis)
            updateNotification()
        } catch (_: Exception) {
            finishRecording("Recording could not pause. The captured audio has been stopped; review it before uploading.")
        }
    }

    private fun resumeRecording() {
        val active = recorder ?: return stopSelf()
        if (!state.value.paused || finishing) return
        try {
            active.resume()
            activeSince = SystemClock.elapsedRealtime()
            state.value = state.value.copy(paused = false, error = null)
            persist("recording", completedMillis)
            updateNotification()
        } catch (_: Exception) {
            finishRecording("Recording could not resume. Review the saved audio and start another recording to continue.")
        }
    }

    private fun elapsedMillis(): Long = completedMillis +
        if (activeSince > 0L && !state.value.paused) SystemClock.elapsedRealtime() - activeSince else 0L

    private fun finishRecording(message: String? = null) {
        if (finishing) return
        val finalizationStarted=Performance.now()
        DeveloperDiagnostics.emit("recording_stopped")
        Performance.start("stop_to_report")
        finishing = true
        handler.removeCallbacks(ticker)
        val elapsed = elapsedMillis()
        val active = recorder
        recorder = null
        var stopFailed = false
        if (active != null) {
            active.setOnErrorListener(null)
            active.setOnInfoListener(null)
            try { active.stop() } catch (_: Exception) { stopFailed = true }
            runCatching { active.reset() }
            runCatching { active.release() }
        }
        val file = outputFile
        val valid = file?.let(::readableAudio) == true
        val error = when {
            valid -> message
            active != null || file != null -> message ?: if (stopFailed) {
                "The recording was too short or was interrupted before audio could be saved. Please record again."
            } else "No playable audio was captured. Please record again."
            else -> message
        }
        state.value = RecordingState(
            elapsedSeconds = (elapsed / 1000).toInt(),
            filePath = if (valid) file!!.absolutePath else null,
            error = error,
            notice = micInterruption.summary(elapsed),
        )
        if (file != null) persist(if (valid) "ready" else "interrupted", elapsed, error)
        if(file!=null)runCatching{RecordingRecovery(this).stage(file,if(valid)"RECORDED_LOCALLY"else "RECORDING_FINALIZATION_FAILED")}.onFailure{
            Performance.record("recovery_state",finalizationStarted,"LOCAL_STATE_FAILED")
            state.value=state.value.copy(error=if(valid)"Audio was captured, but its recovery details could not be saved. Keep this screen open and retry when device storage is available."else state.value.error)
        }
        Performance.record("audio_finalization",finalizationStarted,if(valid)"ok"else "RECORDING_FINALIZATION_FAILED")
        DeveloperDiagnostics.emit(if(valid)"upload_pending"else "recording_unavailable",status=if(valid)"pending"else "failed")
        activeSince = 0L
        completedMillis = elapsed
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        // Android does not promise onDestroy after a kill. Sidecars provide honest recovery in that case.
        if (recorder != null && !finishing) finishRecording("Recording stopped when the service closed. Review the saved audio before uploading.")
        handler.removeCallbacksAndMessages(null)
        if (liveService === this) {
            liveService = null
            startRequested = false
        }
        super.onDestroy()
    }

    private fun persist(status: String, elapsed: Long, error: String? = null) {
        val file = outputFile ?: return
        writeMetadata(file, status, elapsed, error)
    }

    private fun updateNotification() {
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification()) }
    }

    private fun notification(): Notification {
        val current = state.value
        val action = if (current.paused) ACTION_RESUME else ACTION_PAUSE
        val label = if (current.paused) "Resume" else "Pause"
        val elapsed = current.elapsedSeconds
        val duration = "%d:%02d".format(elapsed / 60, elapsed % 60)
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(if (current.paused) "Recording paused" else "Recording audio")
            .setContentText("WoundCoPilot · $duration")
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .addAction(Notification.Action.Builder(null, label, actionIntent(action)).build())
            .addAction(Notification.Action.Builder(null, "Stop and save", actionIntent(ACTION_STOP)).build())
        packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
            builder.setContentIntent(PendingIntent.getActivity(this, 30, launch,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        }
        return builder.build()
    }

    private fun actionIntent(action: String): PendingIntent = PendingIntent.getService(
        this, action.hashCode(), Intent(this, RecordingService::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        val state = MutableStateFlow(RecordingState())
        private const val CHANNEL = "woundcopilot_recording"
        private const val NOTIFICATION_ID = 4041
        internal const val ACTION_START = "com.woundcopilot.android.recording.START"
        internal const val ACTION_PAUSE = "com.woundcopilot.android.recording.PAUSE"
        internal const val ACTION_RESUME = "com.woundcopilot.android.recording.RESUME"
        internal const val ACTION_STOP = "com.woundcopilot.android.recording.STOP"
        internal const val EXTRA_OWNER = "com.woundcopilot.android.recording.OWNER"
        @Volatile private var liveService: RecordingService? = null
        @Volatile private var startRequested = false

        /** Test seam: instrumentation replaces this to simulate a revoked permission without killing the test process. */
        @Volatile internal var microphoneGranted: (Context) -> Boolean = { context ->
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        }

        @JvmStatic @JvmOverloads fun start(context: Context, ownerKey: String = "") {
            // Experimental crash-resistant recorder: exists only in the sandbox build. Everywhere else this is skipped.
            crashSafeBackend()?.let { backend ->
                if (backend.isActive()) return
                if (!microphoneGranted(context)) { state.value = RecordingState(error = "Microphone permission is required to record."); return }
                backend.start(context, ownerKey)
                return
            }
            if (liveService?.recorder != null || startRequested) return
            // Never launch a microphone foreground service that cannot finish starting.
            if (!microphoneGranted(context)) {
                state.value = RecordingState(error = "Microphone permission is required to record.")
                return
            }
            startRequested = true
            state.value = RecordingState()
            try {
                context.startForegroundService(Intent(context, RecordingService::class.java).setAction(ACTION_START).putExtra(EXTRA_OWNER, ownerKey))
            } catch (_: Exception) {
                startRequested = false
                state.value = RecordingState(error = "Recording could not start. Keep WoundCoPilot open and allow microphone access, then try again.")
            }
        }

        @JvmStatic fun pause(context: Context) = sendControl(context, ACTION_PAUSE)
        @JvmStatic fun resume(context: Context) = sendControl(context, ACTION_RESUME)
        @JvmStatic fun stop(context: Context) = sendControl(context, ACTION_STOP)

        private fun sendControl(context: Context, action: String) {
            crashSafeBackend()?.takeIf { it.isActive() }?.let { it.control(context, action); return }
            if (liveService == null && !startRequested) {
                restorePending(context)
                return
            }
            try {
                context.startService(Intent(context, RecordingService::class.java).setAction(action))
            } catch (_: Exception) {
                state.value = state.value.copy(error = "The recording control could not be delivered. Use the recording notification to stop or pause.")
            }
        }

        /** Call at app startup. Only finalized, readable audio is returned as a usable file path. */
        @JvmStatic fun restorePending(context: Context): RecordingState? {
            if (liveService?.recorder != null || startRequested || crashSafeBackend()?.isActive() == true) return state.value
            val restored = pendingRecordings(context).firstOrNull() ?: return null
            state.value = restored
            return restored
        }

        /** Each capture keeps its own sidecar, so a new recording cannot erase an earlier pending one. */
        @JvmStatic fun pendingRecordings(context: Context): List<RecordingState> {
            crashSafeBackend()?.recoverOrphans(context)
            val directory = recordingsDirectory(context)
            return directory.listFiles { file -> file.isFile && file.name.endsWith(".m4a.json") }
                .orEmpty().filter { metadata ->
                    !state.value.recording || metadata.name != (liveService?.outputFile?.name ?: crashSafeBackend()?.activeFileName()) + ".json"
                }.sortedByDescending {
                    it.name.substringAfter("recording-").substringBefore('-').toLongOrNull() ?: it.lastModified()
                }.mapNotNull { metadata ->
                    runCatching {
                        val data = JSONObject(AtomicFile(metadata).openRead().bufferedReader().use { it.readText() })
                        val candidate = File(directory, data.getString("filename"))
                        if (candidate.canonicalFile.parentFile != directory.canonicalFile || candidate.extension != "m4a") return@runCatching null
                        val readable = readableAudio(candidate)
                        val status = data.optString("status")
                        val interrupted = status != "ready"
                        val savedError = data.optString("error").takeIf { it.isNotBlank() && it != "null" }
                        val recoveryError = when {
                            !readable -> "An interrupted recording could not be finalized. The original file is retained, but cannot be uploaded as playable audio."
                            interrupted -> "Recording was interrupted. The saved audio is available; review it before uploading."
                            else -> savedError
                        }
                        if (interrupted) writeMetadata(candidate, if (readable) "ready" else "interrupted", data.optLong("elapsedMillis"), recoveryError)
                        RecordingState(
                            elapsedSeconds = (data.optLong("elapsedMillis") / 1000).toInt(),
                            filePath = if (readable) candidate.absolutePath else null,
                            error = recoveryError,
                        )
                    }.getOrNull()
                }
        }

        /** Acknowledge only after upload succeeds or the user explicitly discards a saved capture. */
        @JvmStatic fun clearPending(context: Context, filePath: String, deleteFile: Boolean = false) {
            val directory = recordingsDirectory(context).canonicalFile
            val file = File(filePath).canonicalFile
            require(file.parentFile == directory && file.extension == "m4a") { "Invalid recording path" }
            check(liveService?.outputFile?.canonicalFile != file || liveService?.recorder == null) { "Recording is still active" }
            AtomicFile(File(file.absolutePath + ".json")).delete()
            crashSafeBackend()?.discard(context, file)
            if (deleteFile) file.delete()
            if (!state.value.recording && state.value.filePath?.let { File(it).canonicalFile } == file) state.value = RecordingState()
        }

        internal fun recordingsDirectory(context: Context) = File(context.filesDir, "recordings").apply {
            check(exists() || mkdirs()) { "Recording storage is unavailable" }
        }

        internal fun writeMetadata(file: File, status: String, elapsed: Long, error: String?) {
            val atomic = AtomicFile(File(file.absolutePath + ".json"))
            var stream: FileOutputStream? = null
            try {
                stream = atomic.startWrite()
                stream.write(JSONObject().apply {
                    put("filename", file.name)
                    put("status", status)
                    put("elapsedMillis", elapsed)
                    put("updatedAt", System.currentTimeMillis())
                    put("error", error ?: JSONObject.NULL)
                }.toString().toByteArray(Charsets.UTF_8))
                atomic.finishWrite(stream)
            } catch (_: Exception) {
                if (stream != null) atomic.failWrite(stream)
                // Keep a healthy recording running even if recovery metadata cannot be updated.
            }
        }

        internal fun readableAudio(file: File): Boolean {
            if (crashSafeBackend()?.isSealedRecording(file) == true) return true // sandbox: encrypted recording behind a placeholder
            if (!file.isFile || file.length() < 128L) return false
            val retriever = MediaMetadataRetriever()
            return try {
                retriever.setDataSource(file.absolutePath)
                (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) > 0L
            } catch (_: Exception) {
                false
            } finally {
                runCatching { retriever.release() }
            }
        }
    }
}

/** Creates private, shareable exports. The caller grants temporary read access on its share intent. */
object ExportTools {
    private val AUTHORITY = BuildConfig.APPLICATION_ID + ".files"
    private const val MAX_EXPORT_AGE_MILLIS = 24L * 60L * 60L * 1000L

    fun createText(context: Context, text: String, title: String): Uri {
        val file = exportFile(context, title, "txt")
        return try {
            file.writeText(text, Charsets.UTF_8)
            FileProvider.getUriForFile(context, AUTHORITY, file)
        } catch (error: Exception) {
            file.delete()
            throw error
        }
    }

    /** Uses Android's text shaper for Unicode, RTL, and word wrapping; pages break only between lines. */
    fun createPdf(context: Context, text: String, title: String): Uri {
        val file = exportFile(context, title, "pdf")
        val document = PdfDocument()
        try {
            val width = 595
            val height = 842
            val margin = 48
            val contentWidth = width - margin * 2
            val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = 11f
                color = Color.rgb(29, 40, 50)
                typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            }
            val smallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = 8f
                color = Color.rgb(87, 98, 108)
                typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            }
            val body = text.replace("\r\n", "\n").replace('\r', '\n').ifEmpty { " " }
            val layout = StaticLayout.Builder.obtain(body, 0, body.length, bodyPaint, contentWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false)
                .setLineSpacing(2f, 1.12f)
                .apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        setBreakStrategy(android.graphics.text.LineBreaker.BREAK_STRATEGY_HIGH_QUALITY)
                        setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                    }
                }
                .build()
            var firstLine = 0
            var pageNumber = 1
            while (firstLine < layout.lineCount) {
                val page = document.startPage(PdfDocument.PageInfo.Builder(width, height, pageNumber).create())
                val canvas = page.canvas
                canvas.drawColor(Color.WHITE)
                val bodyTop = margin
                val bodyBottom = height - 61
                val firstTop = layout.getLineTop(firstLine)
                var endLine = firstLine
                while (endLine + 1 < layout.lineCount &&
                    layout.getLineBottom(endLine + 1) - firstTop <= bodyBottom - bodyTop
                ) endLine++
                val renderedHeight = layout.getLineBottom(endLine) - firstTop
                canvas.save()
                canvas.clipRect(margin, bodyTop, width - margin, bodyTop + renderedHeight)
                canvas.translate(margin.toFloat(), (bodyTop - firstTop).toFloat())
                layout.draw(canvas)
                canvas.restore()
                val rule = Paint().apply { color = Color.rgb(218, 224, 228); strokeWidth = 0.5f }
                canvas.drawLine(margin.toFloat(), (height - 43).toFloat(), (width - margin).toFloat(), (height - 43).toFloat(), rule)
                val number = pageNumber.toString()
                canvas.drawText(number, width - margin - smallPaint.measureText(number), (height - 27).toFloat(), smallPaint)
                document.finishPage(page)
                firstLine = endLine + 1
                pageNumber++
            }
            FileOutputStream(file).use { document.writeTo(it) }
            return FileProvider.getUriForFile(context, AUTHORITY, file)
        } catch (error: Exception) {
            file.delete()
            throw error
        } finally {
            document.close()
        }
    }

    private fun exportFile(context: Context, title: String, extension: String): File {
        val directory = File(context.cacheDir, "exports").apply {
            check(exists() || mkdirs()) { "Export storage is unavailable" }
        }
        val cutoff = System.currentTimeMillis() - MAX_EXPORT_AGE_MILLIS
        directory.listFiles()?.forEach { prior ->
            if (prior.isFile && prior.lastModified() < cutoff && prior.extension in setOf("txt", "pdf")) prior.delete()
        }
        val local=java.time.ZonedDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"))
        return File(directory,"WoundCoPilot-$local-${UUID.randomUUID().toString().take(8)}.$extension")
    }
}
