package com.woundcopilot.android

import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties

/*
 * PROTOTYPE (sandbox build only). Crash-resistant recording storage.
 *
 * Idea in plain words: the sound is saved as a chain of small "AAC frames" (about 43 ms each). Every frame is its own
 * little record, so a file can be read up to its last complete record even if the app was killed while writing it.
 * The chain is split into segment files (default 5 minutes each). Nothing here needs Android, so it is tested on a PC.
 *
 * Two record styles share this code:
 *  - plain  : standard ADTS frames (7-byte header + sound). No protection, used for tests and as a reference.
 *  - sealed : each frame is encrypted and signed on its own (see SealedAudio.kt), and carries a running frame number,
 *             so a deleted, damaged, repeated or reordered piece is detected and its exact size is known.
 */

/** ADTS framing: a 7-byte header in front of every AAC frame. */
internal object Adts {
    const val HEADER_BYTES = 7
    const val SAMPLES_PER_FRAME = 1024
    private val RATES = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000)

    fun rateIndex(sampleRate: Int): Int {
        val index = RATES.indexOf(sampleRate)
        require(index >= 0) { "Unsupported sample rate $sampleRate" }
        return index
    }

    /** Header for an AAC-LC frame whose raw payload is [payloadBytes] long. */
    fun header(payloadBytes: Int, sampleRate: Int, channels: Int): ByteArray {
        require(payloadBytes in 1..(8191 - HEADER_BYTES)) { "Frame size out of range" }
        require(channels in 1..7)
        val length = payloadBytes + HEADER_BYTES
        val profile = 2 // AAC-LC
        return byteArrayOf(
            0xFF.toByte(),
            0xF1.toByte(), // MPEG-4, layer 0, no CRC
            (((profile - 1) shl 6) or (rateIndex(sampleRate) shl 2) or (channels shr 2)).toByte(),
            (((channels and 3) shl 6) or (length shr 11)).toByte(),
            ((length and 0x7FF) shr 3).toByte(),
            (((length and 7) shl 5) or 0x1F).toByte(),
            0xFC.toByte(),
        )
    }

    /** Total frame length (header + payload) if a valid header for this format starts at [offset], else -1. */
    fun frameLength(buffer: ByteArray, offset: Int, available: Int, sampleRate: Int, channels: Int): Int {
        if (available < HEADER_BYTES) return -1
        val b = IntArray(HEADER_BYTES) { buffer[offset + it].toInt() and 0xFF }
        if (b[0] != 0xFF || b[1] != 0xF1) return -1
        if ((b[2] shr 6) != 1) return -1 // AAC-LC only
        if (((b[2] shr 2) and 0xF) != rateIndex(sampleRate)) return -1
        if ((((b[2] and 1) shl 2) or (b[3] shr 6)) != channels) return -1
        if ((b[5] and 0x1F) != 0x1F || (b[6] and 0xFC) != 0xFC) return -1
        val length = ((b[3] and 3) shl 11) or (b[4] shl 3) or (b[5] shr 5)
        return if (length > HEADER_BYTES) length else -1
    }

    fun framesToMillis(frames: Long, sampleRate: Int): Long = frames * SAMPLES_PER_FRAME * 1000L / sampleRate
    /** Number of frames that cover at least [seconds] of sound. */
    fun framesFor(seconds: Int, sampleRate: Int): Int = ((seconds.toLong() * sampleRate + SAMPLES_PER_FRAME - 1) / SAMPLES_PER_FRAME).toInt()
}

/** One frame read back from a segment. [index] is the running frame number, or -1 when the style does not record it. */
internal class FrameRecord(val index: Long, val payload: ByteArray)

/** How one frame becomes bytes on disk, and how it is read back. */
internal interface FrameCodec {
    /** True when each record carries its own running frame number (so lost frames can be counted exactly). */
    val indexed: Boolean
    fun encode(segment: Int, index: Long, payload: ByteArray, length: Int): ByteArray
    /** Reads the next record, or returns null at the first thing that is not a complete, valid record. */
    fun read(input: InputStream, segment: Int, consumed: IntArray): FrameRecord?
}

