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
 * Should the phone keep the SAME request code for the next try of a request that can call the AI (and so be charged)?
 *
 * The code is what stops a second charge: a report the server already finished is sent back for the same code with no new AI
 * call and no new charge. An error on a LATER try says nothing about an EARLIER try, which may have finished and been charged:
 * a refused try (403, 402, 422 ...) did no work itself, but the first one might have. So the phone keeps the code through
 * everything: a lost or unreadable reply, a gateway error, "busy", "still processing", "no report found yet", a refused try.
 * It starts a NEW request only when the server confirms the request ended with no charge and can never finish later.
 */
internal fun reportCodeKept(reportState: String?): Boolean = reportState != REPORT_ENDED_NO_CHARGE

/** Requests that can call the AI and be charged: these keep their request code (see [reportCodeKept]). */
internal fun chargedAiPath(path: String): Boolean =
    listOf("/quick-report", "/refine-report", "/improve-report", "/improve-assessment", "/consult", "/draft").any { path.endsWith(it) }

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

    /**
     * After an error: an AI request (see [chargedAiPath]) keeps its note, and so its code, unless the server says it is over.
     * Any other request keeps the rule from earlier builds. [forceKeep] marks a request as an AI request whatever its path.
     */
    fun afterError(storage: String, path: String, forceKeep: Boolean, status: Int, ambiguous: Boolean, reportState: String?) {
        if (forceKeep || chargedAiPath(path)) { if (!reportCodeKept(reportState)) store.remove(storage) }
        else if (!ambiguous && status in listOf(400, 402, 403, 404, 409, 413, 415, 422, 429, 502, 503)) store.remove(storage)
    }

    /**
     * An unfinished AI report of this visit. The note's name depends on the exact request (for example whether the Ask
     * Consultant conversation was included), and after the app is reopened that setting starts off. So the phone also remembers,
     * per visit, WHICH note is the unfinished report ([point]) and [pendingFor] finds it again, whatever the new settings are.
     * [stamp] is the sources version: if the sources changed, it is a different report and nothing is resumed.
     */
    fun point(pointer: String, stamp: String, storage: String) = store.write(pointer, "$stamp\n$storage")
    fun clearPointer(pointer: String) = store.remove(pointer)
    fun pendingFor(pointer: String, stamp: String, legacy: (String) -> RequestRecord? = { null }): Pair<String, RequestRecord>? {
        val parts = store.read(pointer)?.split("\n", limit = 2) ?: return null
        if (parts.size != 2 || parts[0] != stamp) return null
        val raw = store.read(parts[1]) ?: return null
        val record = parse(raw) ?: legacy(raw) ?: return null
        return parts[1] to record
    }

    private fun save(storage: String, record: RequestRecord) = store.write(storage, listOf(HEADER, record.code, record.payload, record.response ?: "").joinToString("\n"))

    private fun parse(raw: String): RequestRecord? {
        val parts = raw.split("\n", limit = 4)
        if (parts.size != 4 || parts[0] != HEADER || parts[1].isBlank()) return null
        return RequestRecord(parts[1], parts[2], parts[3].ifEmpty { null })
    }
}
