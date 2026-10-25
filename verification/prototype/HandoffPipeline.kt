package com.woundcopilot.android

import java.io.ByteArrayOutputStream
import java.io.File

/*
 * PC check of the long-visit hand-over with GENUINE encoder audio. Feeds the frames of a real AAC file through the same HandoffRouter
 * the recorder uses, into real encrypted pieces (one session per part, each with its own key), then rebuilds every part exactly as the
 * app does (SealedRecording) and writes the parts for handoff_check.py. Usage: HandoffPipelineKt <fixture.aac> <outDir> <framesPerPart>
 */
fun main(args: Array<String>) {
    val fixture = File(args[0]); val out = File(args[1]).apply { deleteRecursively(); mkdirs() }; val per = args[2].toInt()
    val rate = 24000; val wrapper = TestKeyWrapper()
    val base = AdtsFile.payloads(fixture.readBytes(), rate, 1)
    val dirs = mutableListOf<File>()

    class Sink(val dir: File, val key: SessionKey) : FrameSink {
        val writer = SegmentWriter(dir, SealedCodec(key), 700, onSegmentClosed = { n, f -> SessionInfo(dir).update(SessionInfo.framesKey(n), f.toString()) })
        override fun write(frame: ByteArray, length: Int) = writer.writeFrame(frame, length)
        override fun syncToDisk() = writer.syncToDisk()
        override fun abandon() { runCatching { writer.close() }; key.wipe(); dir.deleteRecursively() }
        override fun close() { writer.close(); key.wipe() }
    }
    fun open(part: Int): Sink {
        val dir = File(out, "session-part$part").apply { mkdirs() }
        val key = SealedSession.begin(dir, wrapper, mapOf("finalName" to "part$part.m4a", "sampleRate" to "$rate", "channels" to "1") +
            VisitContinuation.settings("visit", part, if (part > 1) "part${part - 1}.m4a" else null))
        dirs += dir
        return Sink(dir, key)
    }
    val router = HandoffRouter(open(1), per, openNext = { n -> open(n) }, maxParts = 5, retryAfterFrames = 10)
    base.forEach { router.writeFrame(it) }
    router.close()

    // the whole visit as one plain stream: the reference every part must agree with
    File(out, "reference.adts").writeBytes(ByteArrayOutputStream().also { o -> base.forEach { o.write(Adts.header(it.size, rate, 1)); o.write(it) } }.toByteArray())
    val lines = mutableListOf("parts=${dirs.size}", "framesTotal=${base.size}", "per=$per")
    dirs.forEachIndexed { i, dir ->
        val info = SessionInfo(dir).read()!!
        val opened = SealedRecording.open(dir, info, wrapper, interrupted = false)
        val rec = opened.recording ?: error("part ${i + 1}: ${opened.reason}")
        File(out, "part${i + 1}.whole.m4a").writeBytes(rec.whole())
        lines += "part${i + 1}.frames=${rec.frameCount}"; lines += "part${i + 1}.warning=${opened.plan!!.warning() ?: ""}"
        lines += "part${i + 1}.visit=${info["visitId"]}/${info["visitPart"]}/${info["previousName"] ?: ""}"
        rec.wipe()
    }
    File(out, "handoff.txt").writeText(lines.joinToString("\n") + "\n")
    println(lines.joinToString("; "))
}
