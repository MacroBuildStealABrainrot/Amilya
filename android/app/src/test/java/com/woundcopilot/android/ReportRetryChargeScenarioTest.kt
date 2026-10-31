package com.woundcopilot.android

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The worst case for a second AI charge, played out with FAKE data, a FAKE server and FAKE billing.
 *
 * The phone side is the real code: the request notebook (RequestJournal) and the real rule for the request code (reportCodeKept).
 * The server side is a stand-in that behaves the way the real server is proven to behave by backend/tests/test_report_retry_after_failure.py:
 *  - the same request code again  -> the finished report is sent back: no new AI call, no new charge;
 *  - a different request code     -> a new report: a new AI call and a new charge;
 *  - a request that can never finish (3 failed attempts) is answered with "ended-no-charge" and nothing is charged.
 *
 * Three numbers are counted at the end: reports created, AI calls that wrote a report, charges recorded. The safe result is 1, 1, 1.
 */
class ReportRetryChargeScenarioTest {
    private class Reply(val status: Int, val reportState: String? = null)

    private class FakeServer {
        val reports = mutableSetOf<String>()
        var reportsCreated = 0; var aiCallsThatWroteAReport = 0; var chargesRecorded = 0
        val failedAttempts = mutableMapOf<String, Int>()
        /** [neverFinishes] = the AI provider is down: nothing is written or charged, and after 3 attempts the server says the code is over. */
        fun request(code: String, replyReachesPhone: Boolean, neverFinishes: Boolean = false): Reply {
            if (neverFinishes && code !in reports) {
                val n = (failedAttempts[code] ?: 0) + 1; failedAttempts[code] = n
                return Reply(502, if (n >= 3) REPORT_ENDED_NO_CHARGE else null)
            }
            if (code !in reports) { reports += code; reportsCreated++; aiCallsThatWroteAReport++; chargesRecorded++ }
            return if (replyReachesPhone) Reply(200) else Reply(504)    // 504: the server did the work, only a middle-man's error reaches the phone
        }
        fun numbers() = "reports created=$reportsCreated, AI calls that wrote a report=$aiCallsThatWroteAReport, charges recorded=$chargesRecorded"
    }

    private class MapStore : CodeStore {
        val data = mutableMapOf<String, String>()
        override fun read(key: String) = data[key]
        override fun write(key: String, value: String) { data[key] = value }
        override fun remove(key: String) { data.remove(key) }
    }

    /** One tap on "Create report" / "Try again", the way the app does it: note first, send, then keep or forget the note. */
    private class Phone(val store: MapStore) {
        private var codes = 0
        var journal = RequestJournal(store) { "code-${++codes}-${store.data.size}-${System.nanoTime()}" }
        val codesUsed = mutableListOf<String>()
        fun reopenApp() { journal = RequestJournal(store) { "code-new-${System.nanoTime()}" } }
        fun tap(server: FakeServer, replyReachesPhone: Boolean, neverFinishes: Boolean = false): Int {
            val storage = "pending-quick-report-1"
            val record = journal.open(storage, "{\"template\":\"nursing\"}")
            if (record.response != null) return 200
            codesUsed += record.code
            val reply = server.request(record.code, replyReachesPhone, neverFinishes)
            if (reply.status == 200) { journal.saveAnswer(storage, record, "{\"text\":\"FAKE REPORT\"}"); journal.forget(storage) }
            else journal.afterReportError(storage, reply.status, ambiguous = false, reportState = reply.reportState)
            return reply.status
        }
    }

    private fun check(label: String, server: FakeServer) {
        println("RESULT $label: ${server.numbers()}")
        assertEquals("$label: reports created", 1, server.reportsCreated)
        assertEquals("$label: AI calls that wrote a report", 1, server.aiCallsThatWroteAReport)
        assertEquals("$label: charges recorded", 1, server.chargesRecorded)
    }

    @Test fun serverFinishes_phoneGetsFourErrors_retryAtOnce_1_1_1() {
        val server = FakeServer(); val phone = Phone(MapStore())
        repeat(4) { assertEquals(504, phone.tap(server, replyReachesPhone = false)) }
        assertEquals(200, phone.tap(server, replyReachesPhone = true))               // Try again, app still open
        assertEquals("one request code for all five taps", 1, phone.codesUsed.toSet().size)
        check("retry at once", server)
    }

    @Test fun serverFinishes_phoneGetsFourErrors_nurseClosesAndReopensApp_retry_1_1_1() {
        val server = FakeServer(); val phone = Phone(MapStore())
        repeat(4) { assertEquals(504, phone.tap(server, replyReachesPhone = false)) }
        phone.reopenApp()                                                             // app closed, then opened again
        assertEquals(200, phone.tap(server, replyReachesPhone = true))
        assertEquals("one request code before and after reopening", 1, phone.codesUsed.toSet().size)
        check("retry after reopening the app", server)
    }

    @Test fun manyMoreErrorsAndAReopenInTheMiddle_stillOneCharge() {
        val server = FakeServer(); val phone = Phone(MapStore())
        repeat(10) { if (it == 5) phone.reopenApp(); phone.tap(server, replyReachesPhone = false) }
        phone.reopenApp()
        assertEquals(200, phone.tap(server, replyReachesPhone = true))
        check("ten errors, two reopenings", server)
    }

    @Test fun noReportFoundYetIsStillUncertain_theCodeIsKept_1_1_1() {
        val server = FakeServer(); val phone = Phone(MapStore())
        phone.tap(server, replyReachesPhone = false)                                  // the server finished; the phone only saw 504
        // A later check answers "no report found yet" (404) although the report exists: the phone must not start a fresh request.
        val storage = "pending-quick-report-1"
        phone.journal.afterReportError(storage, 404, ambiguous = false, reportState = null)
        assertEquals(200, phone.tap(server, replyReachesPhone = true))
        check("'no report found yet' answer", server)
    }

    @Test fun aNewRequestStartsOnlyWhenTheServerConfirmsTheOldOneEndedWithNoCharge() {
        val server = FakeServer(); val phone = Phone(MapStore())
        assertEquals(502, phone.tap(server, replyReachesPhone = true, neverFinishes = true))   // provider down: attempt 1
        assertEquals(502, phone.tap(server, replyReachesPhone = true, neverFinishes = true))   // attempt 2
        assertEquals("same code kept until the server says it is over", 1, phone.codesUsed.toSet().size)
        assertEquals(502, phone.tap(server, replyReachesPhone = true, neverFinishes = true))   // attempt 3: server answers ended-no-charge
        assertEquals(0, server.chargesRecorded)
        assertEquals(200, phone.tap(server, replyReachesPhone = true))                          // provider is back: NEW code, allowed now
        assertEquals("a new code only after the server's confirmation", 2, phone.codesUsed.toSet().size)
        check("new request after confirmed end", server)
    }

    @Test fun serverBusyAnswerStillProcessing409_keepsTheCode() {
        val store = MapStore(); val j = RequestJournal(store) { "c" }
        j.open("k", "{}"); j.afterReportError("k", 409, ambiguous = false, reportState = null)
        assertEquals("c", RequestJournal(store) { "other" }.open("k", "{}").code)
    }
}
