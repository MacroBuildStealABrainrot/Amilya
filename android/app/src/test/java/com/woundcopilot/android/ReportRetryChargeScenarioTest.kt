package com.woundcopilot.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The worst cases for a second AI charge, played out with FAKE data, a FAKE server and FAKE billing.
 *
 * The phone side is the real code: the request notebook (RequestJournal), the rule for the request code (reportCodeKept,
 * chargedAiPath) and the "resume the unfinished report" lookup, called in the same order as in AppState.
 * The server side is a stand-in that behaves the way the real server is proven to behave by backend/tests/test_report_retry_after_failure.py:
 *  - the same request code again  -> the finished result is sent back: no new AI call, no new charge;
 *  - a different request code     -> a new result: a new AI call and a new charge;
 *  - a request that can never finish (3 failed attempts) is answered with "ended-no-charge" and nothing is charged.
 *
 * Three numbers are counted at the end: results created, AI calls that wrote a result, charges recorded. The safe result is 1, 1, 1.
 */
class ReportRetryChargeScenarioTest {
    private enum class Mode { OK, REPLY_LOST, REFUSED_403, PROVIDER_DOWN }
    private class Reply(val status: Int, val text: String? = null, val reportState: String? = null)

    private class FakeServer {
        val results = mutableMapOf<String, String>()
        var created = 0; var aiCalls = 0; var charges = 0
        val failedAttempts = mutableMapOf<String, Int>()
        fun request(code: String, payload: String, mode: Mode): Reply {
            if (mode == Mode.REFUSED_403) return Reply(403)                         // refused before any work: nothing created, nothing charged
            if (mode == Mode.PROVIDER_DOWN && code !in results) {
                val n = (failedAttempts[code] ?: 0) + 1; failedAttempts[code] = n
                return Reply(502, reportState = if (n >= 3) REPORT_ENDED_NO_CHARGE else null)
            }
            if (code !in results) {
                // The text is written from what the request contains, so a request without the conversation misses it.
                results[code] = "FAKE RESULT" + (if ("OBSERVATION-ONLY-IN-CHAT" in payload) " + OBSERVATION-ONLY-IN-CHAT" else "")
                created++; aiCalls++; charges++
            }
            return if (mode == Mode.REPLY_LOST) Reply(504) else Reply(200, results.getValue(code))   // 504: the work was done, only a middle-man's error reaches the phone
        }
        fun numbers() = "results created=$created, AI calls that wrote a result=$aiCalls, charges recorded=$charges"
    }

    private class MapStore : CodeStore {
        val data = mutableMapOf<String, String>()
        override fun read(key: String) = data[key]
        override fun write(key: String, value: String) { data[key] = value }
        override fun remove(key: String) { data.remove(key) }
    }

    private class Phone(val store: MapStore) {
        private var counter = 0
        private fun codes(): RequestJournal = RequestJournal(store) { "code-${++counter}-${System.nanoTime()}" }
        var journal = codes()
        val codesUsed = mutableListOf<String>()
        fun reopenApp() { journal = codes() }          // a new notebook object over the same saved notes = the app was closed and opened again

        /** "Create report" / "Try again" / Document -> Generate report, in the order AppState.draftQuick does it. */
        fun generate(server: FakeServer, mode: Mode, withChat: Boolean = false, sourcesVersion: String = "1"): Reply {
            val payload = if (withChat) "{\"template\":\"nursing\",\"consultation\":\"OBSERVATION-ONLY-IN-CHAT\"}" else "{\"template\":\"nursing\"}"
            val ownName = "pending-quick-report-$sourcesVersion" + if (withChat) "-consult-abc" else ""      // the name depends on the settings
            val pointer = "pending-report"
            val earlier = journal.pendingFor(pointer, sourcesVersion)
            val storage = earlier?.first ?: ownName
            journal.point(pointer, sourcesVersion, storage)
            val record = journal.open(storage, payload)
            record.response?.let { return Reply(200, it) }
            codesUsed += record.code
            val reply = server.request(record.code, record.payload, mode)
            if (reply.status == 200) { journal.saveAnswer(storage, record, reply.text!!); journal.forget(storage); journal.clearPointer(pointer) }
            else journal.afterError(storage, "/encounters/E1/quick-report", true, reply.status, false, reply.reportState)
            return reply
        }

