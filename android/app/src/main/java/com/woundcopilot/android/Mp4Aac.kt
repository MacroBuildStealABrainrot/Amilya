package com.woundcopilot.android

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/*
 * PROTOTYPE (sandbox build only). Builds a standard MPEG-4 audio file (.m4a) IN MEMORY from raw AAC frames.
 *
 * Why this exists: the finished recording is no longer a plain .m4a file on disk. It stays as encrypted pieces, and an
 * ordinary .m4a is made in memory only when it is needed (to play it, or to upload a 2-minute part). This writer does not
 * touch a disk and does not depend on Android's MediaMuxer, so it can be checked on a PC with ffprobe/ffmpeg and with the
 * server's own audio checks. The output is deterministic (no clock values), so the same frames always give the same bytes
 * and a retried upload is recognised by the server as the same part.
 */

/**
 * Many small byte strings (AAC frames) kept in one block of memory instead of one object each. When it has to grow it wipes
 * the block it leaves behind, and [wipe] clears everything, so sound is not left lying around in memory after use.
 */
internal class FrameBuffer(initialBytes: Int = 1 shl 20) {
    private var data = ByteArray(initialBytes.coerceAtLeast(1024))
    private var starts = IntArray(4096)
    private var used = 0
    var count = 0; private set
    val totalBytes: Int get() = used

    fun add(source: ByteArray, length: Int = source.size) {
        require(length > 0)
        if (used + length > data.size) {
            val bigger = ByteArray(maxOf(data.size * 2, used + length))
            System.arraycopy(data, 0, bigger, 0, used)
            data.fill(0); data = bigger
        }
        if (count + 1 >= starts.size) starts = starts.copyOf(starts.size * 2)
        System.arraycopy(source, 0, data, used, length)
        starts[count] = used
        used += length
        count++
        starts[count] = used
    }

    fun size(index: Int): Int = starts[index + 1] - starts[index]
    fun start(index: Int): Int = starts[index]
    fun copyOfFrame(index: Int): ByteArray = data.copyOfRange(starts[index], starts[index + 1])
    fun writeTo(out: java.io.OutputStream, from: Int, until: Int) { if (until > from) out.write(data, starts[from], starts[until] - starts[from]) }
    fun bytesBetween(from: Int, until: Int): Int = starts[until] - starts[from]

    fun wipe() { data.fill(0); starts.fill(0); used = 0; count = 0 }
}

internal object Mp4Aac {
    const val SAMPLES_PER_FRAME = 1024

    /** The two-byte "audio specific config" for AAC-LC at this rate and channel count. */
    fun audioSpecificConfig(sampleRate: Int, channels: Int): ByteArray {
        val value = (2 shl 11) or (Adts.rateIndex(sampleRate) shl 7) or (channels shl 3)
        return byteArrayOf((value shr 8).toByte(), value.toByte())
    }

    /** Frames per upload part: just under [seconds] so a part never exceeds the server's per-part limit. */
    fun framesPerPart(seconds: Int, sampleRate: Int): Int = (seconds.toLong() * sampleRate / SAMPLES_PER_FRAME).toInt()

    fun partCount(totalFrames: Int, framesPerPart: Int): Int = if (totalFrames <= 0) 0 else (totalFrames + framesPerPart - 1) / framesPerPart

    /** An .m4a holding frames [from, until). */
    fun build(frames: FrameBuffer, from: Int, until: Int, sampleRate: Int, channels: Int): ByteArray {
        require(from in 0 until until && until <= frames.count) { "No frames to write" }
        val n = until - from
        val payload = frames.bytesBetween(from, until)
        val ftyp = box("ftyp") { writeBytes("M4A "); writeInt(0); writeBytes("M4A "); writeBytes("mp42"); writeBytes("isom") }
        val mdatSize = 8L + payload
        require(mdatSize < 0x7FFF_FFFFL) { "Part too large" }
        val sizes = IntArray(n) { frames.size(from + it) }
        var maxSize = 0; for (s in sizes) if (s > maxSize) maxSize = s
        val bitrate = ((payload.toLong() * 8L * sampleRate) / (n.toLong() * SAMPLES_PER_FRAME)).toInt()
        val withoutOffset = moov(n, sizes, maxSize, bitrate, 0, sampleRate, channels)
        val moov = moov(n, sizes, maxSize, bitrate, ftyp.size + withoutOffset.size + 8, sampleRate, channels)
        val out = ByteArrayOutputStream(ftyp.size + moov.size + 8 + payload)
        out.write(ftyp); out.write(moov)
        DataOutputStream(out).apply { writeInt(mdatSize.toInt()); writeBytes("mdat") }
        frames.writeTo(out, from, until)
        return out.toByteArray()
    }

