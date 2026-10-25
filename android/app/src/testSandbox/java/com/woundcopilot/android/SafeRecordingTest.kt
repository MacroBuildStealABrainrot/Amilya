package com.woundcopilot.android

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.Random

/** Prototype tests for crash-resistant recording storage. Pure JVM: no Android needed. Synthetic data only. */
class SafeRecordingTest {
    @get:Rule val folder = TemporaryFolder()
    private val rate = 24000
    private val channels = 1

    /** A fake AAC payload that carries its own index, so any loss, repeat or reorder is detectable. */
    private fun payload(index: Int, random: Random): ByteArray {
        val size = 8 + random.nextInt(180)
        return ByteArray(size).also { random.nextBytes(it); ByteBuffer.wrap(it).putInt(0, index) }
    }

    private val codec = PlainAdtsCodec(rate, channels)
    /** Frame counts reported by finished pieces, like the real recorder saves in the session settings. */
    private val manifest = mutableMapOf<String, String>()

    private fun writeFrames(dir: File, count: Int, perSegment: Int, seed: Long = 7): List<ByteArray> {
        val random = Random(seed)
        val frames = (0 until count).map { payload(it, random) }
        SegmentWriter(dir, codec, perSegment, onSegmentClosed = { n, f -> manifest[SessionInfo.framesKey(n)] = f.toString() }).use { writer -> frames.forEach { writer.writeFrame(it) } }
        return frames
    }

    /** Reads a joined stream back into payloads. */
    private fun parse(bytes: ByteArray): List<ByteArray> {
        val out = mutableListOf<ByteArray>()
        var position = 0
        while (position < bytes.size) {
            val length = Adts.frameLength(bytes, position, bytes.size - position, rate, channels)
            assertTrue("valid frame header at $position", length > 0 && position + length <= bytes.size)
            out += bytes.copyOfRange(position + Adts.HEADER_BYTES, position + length)
            position += length
        }
        return out
    }

    private fun joined(dir: File): Pair<RecoveryPlan, ByteArray> {
        val plan = SafeSessions.plan(dir, codec, rate, channels)
        return plan to ByteArrayOutputStream().also { SafeSessions.join(plan, codec, it) }.toByteArray()
    }

    @Test fun headerRoundTripForManySizesRatesAndChannels() {
        for (sampleRate in listOf(24000, 16000, 44100, 48000)) for (ch in 1..2) for (size in listOf(1, 2, 100, 2047, 2048, 8184)) {
            val frame = Adts.header(size, sampleRate, ch) + ByteArray(size)
            assertEquals(size + 7, Adts.frameLength(frame, 0, frame.size, sampleRate, ch))
        }
    }

    @Test fun headerRejectsGarbageAndWrongFormat() {
        val good = Adts.header(100, rate, channels) + ByteArray(100)
        assertEquals(-1, Adts.frameLength(good, 0, 3, rate, channels))
        assertEquals(-1, Adts.frameLength(good, 0, good.size, 16000, channels))
        assertEquals(-1, Adts.frameLength(good, 0, good.size, rate, 2))
        val random = Random(1)
        var accepted = 0
        repeat(20000) { val junk = ByteArray(16).also(random::nextBytes); if (Adts.frameLength(junk, 0, junk.size, rate, channels) > 0) accepted++ }
        assertEquals("random bytes must never look like a frame", 0, accepted)
        assertEquals(-1, Adts.frameLength(good.copyOf().also { it[1] = 0xF0.toByte() }, 0, good.size, rate, channels))
    }

    @Test fun fiveMinutesAtTwentyFourKilohertz() {
        assertEquals(7032, Adts.framesFor(300, 24000))
        assertEquals(42666L, Adts.framesToMillis(1000, 24000))
    }

    @Test fun segmentsJoinWithNothingLostRepeatedOrReordered() {
        val dir = folder.newFolder()
        val frames = writeFrames(dir, 1005, 100)
        assertEquals(11, SafeSessions.segments(dir).size)
        val (plan, bytes) = joined(dir)
        assertTrue(plan.intact)
        assertEquals(1005L, plan.totalFrames)
        val back = parse(bytes)
        assertEquals(frames.size, back.size)
        for (i in frames.indices) assertArrayEquals("frame $i", frames[i], back[i])
    }

