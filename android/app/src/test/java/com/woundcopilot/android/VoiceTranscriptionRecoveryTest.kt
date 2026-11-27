package com.woundcopilot.android

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class VoiceTranscriptionRecoveryTest {
    private val job = "544f47a2-9f94-4348-9a82-8db33e7aca84"
    private val part = VoicePendingPart("visit", job, 0, 2, false, null)
    private val pendingMessage = "This AI request is already processing or awaiting reconciliation. Refresh the case before retrying."

    @Test fun legacyAndMachineStatesAreNarrowAndReceiptMatchesTheSavedSection() {
        assertTrue(ApiException(409, pendingMessage).voicePending())
        assertTrue(ApiException(409, "Waiting", transcriptionState = "pending").voicePending())
        assertTrue(ApiException(409, "Unavailable", transcriptionState = "needs_review").voicePending())
        assertFalse(ApiException(409, "The visit changed", ambiguous = true).voicePending())
        assertFalse(ApiException(503, pendingMessage).voicePending())
        val batch = obj("job_id" to job, "completed" to 0, "files" to JSONArray(listOf("one", "two")))
        val receipt = VoicePendingPart.from("visit", batch, ApiException(409, pendingMessage, "sensitive content"))
        assertEquals(part, VoicePendingPart.read(receipt.encode()))
        assertTrue(receipt.matches(batch))
        assertFalse(receipt.matches(batch.put("completed", 1)))
    }

    @Test fun upgradingUsesTheOriginalVisitAndNeverCreatesAnotherForACheckpointedRecording() {
        assertEquals("old", voiceRecordingVisit(null, null, "old", false))
        assertEquals("original", voiceRecordingVisit(null, "original", "newer", false))
        assertEquals("original", voiceRecordingVisit("original", null, "newer", false))
        assertNull(voiceRecordingVisit(null, null, null, true))
        assertThrows(IllegalStateException::class.java) { voiceRecordingVisit(null, null, null, false) }
        assertThrows(IllegalStateException::class.java) { voiceRecordingVisit("other", "original", null, false) }
    }

    @Test fun pollingOnlyReadsStatusUntilTheOriginalSectionIsAcknowledged() = runBlocking {
        val calls = mutableListOf<String>()
        val pauses = mutableListOf<Long>()
        val result = waitForVoicePart(part, false, { pauses += it }) {
            checkVoicePart(part) { path, method, body, key ->
                assertEquals("GET", method)
                assertNull(body); assertNull(key)
                calls += path
                obj("index" to 0, "status" to if (calls.size == 2) "ready" else "pending")
            }
        }
        assertEquals(VoicePartCheck.Ready, result)
        assertEquals(listOf(3000L), pauses)
        assertEquals(List(2) { "/encounters/visit/audio-parts/$job/status?index=0&count=2" }, calls)
    }

    @Test fun oldServiceCanRecoverCompletedTextWithoutAnotherUpload() = runBlocking {
        val saved = obj("evidence" to obj("encounter_id" to "visit", "kind" to "audio",
            "text" to "First speaker. Second speaker.", "metadata" to obj("job_id" to job, "segment_count" to 2)))
        val result = checkVoicePart(part) { path, method, data, key ->
            if (method == "GET") throw ApiException(404, "Route unavailable")
            assertEquals("/encounters/visit/audio-complete", path)
            assertEquals("$job-complete", key)
            assertEquals(job, data!!.getString("job_id"))
            assertEquals(2, data.getInt("count"))
            saved
        }
        assertSame(saved, (result as VoicePartCheck.Complete).result)
    }

    @Test fun oldServiceNeedsExplicitReplayForAnIncompleteMultiSectionRecording() = runBlocking {
        var probes = 0
        suspend fun oldService() = checkVoicePart(part) { path, method, _, key ->
            if (method == "GET") throw ApiException(405, "No status endpoint")
            assertEquals("/encounters/visit/audio-complete", path)
            assertEquals("$job-complete", key)
            probes++
            throw ApiException(409, "A recording segment still needs transcription. Retry that segment first.")
        }
        try {
            waitForVoicePart(part, manual = false, pause = {}) { oldService() }
            fail("An automatic check must not replay the part")
        } catch (_: VoiceStillPending) { assertEquals(4, probes) }
        assertEquals(VoicePartCheck.LegacyIncomplete, waitForVoicePart(part, true, {}) { oldService() })
        assertEquals(5, probes)
    }

    @Test fun uncertainProviderOutcomeIsNeverReplayedAndUserCancellationStopsChecks() = runBlocking {
        val review = part.copy(review = true)
        var reads = 0
        try {
            waitForVoicePart(review, false, {}) { reads++; VoicePartCheck.Waiting }
            fail("The service needs review")
        } catch (_: VoiceStillPending) { assertEquals(0, reads) }
        try {
            waitForVoicePart(review, true, {}) { reads++; VoicePartCheck.LegacyIncomplete }
            fail("An uncertain outcome must not be replayed")
        } catch (_: VoiceStillPending) { assertEquals(1, reads) }
        try {
            waitForVoicePart(part, false, { throw CancellationException("locked") }) { reads++; VoicePartCheck.Waiting }
            fail("Cancellation should escape")
        } catch (_: CancellationException) { assertEquals(2, reads) }
    }
}
