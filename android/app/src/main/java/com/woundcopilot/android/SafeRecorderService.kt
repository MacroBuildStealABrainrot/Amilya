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
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaDataSource
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlin.math.abs

/**
 * EXPERIMENTAL, sandbox build only. Records microphone audio as many small, independent AAC pieces instead of one
 * MP4 file whose index is written only at the very end. If Android kills the app, every piece already written is
 * still playable and recovery rebuilds the normal .m4a that the rest of the app already understands.
 *
 * The original MediaRecorder-based RecordingService is not changed and remains the recorder in debug and release builds.
 * This file has NOT been compiled against the Android SDK in the environment that produced it.
 */
internal object CrashSafeBackendImpl : CrashSafeBackend {
    const val SAMPLE_RATE = 24_000
    const val CHANNELS = 1

    override fun start(context: Context, ownerKey: String) {
        SafeRecorderService.starting = true
        try {
            context.startForegroundService(
                Intent(context, SafeRecorderService::class.java)
                    .setAction(RecordingService.ACTION_START)
                    .putExtra(RecordingService.EXTRA_OWNER, ownerKey),
            )
        } catch (_: Exception) {
            SafeRecorderService.starting = false
            RecordingService.state.value = RecordingState(error = "Recording could not start. Keep WoundCoPilot open and allow microphone access, then try again.")
        }
    }

    override fun control(context: Context, action: String) {
        try {
            context.startService(Intent(context, SafeRecorderService::class.java).setAction(action))
        } catch (_: Exception) {
            RecordingService.state.value = RecordingService.state.value.copy(error = "The recording control could not be delivered. Use the recording notification to stop or pause.")
        }
    }

    override fun isActive(): Boolean = SafeRecorderService.starting || SafeRecorderService.live?.isCapturing() == true

    override fun activeFileName(): String? = SafeRecorderService.live?.finalName

    /**
     * Finds sessions left behind by a killed app (status not "finished") and turns what was saved into a normal
     * recording. The raw pieces stay on disk until the finished file is verified, and are only deleted by [discard].
     */
    @Synchronized
    override fun recoverOrphans(context: Context) {
        val root = RecordingService.recordingsDirectory(context)
        val live = SafeRecorderService.live?.takeIf { it.isCapturing() }?.sessionDirectory
        root.listFiles { f -> f.isDirectory && f.name.startsWith(RetentionRules.SESSION_PREFIX) }.orEmpty().forEach { dir ->
            if (live != null && dir.canonicalFile == live.canonicalFile) return@forEach
            if (SessionInfo(dir).read()?.get("status") == "finished") return@forEach // already rebuilt; nothing to do
            runCatching { consolidate(context, dir, interrupted = true) }
        }
    }

    override fun discard(context: Context, finalFile: File) {
        val root = RecordingService.recordingsDirectory(context)
        root.listFiles { f -> f.isDirectory && f.name.startsWith(RetentionRules.SESSION_PREFIX) }.orEmpty().forEach { dir ->
            val info = SessionInfo(dir).read() ?: return@forEach
            if (info["finalName"] == finalFile.name) {
                dir.deleteRecursively()
                if (SealedPlaceholder.isPlaceholder(finalFile)) finalFile.delete() // holds no sound
            }
        }
    }

    /** The result of opening one recording: the placeholder file the rest of the app uses, and a plain-words warning when audio may be missing. */
    class Rebuilt(val file: File, val warning: String?)