    @Test fun everySegmentEndsOnAFrameBoundaryAndTheNextStartsWithAHeader() {
        val dir = folder.newFolder()
        writeFrames(dir, 450, 100)
        val files = SafeSessions.segments(dir)
        assertEquals(5, files.size)
        for ((i, file) in files.withIndex()) {
            val scan = SegmentScanner.scan(file, SafeSessions.segmentNumber(file), codec)
            assertEquals(0L, scan.trailingBytes)
            assertEquals(if (i < 4) 100 else 50, scan.frames)
        }
    }

    @Test fun killedMidFrameKeepsEveryCompleteFrame() {
        for (cut in listOf(1L, 3L, 7L, 9L, 40L)) {
            val dir = folder.newFolder()
            val frames = writeFrames(dir, 250, 100, seed = 11 + cut)
            val last = SafeSessions.segments(dir).last()
            RandomAccessFile(last, "rw").use { it.setLength(it.length() - cut) }
            val (plan, bytes) = joined(dir)
            assertEquals("cut $cut", 249L, plan.totalFrames)
            assertFalse(plan.intact)
            val back = parse(bytes)
            for (i in back.indices) assertArrayEquals(frames[i], back[i])
        }
    }

    @Test fun killedExactlyAfterAHeaderOrBeforeAnyPayloadStillWorks() {
        val dir = folder.newFolder()
        val frames = writeFrames(dir, 120, 100)
        val last = SafeSessions.segments(dir).last()
        val keep = SegmentScanner.scan(last, SafeSessions.segmentNumber(last), codec).validBytes
        // a new frame started: only 5 bytes of its header were saved
        java.io.FileOutputStream(last, true).use { it.write(Adts.header(60, rate, channels), 0, 5) }
        val (plan, bytes) = joined(dir)
        assertEquals(120L, plan.totalFrames)
        assertEquals(5L, plan.discardedBytes)
        assertEquals(frames.size, parse(bytes).size)
        assertEquals(keep, plan.slices.last().validBytes)
    }

    @Test fun aDamagedFrameHeaderInTheMiddleKeepsTheGoodPartsAndLaterSegments() {
        val dir = folder.newFolder()
        val frames = writeFrames(dir, 300, 100)
        val second = SafeSessions.segments(dir)[1]
        val offset = (0 until 30).sumOf { frames[100 + it].size + Adts.HEADER_BYTES }.toLong()
        RandomAccessFile(second, "rw").use { it.seek(offset); it.write(0) } // break the 31st frame's start code
        val (plan, bytes) = joined(dir)
        assertFalse(plan.intact)
        assertEquals(listOf(100, 30, 100), plan.slices.map { it.frames })
        val back = parse(bytes) // everything kept is still made of valid frames
        assertEquals(230, back.size)
        assertArrayEquals(frames[0], back[0])
        assertArrayEquals(frames[129], back[129])
        assertArrayEquals(frames[200], back[130])
        assertArrayEquals(frames[299], back.last())
    }

    @Test fun damageInsideASoundPayloadCannotBeDetectedButNeverBreaksTheChain() {
        // ADTS has no checksum. A flipped sound byte plays as a tiny glitch; the chain and the frame count stay intact.
        val dir = folder.newFolder()
        val frames = writeFrames(dir, 200, 100)
        val second = SafeSessions.segments(dir)[1]
        RandomAccessFile(second, "rw").use { it.seek(Adts.HEADER_BYTES.toLong() + 3); it.write(0x55) }
        val (plan, _) = joined(dir)
        assertEquals(200L, plan.totalFrames)
        assertTrue(plan.intact)
        assertEquals(200, frames.size)
    }

    @Test fun emptyOrMissingSessionsGiveNothingAndDoNotCrash() {
        val dir = folder.newFolder()
        assertEquals(0, SafeSessions.plan(dir, codec, rate, channels).slices.size)
        File(dir, "seg-00001.aac").writeBytes(ByteArray(0))
        File(dir, "notes.txt").writeText("ignored")
        assertEquals(0, SafeSessions.plan(dir, codec, rate, channels).slices.size)
        assertEquals(0, SafeSessions.plan(File(dir, "missing"), codec, rate, channels).slices.size)
    }

