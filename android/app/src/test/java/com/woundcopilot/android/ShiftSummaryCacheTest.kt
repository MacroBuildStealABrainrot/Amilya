package com.woundcopilot.android

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class ShiftSummaryCacheTest {
    private class Memory : CodeStore {
        val values = linkedMapOf<String, String>()
        override fun read(key: String) = values[key]
        override fun write(key: String, value: String) { values[key] = value }
        override fun remove(key: String) { values.remove(key) }
    }
    private val day = LocalDate.parse("2026-11-01")
    private val zone = ZoneId.of("America/Toronto")
    private fun visit(id: String, revision: Int = 1) = ShiftVisit(id, "Test visit $id", Instant.parse("2026-11-01T14:00:00Z"), revision, true,
        listOf(ShiftReport("report-$id", 1, "signed", "  Exact text\r\nRight heel.  \n", "Conversation left out", listOf("Dose unclear", "Source-only draft has not passed an AI check"), true)))
    private fun cache(store: Memory) = ShiftSummaryCache(store) { store.values.keys.toList() }

    @Test fun reopenKeepsExactTextNoticeAndAllAlertsWithTheSameVisit() {
        val memory = Memory(); cache(memory).remember("owner", visit("A"), day, zone)
        cache(memory).remember("owner", visit("B").copy(reports = listOf(ShiftReport("report-B", 1, "signed", "B ONLY", warningRecordKnown = true))), day, zone)
        val restored = cache(memory).today("owner", day, zone)
        assertEquals(listOf(visit("A"), visit("B").copy(reports = listOf(ShiftReport("report-B", 1, "signed", "B ONLY", warningRecordKnown = true)))), restored)
        assertEquals(visit("A"), cache(memory).read("owner", "A"))
    }

    @Test fun separateOwnersNeverReadEachOthersVisitsEvenForSameVisitId() {
        val memory = Memory(); val c = cache(memory)
        c.remember("owner-A", visit("same"), day, zone)
        c.remember("owner-B", visit("same").copy(label = "B ONLY"), day, zone)
        assertEquals("Test visit same", c.today("owner-A", day, zone).single().label)
        assertEquals("B ONLY", c.today("owner-B", day, zone).single().label)
        assertTrue(c.today("unknown", day, zone).isEmpty())
    }

    @Test fun changedVisitRevisionMakesCachedReportUnavailableUntilCaseIsLoaded() {
        val memory = Memory(); val c = cache(memory)
        c.remember("owner", visit("A"), day, zone)
        c.metadata("owner", visit("A", 2).copy(reportsKnown = false, reports = emptyList()), day, zone)
        assertFalse(c.today("owner", day, zone).single().reportsKnown)
        c.remember("owner", visit("A", 2), day, zone)
        assertTrue(c.today("owner", day, zone).single().reportsKnown)
    }

    @Test fun unchangedMetadataDoesNotEraseTheSignedReportOrItsWarnings() {
        val memory = Memory(); val c = cache(memory)
        c.remember("owner", visit("A"), day, zone)
        c.metadata("owner", visit("A").copy(reportsKnown = false, reports = emptyList()), day, zone)
        assertEquals(visit("A"), c.today("owner", day, zone).single())
    }

    @Test fun olderMetadataCannotReplaceANewerCase() {
        val memory = Memory(); val c = cache(memory)
        c.remember("owner", visit("A", 5), day, zone)
        c.metadata("owner", visit("A", 2).copy(reportsKnown = false), day, zone)
        assertEquals(visit("A", 5), c.today("owner", day, zone).single())
    }

    @Test fun removingVisitRemovesItsLocalSummaryCopyWithoutTouchingOthers() {
        val memory = Memory(); val c = cache(memory)
        c.remember("owner", visit("A"), day, zone); c.remember("owner", visit("B"), day, zone)
        memory.write("unrelated", "keep")
        c.remove("owner", "A")
        assertEquals(listOf("B"), c.today("owner", day, zone).map { it.id })
        assertEquals("keep", memory.read("unrelated"))
    }

    @Test fun bindingLocalVisitToRemoteVisitDoesNotLeaveADuplicate() {
        val memory = Memory(); val c = cache(memory)
        c.remember("owner", visit("local-A").copy(reports = emptyList()), day, zone)
        c.remove("owner", "local-A"); c.remember("owner", visit("remote-A"), day, zone)
        assertEquals(listOf("remote-A"), c.today("owner", day, zone).map { it.id })
    }

    @Test fun nextDayDropsOldSummaryCopiesButKeepsOtherStoredWork() {
        val memory = Memory(); val c = cache(memory)
        c.remember("owner", visit("A"), day, zone); memory.write("draft-owner-A", "keep")
        assertTrue(c.today("owner", day.plusDays(1), zone).isEmpty())
        assertEquals(mapOf("draft-owner-A" to "keep"), memory.values)
    }

    @Test fun largeUnicodeReportRoundTripsWithoutShortening() {
        val memory = Memory(); val c = cache(memory)
        val long = visit("A").copy(reports = listOf(visit("A").reports.single().copy(text = "é🩹\r\n".repeat(20_000))))
        c.remember("owner", long, day, zone)
        assertEquals(long, cache(memory).today("owner", day, zone).single())
    }

    @Test fun corruptStoredReportStopsWithoutLeakingItsContent() {
        val memory = Memory(); val c = cache(memory)
        c.remember("owner", visit("A"), day, zone)
        memory.values[memory.values.keys.single()] = "FAKE-PATIENT-WORDING"
        try { c.today("owner", day, zone); fail("Must stop") }
        catch (e: IllegalStateException) { assertFalse(e.message.orEmpty().contains("FAKE-PATIENT")) }
    }
}
