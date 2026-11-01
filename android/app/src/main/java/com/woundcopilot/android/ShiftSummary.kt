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
)

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

/** Append report text verbatim; the wrappers and warnings never edit or summarise a report. */
internal fun renderShiftSummary(plan: ShiftSummaryPlan, selectedVisitIds: Set<String>): String = buildString {
    append("End-of-shift summary — ${plan.date}\n")
    append("Phone time zone: ${plan.zone.id}\n")
    append("Only visits already available on this phone are listed. No AI check was run for this summary.\n\n")
    append("No finished report\n")
    if (plan.unfinished.isEmpty()) append("None among the visits available on this phone.\n")
    plan.unfinished.forEach {
        append(shiftVisitHeading(it, plan.zone)).append('\n')
        append(if (it.reports.isEmpty()) "No report is saved.\n" else "Only an unfinished report is saved. Review and sign it when ready.\n")
    }
    if (plan.unavailable.isNotEmpty()) {
        append("\nReport not available on this phone\n")
        append("The phone cannot confirm whether these visits have a finished report. Open each visit while online, then return here.\n")
        plan.unavailable.forEach { append(shiftVisitHeading(it, plan.zone)).append('\n') }
    }
    val selected = plan.finished.filter { it.first.id in selectedVisitIds }
    append("\nFinished reports selected: ${selected.size} of ${plan.finished.size}\n")
    plan.finished.filter { it.first.id !in selectedVisitIds }.forEach {
        append("Left out by your choice: ").append(shiftVisitHeading(it.first, plan.zone)).append('\n')
    }
    selected.forEachIndexed { index, (visit, report) ->
        append("\n==================================================\n")
        append("Visit ${index + 1}\n").append(shiftVisitHeading(visit, plan.zone)).append('\n')
        append("Signed report — version ${report.version}\n")
        append("AI check status is not confirmed by this summary.\n")
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
