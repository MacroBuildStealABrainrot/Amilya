package com.woundcopilot.android

import java.io.ByteArrayOutputStream
import java.io.File

/*
 * Phase 2 check (PC only): genuine encoder audio -> encrypted pieces on disk -> SealedRecording (opened through the key
 * wrapper) -> .m4a built IN MEMORY by Mp4Aac -> files written here only so that ffprobe/ffmpeg and the backend's own audio
 * checks can examine them. Usage: SealedUploadPipelineKt <fixture.aac> <outDir>
 */
fun main(args: Array<String>) {
    val fixture = File(args[0]); val out = File(args[1]).apply { deleteRecursively(); mkdirs() }
    val rate = 24000
    val base = AdtsFile.payloads(fixture.readBytes(), rate, 1)
    val wrapper = TestKeyWrapper()

    fun record(name: String, copies: Int, mutate: (File) -> Unit = {}): File {
        val dir = File(out, "session-$name").apply { mkdirs() }
        val key = SealedSession.begin(dir, wrapper, mapOf("finalName" to "$name.m4a", "sampleRate" to "$rate", "channels" to "1"))
        val manifest = mutableMapOf<String, String>()
        SegmentWriter(dir, SealedCodec(key), 700, onSegmentClosed = { n, f -> SessionInfo(dir).update(SessionInfo.framesKey(n), f.toString()) }).use { w ->
            repeat(copies) { base.forEach { w.writeFrame(it) } }
        }
        key.wipe()
        mutate(dir)
        return dir
    }

    fun export(name: String, dir: File, interrupted: Boolean = false) {
        val opened = SealedRecording.open(dir, SessionInfo(dir).read()!!, wrapper, interrupted)
        val rec = opened.recording ?: run { File(out, "$name.txt").writeText("reason=${opened.reason}\n"); return }
        File(out, "$name.whole.m4a").writeBytes(rec.whole())
        for (i in 0 until rec.partCount) File(out, "$name.part-%02d.m4a".format(i)).writeBytes(rec.part(i))
        // opening a second time must give the very same bytes (a retried upload is recognised by the server)
        val again = SealedRecording.open(dir, SessionInfo(dir).read()!!, wrapper, interrupted).recording!!
        File(out, "$name.again-part-00.m4a").writeBytes(again.part(0))
        File(out, "$name.again-part-last.m4a").writeBytes(again.part(again.partCount - 1))
        val adts = ByteArrayOutputStream()
        SafeSessions.join(opened.plan!!, SealedCodec(SealedSession.open(SessionInfo(dir).read()!!, wrapper)!!), adts)
        File(out, "$name.adts").writeBytes(adts.toByteArray())
        File(out, "$name.txt").writeText("frames=${rec.frameCount}\nparts=${rec.partCount}\nmillis=${rec.durationMillis}\nwarning=${opened.plan.warning() ?: ""}\n")
        rec.wipe(); again.wipe()
        println("$name: frames=${opened.plan.totalFrames} parts=${opened.recording.partCount}")
    }

    export("one-minute", record("one-minute", 1))
    export("ten-minutes", record("ten-minutes", 10))
    export("missing-piece", record("missing-piece", 1500 / base.size + 1) { d -> File(d, "seg-00002.aac").delete() })
    // A real kill never reaches the "piece finished" bookkeeping of the last piece, so that entry is removed too.
    export("killed", record("killed", 1) { d ->
        val last = SafeSessions.segments(d).last()
        java.io.RandomAccessFile(last, "rw").use { it.setLength(it.length() - 33) }
        val number = SafeSessions.segmentNumber(last)
        SessionInfo(d).write((SessionInfo(d).read() ?: emptyMap()).filterKeys { it != SessionInfo.framesKey(number) })
    }, interrupted = true)
    // Everything wiped? A wiped buffer must read as zeros.
    val probe = FrameBuffer(); probe.add(byteArrayOf(1, 2, 3)); probe.wipe()
    println("wipedBufferCount=${probe.count}")
}
