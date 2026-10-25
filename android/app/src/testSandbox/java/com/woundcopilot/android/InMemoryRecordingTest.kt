package com.woundcopilot.android

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Random

/** Phase 2 (sandbox): the finished recording is the encrypted pieces; an .m4a is built in memory only when needed. */
class InMemoryRecordingTest {
    @get:Rule val folder = TemporaryFolder()
    private val wrapper = TestKeyWrapper()
    private val rate = 24_000

    private fun frames(n: Int, seed: Long = 11): List<ByteArray> { val r = Random(seed); return List(n) { ByteArray(40 + r.nextInt(300)).also(r::nextBytes) } }

    private fun record(payloads: List<ByteArray>, perPiece: Int = 500): File {
        val dir = folder.newFolder()
        val key = SealedSession.begin(dir, wrapper, mapOf("finalName" to "recording-1.m4a", "sampleRate" to "$rate", "channels" to "1"))
        SegmentWriter(dir, SealedCodec(key), perPiece, onSegmentClosed = { n, f -> SessionInfo(dir).update(SessionInfo.framesKey(n), f.toString()) }).use { w -> payloads.forEach { w.writeFrame(it) } }
        key.wipe()
        return dir
    }

    private fun open(dir: File, interrupted: Boolean = false) = SealedRecording.open(dir, SessionInfo(dir).read()!!, wrapper, interrupted)

    // ---- the memory buffer ----
    @Test fun frameBufferKeepsFramesInOrderAndGrowsWithoutLosingAnything() {
        val buffer = FrameBuffer(1024); val all = frames(5000)
        all.forEach { buffer.add(it) }
        assertEquals(5000, buffer.count)
        for (i in listOf(0, 1, 2499, 4999)) assertArrayEquals(all[i], buffer.copyOfFrame(i))
        assertEquals(all.sumOf { it.size }, buffer.totalBytes)
    }

    @Test fun wipeClearsTheSoundAndTheCount() {
        val buffer = FrameBuffer(); buffer.add(ByteArray(100) { 7 }); buffer.wipe()
        assertEquals(0, buffer.count); assertEquals(0, buffer.totalBytes)
    }

    // ---- the .m4a writer ----
    private fun be32(b: ByteArray, at: Int) = ((b[at].toInt() and 255) shl 24) or ((b[at + 1].toInt() and 255) shl 16) or ((b[at + 2].toInt() and 255) shl 8) or (b[at + 3].toInt() and 255)
    private fun type(b: ByteArray, at: Int) = String(b, at + 4, 4, Charsets.US_ASCII)

    @Test fun theFileStartsWithFtypAndHasEveryBoxInsideItsParent() {
        val buffer = FrameBuffer(); frames(300).forEach { buffer.add(it) }
        val m4a = Mp4Aac.build(buffer, 0, 300, rate, 1)
        assertEquals("ftyp", type(m4a, 0))
        var pos = 0; val top = mutableListOf<String>()
        while (pos < m4a.size) { val size = be32(m4a, pos); assertTrue(size >= 8 && pos + size <= m4a.size); top += type(m4a, pos); pos += size }
        assertEquals(m4a.size, pos); assertEquals(listOf("ftyp", "moov", "mdat"), top)
    }

    @Test fun theSoundInsideIsExactlyTheFramesGivenInOrder() {
        val payloads = frames(200); val buffer = FrameBuffer(); payloads.forEach { buffer.add(it) }
        val m4a = Mp4Aac.build(buffer, 50, 150, rate, 1)
        val mdat = (0 until m4a.size - 8).first { type(m4a, it) == "mdat" && be32(m4a, it) == m4a.size - it }
        val expected = payloads.subList(50, 150).fold(ByteArray(0)) { acc, f -> acc + f }
        assertArrayEquals(expected, m4a.copyOfRange(mdat + 8, m4a.size))
    }

    @Test fun theSameFramesAlwaysGiveTheSameBytes() {
        val buffer = FrameBuffer(); frames(400).forEach { buffer.add(it) }
        assertArrayEquals(Mp4Aac.build(buffer, 0, 400, rate, 1), Mp4Aac.build(buffer, 0, 400, rate, 1))
    }

    @Test fun audioSpecificConfigMatchesAacLc24kMono() {
        assertArrayEquals(byteArrayOf(0x13, 0x08), Mp4Aac.audioSpecificConfig(24_000, 1)) // 00010 (AAC-LC) 0110 (24 kHz) 0001 (mono) 000
    }

    @Test fun partsCoverTheWholeVisitWithoutOverlapAndStayUnderTheServerLimit() {
        val perPart = Mp4Aac.framesPerPart(120, rate)
        assertEquals(2812, perPart)
        assertTrue(perPart * 1024.0 / rate <= 130.0)
        for (total in listOf(1, perPart - 1, perPart, perPart + 1, 3 * perPart, 3 * perPart + 5, 30 * perPart)) {
            val parts = Mp4Aac.partCount(total, perPart)
            assertEquals(total.toLong(), (0 until parts).sumOf { minOf(total, (it + 1) * perPart) - it * perPart }.toLong())
        }
        assertEquals(0, Mp4Aac.partCount(0, perPart))
        // one hour of sound at the recorder's limit must fit the server's 32-part rule
        assertTrue(Mp4Aac.partCount((3_590_000L * rate / 1000 / 1024).toInt(), perPart) <= 32)
    }

    // ---- opening the encrypted recording ----
    @Test fun anEncryptedRecordingOpensToTheSameFramesAndKeyIsNotLeftBehind() {
        val payloads = frames(1700); val dir = record(payloads)
        val opened = open(dir); val rec = opened.recording!!
        assertEquals(1700, rec.frameCount); assertNull(opened.reason)
        for (i in listOf(0, 499, 500, 1699)) assertArrayEquals(payloads[i], rec.frames.copyOfFrame(i))
        rec.wipe(); assertEquals(0, rec.frames.count)
    }