        /** "Improve wording" / "Apply correction": one request per button, named after the report text. */
        fun edit(server: FakeServer, path: String, mode: Mode): Reply {
            val storage = "pending-edit-" + path.substringAfterLast('/')
            val record = journal.open(storage, "{\"text\":\"my report\"}")
            record.response?.let { return Reply(200, it) }
            codesUsed += record.code
            val reply = server.request(record.code, record.payload, mode)
            if (reply.status == 200) { journal.saveAnswer(storage, record, reply.text!!); journal.forget(storage) }
            else journal.afterError(storage, path, false, reply.status, false, reply.reportState)
            return reply
        }
    }

    private fun check(label: String, server: FakeServer) {
        println("RESULT $label: ${server.numbers()}")
        assertEquals("$label: created", 1, server.created)
        assertEquals("$label: AI calls", 1, server.aiCalls)
        assertEquals("$label: charges", 1, server.charges)
    }

    @Test fun serverFinishes_phoneGetsFourErrors_retryAtOnce_1_1_1() {
        val server = FakeServer(); val phone = Phone(MapStore())
        repeat(4) { assertEquals(504, phone.generate(server, Mode.REPLY_LOST).status) }
        assertEquals(200, phone.generate(server, Mode.OK).status)
        assertEquals("one request code for all five taps", 1, phone.codesUsed.toSet().size)
        check("retry at once", server)
    }

    @Test fun serverFinishes_phoneGetsFourErrors_nurseClosesAndReopensApp_retry_1_1_1() {
        val server = FakeServer(); val phone = Phone(MapStore())
        repeat(4) { assertEquals(504, phone.generate(server, Mode.REPLY_LOST).status) }
        phone.reopenApp()
        assertEquals(200, phone.generate(server, Mode.OK).status)
        assertEquals("one request code before and after reopening", 1, phone.codesUsed.toSet().size)
        check("retry after reopening the app", server)
    }

    @Test fun tenErrorsAndTwoReopenings_stillOneCharge() {
        val server = FakeServer(); val phone = Phone(MapStore())
        repeat(10) { if (it == 5) phone.reopenApp(); phone.generate(server, Mode.REPLY_LOST) }
        phone.reopenApp()
        assertEquals(200, phone.generate(server, Mode.OK).status)
        check("ten errors, two reopenings", server)
    }

    @Test fun aRefusedLaterTry403_doesNotEraseTheNumberOfTheFinishedReport_1_1_1() {
        val server = FakeServer(); val phone = Phone(MapStore())
        assertEquals(504, phone.generate(server, Mode.REPLY_LOST).status)       // the server wrote the report and charged once; the reply was lost
        assertEquals(403, phone.generate(server, Mode.REFUSED_403).status)      // next try: access refused (did no work itself)
        assertEquals(403, phone.generate(server, Mode.REFUSED_403).status)
        phone.reopenApp()
        assertEquals(200, phone.generate(server, Mode.OK).status)               // access restored: Try again
        assertEquals("the same request code the whole time", 1, phone.codesUsed.toSet().size)
        check("refused try in the middle", server)
    }

    @Test fun noReportFoundYetIsStillUncertain_theCodeIsKept_1_1_1() {
        val server = FakeServer(); val phone = Phone(MapStore())
        phone.generate(server, Mode.REPLY_LOST)
        phone.journal.afterError("pending-quick-report-1", "/encounters/E1/quick-report", true, 404, false, null)   // "no report found yet"
        assertEquals(200, phone.generate(server, Mode.OK).status)
        check("'no report found yet' answer", server)
    }

