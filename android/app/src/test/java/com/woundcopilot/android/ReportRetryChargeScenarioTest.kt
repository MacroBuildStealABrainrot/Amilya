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
    private enum class Mode { OK, REPLY_LOST, REFUSED_403, PROVIDER_DOWN, REFUSES_CHAT_422 }
    private class Reply(val status: Int, val text: String? = null, val reportState: String? = null)

    private class FakeServer {
        val results = mutableMapOf<String, String>()
        var created = 0; var aiCalls = 0; var charges = 0
        val failedAttempts = mutableMapOf<String, Int>()
        val requests = mutableListOf<String>()      // every payload that reached the server, in order
        fun request(code: String, payload: String, mode: Mode): Reply {
            requests += payload
            if ("\"use_ai\":false" in payload) return Reply(200, "MANUAL DRAFT")      // a draft made without AI: no AI call, no charge, no result stored
            if (mode == Mode.REFUSES_CHAT_422 && "\"consultation\"" in payload) return Reply(422)   // checked before any work: nothing created, nothing charged
            if (mode == Mode.REFUSED_403) return Reply(403)                         // refused before any work: nothing created, nothing charged
            if (mode == Mode.PROVIDER_DOWN && code !in results) {
                val n = (failedAttempts[code] ?: 0) + 1; failedAttempts[code] = n
                return Reply(502, reportState = if (n >= 3) REPORT_ENDED_NO_CHARGE else null)
            }
            if (code !in results) {
                // The text is written from what the request contains, so a request without the conversation misses it.
                results[code] = "FAKE RESULT" + (if ("OBSERVATION-ONLY-IN-CHAT" in payload) " + OBSERVATION-ONLY-IN-CHAT" else "") + (if ("right heel" in payload) " + right heel" else "")
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
            if (reply.status == 200) { journal.saveAnswer(storage, record, reply.text!!); journal.forget(storage); journal.clearPointerIfPointsTo(pointer, storage) }
            else journal.afterError(storage, "/encounters/E1/quick-report", true, reply.status, false, reply.reportState)
            return reply
        }


        // ---- Ask Consultant conversation: sent whole or not at all (same order as AppState.draftQuick) ----
        var includeChat = true                      // AppState.reportFromConsultation
        class Outcome(val reply: Reply?, val message: String?, val notice: String? = null)
        fun generateFromChat(server: FakeServer, mode: Mode, chat: List<String>, attachedFileText: String, sourcesVersion: String = "1", attachmentWarning: String? = null): Outcome {
            val pointer = "pending-report"
            val earlier = journal.pendingFor(pointer, sourcesVersion)
            val used = if (includeChat && earlier == null) chat.filter { it.isNotBlank() } else emptyList()
            if (used.isNotEmpty() && !consultationFits(used)) { includeChat = false; return Outcome(null, CONSULTATION_TOO_LONG_MESSAGE) }   // stop: nothing is sent
            val payload = "{\"template\":\"nursing\",\"sources\":\"$attachedFileText\"" + (if (used.isNotEmpty()) ",\"consultation\":\"${used.joinToString(" | ")}\"" else "") + "}"
            val ownName = "pending-quick-report-$sourcesVersion" + if (used.isNotEmpty()) "-consult-abc" else ""
            val storage = earlier?.first ?: ownName
            journal.point(pointer, sourcesVersion, storage)
            val leftOutKey = "left-out-$storage"
            if (earlier == null && store.read("left-out-choice") != null) { store.write(leftOutKey, "1"); store.remove("left-out-choice") }   // saved on the phone, like AppState
            val attachKey = "attach-warn-$storage"
            if (attachmentWarning != null) store.write(attachKey, attachmentWarning)
            val record = journal.open(storage, payload)
            // the notice is decided from what was REALLY sent and from the saved flag, never supplied by a test
            fun noticeNow() = combineReportNotices("\"consultation\"" !in record.payload && store.read(leftOutKey) != null, null, attachmentWarning ?: store.read(attachKey))
            record.response?.let { val n = noticeNow(); store.remove(leftOutKey); store.remove(attachKey); return Outcome(Reply(200, it), null, n) }
            codesUsed += record.code
            val reply = server.request(record.code, record.payload, mode)
            if (reply.status == 200) { val n = noticeNow(); journal.saveAnswer(storage, record, reply.text!!); journal.forget(storage); journal.clearPointerIfPointsTo(pointer, storage); store.remove(leftOutKey); store.remove(attachKey); return Outcome(reply, null, n) }
            else if (reply.status == 422 && "\"consultation\"" in record.payload) {
                return Outcome(null, CONSULTATION_REFUSED_MESSAGE)                  // note AND pointer are KEPT; no automatic attempt without the conversation
            } else journal.afterError(storage, "/encounters/E1/quick-report", true, reply.status, false, reply.reportState)
            return Outcome(reply, null)
        }

        /** The nurse's own choice "New report from photos and files only": ONCE the earlier request is put aside (AppState.reportWithoutConversation). */
        fun newReportWithoutConversation(server: FakeServer, mode: Mode, chat: List<String>, attachedFileText: String): Outcome {
            journal.abandonPointer("pending-report"); includeChat = false; store.write("left-out-choice", "1")
            return again(server, mode, chat, attachedFileText)
        }
        /** Try again after a failure of that same choice: repeats the report WITHOUT putting anything aside again (AppState.reportFromConsultant's retry). */
        fun again(server: FakeServer, mode: Mode, chat: List<String>, attachedFileText: String): Outcome = generateFromChat(server, mode, chat, attachedFileText)

        /** "Create draft without AI", in the order AppState.draftQuick does it: it never looks up or points at an AI report. */
        fun manualDraft(server: FakeServer, sourcesVersion: String = "1"): Reply {
            val payload = "{\"template\":\"nursing\",\"use_ai\":false}"
            val storage = "pending-quick-report-$sourcesVersion-manual"
            val record = journal.open(storage, payload)
            val reply = server.request(record.code, record.payload, Mode.OK)
            journal.forget(storage); journal.clearPointerIfPointsTo("pending-report", storage)
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

    private fun longChat() = listOf("right heel: dressing changed, 2 cm wound") + List(39) { "x".repeat(1100) }     // 40 messages, over 40,000 letters

    @Test fun conversationTooLong_nothingIsSent_chatIsKept_noReportWithoutIt() {
        val server = FakeServer(); val phone = Phone(MapStore()); val chat = longChat()
        val out = phone.generateFromChat(server, Mode.OK, chat, "wound edges clean, no mention of any heel")
        assertEquals(CONSULTATION_TOO_LONG_MESSAGE, out.message)
        assertEquals("zero automatic attempts to write a report without the conversation", 0, server.requests.size)
        assertEquals("the conversation is preserved", 40, chat.size); assertTrue(chat[0].contains("right heel"))
        assertEquals(0, server.created); assertEquals(0, server.charges)
        // Only the nurse's own next choice (Document -> Generate report) writes a report without it, and then the screen says what happened.
        assertEquals(200, phone.generateFromChat(server, Mode.OK, chat, "wound edges clean, no mention of any heel").reply!!.status)
        assertEquals(1, server.requests.size); assertTrue(!server.requests[0].contains("consultation"))
    }

    @Test fun lostReply_thenTheConversationGrowsTooLong_theSameRequestIsResumed_noSecondCharge() {
        val server = FakeServer(); val phone = Phone(MapStore())
        val short = listOf("right heel: dressing changed")
        assertEquals(504, phone.generateFromChat(server, Mode.REPLY_LOST, short, "file without the heel").reply!!.status)   // finished and charged, reply lost
        phone.reopenApp(); phone.includeChat = true
        val out = phone.generateFromChat(server, Mode.OK, short + List(39) { "x".repeat(1100) }, "file without the heel")        // chat is now too long
        assertEquals(200, out.reply!!.status)
        assertTrue("the paid report still has the heel observation: ${out.reply.text}", out.reply.text!!.contains("right heel"))
        assertTrue("every request that reached the server contained the conversation", server.requests.all { it.contains("right heel") })
        assertEquals(1, phone.codesUsed.toSet().size)
        check("lost reply, then chat too long", server)
    }

    @Test fun serverRefusesTheConversation422_noAutomaticAttemptWithoutIt_nothingCharged() {
        val server = FakeServer(); val phone = Phone(MapStore())
        val out = phone.generateFromChat(server, Mode.REFUSES_CHAT_422, listOf("right heel: dressing changed"), "file without the heel")
        assertEquals(CONSULTATION_REFUSED_MESSAGE, out.message)
        assertEquals("exactly one request, none without the conversation", 1, server.requests.size)
        assertTrue(server.requests[0].contains("right heel"))
        assertEquals(0, server.created); assertEquals(0, server.charges)
        // Nothing is forgotten behind the nurse's back: the earlier request is still waiting, and a later Generate report recovers it.
        assertTrue(phone.journal.pendingFor("pending-report", "1") != null)
        assertEquals(CONSULTATION_REFUSED_MESSAGE, phone.generateFromChat(server, Mode.REFUSES_CHAT_422, listOf("right heel: dressing changed"), "file without the heel").message)
        assertTrue("still no request without the conversation", server.requests.all { it.contains("right heel") })
    }

    @Test fun reportFinishes_replyLost_thenTheServerRefusesTheNextTry_theRequestCodeSurvives_1_1_1() {
        val server = FakeServer(); val phone = Phone(MapStore())
        val chat = listOf("right heel: dressing changed")
        assertEquals(504, phone.generateFromChat(server, Mode.REPLY_LOST, chat, "file without the heel").reply!!.status)   // paid; reply lost
        phone.reopenApp()
        val refused = phone.generateFromChat(server, Mode.REFUSES_CHAT_422, chat, "file without the heel")                 // next try is refused
        assertEquals(CONSULTATION_REFUSED_MESSAGE, refused.message)
        assertTrue("every request that reached the server contained the conversation", server.requests.all { it.contains("right heel") })
        val back = phone.generateFromChat(server, Mode.OK, chat, "file without the heel")
        assertEquals(200, back.reply!!.status)
        assertTrue("the original note comes back with its chat observation: ${back.reply.text}", back.reply.text!!.contains("right heel"))
        assertEquals("one request code the whole time", 1, phone.codesUsed.toSet().size)
        check("refused next try, then recovered", server)
    }

    @Test fun reopenThroughDocumentGenerate_afterARefusedTry_stillRecoversThePaidNote_1_1_1() {
        val server = FakeServer(); val phone = Phone(MapStore())
        val chat = listOf("right heel: dressing changed")
        assertEquals(504, phone.generateFromChat(server, Mode.REPLY_LOST, chat, "file without the heel").reply!!.status)   // paid; reply lost
        phone.reopenApp(); phone.includeChat = false
        assertEquals(CONSULTATION_REFUSED_MESSAGE, phone.generateFromChat(server, Mode.REFUSES_CHAT_422, chat, "file without the heel").message)
        phone.reopenApp(); phone.includeChat = false                                    // app closed; same visit reopened; "include the conversation" is off
        val back = phone.generateFromChat(server, Mode.OK, chat, "file without the heel")   // Document -> Generate report: the nurse chose nothing to leave out
        assertEquals(200, back.reply!!.status)
        assertTrue("the original note comes back with its chat observation: ${back.reply.text}", back.reply.text!!.contains("right heel"))
        assertEquals(null, back.notice)
        assertEquals("one request code the whole time", 1, phone.codesUsed.toSet().size)
        check("refused try, reopened through Document", server)
    }

    @Test fun twoClearChoices_recoverTheEarlierNote_orStartANewOneFromAttachmentsOnly() {
        assertEquals(listOf(ConsultationChoice.RECOVER_EARLIER, ConsultationChoice.NEW_WITHOUT_CONVERSATION), consultationChoices(earlierReportWaiting = true, fits = true))
        assertEquals(listOf(ConsultationChoice.RECOVER_EARLIER, ConsultationChoice.NEW_WITHOUT_CONVERSATION), consultationChoices(earlierReportWaiting = true, fits = false))
        assertEquals(listOf(ConsultationChoice.CREATE), consultationChoices(false, true))
        assertEquals(listOf(ConsultationChoice.CREATE_WITHOUT_CONVERSATION), consultationChoices(false, false))
    }

    @Test fun choosingRecover_returnsTheEarlierNoteWithItsChat_noChargeAndNoLeftOutNotice() {
        val server = FakeServer(); val phone = Phone(MapStore())
        val chat = listOf("right heel: dressing changed")
        phone.generateFromChat(server, Mode.REPLY_LOST, chat, "file without the heel")
        phone.reopenApp(); phone.includeChat = false
        val recovered = phone.generateFromChat(server, Mode.OK, chat, "file without the heel")     // Recover earlier report
        assertTrue(recovered.reply!!.text!!.contains("right heel")); assertEquals(null, recovered.notice)
        check("recover earlier report", server)
    }

    @Test fun choosingANewReportFromAttachmentsOnly_leavesTheChatOut_andTheNoticeMatchesTheNote() {
        val server = FakeServer(); val phone = Phone(MapStore())
        val chat = listOf("right heel: dressing changed")
        phone.generateFromChat(server, Mode.REPLY_LOST, chat, "file without the heel")              // earlier note, paid, reply lost
        phone.reopenApp()
        val fresh = phone.newReportWithoutConversation(server, Mode.REPLY_LOST, chat, "file without the heel")   // the new request's own reply is lost too
        assertEquals(504, fresh.reply!!.status)
        val retry = phone.again(server, Mode.OK, chat, "file without the heel")   // Try again: same NEW request, nothing put aside again
        assertEquals(200, retry.reply!!.status)
        assertTrue("the new note really leaves the chat out: ${retry.reply.text}", !retry.reply.text!!.contains("right heel"))
        assertEquals(CONSULTATION_LEFT_OUT_NOTICE, retry.notice)
        assertTrue("the second request carried no conversation", !server.requests[1].contains("right heel") && !server.requests[2].contains("right heel"))
        assertEquals("two requests in all: the earlier one and the new one (a deliberate second purchase)", 2, phone.codesUsed.toSet().size)
        assertEquals("the retry of the new request did not buy a third note", 2, server.charges)
    }

    @Test fun aDraftWithoutAi_neverErasesTheLinkToAnAlreadyPaidAiReport_1_1_1() {
        val server = FakeServer(); val phone = Phone(MapStore())
        val chat = listOf("right heel: dressing changed")
        assertEquals(504, phone.generateFromChat(server, Mode.REPLY_LOST, chat, "file without the heel").reply!!.status)   // paid; reply lost
        phone.reopenApp(); phone.includeChat = false                                    // reopened: "include the conversation" is off
        assertEquals("MANUAL DRAFT", phone.manualDraft(server).text)                    // Document -> Create draft without AI
        assertEquals("the manual draft added no charge", 1, server.charges)
        val back = phone.generateFromChat(server, Mode.OK, chat, "file without the heel")  // back for the original AI report
        assertEquals(200, back.reply!!.status)
        assertTrue("the original AI note comes back with its chat observation: ${back.reply.text}", back.reply.text!!.contains("right heel"))
        assertEquals("one request code for the AI report", 1, phone.codesUsed.toSet().size)
        check("manual draft in between", server)
    }

    @Test fun newReportWithoutTheChat_replyLost_appClosed_reopened_noticeStillShown_withoutTheTestSupplyingIt() {
        val server = FakeServer(); val phone = Phone(MapStore())
        val chat = listOf("right heel: dressing changed")
        phone.generateFromChat(server, Mode.REPLY_LOST, chat, "file without the heel")              // earlier note, paid, reply lost
        phone.reopenApp()
        assertEquals(504, phone.newReportWithoutConversation(server, Mode.REPLY_LOST, chat, "file without the heel").reply!!.status)
        phone.reopenApp()                                                                          // app closed before the note came back
        val again = phone.again(server, Mode.OK, chat, "file without the heel")
        assertEquals(200, again.reply!!.status)
        assertTrue("the note leaves out the heel: ${again.reply.text}", !again.reply.text!!.contains("right heel"))
        assertEquals("the warning comes from the saved flag", CONSULTATION_LEFT_OUT_NOTICE, again.notice)
        assertEquals("earlier note + new note only", 2, server.charges)
    }

    @Test fun recoveredEarlierNote_neverShowsTheLeftOutNotice_evenAfterAnAbandonedChoice() {
        val server = FakeServer(); val phone = Phone(MapStore())
        val chat = listOf("right heel: dressing changed")
        phone.generateFromChat(server, Mode.REPLY_LOST, chat, "file without the heel")
        phone.reopenApp()
        val back = phone.generateFromChat(server, Mode.OK, chat, "file without the heel")
        assertTrue(back.reply!!.text!!.contains("right heel")); assertEquals(null, back.notice)
    }

    @Test fun doneAfterARefusedConversation_offersBothChoicesInTheSameVisit() {
        // the real AppState.finishConsultant / reportFromConsultant rules
        assertTrue(showsReportChoicesOnDone("consultant", hasMessages = true, editorBlank = true))
        var mode: String? = "consultant"; val before = mode
        mode = "document"                                                                           // chooseQuickMode("document") before the request
        mode = modeAfterFailedConsultantReport(before, editorBlank = true, current = mode)          // the server refused: no report written
        assertEquals("consultant", mode)
        assertTrue("tapping Done again shows the dialog", showsReportChoicesOnDone(mode, true, true))
        assertEquals("a report that DID arrive stays on Document", "document", modeAfterFailedConsultantReport("consultant", editorBlank = false, current = "document"))
        assertEquals(listOf(ConsultationChoice.RECOVER_EARLIER, ConsultationChoice.NEW_WITHOUT_CONVERSATION), consultationChoices(true, true))
    }

    @Test fun chatOnlyInTheVisit_replyLost_refusedTry_appReopened_noFile_theNurseCanStillRecoverRightHeel_oneCharge() {
        val server = FakeServer(); val phone = Phone(MapStore())
        val chat = listOf("right heel: dressing changed")                    // "right heel" is ONLY in the chat; the visit has no file at all
        assertEquals(504, phone.generateFromChat(server, Mode.REPLY_LOST, chat, "").reply!!.status)       // finished and paid; reply lost
        assertEquals(403, phone.generateFromChat(server, Mode.REFUSED_403, chat, "").reply!!.status)      // the next try is refused
        phone.reopenApp(); phone.includeChat = false                          // closed and reopened the visit
        assertTrue("with no file, the Document screen still offers the recovery button", showsGenerateOnDocument(true, 0, earlierReportWaiting = true))
        assertTrue("and does not offer a plain Generate report with nothing to generate from", !showsGenerateOnDocument(true, 0, earlierReportWaiting = false))
        val back = phone.generateFromChat(server, Mode.OK, chat, "")         // taps Recover earlier report
        assertEquals(200, back.reply!!.status)
        assertTrue("right heel is back: ${back.reply.text}", back.reply.text!!.contains("right heel"))
        assertEquals("one request number", 1, phone.codesUsed.toSet().size)
        check("chat-only recovery after reopening", server)
    }

    @Test fun omissionWarning_sitsAlongsideOtherWarnings_andSurvivesClosingAndReopeningTheDraft() {
        val shown = combineReportNotices(true, "Check the dose.", "One photo could not be read.")
        assertEquals(listOf(CONSULTATION_LEFT_OUT_NOTICE, "Check the dose.", "One photo could not be read."), shown!!.split("\n"))
        // the draft saves these warnings with it and gives them back unchanged when the visit is reopened
        val store = MapStore(); store.write("draft-notice", shown)
        val reopened = MapStore().also { it.data.putAll(store.data) }
        assertEquals(shown, reopened.read("draft-notice"))
        // the original report that included the chat carries no omission warning, and nothing else is lost
        assertEquals("Check the dose.", combineReportNotices(false, "Check the dose.", null))
        assertEquals(null, combineReportNotices(false, null, null))
        assertTrue(!combineReportNotices(false, "Check the dose.", null)!!.contains("leave it out"))
    }

    @Test fun unreadablePhotoWarning_survivesFullRestart_andNamesThePhoto_noExtraCharge() {
        val server = FakeServer(); val phone = Phone(MapStore())
        val chat = listOf("right heel: dressing changed")
        val warn = "heel.jpg could not be read."
        assertEquals(504, phone.generateFromChat(server, Mode.REPLY_LOST, chat, "", attachmentWarning = warn).reply!!.status)
        phone.reopenApp(); phone.includeChat = false                      // fully closed: the in-memory warning is gone
        val back = phone.generateFromChat(server, Mode.OK, chat, "")      // recovered through Document, nothing supplied
        assertEquals(200, back.reply!!.status)
        assertEquals("the saved warning names the photo", warn, back.notice)
        assertEquals(1, server.charges)
    }

    @Test fun switchingReports_neverCarriesTheOldBanner() {
        val banner = combineReportNotices(true, "Check the dose.", null)
        assertEquals(banner, noticeForShownReport(banner))
        assertEquals("an older note with no saved warning shows none", null, noticeForShownReport(null))
        assertEquals(null, noticeForShownReport(""))
    }
}
