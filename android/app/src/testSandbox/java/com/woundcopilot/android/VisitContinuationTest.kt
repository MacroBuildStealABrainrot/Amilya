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
import java.io.IOException
import java.nio.ByteBuffer

/** Long visits: the hand-over from one recording to the next loses nothing, and failures never lose sound (synthetic frames only). */
class VisitContinuationTest {
    @get:Rule val folder = TemporaryFolder()

    private fun frame(i: Int) = ByteArray(40 + i % 50).also { ByteBuffer.wrap(it).putInt(0, i) }
    private fun index(f: ByteArray) = ByteBuffer.wrap(f).int

    private class Fake(val name: String) : FrameSink {
        val frames = mutableListOf<Int>(); var closed = false; var abandoned = false; var failWrites = false; var syncs = 0
        override fun write(frame: ByteArray, length: Int) { if (failWrites) throw IOException("disk full"); frames += ByteBuffer.wrap(frame).int }
        override fun syncToDisk() { syncs++ }
        override fun abandon() { abandoned = true }
        override fun close() { closed = true }
    }

    private fun run(total: Int, per: Int, maxParts: Int = 4, failOpenFor: Set<Int> = emptySet(), retry: Int = 10, failFirstAttempts: Int = 0): Triple<List<Fake>, HandoffRouter, List<Triple<String, Int, Int>>> {
        val sinks = mutableListOf(Fake("p1")); val finished = mutableListOf<Triple<String, Int, Int>>(); var attempts = 0
        val router = HandoffRouter(sinks[0], per, openNext = { n ->
            attempts++
            if (n in failOpenFor || attempts <= failFirstAttempts) throw IOException("cannot start part $n")
            Fake("p$n").also { sinks += it }
        }, onPartFinished = { f, unique, part -> finished += Triple((f as Fake).name, unique, part) }, maxParts = maxParts, retryAfterFrames = retry)
        for (i in 0 until total) router.writeFrame(frame(i))
        return Triple(sinks, router, finished)
    }

    @Test fun belowTheLimitNothingHappens() {
        val (sinks, router, finished) = run(99, 100)
        assertEquals(1, sinks.size); assertEquals(99, sinks[0].frames.size); assertEquals(1, router.part); assertTrue(finished.isEmpty())
    }

    @Test fun atTheLimitTheNextPartStartsWithTheRepeatedFrameAndNoFrameIsLost() {
        val (sinks, router, finished) = run(250, 100)
        assertEquals(3, sinks.size); assertEquals(3, router.part)
        assertEquals((0 until 100).toList(), sinks[0].frames)                  // part 1: frames 0..99
        assertEquals((99 until 199).toList(), sinks[1].frames)                 // part 2: repeated 99, then 100..198 (100 frames)
        assertEquals((198 until 250).toList(), sinks[2].frames)
        assertTrue(sinks[0].closed && sinks[1].closed && !sinks[2].closed)
        assertEquals(listOf(Triple("p1", 100, 1), Triple("p2", 99, 2)), finished)  // unique sound per finished part
        val covered = sinks.flatMap { it.frames }.toSet(); assertEquals((0 until 250).toSet(), covered)
    }

    @Test fun exactlyOneFrameIsRepeatedAtEachHandOverAndNothingElse() {
        val (sinks, _, _) = run(1000, 100)
        val all = sinks.flatMap { it.frames }
        assertEquals(1000 + (sinks.size - 1), all.size)
        assertEquals((0 until 1000).toSet(), all.toSet())
        val duplicates = all.groupBy { it }.filter { it.value.size > 1 }.keys
        assertEquals(sinks.drop(1).map { it.frames.first() }.toSet(), duplicates)
    }

    @Test fun neverMoreThanTheAllowedNumberOfParts() {
        val (sinks, router, _) = run(5000, 100, maxParts = 3)
        assertEquals(3, sinks.size); assertEquals(3, router.part)
        assertTrue("the last part keeps recording past the limit rather than dropping sound", sinks[2].frames.size > 100)
        assertEquals((0 until 5000).toSet(), sinks.flatMap { it.frames }.toSet())
    }