internal fun InputStream.readFullyOrFalse(buffer: ByteArray, count: Int): Boolean {
    var done = 0
    while (done < count) {
        val n = read(buffer, done, count - done)
        if (n < 0) return false
        done += n
    }
    return true
}

internal class PlainAdtsCodec(private val sampleRate: Int, private val channels: Int) : FrameCodec {
    override val indexed = false
    override fun encode(segment: Int, index: Long, payload: ByteArray, length: Int): ByteArray {
        val out = ByteArray(Adts.HEADER_BYTES + length)
        System.arraycopy(Adts.header(length, sampleRate, channels), 0, out, 0, Adts.HEADER_BYTES)
        System.arraycopy(payload, 0, out, Adts.HEADER_BYTES, length)
        return out
    }
    override fun read(input: InputStream, segment: Int, consumed: IntArray): FrameRecord? {
        val header = ByteArray(Adts.HEADER_BYTES)
        if (!input.readFullyOrFalse(header, Adts.HEADER_BYTES)) return null
        val length = Adts.frameLength(header, 0, Adts.HEADER_BYTES, sampleRate, channels)
        if (length < 0) return null
        val payload = ByteArray(length - Adts.HEADER_BYTES)
        if (!input.readFullyOrFalse(payload, payload.size)) return null
        consumed[0] = length
        return FrameRecord(-1, payload)
    }
}

