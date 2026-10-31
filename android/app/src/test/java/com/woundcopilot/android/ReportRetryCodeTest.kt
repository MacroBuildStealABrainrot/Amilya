package com.woundcopilot.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** An AI report retry keeps the SAME request code until the server says the request is over, so a finished report is never paid for twice. */
class ReportRetryCodeTest {
    @Test fun anythingUncertainKeepsTheCode() {
        for (status in listOf(404, 409, 429, 500, 502, 503, 504)) assertTrue("$status", reportCodeKept(status, ambiguous = false, reportState = null))
        for (status in listOf(0, 409, 502, 504)) assertTrue("unreadable $status", reportCodeKept(status, ambiguous = true, reportState = null))
    }
    @Test fun manyErrorsInARowNeverGiveTheCodeUp() {
        repeat(100) { assertTrue(reportCodeKept(504, ambiguous = false, reportState = null)) }
    }
    @Test fun onlyTheServersEndedNoChargeAnswerGivesTheCodeUp() {
        assertFalse(reportCodeKept(502, ambiguous = false, reportState = REPORT_ENDED_NO_CHARGE))
        assertFalse(reportCodeKept(504, ambiguous = false, reportState = REPORT_ENDED_NO_CHARGE))
        assertTrue(reportCodeKept(502, ambiguous = false, reportState = "something-else"))
    }
    @Test fun aRequestRefusedBeforeAnyWorkDropsTheCode() {
        for (status in listOf(400, 402, 403, 413, 415, 422)) assertFalse("$status", reportCodeKept(status, ambiguous = false, reportState = null))
    }
}

/** The phone's note of a request survives closing and reopening the app, and the legacy (older build) note format is still read. */
class RequestJournalTest {
    private class MapStore : CodeStore {
        val data = mutableMapOf<String, String>()
        override fun read(key: String) = data[key]
        override fun write(key: String, value: String) { data[key] = value }
        override fun remove(key: String) { data.remove(key) }
    }
    private var counter = 0
    private fun journal(store: CodeStore) = RequestJournal(store) { "code-${++counter}" }

    @Test fun theNoteIsWrittenBeforeAnythingIsSent() {
        val store = MapStore()
        val record = journal(store).open("k", "{\"a\":1}")
        assertEquals(record.code, store.data.getValue("k").lines()[1])
    }
    @Test fun reopeningTheAppFindsTheSameCode() {
        val store = MapStore()
        val first = journal(store).open("k", "{\"a\":1}")
        val afterReopen = journal(store).open("k", "{\"a\":1}")        // a new journal object over the same stored notes = app reopened
        assertEquals(first.code, afterReopen.code)
        assertNull(afterReopen.response)
    }
    @Test fun aSavedAnswerIsFoundAfterReopening() {
        val store = MapStore()
        val j = journal(store); val r = j.open("k", "{\"a\":1}")
        j.saveAnswer("k", r, "{\"text\":\"FAKE\"}")
        val again = journal(store).open("k", "{\"a\":1}")
        assertEquals("{\"text\":\"FAKE\"}", again.response); assertEquals(r.code, again.code)
    }
    @Test fun anErrorThatKeepsTheCodeLeavesTheNote_andOnlyTheEndedAnswerRemovesIt() {
        val store = MapStore(); val j = journal(store); j.open("k", "{}")
        j.afterReportError("k", 504, false, null); assertTrue("k" in store.data)
        j.afterReportError("k", 409, false, null); assertTrue("k" in store.data)
        j.afterReportError("k", 502, false, REPORT_ENDED_NO_CHARGE); assertFalse("k" in store.data)
    }
    @Test fun aNoteFromAnOlderBuildKeepsItsCode() {
        val store = MapStore(); store.data["k"] = "{\"key\":\"old-code\"}"
        val r = journal(store).open("k", "{}") { raw -> if (raw.startsWith("{")) RequestRecord("old-code", "{\"p\":1}", null) else null }
        assertEquals("old-code", r.code)
        assertEquals("old-code", journal(store).open("k", "{}").code)   // rewritten in the new format
    }
    @Test fun otherRequestsKeepTheirEarlierRule() {
        val store = MapStore(); val j = journal(store)
        j.open("a", "{}"); j.afterOtherError("a", 503, false); assertFalse("a" in store.data)
        j.open("b", "{}"); j.afterOtherError("b", 503, true); assertTrue("b" in store.data)
    }
}
