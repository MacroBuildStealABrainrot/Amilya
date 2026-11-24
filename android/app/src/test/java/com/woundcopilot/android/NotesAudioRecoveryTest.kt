package com.woundcopilot.android

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NotesAudioRecoveryTest {
    private fun audio(
        id: String = "source", owner: String = "account", visit: String = "encounter",
        words: String = "Chair repositioned.", metadata: JSONObject = obj("media_hash" to "digest")
    ) = obj("id" to id, "kind" to "audio", "text" to words, "metadata" to metadata,
        "created_by" to owner, "encounter_id" to visit)

    private fun part(n: Int, words: String = "Practice note.", owner: String = "account") =
        audio(id = "part-$n", words = words, owner = owner, metadata = obj(
            "job_id" to "job-$n", "segment_count" to 2, "visit_id" to "recording", "visit_parts" to 2, "visit_part" to n))

    @Test fun exactWholeFileOrFinishedChunkJobMatchesOnlyInTheSameAccountAndVisit() {
        val single = audio()
        val chunked = part(1)
        val wrong = listOf(audio(owner = "other"), audio(visit = "other"), audio(id = ""),
            audio(metadata = obj("job_id" to "job-1","segment_count" to 0)), audio().put("kind", "audio_part"))
        assertNull(completedNotesAudio(wrong, "account", "encounter", "digest", "job-1"))
        assertSame(single, completedNotesAudio(wrong + single, "account", "encounter", "digest", null))
        assertSame(chunked, completedNotesAudio(wrong + chunked, "account", "encounter", "different", "job-1"))
        assertNull(completedNotesAudio(listOf(chunked), "account", "encounter", "different", "other-job"))
        assertNull(completedNotesAudio(listOf(audio(metadata = obj())), "account", "encounter", "", null))
    }

    @Test fun onlyOnePartOfLongVisitCanNeverStartReport() {
        val first = part(1)
        val other = part(2)
        assertFalse(notesRecordingComplete(first, listOf(first, first), 2))
        assertFalse(notesRecordingComplete(first, listOf(first, part(2, words = "")), 2))
        assertFalse(notesRecordingComplete(first, listOf(first, part(2, owner = "other")), 2))
        assertFalse(notesRecordingComplete(first, listOf(first, part(2).apply { getJSONObject("metadata").put("visit_id", "other") }), 2))
        assertFalse(notesRecordingComplete(first, listOf(first, part(2).apply { getJSONObject("metadata").put("segment_count", 0) }), 2))
        assertFalse(notesRecordingComplete(first, listOf(first, other), 3))
        assertTrue(notesRecordingComplete(first, listOf(first, other), 2))
        assertTrue(notesRecordingComplete(first, listOf(first, other), 1))
        assertFalse(notesRecordingComplete(audio(), listOf(audio()), 2))
        assertTrue(notesRecordingComplete(audio(), listOf(audio()), 1))
    }

    @Test fun retriesOpenAnExistingDraftOnlyAfterTheRecordingReachedReportWriting() {
        for (phase in listOf("TRANSCRIBED", "GENERATING_REPORT", "REPORT_READY")) {
            assertTrue(notesShouldReopenRecordingDraft(phase, "Practice report with my edits."))
            assertFalse(notesShouldReopenRecordingDraft(phase, " "))
        }
        for (phase in listOf("UPLOADING", "RECORDING", "RECORDED_LOCALLY")) {
            assertFalse(notesShouldReopenRecordingDraft(phase, "Another note already in this visit."))
        }
    }

    @Test fun onlyAFinishedNursingReportFromTheSameAccountAndVisitCanOutlastItsAudio() {
        val done = obj("id" to "job", "owner" to "practice-owner", "file" to "/practice/test.m4a",
            "template" to "nursing", "phase" to "REPORT_READY", "resume_from" to "REPORT_READY",
            "report" to "A made-up report.", "case" to obj("encounter" to obj("id" to "practice-visit")))
        assertEquals("practice-visit", readyNotesRecordingVisit(done, "practice-owner"))
        assertNull(readyNotesRecordingVisit(done, "someone-else"))
        for ((field, value) in listOf("resume_from" to "TRANSCRIBED", "resume_from" to "UPLOADING",
            "template" to "soap", "report" to " ", "phase" to "COMPLETED", "id" to "", "file" to "")) {
            assertNull(readyNotesRecordingVisit(JSONObject(done.toString()).put(field, value), "practice-owner"))
        }
        assertNull(readyNotesRecordingVisit(JSONObject(done.toString()).put("case", obj()), "practice-owner"))
    }
}
