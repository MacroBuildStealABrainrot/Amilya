package com.woundcopilot.android

import org.json.JSONObject
import java.util.UUID

/** A new document gets a server encounter ID later. Its input form keeps its original key. */
internal fun notesInputKey(owner: String, encounter: String, request: String?): String {
    val validRequest = request?.takeIf {
        runCatching { UUID.fromString(it).toString() == it }.getOrDefault(false)
    }
    val id = if (!encounter.startsWith("local-") && validRequest != null) "local-$validRequest" else encounter
    return "evidence-form-$owner-$id-typed"
}

/** A remote encounter can only use the local form when our own stored start response links both IDs. */
internal fun boundNotesRequest(owner: String, encounter: String, request: String?, read: (String) -> String?): String? {
    if (encounter.startsWith("local-") || request.isNullOrBlank()) return null
    if (notesInputKey(owner, encounter, request) == notesInputKey(owner, encounter, null)) return null
    val response = read("local-bind-$owner-local-$request") ?: return null
    val bound = runCatching { JSONObject(response).getJSONObject("encounter").str("id") }.getOrNull()
    return request.takeIf { bound == encounter }
}

internal fun notesInputText(snapshot: String?): String = snapshot?.let {
    runCatching { JSONObject(it).str("text") }.getOrNull()
}.orEmpty()

/** Only the actual text sources are offered for review. Clinician-provided entries keep their order and wording. */
internal fun notesReviewText(sources: List<JSONObject>, unsent: List<String>): String {
    val received = sources.filter { it.str("kind") in setOf("typed", "transcript", "audio") }
    val receivedText = received.map { it.str("text") }.filter(String::isNotBlank)
    val receivedTyped = received.filter { it.str("kind") == "typed" }.map { it.str("text") }.toSet()
    return (receivedText + unsent.filter(String::isNotBlank).distinct().filterNot { it in receivedTyped }).joinToString("\n\n")
}

/** Bound to one form revision so saving a report never discards later typing, even if the words match again. */
internal data class NotesAdoptedForm(val key: String, val digest: String, val editId: String) {
    fun json() = obj("key" to key, "digest" to digest, "edit_id" to editId)

    fun unchanged(allowedKeys: List<String>, snapshot: String?, hash: (String) -> String): Boolean {
        if (key !in allowedKeys || snapshot == null) return false
        val form = runCatching { JSONObject(snapshot) }.getOrNull() ?: return false
        return form.str("edit_id") == editId && hash(form.str("text")) == digest
    }

    companion object {
        fun restore(data: JSONObject?): NotesAdoptedForm? {
            if (data == null || data.str("key").isBlank() || !Regex("[0-9a-f]{64}").matches(data.str("digest"))) return null
            return NotesAdoptedForm(data.str("key"), data.str("digest"), data.str("edit_id"))
        }
    }
}
