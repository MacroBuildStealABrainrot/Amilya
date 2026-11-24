package com.woundcopilot.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowFeedbackTest {
    @Test fun routineReviewRemindersAreNotRepeated() {
        assertTrue(routineReminder("Review negation, units, laterality, numbers and speaker attribution before confirmation."))
        assertTrue(routineReminder("No message was sent and no clinical evidence was added."))
        assertTrue(routineReminder("Review the transcript against the recording, including words at segment boundaries."))
        assertTrue(routineReminder("Photo observations require clinical correlation."))
    }

    @Test fun specificWarningsStillShow() {
        assertFalse(routineReminder("The microphone was taken by a phone call; the visit was saved in two parts."))
        assertFalse(routineReminder("Confirm side (L/R)."))
    }

    @Test fun reportWaitAndPermanentFailureSurviveTheAutomaticRetryDecision() {
        val wait = ApiException(503, "synthetic body", retryAfterSeconds = 30, retryAllowed = true)
        assertFalse(canRetryReportImmediately(wait))
        assertTrue(canRetryRequest(wait))
        val blocked = ApiException(502, "synthetic body", category = "OUTPUT_LIMIT", retryAllowed = false)
        assertFalse(canRetryReportImmediately(blocked))
        assertFalse(canRetryRequest(blocked))
        assertTrue(canRetryReportImmediately(ApiException(504, "", retryAllowed = true)))
        assertFalse(canRetryReportImmediately(ApiException(429, "")))
    }

    @Test fun reportFailureGivesSafeDetailsForTypedAndSpokenNotes() {
        val reference = "a2bf8c15-94b0-4b61-a75c-3bc1d8a93c4b"
        val error = ApiException(502, "synthetic private text", requestId = reference)
        val message = clinicianError(error, "report")
        assertTrue(message.contains("HTTP 502"))
        assertTrue(message.contains(reference))
        assertFalse(message.contains("synthetic private text"))
        assertFalse(message.contains("recording", ignoreCase = true))
        val paused = clinicianError(ApiException(503, "", requestId = reference, category = "AI_PAUSED"), "report")
        assertTrue(paused.contains(reference))
        assertTrue(paused.contains("AI_PAUSED"))
        val checked = clinicianError(ApiException(502,
            "The report did not pass source-faithfulness review. Your original source and editor text remain available."), "report")
        assertTrue(checked.contains("verify the draft against your notes"))
    }

    @Test fun interruptedAudioSectionKeepsItsProgressMessage() {
        val error = ReportRetryException("Transcription stopped at section 2 of 3.")
        assertEquals(error.message, clinicianError(error, "audio"))
        assertTrue(canRetryRequest(error))
    }
}