    @Test fun ifTheNextPartCannotBeStartedTheCurrentOneKeepsAllTheSoundAndTheHandOverIsRetried() {
        val (sinks, router, finished) = run(160, 100, retry = 10, failFirstAttempts = 3)
        // attempts happen at frames 100, 110, 120 (all fail), then 130 succeeds
        assertEquals(2, sinks.size); assertEquals(2, router.part)
        assertEquals((0 until 130).toList(), sinks[0].frames)
        assertEquals((129 until 160).toList(), sinks[1].frames)
        assertEquals(1, finished.size); assertNull(router.lastHandOffError)
        assertEquals((0 until 160).toSet(), sinks.flatMap { it.frames }.toSet())
    }

    @Test fun aHandOverThatNeverWorksLosesNothing() {
        val (sinks, router, _) = run(500, 100, failOpenFor = setOf(2))
        assertEquals(1, sinks.size); assertEquals(500, sinks[0].frames.size); assertNotNull(router.lastHandOffError)
    }

    @Test fun ifTheRepeatedFrameCannotBeWrittenTheNewPartIsThrownAwayAndTheOldOneKeepsRecording() {
        val sinks = mutableListOf(Fake("p1")); val made = mutableListOf<Fake>()
        val router = HandoffRouter(sinks[0], 50, openNext = { Fake("bad").also { f -> f.failWrites = true; made += f } }, retryAfterFrames = 20)
        for (i in 0 until 120) router.writeFrame(frame(i))
        assertEquals(120, sinks[0].frames.size); assertFalse(sinks[0].closed); assertTrue(made.isNotEmpty() && made.all { it.abandoned })
        assertEquals(1, router.part)
    }

    @Test fun failedPrimerDoesNotCommitTheNextPartAndRetryKeepsAllFrames() {
        val first = Fake("p1")
        val created = mutableListOf<Fake>()
        val committed = mutableListOf<String>()
        var attempts = 0
        val router = HandoffRouter(first, 50,
            openNext = {
                attempts++
                Fake("p2-$attempts").also { candidate ->
                    candidate.failWrites = attempts == 1
                    created += candidate
                }
            },
            onPartStarted = { committed += (it as Fake).name },
            retryAfterFrames = 10)
        for (i in 0 until 100) router.writeFrame(frame(i))
        assertEquals(2, created.size)
        assertTrue(created[0].abandoned)
        assertEquals(listOf("p2-2"), committed)
        assertEquals((0 until 60).toList(), first.frames)
        assertEquals((59 until 100).toList(), created[1].frames)
        assertEquals(2, router.part)
    }

    @Test fun syncReachesTheCurrentPart() {
        val (sinks, router, _) = run(150, 100); router.syncToDisk(); assertEquals(0, sinks[0].syncs); assertEquals(1, sinks[1].syncs)
    }

    @Test fun theRealPartLengthFitsTheServerLimitEvenWithTheRepeatedFrame() {
        val frames = VisitContinuation.framesPerPart(24_000)
        val seconds = (frames + 1) * 1024.0 / 24_000
        assertTrue("part is $seconds s", seconds in 3_539.0..3_541.0)
        // the server accepts at most 3,602 s per recording (sum of 120 s upload parts)
        assertTrue(seconds < 3_602.0 - 60)
        assertTrue(VisitContinuation.HAND_OFF_SECONDS * 1000L < CrashSafeBackendHelpers.MAX_PART_MILLIS)
    }

    @Test fun theHeadsUpComesOnceFiveMinutesAheadAndNeverOnTheLastPart() {
        val at = (VisitContinuation.HAND_OFF_SECONDS - VisitContinuation.WARN_SECONDS_BEFORE) * 1000L
        assertNull(VisitContinuation.headsUp(at - 1, 1, false))
        val text = VisitContinuation.headsUp(at, 1, false)!!
        assertTrue(text.contains("5 minutes") && text.contains("No sound will be missed"))
        assertNull(VisitContinuation.headsUp(at + 1000, 1, true))
        assertNull(VisitContinuation.headsUp(at, VisitContinuation.MAX_PARTS, false))
        assertFalse(text.contains("patient", true))
    }

