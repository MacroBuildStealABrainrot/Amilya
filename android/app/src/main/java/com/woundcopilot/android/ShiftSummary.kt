package com.woundcopilot.android

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Values only: no Android, storage, network, AI, logging or billing dependencies. */
internal data class ShiftReport(
    val id: String,
    val version: Int,
    val status: String,
    val text: String,
    val notice: String? = null,
    val alerts: List<String> = emptyList(),
    val warningRecordKnown: Boolean = false,
    /** True when the text is the clinician's newer edit kept on this phone and not yet saved to the server. Never set on a signed report. */
    val editedOnPhone: Boolean = false,
)

internal data class ShiftVisit(
    val id: String,
    val label: String,
    val startedAt: Instant,
    val revision: Int,
    val reportsKnown: Boolean,
    val reports: List<ShiftReport> = emptyList(),
)

internal data class ShiftSummaryPlan(
    val date: LocalDate,
    val zone: ZoneId,
    val finished: List<Pair<ShiftVisit, ShiftReport>>,
    val unfinished: List<ShiftVisit>,
    val unavailable: List<ShiftVisit>,
) {
    /** Visits whose newest report is saved with text but not signed. These are real reports, listed and shareable, and always labelled. */
    val drafts: List<Pair<ShiftVisit, ShiftReport>>
        get() = unfinished.mapNotNull { visit ->
            visit.reports.maxWithOrNull(compareBy<ShiftReport> { it.version }.thenBy { it.id })
                ?.takeIf { it.status != "signed" && it.text.isNotBlank() }?.let { visit to it }
        }

    /** Every report written today, signed or not, in visit order. This is the list the clinician ticks. */
    val reports: List<Pair<ShiftVisit, ShiftReport>>
        get() = (finished + drafts).sortedWith(compareBy<Pair<ShiftVisit, ShiftReport>> { it.first.startedAt }.thenBy { it.first.id })

    /** Visits of today with no report text at all. */
    val withoutReport: List<ShiftVisit>
        get() = drafts.map { it.first.id }.toSet().let { withText -> unfinished.filter { it.id !in withText } }
}

/**
 * Adds the clinician's own newer edit kept on this phone (typed but not yet saved to the server) to that visit's newest
 * UNSIGNED report, or as the visit's only report when none was saved. A signed report is never replaced. A visit whose
 * reports this phone cannot confirm stays unavailable.
 */
internal fun applyPhoneEdits(visits: List<ShiftVisit>, edits: Map<String, String>): List<ShiftVisit> = visits.map { visit ->
    val text = edits[visit.id]?.takeIf { it.isNotBlank() }
    if (text == null || !visit.reportsKnown) return@map visit
    val latest = visit.reports.maxWithOrNull(compareBy<ShiftReport> { it.version }.thenBy { it.id })
    when {
        latest == null -> visit.copy(reports = listOf(ShiftReport("phone-${visit.id}", 0, "phone_draft", text, warningRecordKnown = true, editedOnPhone = true)))
        latest.status == "signed" || latest.text == text -> visit
        else -> visit.copy(reports = visit.reports.map { if (it === latest) it.copy(text = text, editedOnPhone = true) else it })
    }
}

/** Never invent today's date for an undated visit. The caller must stop if all three are missing. */
internal fun shiftVisitStart(createdAt: String, rememberedStart: Instant?, updatedAt: String): Instant? =
    runCatching { Instant.parse(createdAt) }.getOrNull() ?: rememberedStart ?: runCatching { Instant.parse(updatedAt) }.getOrNull()

/** A saved draft is not finished. A newer unfinished version must not be hidden by an older signature. */
internal fun planShiftSummary(visits: List<ShiftVisit>, today: LocalDate, zone: ZoneId): ShiftSummaryPlan {
    require(visits.map { it.id }.distinct().size == visits.size) { "The visit list could not be checked. Reopen the summary." }
    val finished = mutableListOf<Pair<ShiftVisit, ShiftReport>>()
    val unfinished = mutableListOf<ShiftVisit>()
    val unavailable = mutableListOf<ShiftVisit>()
    visits.filter { it.startedAt.atZone(zone).toLocalDate() == today }
        .sortedWith(compareBy<ShiftVisit> { it.startedAt }.thenBy { it.id }).forEach { visit ->
            val report = visit.reports.maxWithOrNull(compareBy<ShiftReport> { it.version }.thenBy { it.id })
            when {
                !visit.reportsKnown -> unavailable += visit
                report == null || report.status != "signed" || report.text.isBlank() -> unfinished += visit
                else -> finished += visit to report
            }
        }
    return ShiftSummaryPlan(today, zone, finished, unfinished, unavailable)
}

internal fun shiftVisitHeading(visit: ShiftVisit, zone: ZoneId): String =
    "${visit.startedAt.atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm"))} — ${visit.label.ifBlank { "Visit" }}\nVisit reference: ${visit.id}"

/** Append report text verbatim; the wrappers and warnings never edit or summarise a report. [selectedVisitIds] covers signed and unsigned reports alike. */
internal fun renderShiftSummary(plan: ShiftSummaryPlan, selectedVisitIds: Set<String>, compactEmpty: Boolean = false): String = buildString {
    append("End-of-shift summary — ${plan.date}\n")
    append("Phone time zone: ${plan.zone.id}\n")
    append("Only visits already available on this phone are listed. Reports are copied exactly as saved. No AI was used.\n\n")
    val all = plan.reports
    val selected = all.filter { it.first.id in selectedVisitIds }
    val signed = selected.count { it.second.status == "signed" }
    append("Reports included: ${selected.size} of ${all.size} ($signed signed, ${selected.size - signed} saved but not signed)\n")
    all.filter { it.first.id !in selectedVisitIds }.forEach {
        append("Left out by your choice: ").append(shiftVisitHeading(it.first, plan.zone)).append('\n')
    }
    val empty = plan.withoutReport
    if (empty.isNotEmpty()) {
        if (compactEmpty) append("${empty.size} other visit(s) today have no report saved (for example a failed or consultant-only visit).\n")
        else empty.forEach { append(shiftVisitHeading(it, plan.zone)).append('\n').append("No report is saved.\n") }
    }
    if (plan.unavailable.isNotEmpty()) {
        append("\nReport not available on this phone\n")
        append("The phone cannot confirm whether these visits have a finished report. Open each visit while online, then return here.\n")
        plan.unavailable.forEach { append(shiftVisitHeading(it, plan.zone)).append('\n') }
    }
    selected.forEachIndexed { index, (visit, report) ->
        append("\n==================================================\n")
        append("Visit ${index + 1}\n").append(shiftVisitHeading(visit, plan.zone)).append('\n')
        if (report.status == "signed") append("Signed report — version ${report.version}\n")
        else append("SAVED REPORT — NOT SIGNED${if (report.version > 0) " (version ${report.version})" else ""}\n")
        if (report.editedOnPhone) append("Includes your latest edits made on this phone that were not yet saved.\n")
        if (!report.warningRecordKnown) append("Saved warnings could not be confirmed on this phone. Review the original report before use.\n")
        if (!report.notice.isNullOrBlank()) append("\nSaved report warning:\n").append(report.notice).append('\n')
        if (report.alerts.isNotEmpty()) {
            append("\nSaved safety alerts and report warnings:\n")
            report.alerts.forEach { append(it).append('\n') }
        }
        append("\n--- Saved report text (unchanged) ---\n")
        append(report.text)
        append("\n--- End of report for visit reference: ${visit.id} ---\n")
    }
}
