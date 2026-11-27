package com.woundcopilot.android

import kotlinx.coroutines.delay
import org.json.JSONObject
import java.util.UUID

private const val legacyPendingText = "This AI request is already processing or awaiting reconciliation. Refresh the case before retrying."
private val voiceRequestId = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")

internal fun ApiException.voicePending(): Boolean = status == 409 &&
    (transcriptionState == "pending" || transcriptionState == "needs_review" || message == legacyPendingText)

internal fun voiceRecordingVisit(pending: String?, pinned: String?, accountCache: String?, newBatch: Boolean): String? {
    check(pending == null || pinned == null || pending == pinned) { "Could not match this recording to its original transcription request." }
    val visit = (pending ?: pinned ?: accountCache)?.takeIf { it.isNotBlank() }
    check(newBatch || visit != null) { "Could not find the original transcription request." }
    return visit
}

internal data class VoicePendingPart(
    val visit: String, val job: String, val index: Int, val count: Int,
    val review: Boolean, val reference: String?,
) {
    fun encode(): String = obj("visit" to visit, "job" to job, "index" to index, "count" to count,
        "review" to review, "reference" to reference).toString()

    fun matches(batch: JSONObject): Boolean =
        batch.str("job_id") == job && batch.optJSONArray("files")?.length() == count &&
            batch.optInt("completed", -1) == index

    companion object {
        fun read(data: String): VoicePendingPart {
            val json = JSONObject(data)
            val part = VoicePendingPart(json.getString("visit"), json.getString("job"), json.getInt("index"),
                json.getInt("count"), json.optBoolean("review"), json.str("reference").takeIf(voiceRequestId::matches))
            require(part.visit.isNotBlank() && UUID.fromString(part.job).toString() == part.job &&
                part.count in 1..32 && part.index in 0 until part.count) { "The saved transcription request could not be read." }
            return part
        }

        fun from(visit: String, batch: JSONObject, error: ApiException): VoicePendingPart =
            VoicePendingPart(visit, batch.getString("job_id"), batch.getInt("completed"), batch.getJSONArray("files").length(),
                error.transcriptionState == "needs_review", error.requestId?.takeIf(voiceRequestId::matches))
    }
}

internal sealed class VoicePartCheck {
    object Waiting : VoicePartCheck()
    object Ready : VoicePartCheck()
    object LegacyIncomplete : VoicePartCheck()
    data class Complete(val result: JSONObject) : VoicePartCheck()
}

internal class VoiceStillPending : Exception()

/** Only probe for already saved data here. A new upload is never used as a status check. */
internal suspend fun waitForVoicePart(
    part: VoicePendingPart, manual: Boolean,
    pause: suspend (Long) -> Unit = { delay(it) }, probe: suspend () -> VoicePartCheck,
): VoicePartCheck {
    if (part.review && !manual) throw VoiceStillPending()
    val delays = if (part.review) listOf(0L) else listOf(0L, 3_000L, 7_000L, 15_000L)
    for (wait in delays) {
        if (wait > 0) pause(wait)
        val saved = probe()
        if (saved == VoicePartCheck.LegacyIncomplete) {
            // On an older service, only the original segment request can replay a saved partial result.
            if (manual && !part.review) return saved
        } else if (saved != VoicePartCheck.Waiting) return saved
    }
    throw VoiceStillPending()
}

/** Older servers have no per-section status endpoint. Finalizing existing parts does not run speech recognition. */
internal suspend fun checkVoicePart(part: VoicePendingPart, request: suspend (String, String, JSONObject?, String?) -> JSONObject): VoicePartCheck {
    val root = "/encounters/${part.visit}"
    val status = try {
        request("$root/audio-parts/${part.job}/status?index=${part.index}&count=${part.count}", "GET", null, null)
    } catch (e: ApiException) {
        if (e.status != 404 && e.status != 405) throw e
        null
    }
    if (status != null) {
        check(status.optInt("index", -1) == part.index) { "Could not verify this recording's transcription status." }
        return when (status.str("status")) {
            "ready" -> VoicePartCheck.Ready
            "pending" -> VoicePartCheck.Waiting
            else -> error("Could not verify this recording's transcription status.")
        }
    }
    val result = try {
        request("$root/audio-complete", "POST", obj("job_id" to part.job, "count" to part.count), "${part.job}-complete")
    } catch (e: ApiException) {
        if (e.status == 409 && e.message == "A recording segment still needs transcription. Retry that segment first.")
            return VoicePartCheck.LegacyIncomplete
        throw e
    }
    val source = result.getJSONObject("evidence")
    val metadata = source.getJSONObject("metadata")
    check(source.str("kind") == "audio" && source.str("encounter_id") == part.visit &&
        metadata.str("job_id") == part.job && metadata.optInt("segment_count") == part.count) {
        "Could not verify the transcript belongs to this recording."
    }
    return VoicePartCheck.Complete(result)
}
