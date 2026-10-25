package com.woundcopilot.android

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.Random

/**
 * Needs a REAL tiny full disk, so it only runs when -Dfull.dir=<folder on a 1 MB RAM disk> is given (verification/run_storage_full_check.sh
 * mounts one). Otherwise it is skipped, not passed.
 */
class StorageFullTest {
    private val dirName = System.getProperty("full.dir")

    @Test fun aDiskThatFillsUpMidRecordingLosesAtMostTheLastFramesAndEverythingBeforeIsRecoverable() {
        assumeTrue("no small disk supplied", dirName != null)
        val root = File(dirName!!).also { it.mkdirs() }; val dir = File(root, "safe-recording-1")
        val key = SealedSession.begin(dir, TestKeyWrapper(), mapOf("finalName" to "recording-1.m4a", "status" to "recording"))
        val codec = SealedCodec(key); val random = Random(3); val originals = ArrayList<ByteArray>(); val closed = HashMap<String, String>()
        var failure: IOException? = null
        val writer = SegmentWriter(dir, codec, 200, onSegmentClosed = { n, f -> closed[SessionInfo.framesKey(n)] = f.toString() })
        try { while (originals.size < 100_000) { val p = ByteArray(90 + random.nextInt(50)).also(random::nextBytes); writer.writeFrame(p); originals += p } }
        catch (e: IOException) { failure = e }
        runCatching { writer.close() }
        assertTrue("the small disk must really have run out (it did not)", failure != null)
        println("disk full after ${originals.size} frames: ${failure!!.message}")
        // "restart": everything is rebuilt from the files alone, with the manifest that survived
        val manifest = closed.filterKeys { true }
        val plan = SafeSessions.plan(dir, codec, 24000, 1, manifest, false)
        val buffer = FrameBuffer(); SafeSessions.frames(plan, codec, buffer)
        val recovered = plan.totalFrames.toInt()
        println("recovered $recovered of ${originals.size} frames written before the failure; problems=${plan.problems.map { it.kind }}")
        assertTrue("recovered more than the writer reported", recovered <= originals.size + 1)
        assertTrue("lost more than the last piece's unsynced tail", recovered >= originals.size - 200)
        assertTrue("some audio must be recoverable", recovered > 500)
        assertEquals(recovered, buffer.count)
        for (i in 0 until recovered) assertArrayEquals("frame $i", originals[i], buffer.copyOfFrame(i))
        buffer.wipe()
    }
}
