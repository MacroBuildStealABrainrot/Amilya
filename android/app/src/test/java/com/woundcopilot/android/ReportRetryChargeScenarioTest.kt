package com.woundcopilot.android

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The worst case for a second AI charge, played out with FAKE data and a FAKE server.
 *
 *  1. The phone asks for a report with request code A.
 *  2. The server finishes the report (one AI call, one charge) but the answer never reaches the phone:
 *     the phone gets error replies instead (a gateway in between gives up and says 504).
 *  3. The phone gets four such error replies in a row.
 *  4. The nurse taps "Try again".
 *
 * The fake server below behaves exactly like the real one, which is proven separately by the server tests in
 * backend/tests/test_report_retry_after_failure.py:
 *  - same request code again  -> the finished report is sent back, no new AI call, no new charge;
 *  - a different request code -> a new report: a new AI call and a new charge.
 *
 * It counts three numbers: reports created, AI calls that wrote a report, charges recorded. The safe result is 1, 1, 1.
 */
class ReportRetryChargeScenarioTest {
    private class FakeServer {
        val reportsByCode = mutableMapOf<String, String>()
        var reportsCreated = 0
        var aiCallsThatWroteAReport = 0
        var chargesRecorded = 0
        /** [replyReachesPhone] false = the server did its work but the phone only sees a 504 from the gateway. */
        fun requestReport(code: String, replyReachesPhone: Boolean): Int {
            if (code !in reportsByCode) {
                aiCallsThatWroteAReport++; chargesRecorded++; reportsCreated++
                reportsByCode[code] = "FAKE REPORT"
            }
            return if (replyReachesPhone) 200 else 504
        }
    }

    /** The phone's loop, using the phone's real rule for what to do with the request code after an error reply. */
    private fun playScenario(server: FakeServer): String {
        var code = "code-1"
        var serverErrors = 0
        var next = 2
        repeat(REPORT_CODE_MAX_SERVER_ERRORS) {
            val status = server.requestReport(code, replyReachesPhone = false)
            val kept = reportCodeAfterError(status, ambiguous = false, serverErrorsSoFar = serverErrors)
            if (kept == null) { code = "code-${next++}"; serverErrors = 0 } else serverErrors = kept
        }
        // The nurse taps "Try again". This time the reply gets through.
        server.requestReport(code, replyReachesPhone = true)
        return code
    }

    @Test fun serverFinishesOnceFourErrorRepliesThenRetry_oneReportOneAiCallOneCharge() {
        val server = FakeServer()
        val finalCode = playScenario(server)
        println("RESULT reports created=${server.reportsCreated} AI calls that wrote a report=${server.aiCallsThatWroteAReport} charges recorded=${server.chargesRecorded} (request code on the retry: $finalCode)")
        assertEquals("reports created", 1, server.reportsCreated)
        assertEquals("AI calls that wrote a report", 1, server.aiCallsThatWroteAReport)
        assertEquals("charges recorded", 1, server.chargesRecorded)
    }
}