    /**
     * Opens the encrypted pieces (unlocking the per-recording key through the Android Keystore), checks them for missing,
     * damaged or skipped audio, and makes the recording available to the rest of the app. NO plain .m4a is written: the file
     * the app sees is a small placeholder with no sound (see [SealedPlaceholder]); playing and uploading decrypt in memory.
     * The encrypted pieces are the only copy of the sound and are kept until the user's upload or discard is confirmed.
     */
    fun consolidate(context: Context, dir: File, interrupted: Boolean): Rebuilt? {
        val sessionInfo = SessionInfo(dir)
        val info = sessionInfo.read() ?: return null
        val finalName = info["finalName"] ?: return null
        val root = RecordingService.recordingsDirectory(context)
        val finalFile = File(root, finalName)
        if (finalFile.name != finalName || finalFile.extension != "m4a" || dir.name != SealedPlaceholder.sessionName(finalName)) return null
        val opened = SealedRecording.open(dir, info, AndroidKeystoreWrapper(), interrupted)
        val recording = opened.recording
        if (recording == null) {
            when (opened.reason) {
                "empty" -> {
                    sessionInfo.update("status", "empty")
                    RecordingService.writeMetadata(finalFile, "interrupted", 0L, "No audio had been saved before the recording was interrupted. Please record again.")
                }
                "locked" -> RecordingService.writeMetadata(finalFile, "interrupted", 0L,
                    "The saved audio could not be unlocked on this phone, so it cannot be opened now. The saved pieces were kept; nothing was deleted.")
                else -> RecordingService.writeMetadata(finalFile, "interrupted", 0L,
                    "The saved audio could not be read right now. The saved pieces were kept; open the app again to retry.")
            }
            return null
        }
        try {
            val plan = opened.plan!!
            // Android's own parser must be able to read what we build, or playback in the app would fail. Not fatal: upload does not use it.
            val previewOk = previewWorks(recording)
            if (!SealedPlaceholder.isPlaceholder(finalFile)) SealedPlaceholder.write(finalFile, dir.name)
            val elapsed = info["elapsedMillis"]?.toLongOrNull()?.takeIf { !interrupted } ?: recording.durationMillis
            val warning = plan.warning()
            val note = listOfNotNull(
                if (interrupted) "Recording was interrupted. The audio saved before the interruption is available; review it before uploading." else null,
                warning,
                VisitContinuation.note(info["visitPart"]?.toIntOrNull(), info["previousName"], info["nextName"]),
            ).joinToString(" ").ifBlank { null }
            RecordingService.writeMetadata(finalFile, "ready", elapsed, note)
            runCatching { RecordingRecovery(context).stage(finalFile, "RECORDED_LOCALLY") }
            sessionInfo.update("audioMillis", recording.durationMillis.toString())
            sessionInfo.update("previewCheck", if (previewOk) "ok" else "failed")
            if (plan.problems.isNotEmpty()) sessionInfo.update("recoveryWarning", warning.orEmpty())
            sessionInfo.update("status", "finished")
            return Rebuilt(finalFile, warning)
        } finally {
            recording.wipe()
        }
    }

    /** True when the phone's own media parser reads the in-memory .m4a and reports about the right length. */
    private fun previewWorks(recording: SealedRecording): Boolean {
        val bytes = recording.whole()
        val retriever = android.media.MediaMetadataRetriever()
        return try {
            retriever.setDataSource(BytesDataSource(bytes, bytes.size))
            val millis = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            abs(millis - recording.durationMillis) <= 1_000L
        } catch (_: Exception) {
            false
        } finally {
            runCatching { retriever.release() }
            bytes.fill(0)
        }
    }

    private fun sessionDirectoryOf(finalFile: File) = File(finalFile.parentFile, SealedPlaceholder.sessionName(finalFile.name))

    override fun visitParts(finalFile: File): List<String> {
        val root = finalFile.parentFile ?: return listOf(finalFile.name)
        return VisitContinuation.orderParts(finalFile.name) { name -> SessionInfo(File(root, SealedPlaceholder.sessionName(name))).read() }
    }

    override fun isSealedRecording(finalFile: File): Boolean {
        if (!SealedPlaceholder.isPlaceholder(finalFile)) return false
        val info = SessionInfo(sessionDirectoryOf(finalFile)).read() ?: return false
        return SealedSession.isSealed(info) && info["status"] == "finished" && (info["audioMillis"]?.toLongOrNull() ?: 0L) > 0L
    }

    override fun sealedRecording(finalFile: File): SealedRecordingHandle? =
        if (isSealedRecording(finalFile)) SealedHandle(sessionDirectoryOf(finalFile)) else null

    /** Decrypts into memory on first use; [close] overwrites the sound. Nothing here writes sound to disk. */
    private class SealedHandle(private val dir: File) : SealedRecordingHandle {
        private var opened: SealedRecording? = null

