package com.woundcopilot.android

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Uses GENUINE AAC audio made by a real encoder (ffmpeg, AAC-LC, 24 kHz, mono, 32 kbit/s; made-up tones, no voice).
 * Run with  -Dfixture.dir=<folder holding tone-visit-60s.aac>. This proves our frame reading/writing handles real
 * encoder output byte for byte. It does NOT prove the phone's own encoder (MediaCodec) output; that needs the phone.
 */
class RealFixtureTest {
    @get:Rule val folder = TemporaryFolder()
    private val rate = 24000
    private val channels = 1
    private val original: ByteArray by lazy {
        val dir = System.getProperty("fixture.dir")?.takeIf { it.isNotBlank() } ?: "src/androidTestSandbox/assets/fixtures"
        File(dir, "tone-visit-60s.aac").readBytes()
    }
    private val payloads by lazy { AdtsFile.payloads(original, rate, channels) }

    @Test fun everyFrameOfGenuineEncoderOutputIsAccepted() {
        assertTrue("fixture has ${payloads.size} frames", payloads.size in 1400..1420) // 60 s / 42.67 ms = 1406 (+ start-up frame)
        var position = 0
        for (p in payloads) { assertEquals(p.size + 7, Adts.frameLength(original, position, original.size - position, rate, channels)); position += p.size + 7 }
        assertEquals(original.size, position)
    }

    @Test fun ourHeaderForARealPayloadIsByteIdenticalToTheEncodersOwn() {
        var position = 0
        for (p in payloads) {
            assertArrayEquals(original.copyOfRange(position, position + 7), Adts.header(p.size, rate, channels))
            position += p.size + 7
        }
    }

    private fun roundTrip(codec: FrameCodec, perSegment: Int): ByteArray {
        val dir = folder.newFolder()
        SegmentWriter(dir, codec, perSegment).use { w -> payloads.forEach { w.writeFrame(it) } }
        val plan = SafeSessions.plan(dir, codec, rate, channels)
        assertTrue(plan.intact); assertEquals(payloads.size.toLong(), plan.totalFrames)
        return ByteArrayOutputStream().also { SafeSessions.join(plan, codec, it) }.toByteArray()
    }

    @Test fun plainPiecesJoinBackToTheExactOriginalFile() {
        for (perSegment in listOf(50, 333, 1000)) assertArrayEquals(original, roundTrip(PlainAdtsCodec(rate, channels), perSegment))
    }

    @Test fun sealedPiecesJoinBackToTheExactOriginalFile() {
        assertArrayEquals(original, roundTrip(SealedCodec(SessionKey.generate()), 300))
    }

    @Test fun lengthOfTheRebuiltAudioMatchesTheFrameCount() {
        val ms = Adts.framesToMillis(payloads.size.toLong(), rate)
        assertTrue("$ms ms", ms in 59_500..60_500)
    }
}