/** Small settings file for one recording session, replaced atomically so a kill can never leave half a file. */
internal class SessionInfo(val directory: File) {
    private val file = File(directory, "session.properties")
    fun write(values: Map<String, String>) = synchronized(LOCK) {
        directory.mkdirs()
        val properties = Properties().apply { values.forEach { (k, v) -> setProperty(k, v) } }
        val temporary = File(directory, "session.properties.tmp")
        FileOutputStream(temporary).use { out ->
            properties.store(out, null)
            out.fd.sync()
        }
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
    fun read(): Map<String, String>? {
        if (!file.isFile) return null
        return try {
            val properties = Properties()
            FileInputStream(file).use { properties.load(it) }
            properties.stringPropertyNames().associateWith { properties.getProperty(it) }
        } catch (_: IOException) { null }
    }
    /** Read-change-save as one step, so the clock updates and the piece counts (two threads) can never overwrite each other. */
    fun update(key: String, value: String) = synchronized(LOCK) { write((read() ?: emptyMap()) + (key to value)) }
    companion object {
        private val LOCK = Any()
        fun framesKey(segment: Int) = "seg.$segment.frames"
    }
}

/**
 * Writes frames into numbered segment files (seg-00001.aac, ...). A new segment starts only on a record boundary,
 * so joining the segments in order gives exactly the frames that were written: nothing lost, nothing repeated.
 * [onSegmentClosed] is told how many frames each finished segment holds, so recovery can later notice a short piece.
 */
internal class SegmentWriter(
    private val directory: File,
    private val codec: FrameCodec,
    private val framesPerSegment: Int,
    private val syncEveryFrames: Int = 120,
    private val onSegmentClosed: (segment: Int, frames: Int) -> Unit = { _, _ -> },
) : AutoCloseable {
    init { require(framesPerSegment > 0 && syncEveryFrames > 0) }
    private var stream: FileOutputStream? = null
    private var framesInSegment = 0
    private var sinceSync = 0
    var framesWritten = 0L; private set
    var segmentsOpened = 0; private set

    fun segmentFile(number: Int) = File(directory, "seg-%05d.aac".format(number))

    @Throws(IOException::class)
    fun writeFrame(payload: ByteArray, length: Int = payload.size) {
        if (stream == null || framesInSegment >= framesPerSegment) rotate()
        val record = codec.encode(segmentsOpened, framesWritten, payload, length)
        // One write per frame, no extra buffering: once write() returns, the frame survives the app being killed.
        stream!!.write(record, 0, record.size)
        framesInSegment++; framesWritten++; sinceSync++
        if (sinceSync >= syncEveryFrames) syncToDisk()
    }

    /** Also protects against the phone losing power. A process kill does not need this. */
    fun syncToDisk() { stream?.fd?.sync(); sinceSync = 0 }

    private fun rotate() {
        closeCurrent()
        directory.mkdirs()
        var number = segmentsOpened + 1
        while (segmentFile(number).exists()) number++ // never overwrite earlier audio
        stream = FileOutputStream(segmentFile(number))
        segmentsOpened = number
        framesInSegment = 0
    }

    private fun closeCurrent() {
        val s = stream ?: return
        runCatching { s.fd.sync() }
        s.close()
        stream = null
        // Reported only after the data is on disk. A failure here must never stop the recording.
        runCatching { onSegmentClosed(segmentsOpened, framesInSegment) }
    }

    override fun close() { closeCurrent() }
}

/** What a scan found in one segment file. [validBytes] ends exactly on a record boundary. */
internal data class ScanResult(val frames: Int, val validBytes: Long, val totalBytes: Long, val firstIndex: Long, val lastIndex: Long) {
    val trailingBytes: Long get() = totalBytes - validBytes
}

internal object SegmentScanner {
    /** Reads [file] from the start and stops at the first byte that is not part of a complete, valid record. */
    fun scan(file: File, segment: Int, codec: FrameCodec): ScanResult {
        val total = file.length()
        if (total == 0L) return ScanResult(0, 0, 0, -1, -1)
        var frames = 0
        var position = 0L
        var first = -1L
        var last = -1L
        val consumed = IntArray(1)
        BufferedInputStream(FileInputStream(file), 64 * 1024).use { input ->
            while (true) {
                val record = codec.read(input, segment, consumed) ?: break
                // Inside one segment, frame numbers must go up by exactly one. Anything else is damage.
                if (codec.indexed && last >= 0 && record.index != last + 1) break
                if (first < 0) first = record.index
                last = record.index
                position += consumed[0]
                frames++
            }
        }
        return ScanResult(frames, position, total, first, last)
    }
}

/** One piece of a segment file that may be used: the first [validBytes] bytes. */
internal data class SegmentSlice(
    val file: File, val number: Int, val validBytes: Long, val frames: Int, val trailingBytes: Long,
    val firstIndex: Long, val lastIndex: Long,
)

internal enum class ProblemKind { MISSING_AUDIO, DAMAGED_PIECE, REPEATED_AUDIO, CUT_SHORT, UNREADABLE }

/** [lostMillis] is null when the amount cannot be known (plain style without a count for that piece). */
internal data class RecoveryProblem(val kind: ProblemKind, val atMillis: Long, val lostMillis: Long?, val detail: String)

internal data class RecoveryPlan(
    val slices: List<SegmentSlice>,
    val sampleRate: Int,
    val channels: Int,
    val problems: List<RecoveryProblem>,
    /** The last saved piece ended in a partly written record (normal after a kill; at most a fraction of a second). */
    val tornTail: Boolean,
) {
    val totalFrames: Long get() = slices.sumOf { it.frames.toLong() }
    val durationMillis: Long get() = Adts.framesToMillis(totalFrames, sampleRate)
    val discardedBytes: Long get() = slices.sumOf { it.trailingBytes }
    /** True when every segment ended cleanly (nothing was cut off). */
    val intact: Boolean get() = discardedBytes == 0L && problems.isEmpty()
    val hasMissingAudio: Boolean get() = problems.isNotEmpty()

    /** A plain-words warning for the clinician, or null when nothing is missing. */
    fun warning(): String? {
        if (problems.isEmpty()) return null
        val parts = problems.take(3).map { p ->
            val where = clock(p.atMillis)
            val amount = p.lostMillis?.let { lengthWords(it) }
            when (p.kind) {
                ProblemKind.REPEATED_AUDIO -> "some audio is repeated around $where"
                ProblemKind.CUT_SHORT -> if (amount != null) "the last $amount of the visit is missing (the audio stops at $where)" else "the end of the visit is missing (the audio stops at $where)"
                else -> if (amount != null) "about $amount of audio is missing around $where" else "some audio is missing around $where (the amount is unknown)"
            }
        }
        val more = if (problems.size > 3) " ${problems.size - 3} more problem(s) were found." else ""
        return "Part of this recording may be missing: " + parts.joinToString("; ") + ".$more " +
            "Listen to the whole recording and re-record anything important before relying on it."
    }

    companion object {
        fun clock(ms: Long): String { val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60) }
        fun lengthWords(ms: Long): String {
            val s = (ms + 500) / 1000
            return when {
                s < 1 -> "less than a second"
                s < 60 -> "$s second" + if (s == 1L) "" else "s"
                else -> "${s / 60} min " + "%02d s".format(s % 60)
            }
        }
    }
}