    @Test fun improveWording_lostReply_thenAnother502and503_retrySameEdit_noSecondCharge() {
        val server = FakeServer(); val phone = Phone(MapStore())
        val path = "/encounters/E1/improve-report"
        assertEquals(504, phone.edit(server, path, Mode.REPLY_LOST).status)
        phone.journal.afterError("pending-edit-improve-report", path, false, 502, false, null)
        phone.journal.afterError("pending-edit-improve-report", path, false, 503, false, null)
        phone.reopenApp()
        assertEquals(200, phone.edit(server, path, Mode.OK).status)
        assertEquals(1, phone.codesUsed.toSet().size)
        check("Improve wording, lost reply then 502 and 503", server)
    }

    @Test fun applyCorrection_lostReply_retrySameCorrection_noSecondCharge() {
        val server = FakeServer(); val phone = Phone(MapStore())
        assertEquals(504, phone.edit(server, "/encounters/E1/refine-report", Mode.REPLY_LOST).status)
        phone.journal.afterError("pending-edit-refine-report", "/encounters/E1/refine-report", false, 502, false, null)
        assertEquals(200, phone.edit(server, "/encounters/E1/refine-report", Mode.OK).status)
        assertEquals(1, phone.codesUsed.toSet().size)
        check("Apply correction, lost reply then 502", server)
    }

    @Test fun reopeningAnAskConsultantReport_viaDocumentGenerate_resumesTheSameRequest_withTheChatObservation() {
        val server = FakeServer(); val phone = Phone(MapStore())
        // The nurse generates the report FROM the Ask Consultant screen ("include this conversation" is on): the server finishes, the reply is lost.
        assertEquals(504, phone.generate(server, Mode.REPLY_LOST, withChat = true).status)
        phone.reopenApp()
        // After reopening, "include this conversation" starts off. She opens Document -> Generate report: a different request name.
        val reply = phone.generate(server, Mode.OK, withChat = false)
        assertEquals(200, reply.status)
        assertTrue("the report still contains the observation that was entered only in the chat: ${reply.text}", reply.text!!.contains("OBSERVATION-ONLY-IN-CHAT"))
        assertEquals("the same request code before and after reopening", 1, phone.codesUsed.toSet().size)
        check("Ask Consultant report reopened through Document", server)
    }

    @Test fun ifTheSourcesChanged_itIsADifferentReport_andIsNotResumed() {
        val server = FakeServer(); val phone = Phone(MapStore())
        phone.generate(server, Mode.REPLY_LOST, sourcesVersion = "1")
        phone.reopenApp()
        assertEquals(200, phone.generate(server, Mode.OK, sourcesVersion = "2").status)
        assertEquals("a report for new sources is a new request", 2, phone.codesUsed.toSet().size)
    }

    @Test fun aNewRequestStartsOnlyWhenTheServerConfirmsTheOldOneEndedWithNoCharge() {
        val server = FakeServer(); val phone = Phone(MapStore())
        assertEquals(502, phone.generate(server, Mode.PROVIDER_DOWN).status)
        assertEquals(502, phone.generate(server, Mode.PROVIDER_DOWN).status)
        assertEquals("same code kept until the server says it is over", 1, phone.codesUsed.toSet().size)
        assertEquals(502, phone.generate(server, Mode.PROVIDER_DOWN).status)   // third attempt: the server answers ended-no-charge
        assertEquals(0, server.charges)
        assertEquals(200, phone.generate(server, Mode.OK).status)               // provider is back: NEW code is allowed now
        assertEquals("a new code only after the server's confirmation", 2, phone.codesUsed.toSet().size)
        check("new request after confirmed end", server)
    }

    @Test fun serverBusyStillProcessing409_keepsTheCode() {
        val store = MapStore(); val j = RequestJournal(store) { "c" }
        j.open("k", "{}"); j.afterError("k", "/encounters/E1/quick-report", true, 409, false, null)
        assertEquals("c", RequestJournal(store) { "other" }.open("k", "{}").code)
    }
}
