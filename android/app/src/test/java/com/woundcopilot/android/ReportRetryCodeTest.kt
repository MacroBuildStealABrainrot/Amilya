package com.woundcopilot.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** An AI request retry keeps the SAME request code until the server says the request is over, so a finished report is never paid for twice. */
class ReportRetryCodeTest {
    @Test fun everyErrorKeepsTheCode_aRefusedLaterTryTellsNothingAboutTheEarlierOne() {
        // Only the server's own "ended-no-charge" answer is a reason to give the code up.
        assertTrue(reportCodeKept(null))
        assertTrue(reportCodeKept("something-else"))
    }
    @Test fun onlyTheServersEndedNoChargeAnswerGivesTheCodeUp() {
        assertFalse(reportCodeKept(REPORT_ENDED_NO_CHARGE))
    }
    @Test fun everyButtonThatCanCallTheAiKeepsItsCode() {
        for (path in listOf("/encounters/E1/quick-report", "/encounters/E1/refine-report", "/encounters/E1/improve-report",
            "/encounters/E1/improve-assessment", "/encounters/E1/consult", "/encounters/E1/draft")) assertTrue(path, chargedAiPath(path))
    }
    @Test fun requestsThatCannotBeChargedKeepTheirEarlierRule() {
        for (path in listOf("/encounters/E1/evidence", "/encounters/E1/documents", "/quick/start")) assertFalse(path, chargedAiPath(path))
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
        val path = "/encounters/E1/quick-report"
        for (status in listOf(504, 409, 403, 402, 422, 404, 502)) { j.afterError("k", path, true, status, false, null); assertTrue("$status", "k" in store.data) }
        j.afterError("k", path, true, 502, false, REPORT_ENDED_NO_CHARGE); assertFalse("k" in store.data)
    }
    @Test fun aNoteFromAnOlderBuildKeepsItsCode() {
        val store = MapStore(); store.data["k"] = "{\"key\":\"old-code\"}"
        val r = journal(store).open("k", "{}") { raw -> if (raw.startsWith("{")) RequestRecord("old-code", "{\"p\":1}", null) else null }
        assertEquals("old-code", r.code)
        assertEquals("old-code", journal(store).open("k", "{}").code)   // rewritten in the new format
    }
    @Test fun otherRequestsKeepTheirEarlierRule() {
        val store = MapStore(); val j = journal(store)
        j.open("a", "{}"); j.afterError("a", "/encounters/E1/evidence", false, 503, false, null); assertFalse("a" in store.data)
        j.open("b", "{}"); j.afterError("b", "/encounters/E1/evidence", false, 503, true, null); assertTrue("b" in store.data)
    }
}
