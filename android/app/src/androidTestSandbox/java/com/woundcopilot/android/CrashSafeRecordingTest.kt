package com.woundcopilot.android

import android.Manifest
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos

/**
 * Sandbox-only phone tests for the EXPERIMENTAL crash-resistant, encrypted recorder. NEVER RUN so far: the workspace that
 * wrote them has no Android SDK or device. Run with connectedSandboxAndroidTest (RUN-ON-YOUR-PC.md, Step 8).
 *
 * Sound used: genuine AAC made by a real encoder from made-up tones (assets/fixtures/tone-visit-60s.aac). In that audio
 * second k is a tone of 300 + 40*(k mod 40) Hz, so the PITCH at any moment says which second of the original it is.
 * That lets these tests check the rebuilt file's timeline after it has been through the phone's own muxer and decoder.
 * No patient data, no network.
 */
@RunWith(AndroidJUnit4::class)
class CrashSafeRecordingTest {
    @get:Rule val permission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val testContext: Context get() = InstrumentationRegistry.getInstrumentation().context
    private val created = mutableListOf<String>()
    private val rate = CrashSafeBackendImpl.SAMPLE_RATE

    @After fun cleanUp() {
        SafeRecorderService.segmentSecondsForTests = null
        RecordingService.stop(context)
        waitUntil(10_000) { !RecordingService.state.value.recording }
        created.forEach { runCatching { RecordingService.clearPending(context, it, deleteFile = true) } }
        RecordingService.state.value = RecordingState()
    }

