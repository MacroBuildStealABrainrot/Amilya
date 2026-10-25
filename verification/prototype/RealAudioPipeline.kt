package com.woundcopilot.android

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile

/*
 * Feeds GENUINE encoder output (ffmpeg AAC-LC) through the real recorder storage code (sealed pieces, kill, damage,
 * missing piece, recovery, join) and writes the rebuilt ADTS audio so that real audio tools can check it.
 * Usage: RealAudioPipelineKt <fixture.aac> <outDir> [segmentFrames]
 * Output per scenario: <name>.aac (rebuilt audio) and <name>.txt (frames, milliseconds, warning).
 * No Android, no microphone: it proves the storage/recovery code on real audio, not the phone's encoder.
 */
fun main(args: Array<String>) {
    val fixture = File(args[0]); val out = File(args[1]).apply { mkdirs() }
    val perPiece = args.getOrNull(2)?.toInt() ?: 300
    val rate = 24000
    val payloads = AdtsFile.payloads(fixture.readBytes(), rate, 1)
    val wrapper = TestKeyWrapper()

    fun session(name: String, sealed: Boolean = true, cleanStop: Boolean = false, mutate: (File) -> Unit = {}) {
        val dir = File(out, "session-$name").apply { deleteRecursively(); mkdirs() }
        val manifest = mutableMapOf<String, String>()
        val key = if (sealed) SealedSession.begin(dir, wrapper, mapOf("finalName" to "$name.m4a")) else null
        val codec: FrameCodec = if (key != null) SealedCodec(key) else PlainAdtsCodec(rate, 1)
        SegmentWriter(dir, codec, perPiece, onSegmentClosed = { n, f -> manifest[SessionInfo.framesKey(n)] = f.toString() }).use { w -> payloads.forEach { w.writeFrame(it) } }
        mutate(dir)
        val reopened: FrameCodec = if (sealed) SealedCodec(SealedSession.open(SessionInfo(dir).read()!!, wrapper)!!) else codec
        val plan = SafeSessions.plan(dir, reopened, rate, 1, manifest.filterKeys { false }, cleanStop)
        val bytes = ByteArrayOutputStream().also { SafeSessions.join(plan, reopened, it) }.toByteArray()
        File(out, "$name.aac").writeBytes(bytes)
        File(out, "$name.txt").writeText(
            "frames=${plan.totalFrames}\nmillis=${plan.durationMillis}\nproblems=${plan.problems.size}\n" +
                plan.problems.joinToString("\n") { "problem=${it.kind} at=${it.atMillis} lost=${it.lostMillis}" } +
                "\nwarning=${plan.warning() ?: ""}\n",
        )
        println("$name: frames=${plan.totalFrames} problems=${plan.problems.size}")
    }

    File(out, "original.aac").writeBytes(fixture.readBytes())
    session("clean-sealed")
    session("clean-plain", sealed = false)
    session("killed-midrecord") { d -> RandomAccessFile(SafeSessions.segments(d).last(), "rw").use { it.setLength(it.length() - 33) } }
    session("missing-piece-2") { d -> File(d, "seg-00002.aac").delete() }
    session("damaged-piece-2") { d ->
        val f = SafeSessions.segments(d)[1]
        RandomAccessFile(f, "rw").use { r -> val at = f.length() / 2; r.seek(at); val b = r.read(); r.seek(at); r.write(b xor 0x40) }
    }
}
