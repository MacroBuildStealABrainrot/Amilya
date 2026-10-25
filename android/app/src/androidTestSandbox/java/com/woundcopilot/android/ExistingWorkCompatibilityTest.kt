package com.woundcopilot.android

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 1.8.5-test10. Release readiness of the crash-resistant recorder: when it is switched on, work that the ORIGINAL recorder left on
 * the phone (an ordinary .m4a plus its sidecar, waiting for upload) must still be listed, playable, uploadable the old way, and never
 * mistaken for an encrypted recording or a multi-part visit, and must not be cleaned up early. Synthetic tone audio only.
 * Runs in the sandbox build, where the crash-resistant recorder is active.
 */
@RunWith(AndroidJUnit4::class)
class ExistingWorkCompatibilityTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val testContext: Context get() = InstrumentationRegistry.getInstrumentation().context
    private val rate = CrashSafeBackendImpl.SAMPLE_RATE
    private val created = mutableListOf<File>()

    @After fun cleanUp() {
        created.forEach { runCatching { RecordingService.clearPending(context, it.absolutePath, deleteFile = true) } }
        File(RecordingService.recordingsDirectory(context), "segments").deleteRecursively()
    }

    private fun fixtureFrames(): FrameBuffer {
        val bytes = testContext.assets.open("fixtures/tone-visit-60s.aac").use { it.readBytes() }
        val buffer = FrameBuffer(); var p = 0
        while (p < bytes.size) {
            val len = Adts.frameLength(bytes, p, bytes.size - p, rate, 1); assertTrue(len > 0)
            buffer.add(bytes.copyOfRange(p + Adts.HEADER_BYTES, p + len)); p += len
        }
        return buffer
    }

    /** A recording exactly as the ORIGINAL MediaRecorder-based recorder leaves it: a real .m4a and a "ready" sidecar. */
    private fun oldStyleRecording(): File {
        val frames = fixtureFrames()
        val file = File(RecordingService.recordingsDirectory(context), "recording-${System.currentTimeMillis()}-legacy.m4a")
        file.writeBytes(Mp4Aac.build(frames, 0, frames.count, rate, 1))
        frames.wipe()
        RecordingService.writeMetadata(file, "ready", 60_000L, null)
        created += file
        return file
    }

    @Test fun theCrashResistantRecorderIsActiveInThisBuild() {
        assertTrue(BuildConfig.CRASH_SAFE_RECORDING)
        assertNotNull(crashSafeBackend())
    }

    @Test fun anOldRecordingIsStillListedAsReadyAndPlayable() {
        val file = oldStyleRecording()
        crashSafeBackend()!!.recoverOrphans(context)
        val listed = RecordingService.pendingRecordings(context).firstOrNull { it.filePath == file.absolutePath }
        assertNotNull("old recording is still offered for upload", listed)
        assertNull("no error on an intact old recording", listed!!.error)
        assertTrue(RecordingService.readableAudio(file))
    }

    @Test fun anOldRecordingIsNeverTreatedAsEncryptedOrAsPartOfAVisit() {
        val file = oldStyleRecording()
        val backend = crashSafeBackend()!!
        assertFalse(SealedPlaceholder.isPlaceholder(file))
        assertFalse(backend.isSealedRecording(file))
        assertNull(backend.sealedRecording(file))
        assertEquals(listOf(file.name), backend.visitParts(file))
    }

    @Test fun anOldRecordingStillUploadsTheOldWayInTwoMinutePieces() {
        val file = oldStyleRecording()
        val pieces = splitAacRecording(file, File(file.parentFile, "segments"))
        try {
            assertEquals(1, pieces.size) // 60 seconds: one piece, no overlap needed
            assertTrue(pieces.all { it.length() > 0 })
        } finally { pieces.forEach { it.delete() } }
    }

    @Test fun theCleanUpDoesNotRemoveAnOldRecordingThatIsWaiting() {
        val file = oldStyleRecording()
        LocalRetention.clean(context)
        assertTrue(file.isFile)
    }
}
