package com.woundcopilot.android

/** Exact whole-message allowlist. Unknown/combined/clinical messages fail closed. */
internal fun feedbackMayFade(message: String, hasAction: Boolean = false): Boolean = !hasAction && message in setOf(
    "No internet connection. Your work is saved on this phone. Tap Try again when you're back online.",
    "Request: No internet connection. Your work is saved on this phone. Tap Try again when you're back online.",
    "Exact document saved to your workspace.",
    "Profile saved.",
    "Saved in the synthetic preview.",
)

/** Owned by AppState, not by a particular screen. Even the same text is a new occurrence. */
internal data class FeedbackMessage(val text: String? = null, val sequence: Long = 0) {
    fun replace(text: String?) = FeedbackMessage(text, sequence + 1)
}

/** Presentation only: expiration never consumes the stored message or invokes Dismiss/retry. */
internal data class FeedbackFadeTimer(
    val message: String,
    val sequence: Long,
    val startedAtMillis: Long,
    val hasAction: Boolean = false,
) {
    val fadeAfterMillis: Long? = if (feedbackMayFade(message, hasAction)) 5_000L else null
    fun isVisibleAt(nowMillis: Long): Boolean = fadeAfterMillis?.let { nowMillis - startedAtMillis < it } ?: true
}