internal object SafeSessions {
    private val NAME = Regex("seg-(\\d{5})\\.aac")

    fun segmentNumber(file: File): Int = NAME.matchEntire(file.name)?.groupValues?.get(1)?.toInt() ?: -1

    fun segments(directory: File): List<File> =
        directory.listFiles { f -> f.isFile && NAME.matches(f.name) }.orEmpty().sortedBy { it.name }

    /**
     * Works out what can be recovered from a session folder, after a normal stop or after the app was killed, and
     * checks it for missing, damaged, skipped or repeated audio.
     *
     * [info] is the session settings (frame counts of finished pieces, how long the visit had run). [cleanStop] is true
     * when the visit ended with the Stop button, so a half-written last record is NOT expected.
     */
    fun plan(
        directory: File, codec: FrameCodec, sampleRate: Int, channels: Int,
        info: Map<String, String>? = null, cleanStop: Boolean = false,
    ): RecoveryPlan {
        val scans = segments(directory).map { file ->
            val number = segmentNumber(file)
            number to SegmentScanner.scan(file, number, codec)
        }
        val slices = scans.filter { it.second.frames > 0 }.map { (number, scan) ->
            SegmentSlice(File(directory, "seg-%05d.aac".format(number)), number, scan.validBytes, scan.frames, scan.trailingBytes, scan.firstIndex, scan.lastIndex)
        }
        val problems = mutableListOf<RecoveryProblem>()
        fun ms(frames: Long) = Adts.framesToMillis(frames, sampleRate)
        fun manifest(number: Int): Int? = info?.get(SessionInfo.framesKey(number))?.toIntOrNull()
        // Pieces that exist on disk but hold nothing readable.
        val unreadable = scans.filter { it.second.frames == 0 && it.second.totalBytes > 0 }.map { it.first }.toSet()

        var cumulative = 0L
        var previous: SegmentSlice? = null
        for (slice in slices) {
            val before = previous
            val skipped = slice.number - (before?.number ?: 0) - 1
            val damagedBefore = before != null && (before.trailingBytes > 0 || manifest(before.number)?.let { it != before.frames } == true)
            if (codec.indexed) {
                // Sealed style: every record carries its frame number, so the exact loss is known.
                val jump = slice.firstIndex - (before?.lastIndex ?: -1) - 1
                if (jump > 0) {
                    val kind = if (damagedBefore || skipped > 0 && ((before?.number ?: 0) + 1 until slice.number).any { it in unreadable }) ProblemKind.DAMAGED_PIECE else ProblemKind.MISSING_AUDIO
                    problems += RecoveryProblem(kind, ms(cumulative), ms(jump), "Before piece ${slice.number}")
                } else if (jump < 0) {
                    problems += RecoveryProblem(ProblemKind.REPEATED_AUDIO, ms(cumulative), 0, "Frame numbers go backwards before piece ${slice.number}")
                }
            } else if (skipped > 0 || damagedBefore) {
                // Plain style: add up the counts we were told about, if every one of them is known.
                val counts = ((before?.number ?: 0) + 1 until slice.number).map { manifest(it) }
                val shortfall = before?.let { p -> manifest(p.number)?.let { (it - p.frames).toLong() } }
                val known = counts.all { it != null } && (before == null || shortfall != null)
                val lost = if (known) counts.sumOf { it!!.toLong() } + (shortfall ?: 0L) else null
                val skippedUnreadable = ((before?.number ?: 0) + 1 until slice.number).any { it in unreadable }
                val kind = if (damagedBefore || skippedUnreadable) ProblemKind.DAMAGED_PIECE else ProblemKind.MISSING_AUDIO
                problems += RecoveryProblem(kind, ms(cumulative), lost?.let { ms(it) }, "Before piece ${slice.number}")
            }
            cumulative += slice.frames
            previous = slice
        }
        // The end of the visit.
        val last = slices.lastOrNull()
        val torn = (last?.trailingBytes ?: 0L) > 0
        if (last != null) {
            val expectedLast = manifest(last.number)
            val manifestTail = info?.keys?.filter { it.startsWith("seg.") && it.endsWith(".frames") }
                ?.mapNotNull { it.removePrefix("seg.").removeSuffix(".frames").toIntOrNull() }?.maxOrNull() ?: 0
            var lostAtEnd = 0L
            if (expectedLast != null && expectedLast > last.frames) lostAtEnd += (expectedLast - last.frames).toLong()
            if (manifestTail > last.number) lostAtEnd += (last.number + 1..manifestTail).sumOf { (manifest(it) ?: 0).toLong() }
            if (lostAtEnd > 0) {
                problems += RecoveryProblem(ProblemKind.CUT_SHORT, ms(cumulative), ms(lostAtEnd), "Finished pieces are missing at the end")
            } else if (cleanStop && torn) {
                problems += RecoveryProblem(ProblemKind.DAMAGED_PIECE, ms(cumulative), null, "The last piece ends in a broken record")
            }
        }
        // Compare with how long the visit had run (pauses excluded), as last saved about once a second.
        val ranMillis = info?.get("elapsedMillis")?.toLongOrNull()
        if (ranMillis != null && ranMillis > 0 && problems.none { it.lostMillis == null }) {
            val recovered = ms(cumulative)
            val explained = problems.sumOf { it.lostMillis ?: 0L }
            val residual = ranMillis - recovered - explained
            val allowed = if (cleanStop) 3_000L else 12_000L
            if (residual > allowed) {
                problems += RecoveryProblem(ProblemKind.CUT_SHORT, recovered, residual, "Less audio was found than the visit had recorded")
            }
        }
        return RecoveryPlan(slices, sampleRate, channels, problems, torn)
    }