    private fun box(type: String, body: DataOutputStream.() -> Unit): ByteArray {
        val inner = ByteArrayOutputStream()
        DataOutputStream(inner).body()
        val bytes = inner.toByteArray()
        val out = ByteArrayOutputStream(bytes.size + 8)
        DataOutputStream(out).apply { writeInt(bytes.size + 8); writeBytes(type) }
        out.write(bytes)
        return out.toByteArray()
    }

    private fun full(type: String, body: DataOutputStream.() -> Unit) = box(type) { writeInt(0); body() }

    private fun moov(n: Int, sizes: IntArray, maxSize: Int, bitrate: Int, dataOffset: Int, rate: Int, channels: Int): ByteArray {
        val movieMillis = n.toLong() * SAMPLES_PER_FRAME * 1000L / rate
        val mediaUnits = n.toLong() * SAMPLES_PER_FRAME
        val mvhd = full("mvhd") {
            writeInt(0); writeInt(0); writeInt(1000); writeInt(movieMillis.toInt())
            writeInt(0x00010000); writeShort(0x0100); writeShort(0); writeInt(0); writeInt(0)
            matrix(); repeat(6) { writeInt(0) }; writeInt(2)
        }
        val tkhd = box("tkhd") {
            writeInt(0x00000003); writeInt(0); writeInt(0); writeInt(1); writeInt(0); writeInt(movieMillis.toInt())
            writeInt(0); writeInt(0); writeShort(0); writeShort(1); writeShort(0x0100); writeShort(0)
            matrix(); writeInt(0); writeInt(0)
        }
        val mdhd = full("mdhd") { writeInt(0); writeInt(0); writeInt(rate); writeInt(mediaUnits.toInt()); writeShort(0x55C4); writeShort(0) }
        val hdlr = full("hdlr") { writeInt(0); writeBytes("soun"); writeInt(0); writeInt(0); writeInt(0); writeBytes("SoundHandler"); writeByte(0) }
        val smhd = full("smhd") { writeShort(0); writeShort(0) }
        val dinf = box("dinf") { write(full("dref") { writeInt(1); write(full("url ") { }.also { it[11] = 1 }) }) }
        val esds = full("esds") {
            val asc = audioSpecificConfig(rate, channels)
            val decoderSpecific = byteArrayOf(0x05, asc.size.toByte()) + asc
            val decoderConfig = byteArrayOf(0x04, (13 + decoderSpecific.size).toByte(), 0x40, 0x15, 0, (maxSize shr 8).toByte(), maxSize.toByte()) +
                int(bitrate.coerceAtLeast(maxSize * 8)) + int(bitrate) + decoderSpecific
            val slConfig = byteArrayOf(0x06, 0x01, 0x02)
            val es = byteArrayOf(0x03, (3 + decoderConfig.size + slConfig.size).toByte(), 0, 0, 0) + decoderConfig + slConfig
            write(es)
        }
        val mp4a = box("mp4a") {
            repeat(6) { writeByte(0) }; writeShort(1); writeInt(0); writeInt(0)
            writeShort(channels); writeShort(16); writeShort(0); writeShort(0); writeInt(rate shl 16)
            write(esds)
        }
        val stsd = full("stsd") { writeInt(1); write(mp4a) }
        val stts = full("stts") { writeInt(1); writeInt(n); writeInt(SAMPLES_PER_FRAME) }
        val stsc = full("stsc") { writeInt(1); writeInt(1); writeInt(n); writeInt(1) }
        val stsz = full("stsz") { writeInt(0); writeInt(n); for (s in sizes) writeInt(s) }
        val stco = full("stco") { writeInt(1); writeInt(dataOffset) }
        val stbl = box("stbl") { write(stsd); write(stts); write(stsc); write(stsz); write(stco) }
        val minf = box("minf") { write(smhd); write(dinf); write(stbl) }
        val mdia = box("mdia") { write(mdhd); write(hdlr); write(minf) }
        val trak = box("trak") { write(tkhd); write(mdia) }
        return box("moov") { write(mvhd); write(trak) }
    }

    private fun DataOutputStream.matrix() {
        writeInt(0x00010000); writeInt(0); writeInt(0); writeInt(0); writeInt(0x00010000); writeInt(0); writeInt(0); writeInt(0); writeInt(0x40000000)
    }

    private fun int(value: Int) = byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())
}