        @Synchronized private fun recording(): SealedRecording {
            opened?.let { return it }
            val info = SessionInfo(dir).read() ?: throw java.io.IOException("The saved recording is missing.")
            val recording = SealedRecording.open(dir, info, AndroidKeystoreWrapper(), interrupted = true).recording
                ?: throw java.io.IOException("The saved recording could not be opened on this phone.")
            opened = recording
            return recording
        }

        override val partCount: Int get() = recording().partCount
        override fun part(index: Int): ByteArray = recording().part(index)
        override fun overlapMillis(index: Int): Long = recording().overlapMillis(index)
        override fun playbackSource(): MediaDataSource {
            val bytes = recording().whole()
            return BytesDataSource(bytes, bytes.size)
        }
        @Synchronized override fun close() { opened?.wipe(); opened = null }
    }

    /** Hands the bytes to the media tools without copying. Closing it overwrites them. */
    private class BytesDataSource(private val data: ByteArray, private val length: Int) : MediaDataSource() {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position >= length) return -1
            val n = minOf(size.toLong(), length - position).toInt()
            System.arraycopy(data, position.toInt(), buffer, offset, n)
            return n
        }
        override fun getSize(): Long = length.toLong()
        override fun close() { data.fill(0) }
    }
}

internal class SafeRecorderService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var audio: AudioRecord? = null
    @Volatile private var capturing = false
    @Volatile private var pausedFlag = false
    @Volatile private var stopRequested = false
    @Volatile private var level = 0f
    private var thread: Thread? = null
    private var completedMillis = 0L
    private var activeSince = 0L
    private var lastPersistedSecond = -1
    private var finishing = false
    @Volatile var finalName: String? = null; private set
    @Volatile var sessionDirectory: File? = null; private set
    private var errorMessage: String? = null
    @Volatile private var sessionKey: SessionKey? = null
    // ---- long visits: automatic hand-over to a second recording (see VisitContinuation) ----
    @Volatile private var partNumber = 1
    @Volatile private var partOffsetMillis = 0L
    private var ownerKeyForParts = ""
    private var visitId = ""
    private val partNames = mutableListOf<String>()
    private val partDirs = mutableListOf<File>()
    private val rebuilds = java.util.Collections.synchronizedList(mutableListOf<Thread>())
    @Volatile private var headsUpShown = false
    private var micInterruption = MicInterruption()
    private var micNoticeShown: String? = null
    private class SecureStorageException(cause: Throwable) : Exception(cause)
    /** The phone is nearly full, or too many unfinished recordings are waiting: a new recording is refused (nothing is deleted). */
    private class StorageLimitException(message: String) : Exception(message)
    private var lastStorageCheck = 0L

    fun isCapturing() = capturing || finishing

    private val ticker = object : Runnable {
        override fun run() {
            if (!capturing || finishing) return
            val elapsed = elapsedMillis()
            // The server accepts at most 3,600 seconds per recording; each part stops a little early so rounding can never push it over.
            // Normally the recorder has already moved on to the next part at 59:00; reaching this means that hand-over did not work (or the last allowed part is full).
            val partElapsed = elapsed - partOffsetMillis
            if (partElapsed >= MAX_MILLIS) { finish(VisitContinuation.failedToContinue(partNumber)); return }
            val current = RecordingService.state.value
            val headsUp = VisitContinuation.headsUp(partElapsed, partNumber, headsUpShown)
            if (headsUp != null) headsUpShown = true
            RecordingService.state.value = current.copy(
                elapsedSeconds = (elapsed / 1000).toInt(),
                amplitude = if (pausedFlag) 0f else level,
                notice = headsUp ?: current.notice,
            )
            if (RecordingService.state.value.elapsedSeconds != lastPersistedSecond) {
                lastPersistedSecond = RecordingService.state.value.elapsedSeconds
                // Android 10+: a phone call or another app can silence this recording without any error. Say so on screen.
                val silenced = !pausedFlag && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                    runCatching { audio?.activeRecordingConfiguration?.isClientSilenced == true }.getOrDefault(false)
                val micNotice = micInterruption.update(silenced, elapsed)
                if (micNotice != micNoticeShown) {
                    micNoticeShown = micNotice
                    if (micNotice != null) RecordingService.state.value = RecordingService.state.value.copy(notice = micNotice)
                }
                persist(if (pausedFlag) "paused" else "recording", elapsed)
                updateNotification()
            }
            if (SystemClock.elapsedRealtime() - lastStorageCheck > 5_000L) {
                lastStorageCheck = SystemClock.elapsedRealtime()
                val low = UnresolvedRetention.whileRecording(freeBytes(RecordingService.recordingsDirectory(this@SafeRecorderService)))
                if (low.level == UnresolvedRetention.Level.BLOCK) { finish(low.message); return }
            }
            handler.postDelayed(this, 100)
        }
    }

    private fun freeBytes(folder: File): Long = android.os.StatFs(folder.path).availableBytes

    override fun onCreate() {
        super.onCreate()
        live = this
        getSystemService(NotificationManager::class.java).createNotificationChannel(
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
            RecordingService.ACTION_START -> begin(intent.getStringExtra(RecordingService.EXTRA_OWNER).orEmpty())
            RecordingService.ACTION_PAUSE -> pause()
            RecordingService.ACTION_RESUME -> resume()
            RecordingService.ACTION_STOP -> finish(null)
            else -> if (!capturing) stopSelf()
        }
        // A killed microphone service must never silently resume capture.
        return START_NOT_STICKY
    }

    private fun begin(ownerKey: String) {
        if (capturing) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            starting = false
            RecordingService.state.value = RecordingState(error = "Microphone permission is required to record.")
            stopSelf()
            return
        }
        try {
            val first = notification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(NOTIFICATION_ID, first, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else startForeground(NOTIFICATION_ID, first)
        } catch (_: Exception) {
            starting = false
            RecordingService.state.value = RecordingState(error = "The microphone could not start. Keep WoundCoPilot open and try again.")
            stopSelf()
            return
        }
        finishing = false; stopRequested = false; pausedFlag = false
        completedMillis = 0L; activeSince = 0L; lastPersistedSecond = -1; errorMessage = null
        partNumber = 1; partOffsetMillis = 0L; headsUpShown = false; ownerKeyForParts = ownerKey
        micInterruption = MicInterruption(); micNoticeShown = null
        partNames.clear(); partDirs.clear(); rebuilds.clear()
        try {
            val root = RecordingService.recordingsDirectory(this)
            // Storage limits (sandbox): refuse to START when the phone is nearly full or too many recordings are waiting. Nothing is deleted.
            val waiting = UnresolvedRetention.waiting(root)
            val verdict = UnresolvedRetention.beforeStarting(freeBytes(root), waiting.sumOf { it.bytes }, waiting.size)
            if (verdict.level == UnresolvedRetention.Level.BLOCK) throw StorageLimitException(verdict.message.orEmpty())
            lastStorageCheck = SystemClock.elapsedRealtime()
            val name = "recording-${System.currentTimeMillis()}-${UUID.randomUUID()}.m4a"
            val finalFile = File(root, name)
            val dir = File(root, "safe-${name.removeSuffix(".m4a")}")
            check(dir.mkdirs()) { "Recording storage is unavailable" }
            finalName = name; sessionDirectory = dir
            visitId = name.removeSuffix(".m4a"); partNames += name; partDirs += dir
            // Same ownership and recovery bookkeeping as the original recorder, committed before state exposes the file.
            registerRecording(name, finalFile, ownerKey)
            // The per-recording key is made and locked (Keystore) and saved BEFORE any sound is written. If that fails,
            // recording does not start: audio is never silently saved without protection.
            val key = try {
                SealedSession.begin(dir, AndroidKeystoreWrapper(), mapOf(
                    "finalName" to name, "status" to "recording", "elapsedMillis" to "0",
                    "sampleRate" to CrashSafeBackendImpl.SAMPLE_RATE.toString(), "channels" to CrashSafeBackendImpl.CHANNELS.toString(),
                    "segmentSeconds" to SEGMENT_SECONDS.toString(),
                ) + VisitContinuation.settings(visitId, 1, null))
            } catch (error: Exception) { throw SecureStorageException(error) }
            sessionKey = key
            RecordingService.state.value = RecordingState(filePath = finalFile.absolutePath)
            capturing = true
            starting = false
            startCaptureThread(dir, key)
            activeSince = SystemClock.elapsedRealtime()
            RecordingService.state.value = RecordingService.state.value.copy(recording = true, paused = false, error = null)
            persist("recording", 0L)
            updateNotification()
            handler.post(ticker)
        } catch (error: Exception) {
            starting = false
            capturing = false
            val message = when (error) {
                is SecurityException -> "Microphone access was denied. Open the app and allow microphone access before recording."
                is SecureStorageException -> "Secure storage for the recording could not be prepared, so recording did not start. Nothing was recorded."
                is StorageLimitException -> error.message.orEmpty()
                else -> "The microphone could not start. Check that another app is not using it and try again."
            }
            // A session folder that never received any sound has nothing to protect; remove it so it is not mistaken for a lost recording.
            sessionDirectory?.takeIf { SafeSessions.segments(it).isEmpty() }?.let { runCatching { it.deleteRecursively() }; sessionDirectory = null }
            finish(message)
        }
    }

    /** Ownership and recovery bookkeeping for one recording (first part or a later part), the same as the original recorder does. */
    private fun registerRecording(name: String, finalFile: File, ownerKey: String) {
        if (ownerKey.isNotBlank()) Vault(this).write("recording-owner-$name", ownerKey)
        ownerKey.split(':', limit = 2).takeIf { it.size == 2 }?.let { DeveloperDiagnostics.bind(Vault(this), it[0], it[1], finalFile) }
        Vault(this).read("recording-context-$ownerKey")?.let { raw ->
            val capture = JSONObject(raw)
            RecordingRecovery(this).remember(finalFile, capture.str("owner"), capture.getJSONObject("case"), capture.str("template", "nursing"))
            RecordingRecovery(this).stage(finalFile, "RECORDING")
        }
    }

    /** One part's encrypted pieces. Closing it closes the pieces and wipes that part's key. */
    private inner class PartSink(val dir: File, val name: String, val key: SessionKey, val writer: SegmentWriter) : FrameSink {
        override fun write(frame: ByteArray, length: Int) = writer.writeFrame(frame, length)
        override fun syncToDisk() = writer.syncToDisk()
        override fun close() { runCatching { writer.close() }; runCatching { key.wipe() } }
        override fun abandon() { runCatching { writer.close() }; runCatching { key.wipe() }; runCatching { dir.deleteRecursively() } }
    }

    private fun newWriter(dir: File, key: SessionKey, rate: Int) = SegmentWriter(
        dir, SealedCodec(key), Adts.framesFor(segmentSecondsForTests ?: SEGMENT_SECONDS, rate),
        // Each finished piece reports how many frames it holds, so recovery can notice a short or missing piece later.
        onSegmentClosed = { number, frames -> SessionInfo(dir).update(SessionInfo.framesKey(number), frames.toString()) },
    )

    /**
     * Starts the next part while the microphone and encoder keep running. The new part gets its own key, saved before any sound, and is
     * only registered with the app once that worked. Any failure throws and the current part simply keeps recording.
     */
    private fun openNextPart(nextPart: Int, rate: Int): FrameSink {
        val root = RecordingService.recordingsDirectory(this)
        val name = "recording-${System.currentTimeMillis()}-${UUID.randomUUID()}.m4a"
        val dir = File(root, "safe-${name.removeSuffix(".m4a")}")
        check(dir.mkdirs()) { "Recording storage is unavailable" }
        try {
            val verdict = UnresolvedRetention.whileRecording(freeBytes(root))
            if (verdict.level == UnresolvedRetention.Level.BLOCK) throw java.io.IOException("not enough free storage to continue")
            val previous = partNames.last()
            val key = SealedSession.begin(dir, AndroidKeystoreWrapper(), mapOf(
                "finalName" to name, "status" to "recording", "elapsedMillis" to "0",
                "sampleRate" to CrashSafeBackendImpl.SAMPLE_RATE.toString(), "channels" to CrashSafeBackendImpl.CHANNELS.toString(),
                "segmentSeconds" to SEGMENT_SECONDS.toString(),
            ) + VisitContinuation.settings(visitId, nextPart, previous))
            val sink = PartSink(dir, name, key, newWriter(dir, key, rate))
            try { registerRecording(name, File(root, name), ownerKeyForParts) } catch (error: Exception) { sink.abandon(); throw error }
            // Tell the finished part where the visit continues BEFORE it is rebuilt, so its note says so.
            partDirs.lastOrNull()?.let { SessionInfo(it).update("nextName", name) }
            partNames += name; partDirs += dir
            return sink
        } catch (error: Exception) {
            runCatching { dir.deleteRecursively() }
            throw error
        }
    }

    /** Runs on the capture thread right after a part was closed: the finished part is rebuilt in the background. */
    private fun partFinished(finished: FrameSink, uniqueFrames: Int, finishedPart: Int) {
        val sink = finished as PartSink
        val partMillis = Adts.framesToMillis(uniqueFrames.toLong(), CrashSafeBackendImpl.SAMPLE_RATE)
        partOffsetMillis += partMillis
        partNumber = finishedPart + 1
        finalName = partNames.last(); sessionDirectory = partDirs.last(); sessionKey = null
        runCatching { SessionInfo(sink.dir).update("elapsedMillis", partMillis.toString()); SessionInfo(sink.dir).update("status", "stopped") }
        val thread = Thread({ runCatching { CrashSafeBackendImpl.consolidate(this, sink.dir, interrupted = false) } }, "safe-rebuild-part-$finishedPart")
        rebuilds += thread; thread.start()
        handler.post {
            headsUpShown = false
            RecordingService.state.value = RecordingService.state.value.copy(notice = VisitContinuation.continuedNotice(partNumber))
            updateNotification()
        }
    }

    private fun startCaptureThread(dir: File, key: SessionKey) {
        val rate = CrashSafeBackendImpl.SAMPLE_RATE
        val minBuffer = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minBuffer > 0) { "audio input unavailable" }
        val record = AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuffer * 4)
        check(record.state == AudioRecord.STATE_INITIALIZED) { "audio input unavailable" }
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, CrashSafeBackendImpl.CHANNELS).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 32_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        record.startRecording()
        audio = record
        val first = PartSink(dir, partNames.last(), key, newWriter(dir, key, rate))
        val writer = HandoffRouter(
            first, VisitContinuation.framesPerPart(rate) .let { handOffFramesForTests ?: it },
            openNext = { n -> openNextPart(n, rate).also { next -> sessionKey = (next as PartSink).key } },
            onPartFinished = ::partFinished,
        )
        thread = Thread({ captureLoop(record, codec, writer) }, "safe-recorder").also { it.start() }
    }

    private fun captureLoop(record: AudioRecord, codec: MediaCodec, writer: HandoffRouter) {
        val pcm = ByteArray(4096)
        val outInfo = MediaCodec.BufferInfo()
        var samples = 0L
        var failure: String? = null
        var wasPaused = false
        fun drain(untilEnd: Boolean): Boolean {
            val deadline = if (untilEnd) SystemClock.elapsedRealtime() + 10_000L else Long.MAX_VALUE
            while (true) {
                if (untilEnd && SystemClock.elapsedRealtime() >= deadline) throw java.io.IOException("Audio encoder did not finish within 10 seconds")
                val index = codec.dequeueOutputBuffer(outInfo, if (untilEnd) 10_000 else 0)
                if (index < 0) { if (untilEnd) continue else return false }
                val out = codec.getOutputBuffer(index)
                val ended = outInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                if (out != null && outInfo.size > 0 && outInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                    val bytes = ByteArray(outInfo.size)
                    out.position(outInfo.offset); out.get(bytes)
                    writer.writeFrame(bytes, bytes.size)
                }
                codec.releaseOutputBuffer(index, false)
                if (ended) return true
                if (!untilEnd) continue
            }
        }
        try {
            while (!stopRequested) {
                if (pausedFlag) {
                    if (!wasPaused) { runCatching { record.stop() }; writer.syncToDisk(); wasPaused = true }
                    Thread.sleep(40)
                    continue
                }
                if (wasPaused) { record.startRecording(); wasPaused = false }
                val read = record.read(pcm, 0, pcm.size)
                if (read < 0) { failure = "The microphone stopped providing audio."; break }
                if (read == 0) continue
                var peak = 0
                var i = 0
                while (i + 1 < read) { peak = maxOf(peak, abs(((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort().toInt())); i += 2 }
                level = kotlin.math.sqrt(peak / 32767f)
                var offset = 0
                while (offset < read) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex < 0) { drain(false); continue }
                    val input = codec.getInputBuffer(inIndex)!!
                    input.clear()
                    val chunk = minOf(read - offset, input.remaining())
                    input.put(pcm, offset, chunk)
                    codec.queueInputBuffer(inIndex, 0, chunk, samples * 1_000_000L / CrashSafeBackendImpl.SAMPLE_RATE, 0)
                    samples += chunk / 2
                    offset += chunk
                    drain(false)
                }
            }
            // Flush what the encoder still holds, so the last seconds before Stop are not lost.
            var queued = false
            val flushDeadline = SystemClock.elapsedRealtime() + 10_000L
            while (!queued) {
                if (SystemClock.elapsedRealtime() >= flushDeadline) throw java.io.IOException("Audio encoder did not accept final frame within 10 seconds")
                val inIndex = codec.dequeueInputBuffer(10_000)
                if (inIndex >= 0) {
                    codec.queueInputBuffer(inIndex, 0, 0, samples * 1_000_000L / CrashSafeBackendImpl.SAMPLE_RATE, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    queued = true
                }
            }
            drain(true)
        } catch (error: Exception) {
            failure = failure ?: if (error is java.io.IOException)
                "The recording stopped because the phone could not save more audio (its storage may be full). The audio saved so far is kept. Free some space, then record again to continue."
            else "Recording was interrupted by the microphone. Review the saved audio before uploading."
        } finally {
            runCatching { writer.close() }
            runCatching { sessionKey?.wipe(); sessionKey = null } // the unlocked key leaves memory as soon as the last piece is closed
            runCatching { record.stop() }
            runCatching { record.release() }
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }
        handler.post { capturing = false; finish(failure) }
    }

    private fun pause() {
        if (!capturing || pausedFlag || finishing) return
        completedMillis += SystemClock.elapsedRealtime() - activeSince
        activeSince = 0L
        pausedFlag = true
        RecordingService.state.value = RecordingService.state.value.copy(paused = true, amplitude = 0f, elapsedSeconds = (completedMillis / 1000).toInt())
        persist("paused", completedMillis)
        updateNotification()
    }

    private fun resume() {
        if (!capturing || !pausedFlag || finishing) return
        activeSince = SystemClock.elapsedRealtime()
        pausedFlag = false
        RecordingService.state.value = RecordingService.state.value.copy(paused = false, error = null)
        persist("recording", completedMillis)
        updateNotification()
    }

    private fun elapsedMillis(): Long = completedMillis + if (activeSince > 0L && !pausedFlag) SystemClock.elapsedRealtime() - activeSince else 0L

    /** First call asks the capture thread to stop; when it has flushed and closed the pieces it calls back here. */
    private fun finish(message: String?) {
        if (finishing) return
        if (message != null) errorMessage = message
        if (capturing) {
            // Ask the capture thread to flush; it will call finish() again after closing the last piece.
            val elapsed = elapsedMillis()
            completedMillis = elapsed; activeSince = 0L
            stopRequested = true
            return
        }
        finishing = true
        handler.removeCallbacks(ticker)
        val elapsed = completedMillis.takeIf { it > 0 } ?: elapsedMillis()
        val dir = sessionDirectory
        val earlyError = errorMessage
        if (dir == null) {
            publish(elapsed, null, earlyError ?: "No playable audio was captured. Please record again.", hadSession = false)
            return
        }
        val lastPartElapsed = (elapsed - partOffsetMillis).coerceAtLeast(0L)
        runCatching { SessionInfo(dir).update("elapsedMillis", lastPartElapsed.toString()); SessionInfo(dir).update("status", "stopped") }
        // Joining and converting a long visit takes a moment: do it off the main thread. The foreground service stays up meanwhile,
        // and if the app is killed during this step the next start rebuilds the recording from the saved pieces.
        Thread({
            val rebuilt = runCatching { CrashSafeBackendImpl.consolidate(this, dir, interrupted = false) }.getOrNull()
            rebuilds.toList().forEach { runCatching { it.join(60_000) } } // earlier parts finish rebuilding in the background
            val parts = partNames.size
            handler.post { publish(elapsed, rebuilt, earlyError, hadSession = true, parts = parts) }
        }, "safe-rebuild").start()
    }

    private fun publish(elapsed: Long, rebuilt: CrashSafeBackendImpl.Rebuilt?, early: String?, hadSession: Boolean, parts: Int = 1) {
        var error = early
        if (hadSession && rebuilt == null && error == null) error = "The recording could not be completed right now. Whatever was saved has been kept; reopen the app to try again."
        // If audio may be missing, say so clearly on the screen that offers the recording for upload.
        error = listOfNotNull(error, rebuilt?.warning).joinToString(" ").ifBlank { null }
        RecordingService.state.value = RecordingState(
            elapsedSeconds = (elapsed / 1000).toInt(),
            filePath = rebuilt?.file?.absolutePath,
            error = error,
            notice = listOfNotNull(VisitContinuation.finishedNotice(parts), micInterruption.summary(elapsed)).joinToString(" ").ifBlank { null },
        )
        if (!hadSession) finalName?.let { name ->
            // Started but never recorded anything: close the recovery job so it is not offered as a lost recording.
            val file = File(RecordingService.recordingsDirectory(this), name)
            runCatching { RecordingRecovery(this).stage(file, "RECORDING_FINALIZATION_FAILED") }
            RecordingService.writeMetadata(file, "interrupted", elapsed, error)
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        // If Android destroys the service while recording, the capture thread flushes and the pieces stay recoverable.
        if (capturing && !finishing) { errorMessage = "Recording stopped when the service closed. Review the saved audio before uploading."; stopRequested = true }
        handler.removeCallbacksAndMessages(null)
        if (live === this) live = null
        starting = false
        super.onDestroy()
    }

    private fun persist(status: String, elapsed: Long) {
        val name = finalName ?: return
        val dir = sessionDirectory ?: return
        val partElapsed = (elapsed - partOffsetMillis).coerceAtLeast(0L)
        RecordingService.writeMetadata(File(RecordingService.recordingsDirectory(this), name), status, partElapsed, null)
        runCatching { SessionInfo(dir).update("elapsedMillis", partElapsed.toString()) }
    }

    private fun updateNotification() {
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification()) }
    }

    private fun notification(): Notification {
        val current = RecordingService.state.value
        val action = if (current.paused) RecordingService.ACTION_RESUME else RecordingService.ACTION_PAUSE
        val label = if (current.paused) "Resume" else "Pause"
        val elapsed = current.elapsedSeconds
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(if (current.paused) "Recording paused" else "Recording audio")
            .setContentText("WoundCoPilot · %d:%02d".format(elapsed / 60, elapsed % 60))
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false)
            .apply { if (current.notice != null && current.recording) setSubText("Long visit: continues in several recordings") }
            .addAction(Notification.Action.Builder(null, label, actionIntent(action)).build())
            .addAction(Notification.Action.Builder(null, "Stop and save", actionIntent(RecordingService.ACTION_STOP)).build())
        packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
            builder.setContentIntent(PendingIntent.getActivity(this, 30, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        }
        return builder.build()
    }

    private fun actionIntent(action: String): PendingIntent = PendingIntent.getService(
        this, action.hashCode(), Intent(this, SafeRecorderService::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        const val SEGMENT_SECONDS = 300
        /** 10 s under the backend's 3,600 s whole-file limit: a file measured at 3,600.02 s would be refused (tested: exactly 3,600.000 s passes). */
        const val MAX_MILLIS = 3_590_000L
        /** Only the phone test sets this (to rotate pieces quickly). Null in normal use. */
        @Volatile internal var segmentSecondsForTests: Int? = null
        /** Only the phone test sets this (hand over after a few seconds of sound, not 59 minutes). Null in normal use. */
        @Volatile internal var handOffFramesForTests: Int? = null
        private const val CHANNEL = "woundcopilot_recording"
        private const val NOTIFICATION_ID = 4041
        @Volatile var live: SafeRecorderService? = null
        @Volatile var starting = false
    }
}
