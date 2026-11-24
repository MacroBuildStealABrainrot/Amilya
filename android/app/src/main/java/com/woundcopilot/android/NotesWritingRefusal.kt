package com.woundcopilot.android

/** A prompt to review existing words, scoped to exactly the visit and revision that requested AI writing. */
internal data class NotesWritingRefusal(val owner: String, val visit: String, val revision: Int, val reason: String) {
    fun matches(currentOwner: String, currentVisit: String, currentRevision: Int) =
        owner == currentOwner && visit == currentVisit && revision == currentRevision
}

internal fun notesWritingRefusalReason(error: ApiException): String? {
    if (error.ambiguous || error.retryAllowed == true || (error.retryAfterSeconds ?: 0L) > 0L) return null
    if (error.status == 502 && error.reportState == REPORT_ENDED_NO_CHARGE &&
        error.category == null && error.upstreamStatus == null) {
        return when (error.message) {
            "The report did not pass source-faithfulness review. Your original source and editor text remain available." ->
                "The AI draft didn’t match the original notes. It was discarded."
            "The report check could not be verified. Your original source and editor text remain available." ->
                "The AI draft couldn't be verified. It was discarded."
            else -> null
        }
    }
    if (error.status == 402 && error.category == null) {
        return when {
            error.message.orEmpty().startsWith("An active paid plan is required") -> "AI writing needs an active plan for this account."
            error.message.orEmpty().startsWith("More credits are needed") -> "This account has too few credits for AI writing."
            else -> "AI writing is unavailable for this account."
        }
    }
    if (error.status != 503 || error.retryAllowed != false) return null
    return when (error.category) {
        "AI_PAUSED" -> "AI writing is paused."
        "AI_NOT_CONFIGURED" -> "AI writing hasn't been set up."
        "PROVIDER_QUOTA" -> "The AI service has reached its spending limit."
        else -> null
    }
}
