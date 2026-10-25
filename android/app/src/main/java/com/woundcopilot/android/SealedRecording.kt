package com.woundcopilot.android

import java.io.File

/*
 * PROTOTYPE (sandbox build only). The "finished recording" in the sandbox is the set of encrypted pieces. This class opens
 * them (key unlocked through the KeyWrapper), checks them, and keeps the sound in memory only for as long as it is needed to
 * make an .m4a for playback or a 2-minute upload part. Nothing decrypted is written to disk.
 */
/** Result of opening a session folder. [recording] is null when it could not be unlocked or holds no sound; [reason] says why. */
internal class OpenedRecording(val recording: SealedRecording?, val plan: RecoveryPlan?, val reason: String?)

internal class SealedRecording private constructor(
    val frames: FrameBuffer,
    val plan: RecoveryPlan,
    val sampleRate: Int,
    val channels: Int,
) {
    val frameCount: Int get() = frames.count
    val durationMillis: Long get() = Adts.framesToMillis(frames.count.toLong(), sampleRate)
    val partFrames: Int = Mp4Aac.framesPerPart(PART_SECONDS, sampleRate)
    val partCount: Int get() = Mp4Aac.partCount(frames.count, partFrames)
    /** Each part after the first also starts with the last [OVERLAP_SECONDS] of the part before, so a word cut at the boundary is heard whole once. */
    val overlapFrames: Int = Mp4Aac.framesPerPart(OVERLAP_SECONDS, sampleRate)

    /** The whole recording as an .m4a (for playback). */
    fun whole(): ByteArray = Mp4Aac.build(frames, 0, frames.count, sampleRate, channels)

    /**
     * Part [index] (0-based) as an .m4a. The same recording always gives the same bytes for the same part.
     * Parts after the first begin [overlapFrames] early (3 seconds of sound repeated from the previous part); the server removes
     * the repeated words only when both copies match word for word (see backend/server/audio_overlap.py).
     */
    fun part(index: Int): ByteArray {
        require(index in 0 until partCount) { "No such part" }
        val (from, until) = partRange(index)
        return Mp4Aac.build(frames, from, until, sampleRate, channels)
    }

    /** First and end (exclusive) frame of part [index], including the repeated overlap. */
    fun partRange(index: Int): Pair<Int, Int> {
        val nominal = index * partFrames
        val from = if (index == 0) 0 else maxOf(0, nominal - overlapFrames)
        return from to minOf(frames.count, nominal + partFrames)
    }

    /** How much sound at the start of part [index] repeats the end of the part before (0 for the first part). */
    fun overlapMillis(index: Int): Long {
        val (from, _) = partRange(index)
        return Adts.framesToMillis((index * partFrames - from).toLong(), sampleRate)
    }

    fun wipe() = frames.wipe()

    companion object {
        /** Under the server's 130-second limit per part (the app has always cut at 120 s). */
        const val PART_SECONDS = 120
        /** Approved for the sandbox build: 3 seconds of overlap at every 2-minute boundary (parts are then at most 123 s). */
        const val OVERLAP_SECONDS = 3

        /**
         * Opens a session folder. [interrupted] is true when the app was killed before Stop (so a torn last record is normal).
         * The unlocked recording key is wiped before this function returns; only the decrypted sound stays, in memory.
         */
        fun open(directory: File, info: Map<String, String>, wrapper: KeyWrapper, interrupted: Boolean): OpenedRecording {
            val rate = info["sampleRate"]?.toIntOrNull() ?: 24_000
            val channels = info["channels"]?.toIntOrNull() ?: 1
            var key: SessionKey? = null
            val codec: FrameCodec = if (SealedSession.isSealed(info)) {
                key = SealedSession.open(info, wrapper) ?: return OpenedRecording(null, null, "locked")
                SealedCodec(key)
            } else PlainAdtsCodec(rate, channels)
            val buffer = FrameBuffer()
            try {
                val plan = SafeSessions.plan(directory, codec, rate, channels, info, cleanStop = !interrupted)
                if (plan.totalFrames == 0L) return OpenedRecording(null, plan, "empty")
                SafeSessions.frames(plan, codec, buffer)
                return OpenedRecording(SealedRecording(buffer, plan, rate, channels), plan, null)
            } catch (error: Exception) {
                buffer.wipe()
                return OpenedRecording(null, null, "unreadable")
            } finally {
                key?.wipe()
            }
        }
    }
}
