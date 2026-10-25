package com.woundcopilot.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** A rejected security token is recoverable (retry and clear message); other 403s and other errors are unchanged. */
class CsrfRecoveryTest {
    private val csrf = "Invalid request verification. Refresh and try again."

    @Test fun recognisesOnlyTheTokenRejection() {
        assertTrue(isCsrfRejection(403, csrf))
        assertTrue(isCsrfRejection(403, "INVALID REQUEST VERIFICATION"))
        assertFalse(isCsrfRejection(403, "Request origin is not permitted."))
        assertFalse(isCsrfRejection(403, "Consent is required before processing the audio again"))
        assertFalse(isCsrfRejection(401, csrf))
        assertFalse(isCsrfRejection(200, csrf))
    }

    @Test fun aTokenRejectionOffersTryAgain() =
        assertTrue(canRetryRequest(ApiException(403, csrf)))

    @Test fun otherForbiddenAndPaymentErrorsStillDoNot() {
        assertFalse(canRetryRequest(ApiException(403, "Request origin is not permitted.")))
        assertFalse(canRetryRequest(ApiException(403, "Consent is required before processing the audio again")))
        assertFalse(canRetryRequest(ApiException(402, "insufficient credits")))
    }

    @Test fun existingRetryRulesAreUnchanged() {
        assertTrue(canRetryRequest(IOException("offline")))
        assertTrue(canRetryRequest(ApiException(429, "limit")))
        assertTrue(canRetryRequest(ApiException(503, "busy")))
        assertFalse(canRetryRequest(javax.net.ssl.SSLException("tls")))
        assertFalse(canRetryRequest(ApiException(503, "quota", category = "PROVIDER_QUOTA", retryAllowed = false)))
    }

    @Test fun theMessageIsClearAndSaysTheRecordingIsSafe() {
        val text = clinicianError(ApiException(403, csrf), "audio")
        assertTrue(text, text.contains("Try Again"))
        assertTrue(text, text.contains("recording is not affected"))
        assertTrue(text, text.contains("SESSION_TOKEN_REFRESH_FAILED"))
    }

    @Test fun signInRoutesAreNeverRecovered() {
        for (path in listOf("/auth/login", "/auth/signup", "/auth/refresh", "/auth/session")) assertTrue(path, path in AUTH_ENTRY_PATHS)
        assertFalse("/auth/logout" in AUTH_ENTRY_PATHS)
    }
}
