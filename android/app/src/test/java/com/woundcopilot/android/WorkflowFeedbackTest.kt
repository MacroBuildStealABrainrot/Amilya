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

    @Test fun reportRetryMessageIsPlainAndRetryable() {
        val error = ReportRetryException("The report couldn't be finished this time. Your recording and sources are saved. Tap Try again.")
        assertEquals(error.message, clinicianError(error, "report"))
        assertTrue(canRetryRequest(error))
    }
}
