package com.woundcopilot.android

import org.junit.Assert.*
import org.junit.Test

class NotesDraftRecoveryTest {
    private val request = "4f3fd8bb-bfad-45f5-8206-75b1c8b76068"

    @Test fun submittingTypedNotesKeepsTheSameBufferWhenTheLocalVisitGetsAServerId() {
        val local = notesInputKey("one", "local-$request", null)
        val server = notesInputKey("one", "remote-id", request)
        assertEquals(local, server)
        assertNotEquals(local, notesInputKey("two", "remote-id", request))
        assertNotEquals(local, notesInputKey("one", "remote-id", "../untrusted"))
    }

    @Test fun onlyTheOwnerAndVisitFromTheOriginalStartResponseCanUseTheLocalAlias() {
        val bindings = mapOf("local-bind-one-local-$request" to obj("encounter" to obj("id" to "remote-a")).toString())
        assertEquals(request, boundNotesRequest("one", "remote-a", request, bindings::get))
        assertNull(boundNotesRequest("one", "remote-b", request, bindings::get))
        assertNull(boundNotesRequest("two", "remote-a", request, bindings::get))
        assertNull(boundNotesRequest("one", "remote-a", "../bad", bindings::get))
    }

    @Test fun typedRecoveryPreservesWordingAndIgnoresUnrelatedInputs() {
        val original = "  Original words.\nSecond line. "
        val typed = obj("kind" to "typed", "text" to original)
        val image = obj("kind" to "photo", "text" to "AI interpretation")
        val section = obj("kind" to "audio_part", "text" to "Unjoined audio")
        val speech = obj("kind" to "audio", "text" to "Words spoken.")
        assertEquals(original, notesReviewText(emptyList(),listOf(original)))
        assertEquals("$original\n\nWords spoken.\n\nUnsent addition.",
            notesReviewText(listOf(typed, image, section, speech), listOf(original, "Unsent addition.")))
        assertEquals("", notesReviewText(listOf(image, section), listOf("", "  ")))
    }

    @Test fun savingAnEditedReportRetiresOnlyItsOriginalUnchangedAdoptedTypedForm() {
        val key = notesInputKey("one", "local-$request", null)
        val original = "Original synthetic note"
        val finalReport = "Edited synthetic report"
        val editId = "first-edit"
        fun digest(text: String) = java.security.MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        fun form(text: String, id: String) = obj("text" to text, "edit_id" to id).toString()
        val marker = NotesAdoptedForm(key, digest(original), editId)
        val reopened = obj("text" to finalReport, "notes_adopted_form" to marker.json())
        val adopted = requireNotNull(NotesAdoptedForm.restore(reopened.optJSONObject("notes_adopted_form")))
        val remoteKeys = listOf(notesInputKey("one", "remote", request), notesInputKey("one", "remote", null))
        assertEquals(finalReport, reopened.str("text"))
        assertTrue(adopted.unchanged(remoteKeys, form(original, editId), ::digest))
        assertFalse(adopted.unchanged(remoteKeys, form("New words", editId), ::digest))
        assertFalse(adopted.unchanged(remoteKeys, form(original, "retyped-later"), ::digest))
        assertFalse(adopted.unchanged(listOf(notesInputKey("two", "remote", request)), form(original, editId), ::digest))
        assertFalse(adopted.unchanged(listOf(notesInputKey("one", "other-visit", null)), form(original, editId), ::digest))
        assertFalse(adopted.unchanged(remoteKeys, null, ::digest))
        assertNull(NotesAdoptedForm.restore(obj("text" to finalReport).optJSONObject("notes_adopted_form")))
    }
}
