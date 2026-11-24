package com.woundcopilot.android

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NotesDraftIndexTest {
    private val owner = "11111111-1111-4111-8111-111111111111"
    private val other = "22222222-2222-4222-8222-222222222222"
    private val remote = "33333333-3333-4333-8333-333333333333"
    private val local = "local-44444444-4444-4444-8444-444444444444"
    private val binding = "local-bind-$owner-$local"
    private val oldForm = "evidence-form-$owner-$local-typed"
    private val newForm = "evidence-form-$owner-$remote-typed"
    private val report = "draft-$owner-$remote"
    private val bound = JSONObject().put("encounter", JSONObject().put("id", remote)).toString()
    private fun form(text: String) = JSONObject().put("text", text).toString()
    private fun rows(records: Map<String, String>, timestamp: (String) -> Long = {1000L}) =
        NotesDraftIndex.list(owner, records.keys.toList(), records::get, timestamp, 900L)

    @Test fun legacyOrphanReopensByOriginalKeyWhileMatchingDuplicateOnlyAppearsOnce() {
        val files = mutableMapOf(binding to bound, oldForm to form("Synthetic input"))
        assertEquals(oldForm, rows(files).single().key)
        files[newForm] = form("Synthetic input")
        assertEquals(1, rows(files).size)
        files[newForm] = form("A separate edit")
        assertEquals(setOf(oldForm,newForm), rows(files).map { it.key }.toSet())
    }

    @Test fun reviewedAndSavedCopiesSuppressOnlyMatchingSource() {
        val files = mutableMapOf(binding to bound, oldForm to form("Reviewed demo"), report to
            JSONObject().put("text","Reviewed demo").put("template","nursing").put("saved_id",JSONObject.NULL).toString())
        assertEquals(NotesDraftKind.REPORT, rows(files).single().kind)
        files[report] = JSONObject(files.getValue(report)).put("saved_id", "synthetic-document").toString()
        assertTrue(rows(files).isEmpty())
        files[oldForm] = form("Changed demo")
        assertEquals(oldForm, rows(files).single().key)
    }

    @Test fun onlyReportDraftsExpireAndOtherOwnerFilesAreNeverOpened() {
        val files = mapOf("evidence-form-$other-$local-typed" to form("different owner"), oldForm to form("older unsent typing"), report to form("expired"),
            newForm to "broken", "draft-$owner-$remote-extra" to form("unrelated"),
            "draft-$owner-$local" to JSONObject().put("text","not nursing").put("template","medical_order").toString(),
            "evidence-form-$owner-$local-transcript" to form("different screen"))
        val reads = mutableListOf<String>()
        assertEquals(oldForm, NotesDraftIndex.list(owner, files.keys.toList(), { reads += it; files[it] },
            {if(it == oldForm || it == report) 800L else 1000L}, 900L).single().key)
        assertFalse("evidence-form-$other-$local-typed" in reads)
        assertFalse(report in reads)
    }
    @Test fun acceptedSourceRemainsAvailableUntilAnEqualOrNewerReportIsSaved() {
        val key = "source-notes-$owner-$remote"
        val records = mutableMapOf(key to JSONObject().put("text","Transcribed example")
            .put("case_revision",3).toString(), newForm to form("Separate unsent example"))
        assertEquals(setOf(NotesDraftKind.SOURCE, NotesDraftKind.TYPED), rows(records).map { it.kind }.toSet())
        records[report] = JSONObject().put("text","Reviewed wording").put("case_revision",2).put("saved_id","doc").toString()
        assertTrue(rows(records).any { it.kind == NotesDraftKind.SOURCE })
        records[report] = JSONObject(records.getValue(report)).put("case_revision",3).toString()
        assertEquals(NotesDraftKind.TYPED, rows(records).single().kind)
        records[report] = JSONObject(records.getValue(report)).apply { remove("saved_id") }.toString()
        assertEquals(setOf(NotesDraftKind.REPORT, NotesDraftKind.TYPED), rows(records).map { it.kind }.toSet())
    }

}
