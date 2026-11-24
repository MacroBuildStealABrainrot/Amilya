package com.woundcopilot.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotesWritingRefusalTest {
    @Test fun aDefiniteAccountOrServiceRefusalOpensTheOffer() {
        assertNotNull(notesWritingRefusalReason(ApiException(402, "Insufficient credits")))
        assertEquals("AI writing needs an active plan for this account.", notesWritingRefusalReason(
            ApiException(402, "An active paid plan is required for AI processing. Manual charting and signing remain available.")))
        assertEquals("This account has too few credits for AI writing.", notesWritingRefusalReason(
            ApiException(402, "More credits are needed for this AI action. Your existing work is saved.")))
        for (category in listOf("AI_PAUSED", "AI_NOT_CONFIGURED", "PROVIDER_QUOTA")) {
            assertNotNull(notesWritingRefusalReason(ApiException(503, "", category = category, retryAllowed = false)))
        }
    }

    @Test fun delaysAmbiguousResultsAndPolicyOrSessionFailuresKeepTheirOwnRecovery() {
        for (error in listOf(
            ApiException(401, ""), ApiException(403, ""), ApiException(428, ""), ApiException(403, "CSRF"),
            ApiException(429, ""), ApiException(502, ""), ApiException(503, ""), ApiException(504, ""),
            ApiException(409, "", ambiguous = true), ApiException(402, "", ambiguous = true),
            ApiException(503, "", category = "AI_PAUSED", retryAllowed = true),
            ApiException(503, "", category = "AI_PAUSED", retryAllowed = false, retryAfterSeconds = 12),
            ApiException(503, "", category = "AI_PAUSED"), ApiException(503, "", category = "PROVIDER_AUTH", retryAllowed = false),
            ApiException(503, "", category = "PROVIDER_APPROVAL_REQUIRED", retryAllowed = false),
            ApiException(503, "", category = "PRIVACY_CONFIGURATION_REQUIRED", retryAllowed = false),
            ApiException(503, "", category = "SECURE_STORAGE_REQUIRED", retryAllowed = false),
        )) assertNull(notesWritingRefusalReason(error))
    }

    @Test fun theOfferBelongsOnlyToTheSameAccountVisitAndRevision() {
        val offer = NotesWritingRefusal("practice-owner", "practice-visit", 2, "AI writing is paused.")
        assertTrue(offer.matches("practice-owner", "practice-visit", 2))
        assertFalse(offer.matches("other", "practice-visit", 2))
        assertFalse(offer.matches("practice-owner", "other", 2))
        assertFalse(offer.matches("practice-owner", "practice-visit", 3))
    }

    @Test fun completedQualityRejectionsNeedTheExactServerMessageAndEndingSignal() {
        val reasons = listOf(
            "The report did not pass source-faithfulness review. Your original source and editor text remain available.",
            "The report check could not be verified. Your original source and editor text remain available.",
        )
        for (reason in reasons) {
            val refused = ApiException(502, reason, reportState = REPORT_ENDED_NO_CHARGE)
            assertNotNull(notesWritingRefusalReason(refused))
            assertFalse(canRetryReportImmediately(refused, notesOnly = true))
            assertTrue(canRetryRequest(refused))
            assertTrue(canRetryReportImmediately(refused, notesOnly = false))
            assertNull(notesWritingRefusalReason(ApiException(502, reason)))
            assertNull(notesWritingRefusalReason(ApiException(502, reason, reportState = REPORT_ENDED_NO_CHARGE, ambiguous = true)))
            assertNull(notesWritingRefusalReason(ApiException(502, reason, reportState = REPORT_ENDED_NO_CHARGE, category = "PRIVACY_CONFIGURATION_REQUIRED")))
        }
        val other = ApiException(502, "Different failure", reportState = REPORT_ENDED_NO_CHARGE)
        assertNull(notesWritingRefusalReason(other))
        assertFalse(canRetryReportImmediately(other, notesOnly = true))
        assertTrue(canRetryReportImmediately(ApiException(502, "Temporary upstream failure"), notesOnly = true))
    }
}
