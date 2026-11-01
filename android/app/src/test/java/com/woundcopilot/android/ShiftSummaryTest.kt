package com.woundcopilot.android

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class ShiftSummaryTest {
    private val day = LocalDate.parse("2026-11-01")
    private val zone = ZoneId.of("America/Toronto")
    private fun visit(id: String, hour: Int = 14, reports: List<ShiftReport> = listOf(report(id))) =
        ShiftVisit(id, "Test visit $id", Instant.parse("2026-11-01T${hour}:00:00Z"), 3, true, reports)
    private fun report(id: String, text: String = "Right heel: dressing changed.", status: String = "signed", version: Int = 1,
                       notice: String? = null, alerts: List<String> = emptyList()) =
        ShiftReport("report-$id-$version", version, status, text, notice, alerts, true)
    private fun plan(vararg visits: ShiftVisit) = planShiftSummary(visits.toList(), day, zone)

    @Test fun zeroVisitsProducesAnHonestEmptyDocument() {
        val p = plan()
        assertTrue(p.finished.isEmpty()); assertTrue(p.unfinished.isEmpty())
        val text = renderShiftSummary(p, emptySet())
        assertTrue(text.contains("Finished reports selected: 0 of 0"))
        assertTrue(text.contains("Only visits already available on this phone"))
    }

    @Test fun oneSignedReportIsCopiedByteForByteIncludingWhitespaceAndUnicode() {
        val body = "  Test wording\r\nRight heel — café.\n\nDo not trim.  \n"
        val p = plan(visit("A", reports = listOf(report("A", body))))
        val text = renderShiftSummary(p, setOf("A"))
        assertTrue(text.contains("--- Saved report text (unchanged) ---\n$body\n--- End of report"))
        assertEquals(body, p.finished.single().second.text)
    }

    @Test fun threeReportsAreOrderedByVisitStartThenIdNotByTextOrLastEdit() {
        val p = plan(visit("C", 16), visit("B", 14), visit("A", 14))
        assertEquals(listOf("A", "B", "C"), p.finished.map { it.first.id })
        val text = renderShiftSummary(p, setOf("A", "B", "C"))
        assertTrue(text.indexOf("Visit reference: A") < text.indexOf("Visit reference: B"))
        assertTrue(text.indexOf("Visit reference: B") < text.indexOf("Visit reference: C"))
    }

    @Test fun noReportAndDraftOnlyAreListedAtTheTopAndNeverIncludedAsFinished() {
        val p = plan(visit("empty", reports = emptyList()), visit("draft", reports = listOf(report("draft", "DRAFT WORDING", "draft"))), visit("ready"))
        assertEquals(listOf("draft", "empty"), p.unfinished.map { it.id })
        val text = renderShiftSummary(p, setOf("empty", "draft", "ready"))
        assertTrue(text.indexOf("No finished report") < text.indexOf("Visit 1\n"))
        assertTrue(text.contains("No report is saved.")); assertTrue(text.contains("Only an unfinished report is saved."))
        assertFalse(text.contains("DRAFT WORDING")); assertEquals(1, p.finished.size)
    }

    @Test fun savedWarningsAndUncheckedSourceOnlyWarningStayInsideTheirOwnSection() {
        val unchecked = "AI generation or checking could not complete. This source-only draft has not passed an AI check."
        val omission = "New report without the conversation. The conversation was left out."
        val p = plan(visit("A", reports = listOf(report("A", notice = omission, alerts = listOf("Dose unclear", unchecked, "A photo could not be read")))), visit("B", 15))
        val text = renderShiftSummary(p, setOf("A", "B"))
        val sectionA = text.substringAfter("Visit 1\n").substringBefore("Visit 2\n")
        val sectionB = text.substringAfter("Visit 2\n")
        listOf(omission, "Dose unclear", unchecked, "A photo could not be read").forEach {
            assertTrue(sectionA.contains(it)); assertFalse(sectionB.contains(it))
        }
        assertFalse(feedbackMayFade(unchecked)); assertFalse(feedbackMayFade(omission))
    }

    @Test fun twoSimilarReportsAreNeverDeduplicatedOrCombined() {
        val a = "Right heel: dressing changed. Pain 2/10."
        val b = "Left heel: dressing changed. Pain 3/10."
        val text = renderShiftSummary(plan(visit("A", reports = listOf(report("A", a))), visit("B", 15, listOf(report("B", b)))), setOf("A", "B"))
        assertEquals(1, Regex(Regex.escape(a)).findAll(text).count())
        assertEquals(1, Regex(Regex.escape(b)).findAll(text).count())
        assertTrue(text.substringAfter("Visit 1\n").substringBefore("Visit 2\n").contains(a))
        assertFalse(text.substringAfter("Visit 2\n").contains(a))
    }

    @Test fun untickingOneVisitLeavesItsBodyAndWarningsOutAndExplainsTheChoice() {
        val p = plan(visit("A", reports = listOf(report("A", "A ONLY", notice = "A WARNING"))), visit("B", 15, listOf(report("B", "B ONLY"))))
        val text = renderShiftSummary(p, setOf("B"))
        assertFalse(text.contains("A ONLY")); assertFalse(text.contains("A WARNING"))
        assertTrue(text.contains("Left out by your choice:")); assertTrue(text.contains("B ONLY"))
        assertTrue(renderShiftSummary(p, emptySet()).contains("Finished reports selected: 0 of 2"))
    }

    @Test fun yesterdayIsExcludedEvenWhenUtcSaysTodayAndTomorrowIsAlsoExcluded() {
        val p = plan(visit("yesterday").copy(startedAt = Instant.parse("2026-11-01T03:59:59Z")),
            visit("today").copy(startedAt = Instant.parse("2026-11-01T04:00:00Z")),
            visit("tomorrow").copy(startedAt = Instant.parse("2026-11-02T05:00:00Z")))
        assertEquals(listOf("today"), p.finished.map { it.first.id })
    }

    @Test fun bothRepeatedHoursOfDaylightSavingDayBelongToToday() {
        val p = plan(visit("first").copy(startedAt = Instant.parse("2026-11-01T05:30:00Z")),
            visit("second").copy(startedAt = Instant.parse("2026-11-01T06:30:00Z")))
        assertEquals(2, p.finished.size)
        assertTrue(shiftVisitHeading(p.finished[0].first, zone).startsWith("01:30"))
        assertTrue(shiftVisitHeading(p.finished[1].first, zone).startsWith("01:30"))
    }

    @Test fun unknownLocalReportIsExplicitlyUnavailableNotMislabelledAsNoReport() {
        val p = plan(visit("unknown").copy(reportsKnown = false))
        assertTrue(p.finished.isEmpty()); assertTrue(p.unfinished.isEmpty())
        assertEquals("unknown", p.unavailable.single().id)
        assertTrue(renderShiftSummary(p, emptySet()).contains("cannot confirm whether these visits have a finished report"))
    }

    @Test fun newerDraftDoesNotFallBackToAnOlderSignedVersion() {
        val p = plan(visit("A", reports = listOf(report("A", "OLD SIGNED", version = 1), report("A", "NEW DRAFT", "draft", 2))))
        assertTrue(p.finished.isEmpty()); assertEquals(1, p.unfinished.size)
        assertFalse(renderShiftSummary(p, setOf("A")).contains("OLD SIGNED"))
    }

    @Test fun latestSignedVersionIsUsedAndOlderVersionIsNotAppended() {
        val p = plan(visit("A", reports = listOf(report("A", "OLD", version = 1), report("A", "NEW", version = 2))))
        assertEquals("NEW", p.finished.single().second.text)
    }

    @Test fun missingWarningHistoryIsDisclosedRatherThanCalledWarningFree() {
        val p = plan(visit("A", reports = listOf(report("A").copy(warningRecordKnown = false))))
        assertTrue(renderShiftSummary(p, setOf("A")).contains("Saved warnings could not be confirmed"))
    }

    @Test fun duplicateVisitIdsFailWithoutEchoingAnyVisitText() {
        try { plan(visit("A"), visit("A")); fail("Must stop") }
        catch (e: IllegalArgumentException) { assertFalse(e.message.orEmpty().contains("Test visit")) }
    }

    @Test fun missingDatesAreNeverGuessedToBeTodayAndStartWinsOverLastEdit() {
        val yesterday = Instant.parse("2026-10-31T14:00:00Z")
        assertEquals(yesterday, shiftVisitStart(yesterday.toString(), null, "2026-11-01T15:00:00Z"))
        assertEquals(yesterday, shiftVisitStart("", yesterday, "2026-11-01T15:00:00Z"))
        assertNull(shiftVisitStart("", null, "not a date"))
    }
}
