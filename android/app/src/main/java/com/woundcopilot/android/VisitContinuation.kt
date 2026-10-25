package com.woundcopilot.android

/*
 * SANDBOX ONLY. Long visits: one recording can hold at most about an hour (the server accepts at most 3,602 seconds of audio per
 * recording), so after 59 minutes the recorder moves on to a SECOND recording without stopping the microphone or the encoder.
 *
 * Plain-words rules (tested on a PC; the service wiring that uses them has never been compiled or run):
 *  - Nothing is skipped. The last sound frame of the finished recording is written again at the start of the new one ("primer"),
 *    because an audio frame can only be decoded properly together with the one before it. The new recording therefore begins with
 *    about 43 ms of faded, repeated sound; no sound is missing. (Verified by decoding both parts and comparing with the whole.)
 *  - Each recording is encrypted with its own new key, exactly like any other recording.
 *  - Every part names the visit it belongs to and the parts before and after it, and says so in its note, so no part can be mistaken
 *    for a complete visit. All parts stay under the same patient visit (same owner and encounter context).
 *  - The clinician gets a heads-up 5 minutes before the hand-over. If the new recording cannot be started (storage, Keystore) the
 *    current one simply keeps recording (up to the old 59:50 safety limit), and the hand-over is retried every ~10 seconds.
 *  - At most MAX_PARTS parts (about 3 h 56 min); after that the recorder stops and saves with a clear message.
 */
internal object VisitContinuation {
    /** Sound per recording before moving on: 59:00. Leaves 2 minutes under the server's 3,602 s limit even with the repeated frame. */
    const val HAND_OFF_SECONDS = 3_540
    const val WARN_SECONDS_BEFORE = 300
    const val MAX_PARTS = 4
    /** After a failed hand-over, try again after this many sound frames (about 10 seconds at 24 kHz). */
    const val RETRY_AFTER_FRAMES = 430

    fun framesPerPart(sampleRate: Int): Int = Adts.framesFor(HAND_OFF_SECONDS, sampleRate)

    /** The heads-up text, or null when none is due. Shown once, [WARN_SECONDS_BEFORE] seconds ahead, and never on the last allowed part. */
    fun headsUp(partElapsedMillis: Long, part: Int, alreadyShown: Boolean): String? {
        if (alreadyShown || part >= MAX_PARTS) return null
        if (partElapsedMillis < (HAND_OFF_SECONDS - WARN_SECONDS_BEFORE) * 1000L) return null
        return "In about ${WARN_SECONDS_BEFORE / 60} minutes this recording will continue automatically in a second recording. No sound will be missed. " +
            "Both recordings stay with this visit and are sent together to make one report."
    }

    fun continuedNotice(part: Int) =
        "This visit is long, so recording continued in part $part. All parts stay with this visit and are sent together to make one report."

    fun finishedNotice(parts: Int): String? =
        if (parts <= 1) null else "This visit was recorded in $parts parts that stay together. All $parts parts are sent together and make one report."

    fun failedToContinue(part: Int) =
        if (part >= MAX_PARTS) "The recording limit was reached (about ${MAX_PARTS - 1} hours of one visit). The saved audio is ready; start another recording to continue."
        else "The recording could not continue into a new recording (the phone may be low on storage), so it was stopped and saved. Free some space and start another recording to continue."

    /**
     * All parts of one visit in recording order, starting from any part. [info] returns a part's stored settings by file name
     * (visitId, previousName, nextName), or null if that part is not on the phone. Follows the links backwards to part 1, then
     * forwards. Stops on a loop, on a part of another visit, or after [MAX_PARTS] + 1 steps. A part named by a link but missing
     * from the phone is still listed, so the caller can say clearly which part is missing.
     */
    fun orderParts(start: String, info: (String) -> Map<String, String>?): List<String> {
        val visit = info(start)?.get("visitId") ?: return listOf(start)
        val seen = mutableSetOf(start)
        fun walk(link: String): List<String> {
            val found = mutableListOf<String>()
            var current: String? = start
            while (current != null && seen.size <= MAX_PARTS) {
                val name = info(current)?.get(link) ?: break
                val linked = info(name)
                if (name in seen || linked != null && linked["visitId"] != visit) break
                seen += name; found += name
                current = if (linked == null) null else name   // a missing part ends the walk in that direction
            }
            return found
        }
        val before = walk("previousName").reversed()
        val after = walk("nextName")
        return before + start + after
    }

