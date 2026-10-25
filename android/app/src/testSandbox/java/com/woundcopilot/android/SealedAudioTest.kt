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
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.Random

/** Tests of the sandbox audio-encryption design on a PC: synthetic data only, test key instead of the Android Keystore. */
class SealedAudioTest {
    @get:Rule val folder = TemporaryFolder()
    private val rate = 24000
    private val channels = 1
    private val wrapper = TestKeyWrapper()

    private fun frames(count: Int, seed: Long = 5): List<ByteArray> {
        val random = Random(seed)
        return (0 until count).map { i -> ByteArray(40 + random.nextInt(120)).also { random.nextBytes(it); ByteBuffer.wrap(it).putInt(0, i) } }
    }

    private class Session(val dir: File, val key: SessionKey, val codec: SealedCodec, val frames: List<ByteArray>, val manifest: Map<String, String>)

    private fun record(count: Int, perSegment: Int, seed: Long = 5): Session {
        val dir = folder.newFolder()
        val key = SealedSession.begin(dir, wrapper, mapOf("finalName" to "x.m4a", "status" to "recording"))
        val codec = SealedCodec(key)
        val all = frames(count, seed)
        val manifest = mutableMapOf<String, String>()
        SegmentWriter(dir, codec, perSegment, onSegmentClosed = { n, f -> manifest[SessionInfo.framesKey(n)] = f.toString() }).use { w -> all.forEach { w.writeFrame(it) } }
        return Session(dir, key, codec, all, manifest)
    }

    private fun joinedFrames(s: Session, cleanStop: Boolean = false): Pair<RecoveryPlan, List<ByteArray>> {
        val plan = SafeSessions.plan(s.dir, s.codec, rate, channels, s.manifest, cleanStop)
        val bytes = ByteArrayOutputStream().also { SafeSessions.join(plan, s.codec, it) }.toByteArray()
        return plan to AdtsFile.payloads(bytes, rate, channels)
    }

    private fun recordLength(payload: ByteArray) = SealedCodec.HEADER + payload.size + SealedCodec.TAG

    @Test fun sealedSessionRoundTripsEveryFrameInOrder() {
        val s = record(450, 100)
        val (plan, back) = joinedFrames(s, cleanStop = true)
        assertEquals(450L, plan.totalFrames); assertTrue(plan.intact)
        assertEquals(s.frames.size, back.size)
        for (i in back.indices) assertArrayEquals("frame $i", s.frames[i], back[i])
    }

    @Test fun theLockedKeyIsSavedBeforeAnySoundIsWritten() {
        val dir = folder.newFolder()
        SealedSession.begin(dir, wrapper, mapOf("finalName" to "x.m4a"))
        // no segment exists yet, but the key blob already survives a "restart"
        assertTrue(SafeSessions.segments(dir).isEmpty())
        val info = SessionInfo(dir).read()!!
        assertTrue(SealedSession.isSealed(info)); assertNotNull(SealedSession.open(info, TestKeyWrapper()))
    }

    @Test fun afterAKillTheSessionOpensWithTheKeystoreKeyAlone() {
        val s = record(250, 100)
        RandomAccessFile(SafeSessions.segments(s.dir).last(), "rw").use { it.setLength(it.length() - 9) } // killed mid-record
        val info = SessionInfo(s.dir).read()!!
        val reopened = SealedCodec(SealedSession.open(info, TestKeyWrapper())!!) // new objects, nothing from memory
        val plan = SafeSessions.plan(s.dir, reopened, rate, channels)
        assertEquals(249L, plan.totalFrames); assertTrue(plan.tornTail); assertTrue(plan.problems.isEmpty())
        val back = AdtsFile.payloads(ByteArrayOutputStream().also { SafeSessions.join(plan, reopened, it) }.toByteArray(), rate, channels)
        for (i in back.indices) assertArrayEquals(s.frames[i], back[i])
    }

    @Test fun aWrongKeystoreKeyCannotUnlockTheSession() {
        val s = record(10, 100)
        val other = TestKeyWrapper(ByteArray(32) { 9 })
        assertNull(SealedSession.open(SessionInfo(s.dir).read()!!, other))
    }

    @Test fun nothingReadableAppearsOnDisk() {
        val s = record(300, 100)
        val disk = SafeSessions.segments(s.dir).joinToString("") { String(it.readBytes(), Charsets.ISO_8859_1) }
        // no 16-byte stretch of any sound frame may be present in the clear
        for (f in s.frames.take(100)) assertFalse(disk.contains(String(f.copyOfRange(8, 24), Charsets.ISO_8859_1)))
        // and the unlocked key is not stored either
        val settings = SessionInfo(s.dir).read()!!.values.joinToString("")
        assertFalse(settings.contains(java.util.Base64.getEncoder().encodeToString(s.key.key)))
        assertTrue(s.dir.walkTopDown().none { it.name.contains("joined") || it.extension == "m4a" || it.extension == "wav" })
    }

