package com.woundcopilot.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OutputLimitFeedbackTest {
    @Test fun truncatedAiOutputIsDistinctAndCannotLoopRetries() {
        val error = ApiException(502, "Synthetic upstream body", category = "OUTPUT_LIMIT", retryAllowed = false)
        assertTrue("OUTPUT_LIMIT" in PHOTO_ERROR_CATEGORIES)
        assertFalse(canRetryRequest(error))
        for (purpose in listOf("report", "photo")) {
            val message = clinicianError(error, purpose)
            assertTrue(message, message.contains("cut short"))
            assertTrue(message, message.contains("OUTPUT_LIMIT"))
            assertFalse(message, message.contains("Synthetic upstream body"))
        }
    }
}