    /** Settings stored with a part's encrypted pieces. */
    fun settings(visitId: String, part: Int, previousName: String?): Map<String, String> =
        mapOf("visitId" to visitId, "visitPart" to part.toString()) + (previousName?.let { mapOf("previousName" to it) } ?: emptyMap())

    /** Note shown with a part, or null for an ordinary single recording. Plain words, no patient details. */
    fun note(part: Int?, previousName: String?, nextName: String?): String? = when {
        previousName == null && nextName == null -> null
        nextName != null -> "This is part ${part ?: 1} of a long visit. The visit continued in another recording. All parts are sent together and make one report."
        else -> "This is the last part of a long visit that was recorded in ${part ?: 2} parts. All parts are sent together and make one report."
    }
}

/** Where sound frames go: one part's encrypted pieces on the phone, or a fake one in a test. */
internal interface FrameSink : AutoCloseable {
    fun write(frame: ByteArray, length: Int)
    fun syncToDisk()
    /** Throw away a part that never received sound (a failed start); must never throw. */
    fun abandon()
}

/**
 * Sends frames to the current part and, when it is full, to the next one without losing or skipping a frame.
 * [openNext] creates the next part (it may throw: then the current part simply keeps going and the hand-over is retried).
 * [onPartFinished] is called after the old part has been closed (the service starts rebuilding it in the background).
 */
internal class HandoffRouter(
    first: FrameSink,
    private val framesPerPart: Int,
    private val openNext: (nextPart: Int) -> FrameSink,
    private val onPartFinished: (finished: FrameSink, uniqueFrames: Int, finishedPart: Int) -> Unit = { _, _, _ -> },
    private val maxParts: Int = VisitContinuation.MAX_PARTS,
    private val retryAfterFrames: Int = VisitContinuation.RETRY_AFTER_FRAMES,
) : AutoCloseable {
    private var current: FrameSink = first
    private var last: ByteArray? = null
    private var framesInPart = 0       // everything written to the current part, including the repeated first frame
    private var primerCount = 0        // 1 for parts after the first
    private var retryAt = 0
    var part = 1; private set
    var lastHandOffError: Throwable? = null; private set
    var handOffs = 0; private set

    init { require(framesPerPart > 1 && maxParts >= 1) }

    @Throws(java.io.IOException::class)
    fun writeFrame(frame: ByteArray, length: Int = frame.size) {
        if (framesInPart >= framesPerPart && part < maxParts && framesInPart >= retryAt) tryHandOff()
        current.write(frame, length)
        framesInPart++
        last?.fill(0)
        last = frame.copyOf(length)
    }

    fun syncToDisk() = current.syncToDisk()

    private fun tryHandOff() {
        var next: FrameSink? = null
        try {
            next = openNext(part + 1)
            last?.let { next.write(it, it.size) }  // the repeated frame: the new part can decode its first real frame completely
            val old = current
            val unique = framesInPart - primerCount
            val finishedPart = part
            current = next; part++; handOffs++
            framesInPart = if (last != null) 1 else 0
            primerCount = framesInPart
            lastHandOffError = null
            runCatching { old.close() }
            runCatching { onPartFinished(old, unique, finishedPart) }
        } catch (t: Throwable) {
            lastHandOffError = t
            retryAt = framesInPart + retryAfterFrames
            if (next != null && current !== next) runCatching { next.abandon() }
        }
    }

    override fun close() {
        runCatching { current.close() }
        last?.fill(0); last = null
    }
}
