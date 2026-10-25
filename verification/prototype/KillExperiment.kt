package com.woundcopilot.android

import java.io.File
import java.nio.ByteBuffer
import java.util.Random

/*
 * Real process-kill experiment for the crash-resistant storage. No Android involved.
 * Parent: starts a child program that writes numbered fake AAC frames as fast as it can, waits a random time, then
 * kills it with SIGKILL (the same "no goodbye" ending Android gives a killed app). Then it checks what can be recovered.
 * Rule being tested: every frame the child reported as written must be recoverable, in order, with no gaps or repeats.
 */
fun main(args: Array<String>) {
    if (args.firstOrNull() == "child") child(File(args[1]), args[2].toInt(), args[3].toLong(), args[4] == "sealed", args.getOrNull(5) == "handoff") else parent(args)
}

private const val RATE = 24000

/** Both sides build the same codec from the same seed. In the real app the key is locked in the Keystore instead. */
private fun codecFor(seed: Long, sealed: Boolean): FrameCodec {
    if (!sealed) return PlainAdtsCodec(RATE, 1)
    val r = Random(seed * 31 + 7)
    return SealedCodec(SessionKey(ByteArray(32).also(r::nextBytes), ByteArray(4).also(r::nextBytes), ByteArray(16).also(r::nextBytes)))
}

private const val HANDOFF_FRAMES = 150

/** Hand-over mode: numbered frames go through the recorder's HandoffRouter into encrypted parts (folders p1, p2, ...), each part with its own key. */
private fun childHandoff(root: File, perSegment: Int, seed: Long) {
    val random = Random(seed)
    class Sink(val dir: File, part: Int) : FrameSink {
        val writer = SegmentWriter(dir, codecFor(seed * 100 + part, true), perSegment)
        override fun write(frame: ByteArray, length: Int) = writer.writeFrame(frame, length)
        override fun syncToDisk() = writer.syncToDisk()
        override fun abandon() { runCatching { writer.close() } }
        override fun close() { writer.close() }
    }
    val router = HandoffRouter(Sink(File(root, "p1"), 1), HANDOFF_FRAMES, openNext = { n -> Sink(File(root, "p$n"), n) }, maxParts = 1000, retryAfterFrames = 5)
    var index = 0
    while (true) {
        val frame = ByteArray(8 + random.nextInt(180)).also { random.nextBytes(it); ByteBuffer.wrap(it).putInt(0, index) }
        router.writeFrame(frame)
        index++
        if (index % 50 == 0) { println(index); System.out.flush() }
    }
}

private fun child(dir: File, perSegment: Int, seed: Long, sealed: Boolean, handoff: Boolean = false) {
    if (handoff) return childHandoff(dir, perSegment, seed)
    val random = Random(seed)
    SegmentWriter(dir, codecFor(seed, sealed), perSegment).use { writer ->
        var index = 0
        while (true) {
            val frame = ByteArray(8 + random.nextInt(180)).also { random.nextBytes(it); ByteBuffer.wrap(it).putInt(0, index) }
            writer.writeFrame(frame)
            index++
            if (index % 50 == 0) { println(index); System.out.flush() }
        }
    }
}