    @Test fun theSameRecordingGivesTheSamePartBytesEveryTimeItIsOpened() {
        val dir = record(frames(6000)); val a = open(dir).recording!!; val b = open(dir).recording!!
        assertEquals(a.partCount, b.partCount); assertTrue(a.partCount >= 2)
        for (i in 0 until a.partCount) assertArrayEquals("part $i", a.part(i), b.part(i))
    }

    // ---- 1.8.5-test10: 3-second overlap between upload parts (sandbox) ----
    @Test fun everyPartAfterTheFirstRepeatsExactlyThreeSecondsAndNoFrameIsSkipped() {
        val dir = record(frames(9000)); val rec = open(dir).recording!!
        assertEquals(70, rec.overlapFrames) // 3 s at 24 kHz, 1024 samples per frame
        assertEquals(0L, rec.overlapMillis(0))
        var covered = 0
        for (i in 0 until rec.partCount) {
            val (from, until) = rec.partRange(i)
            assertTrue("part $i starts no later than the end of the previous part", from <= covered)
            if (i > 0) {
                assertEquals(rec.overlapFrames, covered - from)
                assertEquals(2986L, rec.overlapMillis(i))
            }
            assertTrue("part $i is under the server's 130 s limit", Adts.framesToMillis((until - from).toLong(), 24_000) <= 123_100L)
            covered = until
        }
        assertEquals("every frame is in some part", rec.frameCount, covered)
    }

    @Test fun overlappingPartsAreStillTheSameBytesEveryTime() {
        val dir = record(frames(6000)); val a = open(dir).recording!!; val b = open(dir).recording!!
        for (i in 0 until a.partCount) assertArrayEquals("part $i", a.part(i), b.part(i))
        assertTrue(a.part(1).size > 0)
    }

    @Test fun aWrongKeyGivesNoSoundAndNoCrash() {
        val dir = record(frames(300))
        val other = TestKeyWrapper(ByteArray(32) { 9 })
        val opened = SealedRecording.open(dir, SessionInfo(dir).read()!!, other, false)
        assertNull(opened.recording); assertEquals("locked", opened.reason)
        assertTrue("pieces are untouched", SafeSessions.segments(dir).isNotEmpty())
    }

    @Test fun aMissingPieceStillOpensAndIsReported() {
        val dir = record(frames(1700)); File(dir, "seg-00002.aac").delete()
        val opened = open(dir); assertNotNull(opened.recording)
        assertEquals(1200, opened.recording!!.frameCount)
        assertTrue(opened.plan!!.warning()!!.contains("missing"))
    }

    @Test fun anEmptySessionIsReportedAsEmpty() {
        val dir = folder.newFolder(); SealedSession.begin(dir, wrapper, mapOf("finalName" to "recording-1.m4a"))
        val opened = open(dir); assertNull(opened.recording); assertEquals("empty", opened.reason)
    }

    // ---- the placeholder ----
    @Test fun thePlaceholderHoldsNoSoundAndIsRecognised() {
        val r = folder.newFolder(); val file = File(r, "recording-1.m4a")
        assertFalse(SealedPlaceholder.isPlaceholder(file))
        SealedPlaceholder.write(file, "safe-recording-1")
        assertTrue(SealedPlaceholder.isPlaceholder(file)); assertTrue(file.length() in 256..4096)
        assertTrue(String(file.readBytes(), Charsets.US_ASCII).contains("contains no sound"))
        assertEquals("safe-recording-1", SealedPlaceholder.sessionName("recording-1.m4a"))
    }

    @Test fun ordinaryOrChangedFilesAreNeverMistakenForAPlaceholder() {
        val r = folder.newFolder()
        val audio = File(r, "a.m4a").apply { writeBytes(ByteArray(5000) { 1 }) }; assertFalse(SealedPlaceholder.isPlaceholder(audio))
        val small = File(r, "b.m4a").apply { writeBytes("WOUNDCOPILOT-SEALED-RECORDING-V1".toByteArray()) }; assertFalse("too small", SealedPlaceholder.isPlaceholder(small))
        val wrong = File(r, "c.m4a").apply { writeBytes(ByteArray(400) { 'x'.code.toByte() }) }; assertFalse(SealedPlaceholder.isPlaceholder(wrong))
    }

    @Test fun twoRecordingsHaveDifferentPlaceholdersSoTheUploadFingerprintDiffers() {
        val r = folder.newFolder()
        val a = File(r, "recording-1.m4a"); val b = File(r, "recording-2.m4a")
        SealedPlaceholder.write(a, "safe-recording-1"); SealedPlaceholder.write(b, "safe-recording-2")
        assertFalse(a.readBytes().contentEquals(b.readBytes()))
    }

    @Test fun whenTheBufferGrowsTheOldBlockOfSoundIsWipedNotLeftBehind() {
        val buffer = FrameBuffer(initialBytes = 1024)
        val field = FrameBuffer::class.java.getDeclaredField("data").apply { isAccessible = true }
        buffer.add(ByteArray(600) { 7 })
        val oldBlock = field.get(buffer) as ByteArray
        assertTrue(oldBlock.any { it != 0.toByte() })
        buffer.add(ByteArray(600) { 9 }) // does not fit: a bigger block is made
        assertTrue("a new block must have been made", field.get(buffer) !== oldBlock)
        assertTrue("the old block must be zeroed", oldBlock.all { it == 0.toByte() })
        assertEquals(2, buffer.count); assertArrayEquals(ByteArray(600) { 7 }, buffer.copyOfFrame(0))
    }
}
