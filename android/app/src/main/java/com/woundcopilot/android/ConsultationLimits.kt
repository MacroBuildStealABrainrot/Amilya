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
    "or New report without the conversation."
/** Shown on the report when the nurse chose to leave the conversation out. */
internal const val CONSULTATION_LEFT_OUT_NOTICE = "This report does not include your Ask Consultant conversation. You chose to leave it out."
internal const val CONSULTATION_DIALOG_TOO_LONG =
    "This conversation is too long to put in a report (at most 40 messages and 40,000 letters). You can keep talking, or create the report without the conversation. The conversation will NOT be included."
internal const val CONSULTATION_DIALOG_EARLIER =
    "An earlier report from this visit may already have been written and paid for. Recover it to get that report back with no new charge. " +
    "Or start a new report without the conversation: it will NOT include the conversation and may be charged again."

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

/** Done on Ask Consultant offers the report choices while there is a conversation and no report yet. */
internal fun showsReportChoicesOnDone(quickMode: String?, hasMessages: Boolean, editorBlank: Boolean) = quickMode == "consultant" && hasMessages && editorBlank

/** After a report from the conversation fails with nothing written, go back to the screen the nurse was on. */
internal fun modeAfterFailedConsultantReport(before: String?, editorBlank: Boolean, current: String?) = if (editorBlank) before else current

/** All warnings for a displayed report: the omission warning first, then the server's warnings, then any attachment warning. */
internal fun combineReportNotices(leftOutConversation: Boolean, serverWarnings: String?, attachmentWarning: String?): String? =
    listOfNotNull(if (leftOutConversation) CONSULTATION_LEFT_OUT_NOTICE else null, serverWarnings, attachmentWarning)
        .filter { it.isNotBlank() }.joinToString("\n").ifBlank { null }

/** The Document screen offers Generate report with sources, and ALSO when an earlier (maybe paid) report is waiting, even with no files. */
internal fun showsGenerateOnDocument(editorBlank: Boolean, sourceCount: Int, earlierReportWaiting: Boolean) =
    editorBlank && (sourceCount > 0 || earlierReportWaiting)

/** The warnings on screen belong to the report being shown: switching reports never carries the old banner along. */
internal fun noticeForShownReport(savedWithThatReport: String?): String? = savedWithThatReport?.takeIf { it.isNotBlank() }

/** The attachment warning belongs to the visit it was made for. A different visit gets none of it. */
internal fun attachmentNoticeForVisit(noticeVisit: String?, visitNow: String?, notice: String?): String? =
    if (noticeVisit != null && noticeVisit == visitNow) notice else null

/** Keeps each saved report's warnings with that report (by report id). */
internal class DocumentNotices(private val store: CodeStore, private val keyFor: (String) -> String) {
    fun save(documentId: String, notice: String?) { if (notice.isNullOrBlank()) store.remove(keyFor(documentId)) else store.write(keyFor(documentId), notice) }
    fun read(documentId: String): String? = store.read(keyFor(documentId))?.ifBlank { null }
}

/** Keeps each saved report's safety alerts (for example an unclear dose) with that report, with how many of them are safety alerts. */
internal class DocumentAlertNotes(private val store: CodeStore, private val keyFor: (String) -> String) {
    private val sep = "\u001f"
    fun save(documentId: String, alerts: List<String>, safetyCount: Int) {
        if (alerts.isEmpty()) store.remove(keyFor(documentId)) else store.write(keyFor(documentId), (listOf(safetyCount.toString()) + alerts).joinToString(sep))
    }
    fun read(documentId: String): Pair<List<String>, Int>? {
        val parts = store.read(keyFor(documentId))?.split(sep) ?: return null
        val count = parts.firstOrNull()?.toIntOrNull() ?: return null
        val alerts = parts.drop(1).filter { it.isNotBlank() }
        return if (alerts.isEmpty()) null else alerts to count.coerceIn(0, alerts.size)
    }
}