private fun parent(args: Array<String>) {
    val trials = args.getOrNull(0)?.toInt() ?: 60
    val javaBin = File(System.getProperty("java.home"), "bin/java").path
    val classpath = System.getProperty("java.class.path")
    val random = Random(2026)
    var worst = Long.MAX_VALUE; var failures = 0; var crossedBoundary = 0; var withPartialTail = 0; var maxFrames = 0L; var sealedTrials = 0; var phase2Ok = 0; var handoffOk = 0; var handoffCrossed = 0; var falseAlarms = 0
    val root = File(System.getProperty("java.io.tmpdir"), "wcp-kill-" + System.nanoTime()).apply { mkdirs() }
    repeat(trials) { trial ->
        val dir = File(root, "t$trial")
        val perSegment = listOf(50, 120, 400)[trial % 3]
        val sealed = trial % 2 == 1
        val handoff = trial % 4 == 3 // a quarter of the trials run through the long-visit hand-over (all of them are encrypted trials)
        if (sealed) sealedTrials++
        val child = ProcessBuilder(javaBin, "-cp", classpath, "com.woundcopilot.android.KillExperimentKt", "child", dir.path, perSegment.toString(), (1000L + trial).toString(), if (sealed) "sealed" else "plain", if (handoff) "handoff" else "-")
            .redirectErrorStream(false).start()
        val reader = child.inputStream.bufferedReader()
        val stopAfterReports = 1 + random.nextInt(300)
        var lastReported = 0
        repeat(stopAfterReports) { reader.readLine()?.let { lastReported = it.trim().toInt() } }
        child.destroyForcibly().waitFor() // SIGKILL
        if (handoff) { // long-visit hand-over: every part must recover on its own, and together they must hold every reported frame exactly once (plus one repeated frame per join)
            val partDirs = dir.listFiles { f -> f.isDirectory && f.name.startsWith("p") }.orEmpty().sortedBy { it.name.drop(1).toInt() }
            var ok = partDirs.isNotEmpty(); var next = 0; var allFrames = 0L; var joins = 0
            partDirs.forEachIndexed { i, pdir ->
                val c = codecFor((1000L + trial) * 100 + (i + 1), true)
                val plan = SafeSessions.plan(pdir, c, RATE, 1)
                if (plan.problems.isNotEmpty()) { falseAlarms++; println("FALSE ALARM trial $trial part ${i + 1}: ${plan.warning()}") }
                val buf = FrameBuffer(); SafeSessions.frames(plan, c, buf)
                for (k in 0 until buf.count) {
                    val n = ByteBuffer.wrap(buf.copyOfFrame(k), 0, 4).int
                    if (i > 0 && k == 0) { if (n != next - 1) ok = false; joins++ } // the repeated frame
                    else { if (n != next) ok = false; next = n + 1 }
                }
                allFrames += buf.count; buf.wipe()
            }
            if (!ok || next < lastReported) { failures++; println("FAIL trial $trial (hand-over): reported=$lastReported recovered up to ${next - 1} parts=${partDirs.size}") }
            else { handoffOk++; if (partDirs.size > 1) handoffCrossed++ }
            worst = minOf(worst, next - lastReported.toLong())
            dir.deleteRecursively(); return@repeat
        }
        val codec = codecFor(1000L + trial, sealed)
        val plan = SafeSessions.plan(dir, codec, RATE, 1)
        if (plan.problems.isNotEmpty()) { falseAlarms++; println("FALSE ALARM trial $trial: ${plan.warning()}") } // a plain kill must not look like missing audio
        val out = java.io.ByteArrayOutputStream().also { SafeSessions.join(plan, codec, it) }.toByteArray()
        // read the joined stream back and verify the numbering is 0,1,2,... with no gap or repeat
        var position = 0; var expected = 0; var ok = true
        while (position < out.size) {
            val length = Adts.frameLength(out, position, out.size - position, RATE, 1)
            if (length < 0 || ByteBuffer.wrap(out, position + Adts.HEADER_BYTES, 4).int != expected) { ok = false; break }
            position += length; expected++
        }
        val recovered = plan.totalFrames
        if (ok && sealed) { // Phase 2 path after a real kill: decrypt into memory, numbering must be 0,1,2,... and an .m4a must build
            val buffer = FrameBuffer(); SafeSessions.frames(plan, codec, buffer)
            var inMemoryOk = buffer.count.toLong() == recovered
            for (i in 0 until buffer.count) if (!inMemoryOk || ByteBuffer.wrap(buffer.copyOfFrame(i), 0, 4).int != i) { inMemoryOk = false; break }
            val m4a = if (buffer.count > 0) Mp4Aac.build(buffer, 0, buffer.count, RATE, 1) else ByteArray(0)
            if (buffer.count > 0 && (m4a.size < buffer.totalBytes || String(m4a, 4, 4, Charsets.US_ASCII) != "ftyp")) inMemoryOk = false
            buffer.wipe(); if (buffer.count != 0) inMemoryOk = false
            if (!inMemoryOk) { ok = false; println("FAIL trial $trial: in-memory rebuild after kill is wrong") } else phase2Ok++
        }
        if (!ok || recovered < lastReported || plan.problems.isNotEmpty()) { failures++; println("FAIL trial $trial: reported=$lastReported recovered=$recovered ok=$ok") }
        worst = minOf(worst, recovered - lastReported); maxFrames = maxOf(maxFrames, recovered)
        if (plan.slices.size > 1) crossedBoundary++
        if (!plan.intact) withPartialTail++
        dir.deleteRecursively()
    }
    root.deleteRecursively()
    println("trials=$trials (sealed=$sealedTrials, in-memory .m4a rebuilt correctly after the kill in $phase2Ok; hand-over trials ok $handoffOk, of which $handoffCrossed were killed after the first hand-over) failures=$failures falseMissingAudioWarnings=$falseAlarms killedAfterSegmentBoundary=$crossedBoundary withPartialLastFrame=$withPartialTail " +
        "minimum(recovered - reported)=$worst largestRun=${maxFrames} frames (${Adts.framesToMillis(maxFrames, RATE) / 1000} s of sound)")
    System.exit(if (failures == 0) 0 else 1)
}