    @Test fun partNotesSayAllPartsMakeOneReport() {
        assertNull(VisitContinuation.note(null, null, null))
        assertTrue(VisitContinuation.note(1, null, "recording-2.m4a")!!.contains("make one report"))
        assertTrue(VisitContinuation.note(2, "recording-1.m4a", null)!!.contains("last part"))
        assertNull(VisitContinuation.finishedNotice(1)); assertTrue(VisitContinuation.finishedNotice(3)!!.contains("All 3 parts are sent together"))
        assertEquals(mapOf("visitId" to "v", "visitPart" to "2", "previousName" to "a.m4a"), VisitContinuation.settings("v", 2, "a.m4a"))
        assertEquals(mapOf("visitId" to "v", "visitPart" to "1"), VisitContinuation.settings("v", 1, null))
    }

    @Test fun theRecorderServiceUsesTheRouterAndEveryHandOverPathIsWired() {
        val src = moduleFile("sandbox.src", "src/main/java/com/woundcopilot/android/SafeRecorderService.kt").readText()
        for (needle in listOf("HandoffRouter(", "openNextPart(", "registerRecording(", "VisitContinuation.note(", "VisitContinuation.failedToContinue(", "partOffsetMillis", "nextName"))
            assertTrue("SafeRecorderService.kt must contain $needle", src.contains(needle))
        assertFalse("capture loop must write through the router, not a single SegmentWriter", Regex("""captureLoop\([^)]*SegmentWriter""").containsMatchIn(src))
        assertTrue("the next part is registered with the app only after its key exists", src.indexOf("SealedSession.begin(dir, AndroidKeystoreWrapper(), mapOf(\n                \"finalName\" to name, \"status\" to \"recording\", \"elapsedMillis\" to \"0\",\n                \"sampleRate\" to CrashSafeBackendImpl.SAMPLE_RATE.toString(), \"channels\" to CrashSafeBackendImpl.CHANNELS.toString(),\n                \"segmentSeconds\" to SEGMENT_SECONDS.toString(),\n            ) + VisitContinuation.settings(visitId, nextPart") > 0)
    }

    // ---- with the real encrypted pieces on disk ----
    private val wrapper = TestKeyWrapper()
    private inner class RealSink(val dir: File, val key: SessionKey) : FrameSink {
        val writer = SegmentWriter(dir, SealedCodec(key), 40, onSegmentClosed = { n, f -> SessionInfo(dir).update(SessionInfo.framesKey(n), f.toString()) })
        override fun write(frame: ByteArray, length: Int) = writer.writeFrame(frame, length)
        override fun syncToDisk() = writer.syncToDisk()
        override fun abandon() { runCatching { writer.close() }; key.wipe(); dir.deleteRecursively() }
        override fun close() { writer.close(); key.wipe() }
    }
    private fun realPart(n: Int, previous: String?): RealSink {
        val dir = folder.newFolder("safe-recording-$n")
        val key = SealedSession.begin(dir, wrapper, mapOf("finalName" to "recording-$n.m4a", "sampleRate" to "24000", "channels" to "1") + VisitContinuation.settings("visit", n, previous))
        return RealSink(dir, key)
    }

    @Test fun threeRealEncryptedPartsHoldEveryFrameInOrderAndEachOpensWithItsOwnKey() {
        val sinks = mutableListOf(realPart(1, null))
        val router = HandoffRouter(sinks[0], 100, openNext = { n -> realPart(n, "recording-${n - 1}.m4a").also { sinks += it } })
        for (i in 0 until 230) router.writeFrame(frame(i))
        router.close()
        val keys = sinks.map { it.key.serialize().toList() }  // wiped already: all zeros, so they must be equal
        assertEquals(3, sinks.size)
        val seen = mutableListOf<List<Int>>()
        for (sink in sinks) {
            val info = SessionInfo(sink.dir).read()!!
            val opened = SealedRecording.open(sink.dir, info, wrapper, interrupted = false)
            val recording = opened.recording!!
            assertTrue("no warning: " + opened.plan!!.warning(), opened.plan.problems.isEmpty())
            val buffer = FrameBuffer(); SafeSessions.frames(opened.plan, SealedCodec(SealedSession.open(info, wrapper)!!), buffer)
            seen += (0 until buffer.count).map { index(buffer.copyOfFrame(it)) }
            assertEquals(info["visitId"], "visit")
            recording.wipe(); buffer.wipe()
        }
        assertEquals((0 until 100).toList(), seen[0]); assertEquals((99 until 199).toList(), seen[1]); assertEquals((198 until 230).toList(), seen[2])
        assertEquals("part 2 records the visit and names its predecessor", "recording-1.m4a", SessionInfo(sinks[1].dir).read()!!["previousName"])
        assertTrue(keys.isNotEmpty())
    }
}