    @Test fun neverOverwritesEarlierAudio() {
        val dir = folder.newFolder()
        val existing = File(dir, "seg-00001.aac").also { it.writeBytes(byteArrayOf(1, 2, 3, 4)) }
        writeFrames(dir, 10, 100)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), existing.readBytes())
        assertTrue(File(dir, "seg-00002.aac").isFile)
    }

    @Test fun sessionInfoIsReplacedAtomicallyAndIgnoresLeftovers() {
        val dir = folder.newFolder()
        val info = SessionInfo(dir)
        assertEquals(null, info.read())
        info.write(mapOf("state" to "recording", "finalName" to "a.m4a"))
        File(dir, "session.properties.tmp").writeText("broken half write")
        assertEquals("recording", info.read()!!["state"])
        info.update("state", "stopped")
        assertEquals("stopped", info.read()!!["state"])
        assertEquals("a.m4a", info.read()!!["finalName"])
    }

    @Test fun longVisitSizeIsSmall() {
        // 60 minutes at 32 kbps is about 14 MB. Frames of 100 bytes ~ 23.4 per second plus 7 byte headers.
        val dir = folder.newFolder()
        val frames = Adts.framesFor(60, rate) // one minute is enough to measure; scale up
        writeFrames(dir, frames, Adts.framesFor(300, rate))
        val bytes = SafeSessions.segments(dir).sumOf { it.length() }
        assertTrue(bytes < 60L * 32000 / 8 * 3)
    }

    // ---- Recovery validation: missing, damaged, skipped audio must be found and explained ----

    private fun plan(dir: File, withManifest: Boolean = true, cleanStop: Boolean = false, elapsed: Long? = null): RecoveryPlan {
        val info = if (withManifest) manifest.toMutableMap() else mutableMapOf()
        if (elapsed != null) info["elapsedMillis"] = elapsed.toString()
        return SafeSessions.plan(dir, codec, rate, channels, info, cleanStop)
    }

    @Test fun anIntactSessionHasNoWarning() {
        val dir = folder.newFolder(); writeFrames(dir, 500, 100)
        val p = plan(dir, elapsed = Adts.framesToMillis(500, rate))
        assertTrue(p.problems.isEmpty()); assertEquals(null, p.warning()); assertTrue(p.intact)
    }

    @Test fun aKilledAppWithOnlyABrokenLastRecordIsNotAGap() {
        val dir = folder.newFolder(); writeFrames(dir, 250, 100)
        RandomAccessFile(SafeSessions.segments(dir).last(), "rw").use { it.setLength(it.length() - 20) }
        manifest.remove(SessionInfo.framesKey(3)) // a killed app never got to report its last piece
        val p = plan(dir, elapsed = Adts.framesToMillis(250, rate))
        assertTrue(p.tornTail); assertTrue("a normal kill is not reported as missing audio", p.problems.isEmpty())
        assertEquals(null, p.warning())
        // but if the Stop button had been pressed, a broken last record IS a problem
        assertEquals(ProblemKind.DAMAGED_PIECE, plan(dir, cleanStop = true).problems.single().kind)
    }

    @Test fun aMissingMiddlePieceIsFoundWithItsExactSizeAndPlace() {
        val dir = folder.newFolder(); writeFrames(dir, 500, 100)
        File(dir, "seg-00003.aac").delete()
        val p = plan(dir)
        val problem = p.problems.single()
        assertEquals(ProblemKind.MISSING_AUDIO, problem.kind)
        assertEquals(Adts.framesToMillis(200, rate), problem.atMillis)   // gap sits after 200 good frames
        assertEquals(Adts.framesToMillis(100, rate), problem.lostMillis)
        assertEquals(400L, p.totalFrames)
        val text = p.warning()!!
        assertTrue(text, text.contains("0:08") && text.contains("4 seconds") && text.contains("may be missing"))
    }

    @Test fun withoutPieceCountsTheGapIsStillFoundButItsSizeIsSaidToBeUnknown() {
        val dir = folder.newFolder(); writeFrames(dir, 500, 100)
        File(dir, "seg-00003.aac").delete()
        val p = plan(dir, withManifest = false)
        assertEquals(null, p.problems.single().lostMillis)
        assertTrue(p.warning()!!.contains("amount is unknown"))
    }

    @Test fun aDamagedMiddlePieceReportsTheSecondsLost() {
        val dir = folder.newFolder(); val frames = writeFrames(dir, 300, 100)
        val offset = (0 until 30).sumOf { frames[100 + it].size + Adts.HEADER_BYTES }.toLong()
        RandomAccessFile(SafeSessions.segments(dir)[1], "rw").use { it.seek(offset); it.write(0) }
        val p = plan(dir)
        val problem = p.problems.single()
        assertEquals(ProblemKind.DAMAGED_PIECE, problem.kind)
        assertEquals(Adts.framesToMillis(130, rate), problem.atMillis)
        assertEquals(Adts.framesToMillis(70, rate), problem.lostMillis)
    }

    @Test fun aPieceCutShortExactlyOnAFrameBoundaryIsNoticedFromItsRecordedCount() {
        val dir = folder.newFolder(); val frames = writeFrames(dir, 300, 100)
        val keep = (0 until 60).sumOf { frames[100 + it].size + Adts.HEADER_BYTES }.toLong()
        RandomAccessFile(SafeSessions.segments(dir)[1], "rw").use { it.setLength(keep) } // no broken record left behind
        val p = plan(dir)
        assertEquals(0L, p.slices[1].trailingBytes)
        assertEquals(Adts.framesToMillis(40, rate), p.problems.single().lostMillis)
    }

    @Test fun finishedPiecesMissingAtTheEndAreReportedAsACutShortVisit() {
        val dir = folder.newFolder(); writeFrames(dir, 500, 100)
        File(dir, "seg-00005.aac").delete()
        val p = plan(dir)
        assertEquals(ProblemKind.CUT_SHORT, p.problems.single().kind)
        assertEquals(Adts.framesToMillis(100, rate), p.problems.single().lostMillis)
        assertTrue(p.warning()!!.contains("end of the visit") || p.warning()!!.contains("last"))
    }

    @Test fun aVisitShorterThanItsOwnClockIsFlaggedButSmallGapsAreTolerated() {
        val dir = folder.newFolder(); writeFrames(dir, 4000, 800)  // five 34-second pieces
        File(dir, "seg-00005.aac").delete()
        val noCounts = plan(dir, withManifest = false, elapsed = Adts.framesToMillis(4000, rate))
        assertEquals(ProblemKind.CUT_SHORT, noCounts.problems.single().kind)
        val lost = noCounts.problems.single().lostMillis!!
        assertTrue("about 34 s lost, got $lost", lost in 33_000..35_000)
        // a few seconds of difference (the clock is saved about once a second) is normal after a kill
        val dir2 = folder.newFolder(); writeFrames(dir2, 500, 100)
        assertTrue(plan(dir2, withManifest = false, elapsed = Adts.framesToMillis(500, rate) + 4_000).problems.isEmpty())
    }

    @Test fun skippedPieceAndDamagedPieceTogetherAreBothReported() {
        val dir = folder.newFolder(); val frames = writeFrames(dir, 600, 100)
        File(dir, "seg-00002.aac").delete()
        val offset = (0 until 30).sumOf { frames[300 + it].size + Adts.HEADER_BYTES }.toLong()
        RandomAccessFile(File(dir, "seg-00004.aac"), "rw").use { it.seek(offset); it.write(0) }
        val p = plan(dir)
        assertEquals(2, p.problems.size)
        assertEquals(Adts.framesToMillis(100, rate), p.problems[0].lostMillis)
        assertEquals(Adts.framesToMillis(70, rate), p.problems[1].lostMillis)
        assertTrue(p.warning()!!.contains(";"))
    }

    @Test fun clockAndLengthWordsAreReadable() {
        assertEquals("1:05", RecoveryPlan.clock(65_000)); assertEquals("1 second", RecoveryPlan.lengthWords(1_000))
        assertEquals("3 min 20 s", RecoveryPlan.lengthWords(200_000)); assertEquals("less than a second", RecoveryPlan.lengthWords(100))
    }

    @Test fun twoThreadsUpdatingTheSessionSettingsNeverLoseAnything() {
        val dir = folder.newFolder(); val info = SessionInfo(dir)
        info.write(mapOf("keyBlob" to "KEEP-ME", "finalName" to "a.m4a"))
        val a = Thread { repeat(150) { info.update("elapsedMillis", it.toString()) } }
        val b = Thread { repeat(150) { info.update("seg.$it.frames", "100") } }
        a.start(); b.start(); a.join(); b.join()
        val result = info.read()!!
        assertEquals("KEEP-ME", result["keyBlob"]); assertEquals("a.m4a", result["finalName"])
        assertEquals(150, result.keys.count { it.startsWith("seg.") })
        assertEquals("149", result["elapsedMillis"])
    }
}