    private fun waitUntil(ms: Long, condition: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (condition()) return true; Thread.sleep(50) }
        return condition()
    }

    /** The sandbox keeps encrypted segments and a placeholder; tests must use the in-memory playback handle. */
    private fun durationMillis(file: File): Long {
        val handle = crashSafeBackend()?.sealedRecording(file)
        val r = android.media.MediaMetadataRetriever()
        return try {
            if (handle != null) r.setDataSource(handle.playbackSource()) else r.setDataSource(file.absolutePath)
            r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
        } finally { r.release(); handle?.close() }
    }

    /** Decodes the in-memory audio source used by playback; no plaintext audio file is created. */
    private fun decode(file: File): ShortArray {
        val handle = crashSafeBackend()?.sealedRecording(file)
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        val out = ArrayList<Short>(); val info = MediaCodec.BufferInfo(); var inputDone = false; var done = false
        try {
            if (handle != null) extractor.setDataSource(handle.playbackSource()) else extractor.setDataSource(file.absolutePath)
            extractor.selectTrack(0)
            val format = extractor.getTrackFormat(0)
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!).apply { configure(format, null, null, 0); start() }
            val decoder = requireNotNull(codec)
            while (!done) {
                if (!inputDone) {
                    val i = decoder.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val size = extractor.readSampleData(decoder.getInputBuffer(i)!!, 0)
                        if (size < 0) { decoder.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                        else { decoder.queueInputBuffer(i, 0, size, extractor.sampleTime, 0); extractor.advance() }
                    }
                }
                val o = decoder.dequeueOutputBuffer(info, 10_000)
                if (o >= 0) {
                    val buf = decoder.getOutputBuffer(o)!!.order(ByteOrder.LITTLE_ENDIAN)
                    buf.position(info.offset); buf.limit(info.offset + info.size)
                    while (buf.remaining() >= 2) out.add(buf.short)
                    decoder.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) done = true
                }
            }
        } finally { runCatching { codec?.stop() }; codec?.release(); extractor.release(); handle?.close() }
        return out.toShortArray()
    }

    /** Which of the fixture's tone frequencies is strongest in this one-second slice? Returns the second number (0..39). */
    private fun toneSecond(pcm: ShortArray, startSecond: Int): Int {
        val from = startSecond * rate + rate / 5; val to = from + rate / 2   // middle part of the second, away from the edges
        var best = -1; var bestPower = -1.0
        for (k in 0 until 40) {
            val hz = 300.0 + 40.0 * k
            val coeff = 2.0 * cos(2.0 * PI * hz / rate); var s1 = 0.0; var s2 = 0.0
            for (n in from until minOf(to, pcm.size)) { val s0 = pcm[n] + coeff * s1 - s2; s2 = s1; s1 = s0 }
            val power = s1 * s1 + s2 * s2 - coeff * s1 * s2
            if (power > bestPower) { bestPower = power; best = k }
        }
        return best
    }

    private fun fixturePayloads(): List<ByteArray> {
        val bytes = testContext.assets.open("fixtures/tone-visit-60s.aac").use { it.readBytes() }
        val out = mutableListOf<ByteArray>(); var p = 0
        while (p < bytes.size) {
            val len = Adts.frameLength(bytes, p, bytes.size - p, rate, 1); assertTrue("genuine frame at $p", len > 0)
            out += bytes.copyOfRange(p + Adts.HEADER_BYTES, p + len); p += len
        }
        return out
    }

    /** Builds a session folder exactly as the recorder would leave it after being killed: sealed pieces, no finished file. */
    private fun leftBehindSession(framesPerPiece: Int, mutate: (File) -> Unit = {}): Pair<String, File> {
        val root = RecordingService.recordingsDirectory(context)
        val name = "recording-${System.currentTimeMillis()}-fixture.m4a"
        val dir = File(root, "safe-${name.removeSuffix(".m4a")}").apply { mkdirs() }
        val payloads = fixturePayloads()
        val key = SealedSession.begin(dir, AndroidKeystoreWrapper(), mapOf(
            "finalName" to name, "status" to "recording", "sampleRate" to rate.toString(), "channels" to "1",
            "elapsedMillis" to Adts.framesToMillis(payloads.size.toLong(), rate).toString()))
        SegmentWriter(dir, SealedCodec(key), framesPerPiece, onSegmentClosed = { n, f -> SessionInfo(dir).update(SessionInfo.framesKey(n), f.toString()) })
            .use { w -> payloads.forEach { w.writeFrame(it) } }
        // an app that was killed never got to say its last piece was finished
        SessionInfo(dir).write((SessionInfo(dir).read()!!).filterKeys { it != SessionInfo.framesKey(SafeSessions.segments(dir).size) })
        mutate(dir)
        RecordingService.writeMetadata(File(root, name), "recording", 1000, null)
        created += File(root, name).absolutePath
        return name to dir
    }

    @Test fun sandboxBuildUsesTheExperimentalRecorder() {
        assertTrue("This test must run on the sandbox build", BuildConfig.CRASH_SAFE_RECORDING)
        assertNotNull(crashSafeBackend())
    }

    @Test fun recordPauseResumeStopGivesOnePlayableCompleteFileWithNoGaps() {
        SafeRecorderService.segmentSecondsForTests = 5      // forces several pieces in a short test
        RecordingService.start(context)
        assertTrue(waitUntil(8_000) { RecordingService.state.value.recording })
        Thread.sleep(6_000)
        RecordingService.pause(context)
        assertTrue(waitUntil(5_000) { RecordingService.state.value.paused })
        Thread.sleep(4_000)                                 // this paused time must NOT appear in the audio
        RecordingService.resume(context)
        assertTrue(waitUntil(5_000) { !RecordingService.state.value.paused })
        Thread.sleep(6_000)
        RecordingService.stop(context)
        assertTrue(waitUntil(30_000) { !RecordingService.state.value.recording && RecordingService.state.value.filePath != null })
        val path = RecordingService.state.value.filePath!!.also { created += it }
        val file = File(path)
        assertTrue(RecordingService.readableAudio(file))
        val seconds = durationMillis(file) / 1000.0
        assertTrue("expected about 12 s of audio (6 + 6), paused time excluded; got $seconds", seconds in 10.0..14.5)
        assertEquals("a clean recording must carry no missing-audio warning", null, RecordingService.state.value.error)
        // the pieces must join without a single missing, repeated or damaged frame
        val dir = File(file.parentFile, "safe-" + file.name.removeSuffix(".m4a"))
        val info = SessionInfo(dir).read()!!
        val codec = SealedCodec(SealedSession.open(info, AndroidKeystoreWrapper())!!)
        val plan = SafeSessions.plan(dir, codec, rate, 1, info, cleanStop = true)
        assertTrue("several pieces expected, got ${plan.slices.size}", plan.slices.size >= 2)
        assertTrue(plan.problems.toString(), plan.problems.isEmpty())
        assertTrue("decodes end to end", decode(file).size > 9 * rate)
    }

    @Test fun leftBehindSessionIsRebuiltAndItsTimelineIsRight() {
        val (name, dir) = leftBehindSession(400) { d ->   // killed in the middle of the last record
            RandomAccessFile(SafeSessions.segments(d).last(), "rw").use { it.setLength(it.length() - 9) }
        }
        val recovered = RecordingService.pendingRecordings(context).firstOrNull { it.filePath?.endsWith(name) == true }
        assertNotNull("the interrupted session should be rebuilt", recovered)
        val file = File(recovered!!.filePath!!)
        assertTrue(RecordingService.readableAudio(file))
        val frames = fixturePayloads().size - 1
        val expected = Adts.framesToMillis(frames.toLong(), rate)
        assertTrue("expected about $expected ms, got ${durationMillis(file)}", abs(durationMillis(file) - expected) < 500)
        assertTrue("the user must be told it was interrupted", recovered.error?.contains("interrupted") == true)
        assertFalse("a normal kill is not a missing-audio gap", recovered.error?.contains("may be missing") == true)
        val pcm = decode(file)
        for (second in listOf(3, 17, 31, 44, 55)) assertEquals("second $second plays the right tone", second % 40, toneSecond(pcm, second))
        assertTrue("raw pieces are kept until upload is confirmed", dir.isDirectory)
        RecordingService.clearPending(context, file.absolutePath, deleteFile = true)
        created.clear()
        assertTrue("discard removes the raw pieces too", !dir.exists())
    }

    @Test fun aMissingPieceIsReportedClearlyAndTheRestStillPlaysInOrder() {
        val (name, _) = leftBehindSession(400) { d -> File(d, "seg-00002.aac").delete() }   // 400 frames = about 17 s lost
        val recovered = RecordingService.pendingRecordings(context).firstOrNull { it.filePath?.endsWith(name) == true }
        assertNotNull(recovered)
        val warning = recovered!!.error.orEmpty()
        assertTrue(warning, warning.contains("may be missing") && warning.contains("17 seconds") && warning.contains("0:17"))
        val pcm = decode(File(recovered.filePath!!))
        assertEquals("before the gap", 10 % 40, toneSecond(pcm, 10))
        assertEquals("after the gap the tone has jumped ahead by the lost 17 s", (17 + 30) % 40, toneSecond(pcm, 30))
    }

    @Test fun savedPiecesContainNoReadableSound() {
        val (_, dir) = leftBehindSession(400)
        val disk = SafeSessions.segments(dir).joinToString("") { String(it.readBytes(), Charsets.ISO_8859_1) }
        for (p in fixturePayloads().take(200)) assertFalse(disk.contains(String(p.copyOfRange(8, 24), Charsets.ISO_8859_1)))
        assertTrue(dir.walkTopDown().none { it.name.startsWith("joined") || it.extension == "wav" || it.extension == "pcm" })
    }

    @Test fun theCleanUpNeverDeletesUnfinishedPiecesEvenWhenVeryOld() {
        val (_, dir) = leftBehindSession(400)
        val old = System.currentTimeMillis() - 40L * 24 * 3600 * 1000
        dir.walkTopDown().forEach { it.setLastModified(old) }
        LocalRetention.clean(context, System.currentTimeMillis())
        assertTrue("pieces must survive the clean-up", SafeSessions.segments(dir).isNotEmpty())
        assertTrue(File(dir, "session.properties").isFile)
    }
}