    /** Reads the usable frames of every segment, in order, into [into] (memory only; nothing is written anywhere). */
    fun frames(plan: RecoveryPlan, codec: FrameCodec, into: FrameBuffer) {
        val consumed = IntArray(1)
        for (slice in plan.slices) {
            BufferedInputStream(FileInputStream(slice.file), 64 * 1024).use { input ->
                var left = slice.frames
                while (left > 0) {
                    val record = codec.read(input, slice.number, consumed) ?: throw IOException("Segment ${slice.file.name} changed while reading")
                    into.add(record.payload)
                    record.payload.fill(0)
                    left--
                }
            }
        }
    }

    /**
     * Writes the usable part of every segment, in order, as one standard ADTS stream (used before turning it into
     * an .m4a). Sound is handed over frame by frame; nothing is written to disk by this function.
     */
    fun join(plan: RecoveryPlan, codec: FrameCodec, output: OutputStream) {
        val consumed = IntArray(1)
        for (slice in plan.slices) {
            BufferedInputStream(FileInputStream(slice.file), 64 * 1024).use { input ->
                var left = slice.frames
                while (left > 0) {
                    val record = codec.read(input, slice.number, consumed) ?: throw IOException("Segment ${slice.file.name} changed while joining")
                    output.write(Adts.header(record.payload.size, plan.sampleRate, plan.channels))
                    output.write(record.payload)
                    left--
                }
            }
        }
    }
}