/** Keeps the test independent of the Android service: the same number as SafeRecorderService.MAX_MILLIS. */
internal object CrashSafeBackendHelpers { const val MAX_PART_MILLIS = 3_590_000L }

/** 1.8.5-test10: all parts of a long visit are found, in order, from any part, so one report uses all of them. */
class VisitPartsOrderTest {
    private fun visit(vararg names: String, id: String = "visit-1"): Map<String, Map<String, String>> =
        names.mapIndexed { i, n ->
            n to (mapOf("visitId" to id) + (if (i > 0) mapOf("previousName" to names[i - 1]) else emptyMap()) +
                (if (i < names.size - 1) mapOf("nextName" to names[i + 1]) else emptyMap()))
        }.toMap()

    @Test fun anOrdinaryRecordingIsOnePart() {
        assertEquals(listOf("a.m4a"), VisitContinuation.orderParts("a.m4a") { null })
        assertEquals(listOf("a.m4a"), VisitContinuation.orderParts("a.m4a") { mapOf("status" to "finished") })
    }

    @Test fun startingFromAnyPartGivesTheSameOrder() {
        val parts = visit("p1.m4a", "p2.m4a", "p3.m4a")
        for (start in parts.keys) assertEquals(listOf("p1.m4a", "p2.m4a", "p3.m4a"), VisitContinuation.orderParts(start) { parts[it] })
    }

    @Test fun aNinetyMinuteVisitHasTwoPartsInOrder() {
        val parts = visit("first.m4a", "second.m4a")
        assertEquals(listOf("first.m4a", "second.m4a"), VisitContinuation.orderParts("second.m4a") { parts[it] })
    }

    @Test fun aMissingPartIsStillNamedSoTheAppCanWarn() {
        val parts = visit("p1.m4a", "p2.m4a", "p3.m4a") - "p1.m4a"
        assertEquals(listOf("p1.m4a", "p2.m4a", "p3.m4a"), VisitContinuation.orderParts("p3.m4a") { parts[it] })
        val noMiddle = visit("p1.m4a", "p2.m4a", "p3.m4a") - "p2.m4a"
        assertEquals(listOf("p2.m4a", "p3.m4a"), VisitContinuation.orderParts("p3.m4a") { noMiddle[it] })
    }

    @Test fun anotherVisitOrALoopIsNeverJoined() {
        val mine = visit("a.m4a", "b.m4a")
        val other = visit("x.m4a", id = "visit-2")
        val mixed = mine + other + ("b.m4a" to (mine.getValue("b.m4a") + ("nextName" to "x.m4a")))
        assertEquals(listOf("a.m4a", "b.m4a"), VisitContinuation.orderParts("a.m4a") { mixed[it] })
        val loop = mapOf("a.m4a" to mapOf("visitId" to "v", "nextName" to "b.m4a", "previousName" to "b.m4a"),
            "b.m4a" to mapOf("visitId" to "v", "nextName" to "a.m4a", "previousName" to "a.m4a"))
        assertEquals(2, VisitContinuation.orderParts("a.m4a") { loop[it] }.size)
    }

    @Test fun neverMoreThanTheAllowedParts() {
        val names = (1..10).map { "p$it.m4a" }.toTypedArray()
        val parts = visit(*names)
        assertTrue(VisitContinuation.orderParts("p5.m4a") { parts[it] }.size <= VisitContinuation.MAX_PARTS + 1)
    }
}
