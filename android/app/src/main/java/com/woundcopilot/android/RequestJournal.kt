package com.woundcopilot.android

/** Where the phone keeps its notes between app launches (in the app: the encrypted vault). */
internal interface CodeStore {
    fun read(key: String): String?
    fun write(key: String, value: String)
    fun remove(key: String)
}

/** The server's "this request has ended for good, nothing was charged, it can never finish later" answer. */
internal const val REPORT_ENDED_NO_CHARGE = "ended-no-charge"

/**
 * Should the phone keep the SAME request code for the next try of an AI report?
 *
 * The code is what stops a second charge: a report the server already finished is sent back for the same code with no new AI
 * call and no new charge. So the phone keeps it through every uncertain outcome: a lost or unreadable reply, a gateway error,
 * "busy", "still processing", "no report found yet", a code the phone cannot explain. It starts a NEW request only when
 *  - the server confirms the request ended with no charge and can never finish later ([reportState] = [REPORT_ENDED_NO_CHARGE]), or
 *  - the server refused the request before doing any work (400, 402, 403, 413, 415, 422): nothing was started, nothing charged.
 */
internal fun reportCodeKept(status: Int, ambiguous: Boolean, reportState: String?): Boolean {
    if (reportState == REPORT_ENDED_NO_CHARGE) return false
    if (ambiguous) return true
    return status !in listOf(400, 402, 403, 413, 415, 422)
}

/** One request the phone has sent or is about to send: its code, the exact payload, and the answer once it arrived. */
internal class RequestRecord(val code: String, val payload: String, val response: String?)

/**
 * The phone's note of each server request, written BEFORE the request leaves the phone, so closing or killing the app, or a
 * dropped connection, never loses the request code. The next try (even after the app is reopened) finds the same note.
 * The payload is JSON text on one line, so the note is four plain lines: header, code, payload, answer (empty until it arrives).
 */
internal class RequestJournal(private val store: CodeStore, private val newCode: () -> String) {
    private companion object { const val HEADER = "journal-v2" }

    /** The earlier note for this request, or a new one with a new code (saved at once). [legacy] reads notes from older builds. */
    fun open(storage: String, payload: String, legacy: (String) -> RequestRecord? = { null }): RequestRecord {
        val raw = store.read(storage)
        val existing = raw?.let { parse(it) ?: legacy(it) }
        if (existing != null) { if (parse(raw) == null) save(storage, existing); return existing }
        return RequestRecord(newCode(), payload, null).also { save(storage, it) }
    }

    fun saveAnswer(storage: String, record: RequestRecord, response: String) = save(storage, RequestRecord(record.code, record.payload, response))

    /** The answer has been used (shown, saved): the note is no longer needed. */
    fun forget(storage: String) = store.remove(storage)

    /** After an error on an AI report: keep the note (and so the code) unless [reportCodeKept] says the request is over. */
    fun afterReportError(storage: String, status: Int, ambiguous: Boolean, reportState: String?) {
        if (!reportCodeKept(status, ambiguous, reportState)) store.remove(storage)
    }

    /** After an error on any other request: unchanged rule from earlier builds. */
    fun afterOtherError(storage: String, status: Int, ambiguous: Boolean) {
        if (!ambiguous && status in listOf(400, 402, 403, 404, 409, 413, 415, 422, 429, 502, 503)) store.remove(storage)
    }

    private fun save(storage: String, record: RequestRecord) = store.write(storage, listOf(HEADER, record.code, record.payload, record.response ?: "").joinToString("\n"))

    private fun parse(raw: String): RequestRecord? {
        val parts = raw.split("\n", limit = 4)
        if (parts.size != 4 || parts[0] != HEADER || parts[1].isBlank()) return null
        return RequestRecord(parts[1], parts[2], parts[3].ifEmpty { null })
    }
}
