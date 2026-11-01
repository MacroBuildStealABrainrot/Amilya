package com.woundcopilot.android

import org.junit.Assert.*
import org.junit.Test

class FeedbackFadePolicyTest {
    @Test fun harmlessInformationFadesAfterThreeSeconds() {
        listOf(
            "No internet connection. Your work is saved on this phone. Tap Try again when you're back online.",
            "Request: No internet connection. Your work is saved on this phone. Tap Try again when you're back online.",
            "Exact document saved to your workspace.", "Profile saved.", "Saved in the synthetic preview.",
        ).forEach { text ->
            val timer = FeedbackFadeTimer(text, 1, 100)
            assertTrue(text, timer.isVisibleAt(3099))
            assertFalse(text, timer.isVisibleAt(3100))
        }
    }

    @Test fun allReportAndActionWarningsStay() {
        listOf(
            "New report without the conversation",
            "This report does not include your Ask Consultant conversation. You chose to leave it out because it was too long.",
            "Dose unclear", "Confirm the right heel", "Measurement unclear", "Medication requires review",
            "A photo could not be read", "AI generation or checking could not complete",
            "Your work could not be saved.", "Request: Your session is no longer authorized. Sign in again.\nHTTP 401 · Request 123",
            "Exact document saved to your workspace.\nDose unclear",
            "No internet connection. Your work is saved on this phone.\nThe attachment could not be read.",
            "Report generation: No internet connection. Your work is saved on this phone. Tap Try again when you're back online.",
            "Unknown server warning", "Support request received. Reference: 123",
        ).forEach { text -> assertTrue(text, FeedbackFadeTimer(text, 1, 0).isVisibleAt(Long.MAX_VALUE)) }
    }

    @Test fun retryButtonAlwaysKeepsEvenAnAllowlistedMessageVisible() {
        val timer = FeedbackFadeTimer("Exact document saved to your workspace.", 1, 0, hasAction = true)
        assertTrue(timer.isVisibleAt(Long.MAX_VALUE))
    }

    @Test fun newMessageGetsAFullNewTimerIncludingRepeatedIdenticalText() {
        val first = FeedbackMessage().replace("Profile saved.")
        val oldTimer = FeedbackFadeTimer(first.text!!, first.sequence, 0)
        val next = first.replace("Profile saved.")
        assertNotEquals(first.sequence, next.sequence)
        val newTimer = FeedbackFadeTimer(next.text!!, next.sequence, 2900)
        assertFalse(oldTimer.isVisibleAt(3000))
        assertTrue(newTimer.isVisibleAt(3000))
        assertTrue(newTimer.isVisibleAt(5899))
        assertFalse(newTimer.isVisibleAt(5900))
    }

    @Test fun leavingAndReenteringThePresentationDoesNotConsumeAStayMessage() {
        val stored = FeedbackMessage().replace("AI generation or checking could not complete")
        val beforeLeaving = FeedbackFadeTimer(stored.text!!, stored.sequence, 0)
        assertTrue(beforeLeaving.isVisibleAt(6000))
        // Recreate the presentation with the same ViewModel-owned value, as after disposal.
        val afterReturning = FeedbackFadeTimer(stored.text!!, stored.sequence, 90000)
        assertTrue(afterReturning.isVisibleAt(Long.MAX_VALUE))
        assertEquals("AI generation or checking could not complete", stored.text)
    }

    @Test fun fadingDoesNotEraseTheStoredMessageOrSupportDetails() {
        val stored = FeedbackMessage().replace("Profile saved.")
        assertFalse(FeedbackFadeTimer(stored.text!!, stored.sequence, 0).isVisibleAt(3000))
        assertEquals("Profile saved.", stored.text)
        val reference = "Request: Your session is no longer authorized. Sign in again.\nSupport reference: WC-123456"
        assertTrue(FeedbackFadeTimer(reference, 1, 0).isVisibleAt(Long.MAX_VALUE))
    }

    @Test fun replacingInformationWithASafetyWarningCancelsItsExpiry() {
        val information = FeedbackMessage().replace("Profile saved.")
        val warning = information.replace("Dose unclear")
        val timer = FeedbackFadeTimer(warning.text!!, warning.sequence, 2900)
        assertTrue(timer.isVisibleAt(3000))
        assertTrue(timer.isVisibleAt(Long.MAX_VALUE))
    }

    @Test fun additionalWarningsOrSupportReferencesNeverMatchTheAllowlist() {
        listOf("Profile saved.", "Exact document saved to your workspace.").forEach { text ->
            listOf("\nDose unclear", "\nSupport reference: WC-123456", " Review before sending.").forEach { extra ->
                assertFalse(feedbackMayFade(text + extra))
            }
        }
    }
}
