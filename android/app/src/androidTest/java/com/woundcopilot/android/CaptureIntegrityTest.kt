package com.woundcopilot.android

import android.graphics.Bitmap
import android.graphics.Color
import android.media.ExifInterface
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.os.SystemClock
import androidx.compose.ui.graphics.ImageBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.security.MessageDigest
import java.util.UUID

/** Native decoder/recovery tests using generated fixtures. No microphone, network, or provider calls. */
@RunWith(AndroidJUnit4::class)
class CaptureIntegrityTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val createdFiles = mutableListOf<File>()
    private val createdVaultKeys = mutableListOf<String>()
    private var previousRecordingState = RecordingState()
    private var sequence = 0

    @Before fun requireIdleRecorder() {
        assumeFalse("Do not run capture tests while a real recording is active", RecordingService.state.value.recording)
        assumeFalse("Use a test installation without a signed-in account", Vault(context).read("session") != null)
        previousRecordingState = RecordingService.state.value
    }

    @After fun removeOnlyTestFixtures() {
        createdVaultKeys.forEach { Vault(context).remove(it) }
        createdFiles.asReversed().forEach { file ->
            file.delete()
            File(file.absolutePath + ".bak").delete()
            File(file.absolutePath + ".new").delete()
        }
        if (!RecordingService.state.value.recording) {
            instrumentation.runOnMainSync { RecordingService.state.value = previousRecordingState }
        }
    }

    @Test fun jpegPreviewHonorsExifOrientationWithoutChangingSourceBytes() {
        val jpeg = newFixture("captures", "jpg")
        val image = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888)
        image.eraseColor(Color.rgb(25, 100, 160))
        jpeg.outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
        image.recycle()
        ExifInterface(jpeg.absolutePath).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
        val originalHash = sha256(jpeg)
        val originalLength = jpeg.length()

        assertEquals("image/jpeg", identify(jpeg, "photo", "image/jpeg", "source.jpg"))
        val preview = display(jpeg, 1800)

        assertEquals("EXIF rotation should swap the display dimensions", 40, preview.width)
        assertEquals(80, preview.height)
        assertEquals(originalLength, jpeg.length())
        assertArrayEquals("Preview must never recompress or rewrite the original JPEG", originalHash, sha256(jpeg))
    }

    @Test fun pngAndWebpAreRecognizedAndRetainedInTheirOriginalFormats() {
        @Suppress("DEPRECATION")
        val cases = listOf(
            Triple("png", "image/png", Bitmap.CompressFormat.PNG),
            Triple("webp", "image/webp", Bitmap.CompressFormat.WEBP),
        )
        cases.forEach { (extension, expectedMime, format) ->
            val original = newFixture("captures", extension)
            val image = Bitmap.createBitmap(37, 29, Bitmap.Config.ARGB_8888)
            image.eraseColor(Color.argb(180, 60, 140, 80))
            original.outputStream().use { assertTrue(image.compress(format, 95, it)) }
            image.recycle()
            val originalBytes = original.readBytes()

            assertEquals(expectedMime, identify(original, "photo", expectedMime, "source.$extension"))
            val preview = display(original, 1800)
            assertEquals(37, preview.width)
            assertEquals(29, preview.height)
            assertArrayEquals("Import inspection and preview must preserve $extension bytes", originalBytes, original.readBytes())
        }
    }

    @Test fun unsupportedHeicIsRejectedWithoutCreatingAConvertedOriginal() {
        val heic = newFixture("captures", "heic")
        // A bounded container header is enough to exercise the unsupported-format decision.
        heic.writeBytes(byteArrayOf(0, 0, 0, 24) + "ftypheic".toByteArray() + ByteArray(12))
        val before = heic.readBytes()
        val failure = runCatching { identify(heic, "photo", "image/heic", "original.heic") }.exceptionOrNull()
        assertNotNull("HEIC must require an explicitly supported original, not silent conversion", failure)
        assertTrue(failure!!.message.orEmpty().contains("HEIC"))
        assertArrayEquals(before, heic.readBytes())
    }

    @Test fun finalizedSegmentRecoversWithoutRecordingAndKeepsItsOwnerAndBytes() {
        val file = syntheticM4a()
        val owner = "synthetic-user:${UUID.randomUUID()}"
        saveOwner(file, owner)
        writeSidecar(file, "paused")
        val before = sha256(file)

        val recovered = RecordingService.pendingRecordings(context).single { it.filePath == file.absolutePath }

        assertFalse(recovered.recording)
        assertFalse(recovered.paused)
        assertNotNull("Interrupted metadata should produce an explicit review message", recovered.error)
        assertEquals(owner, Vault(context).read("recording-owner-${file.name}"))
        assertArrayEquals(before, sha256(file))
        assertEquals("ready", JSONObject(File(file.absolutePath + ".json").readText()).str("status"))
    }

    @Test fun failedNewRecordingCannotReassignAnOlderSavedRecordingToAnotherEncounter() {
        val older = syntheticM4a()
        val oldOwner = "synthetic-user:encounter-a-${UUID.randomUUID()}"
        saveOwner(older, oldOwner)
        writeSidecar(older, "ready")
        val olderHash = sha256(older)

        val interrupted = newRecordingFixture()
        interrupted.writeBytes(byteArrayOf(0, 0, 0, 24) + "ftypM4A ".toByteArray())
        val otherOwner = "synthetic-user:encounter-b-${UUID.randomUUID()}"
        saveOwner(interrupted, otherOwner)
        writeSidecar(interrupted, "recording")

        val candidates = RecordingService.pendingRecordings(context)
        assertTrue(candidates.any { it.filePath == older.absolutePath && !it.recording })
        assertFalse("Unfinalized MP4 must not be offered as playable audio", candidates.any { it.filePath == interrupted.absolutePath })
        assertEquals(oldOwner, Vault(context).read("recording-owner-${older.name}"))
        assertEquals(otherOwner, Vault(context).read("recording-owner-${interrupted.name}"))
        assertArrayEquals(olderHash, sha256(older))
        assertTrue("Even an unfinalized original remains retained", interrupted.isFile)
        assertFalse("Recovery must never restart microphone capture", RecordingService.state.value.recording)
    }

    @Test fun uploadAcknowledgementRemovesRecoverySidecarButKeepsOriginalAndOwnership() {
        val file = syntheticM4a()
        val owner = "synthetic-user:ack-${UUID.randomUUID()}"
        saveOwner(file, owner)
        writeSidecar(file, "ready")
        val bytes = file.readBytes()
        instrumentation.runOnMainSync { RecordingService.state.value = RecordingState(filePath = file.absolutePath, elapsedSeconds = 1) }

        RecordingService.clearPending(context, file.absolutePath)

        assertFalse(File(file.absolutePath + ".json").exists())
        assertArrayEquals("Original audio remains available for excerpt verification", bytes, file.readBytes())
        assertEquals(owner, Vault(context).read("recording-owner-${file.name}"))
        assertFalse(RecordingService.pendingRecordings(context).any { it.filePath == file.absolutePath })
        assertNull(RecordingService.state.value.filePath)
    }

    @Test fun serviceAcknowledgementRejectsImportedAudioAndDirectoryTraversalWithoutDeletingIt() {
        val imported = newFixture("captures", "m4a")
        imported.writeBytes("retained synthetic import".toByteArray())
        val bytes = imported.readBytes()

        val direct = runCatching { RecordingService.clearPending(context, imported.absolutePath) }.exceptionOrNull()
        assertTrue(direct is IllegalArgumentException)
        val traversal = File(context.filesDir, "recordings/../captures/${imported.name}")
        val escaped = runCatching { RecordingService.clearPending(context, traversal.absolutePath) }.exceptionOrNull()
        assertTrue(escaped is IllegalArgumentException)
        assertArrayEquals(bytes, imported.readBytes())
    }

    private fun identify(file: File, kind: String, mime: String, name: String): String {
        val method = Class.forName("com.woundcopilot.android.CaptureScreenKt").getDeclaredMethod(
            "identifyCapture", File::class.java, String::class.java, String::class.java, String::class.java,
        ).apply { isAccessible = true }
        return try { method.invoke(null, file, kind, mime, name) as String }
        catch (error: InvocationTargetException) { throw error.targetException }
    }

    private fun display(file: File, longestEdge: Int): ImageBitmap {
        val method = Class.forName("com.woundcopilot.android.CaptureScreenKt").getDeclaredMethod(
            "displayBitmap", File::class.java, Int::class.javaPrimitiveType,
        ).apply { isAccessible = true }
        return try { method.invoke(null, file, longestEdge) as ImageBitmap }
        catch (error: InvocationTargetException) { throw error.targetException }
    }

    private fun newFixture(directoryName: String, extension: String): File {
        val directory = File(context.filesDir, directoryName).apply { check(exists() || mkdirs()) }
        return File(directory, "integrity-test-${UUID.randomUUID()}.$extension").also { createdFiles.add(it) }
    }

    private fun newRecordingFixture(): File {
        val directory = File(context.filesDir, "recordings").apply { check(exists() || mkdirs()) }
        return File(directory, "recording-${System.currentTimeMillis() + ++sequence}-${UUID.randomUUID()}.m4a")
            .also { createdFiles.add(it) }
    }

    private fun saveOwner(file: File, owner: String) {
        val key = "recording-owner-${file.name}"
        createdVaultKeys.add(key)
        Vault(context).write(key, owner)
    }

    private fun writeSidecar(file: File, status: String) {
        File(file.absolutePath + ".json").also { metadata ->
            createdFiles.add(metadata)
            metadata.writeText(obj("filename" to file.name, "status" to status, "elapsedMillis" to 1000,
                "updatedAt" to System.currentTimeMillis(), "error" to null).toString())
        }
    }

    private fun sha256(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes())

    /** Encodes one second of zero PCM through AAC and MP4 muxing; never opens any audio input. */
    private fun syntheticM4a(): File {
        val file = newRecordingFixture()
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxerStarted = false
        var codecStarted = false
        try {
            val sampleRate = 44_100
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 64_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4096)
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            codecStarted = true
            var queuedSamples = 0
            var inputFinished = false
            var outputFinished = false
            var track = -1
            val information = MediaCodec.BufferInfo()
            val deadline = SystemClock.elapsedRealtime() + 20_000
            while (!outputFinished && SystemClock.elapsedRealtime() < deadline) {
                if (!inputFinished) {
                    val inputIndex = codec.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val buffer = codec.getInputBuffer(inputIndex)!!
                        buffer.clear()
                        val count = minOf(buffer.remaining() / 2, sampleRate - queuedSamples)
                        val presentation = queuedSamples * 1_000_000L / sampleRate
                        if (count > 0) {
                            buffer.put(ByteArray(count * 2))
                            codec.queueInputBuffer(inputIndex, 0, count * 2, presentation, 0)
                            queuedSamples += count
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, 0, presentation, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputFinished = true
                        }
                    }
                }
                when (val outputIndex = codec.dequeueOutputBuffer(information, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                    else -> if (outputIndex >= 0) {
                        if (information.size > 0 && (information.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            check(muxerStarted)
                            muxer.writeSampleData(track, codec.getOutputBuffer(outputIndex)!!, information)
                        }
                        outputFinished = (information.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        codec.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }
            check(outputFinished) { "Synthetic AAC encoding exceeded its bounded deadline" }
        } finally {
            if (codecStarted) runCatching { codec.stop() }
            codec.release()
            if (muxerStarted) muxer.stop()
            muxer.release()
        }
        val reader = MediaMetadataRetriever()
        try {
            reader.setDataSource(file.absolutePath)
            check((reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0) > 0)
        } finally { reader.release() }
        return file
    }
}