    @Test fun aWrongKeyReadsNothingInsteadOfNoise() {
        val s = record(100, 100)
        val wrongCodec = SealedCodec(SessionKey.generate())
        assertEquals(0, SafeSessions.plan(s.dir, wrongCodec, rate, channels).slices.size)
    }

    @Test fun aChangedByteStopsThatPieceAndTheLossIsCountedExactly() {
        val s = record(300, 100)
        val offset = (0 until 30).sumOf { recordLength(s.frames[100 + it]) }.toLong() + SealedCodec.HEADER + 3
        RandomAccessFile(SafeSessions.segments(s.dir)[1], "rw").use { f -> f.seek(offset); val b = f.read(); f.seek(offset); f.write(b xor 0x01) }
        val (plan, back) = joinedFrames(s)
        assertEquals(listOf(100, 30, 100), plan.slices.map { it.frames })
        val problem = plan.problems.single()
        assertEquals(ProblemKind.DAMAGED_PIECE, problem.kind)
        assertEquals(Adts.framesToMillis(70, rate), problem.lostMillis)
        assertEquals(Adts.framesToMillis(130, rate), problem.atMillis)
        assertEquals(230, back.size)
        assertArrayEquals(s.frames[129], back[129]); assertArrayEquals(s.frames[200], back[130])
    }

    @Test fun aDeletedPieceIsFoundWithoutAnyCountsAtAll() {
        val s = record(500, 100)
        File(s.dir, "seg-00003.aac").delete()
        val plan = SafeSessions.plan(s.dir, s.codec, rate, channels) // note: no session settings given
        val problem = plan.problems.single()
        assertEquals(ProblemKind.MISSING_AUDIO, problem.kind)
        assertEquals(Adts.framesToMillis(100, rate), problem.lostMillis)
        assertEquals(Adts.framesToMillis(200, rate), problem.atMillis)
    }

    @Test fun aDeletedFirstPieceIsFound() {
        val s = record(300, 100)
        File(s.dir, "seg-00001.aac").delete()
        val plan = SafeSessions.plan(s.dir, s.codec, rate, channels)
        assertEquals(Adts.framesToMillis(100, rate), plan.problems.single().lostMillis)
        assertEquals(0L, plan.problems.single().atMillis)
    }

    @Test fun swappedOrCopiedPiecesAreRejectedNotPlayedInTheWrongPlace() {
        val s = record(500, 100)
        val two = File(s.dir, "seg-00002.aac"); val three = File(s.dir, "seg-00003.aac")
        val a = two.readBytes(); val b = three.readBytes()
        two.writeBytes(b); three.writeBytes(a)                               // swapped
        var plan = SafeSessions.plan(s.dir, s.codec, rate, channels)
        assertEquals(listOf(1, 4, 5), plan.slices.map { it.number })
        assertEquals(Adts.framesToMillis(200, rate), plan.problems.single().lostMillis)
        two.writeBytes(a); three.writeBytes(a)                               // piece 2 copied over piece 3
        plan = SafeSessions.plan(s.dir, s.codec, rate, channels)
        assertEquals(listOf(1, 2, 4, 5), plan.slices.map { it.number })
        assertEquals(Adts.framesToMillis(100, rate), plan.problems.single().lostMillis)
    }

    @Test fun reorderedRecordsInsidePieceAreStopped() {
        val s = record(100, 100)
        val file = SafeSessions.segments(s.dir).single()
        val bytes = file.readBytes()
        val l0 = recordLength(s.frames[0]); val l1 = recordLength(s.frames[1])
        val swapped = bytes.copyOfRange(l0, l0 + l1) + bytes.copyOfRange(0, l0) + bytes.copyOfRange(l0 + l1, bytes.size)
        file.writeBytes(swapped)
        val plan = SafeSessions.plan(s.dir, s.codec, rate, channels)
        assertEquals("reading stops as soon as the order is wrong", 1L, plan.totalFrames)
        assertTrue(plan.hasMissingAudio)
    }

    @Test fun everyFrameUsesADifferentNonce() {
        val key = SessionKey.generate(); val codec = SealedCodec(key)
        val seen = HashSet<String>(); val same = ByteArray(80) { 1 }
        for (i in 0L until 20000L) {
            val rec = codec.encode(1, i, same, same.size)
            assertTrue("repeated ciphertext at $i", seen.add(java.util.Base64.getEncoder().encodeToString(rec.copyOfRange(10, 30))))
        }
    }

    @Test fun killedAtManyPointsKeepsEveryCompleteRecord() {
        for (cut in listOf(1L, 5L, 11L, 26L, 40L)) {
            val s = record(250, 100, seed = 20 + cut)
            RandomAccessFile(SafeSessions.segments(s.dir).last(), "rw").use { it.setLength(it.length() - cut) }
            val (plan, back) = joinedFrames(s)
            assertTrue("cut $cut", plan.totalFrames in 248L..249L)
            assertTrue(plan.problems.isEmpty() || plan.problems.all { it.kind == ProblemKind.CUT_SHORT })
            for (i in back.indices) assertArrayEquals(s.frames[i], back[i])
        }
    }
}
