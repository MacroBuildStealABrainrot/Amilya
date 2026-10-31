package com.woundcopilot.android

/** What the server accepts of an Ask Consultant conversation sent with a report (backend/server/quick_workflows.py). */
internal const val CONSULTATION_MAX_TURNS = 40
internal const val CONSULTATION_MAX_CHARS = 40000
internal const val CONSULTATION_MAX_TURN_CHARS = 10000

internal const val CONSULTATION_TOO_LONG_MESSAGE =
    "This Ask Consultant conversation is too long to put in a report (at most 40 messages and 40,000 letters in all, and 10,000 in one message). " +
    "No report was made and nothing was sent. Your conversation is saved. On the Ask Consultant screen, tap Done and choose Create report without the conversation."
internal const val CONSULTATION_REFUSED_MESSAGE =
    "The server did not accept this Ask Consultant conversation on this try, so nothing was written now and nothing was sent without it. " +
    "Your conversation and the earlier request are saved. On the Ask Consultant screen, tap Done, then choose Recover earlier report to try again, " +
    "or New report from photos and files only."
/** Shown on the report when the nurse chose to leave the conversation out. */
internal const val CONSULTATION_LEFT_OUT_NOTICE = "This report does not include your Ask Consultant conversation. You chose to leave it out."
internal const val CONSULTATION_DIALOG_TOO_LONG =
    "This conversation is too long to put in a report (at most 40 messages and 40,000 letters). You can keep talking, or create the report from your attached photos and files only. The conversation will NOT be included."
internal const val CONSULTATION_DIALOG_EARLIER =
    "An earlier report from this visit may already have been written and paid for. Recover it to get that report back with no new charge. " +
    "Or start a new report from your photos and files only: it will NOT include the conversation and may be charged again."

/** What the Done dialog offers. The first choice is the main button; a second one is a separate, clearly named button. */
internal enum class ConsultationChoice { CREATE, CREATE_WITHOUT_CONVERSATION, RECOVER_EARLIER, NEW_WITHOUT_CONVERSATION }

internal fun consultationChoices(earlierReportWaiting: Boolean, fits: Boolean): List<ConsultationChoice> = when {
    earlierReportWaiting -> listOf(ConsultationChoice.RECOVER_EARLIER, ConsultationChoice.NEW_WITHOUT_CONVERSATION)
    fits -> listOf(ConsultationChoice.CREATE)
    else -> listOf(ConsultationChoice.CREATE_WITHOUT_CONVERSATION)
}

/** True when the whole conversation can be sent as it is. The phone never shortens it or drops its oldest messages. */
internal fun consultationFits(contents: List<String>): Boolean {
    val text = contents.map { it.trim() }
    return text.size <= CONSULTATION_MAX_TURNS && text.all { it.length <= CONSULTATION_MAX_TURN_CHARS } && text.sumOf { it.length } <= CONSULTATION_MAX_CHARS
}
