package com.woundcopilot.android

/** What the server accepts of an Ask Consultant conversation sent with a report (backend/server/quick_workflows.py). */
internal const val CONSULTATION_MAX_TURNS = 40
internal const val CONSULTATION_MAX_CHARS = 40000
internal const val CONSULTATION_MAX_TURN_CHARS = 10000

internal const val CONSULTATION_TOO_LONG_MESSAGE =
    "This Ask Consultant conversation is too long to put in a report (most 40 messages and 40,000 letters in all, 10,000 in one message). " +
    "No report was made and nothing was sent. Your conversation is saved. To write the report WITHOUT it, open Document and tap Generate report."
internal const val CONSULTATION_REFUSED_MESSAGE =
    "The server could not accept this Ask Consultant conversation, so no report was made and nothing was sent without it. " +
    "Your conversation is saved. To write the report WITHOUT it, open Document and tap Generate report."

/** True when the whole conversation can be sent as it is. The phone never shortens it or drops its oldest messages. */
internal fun consultationFits(contents: List<String>): Boolean {
    val text = contents.map { it.trim() }
    return text.size <= CONSULTATION_MAX_TURNS && text.all { it.length <= CONSULTATION_MAX_TURN_CHARS } && text.sumOf { it.length } <= CONSULTATION_MAX_CHARS
}
