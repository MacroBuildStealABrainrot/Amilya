package com.woundcopilot.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Clearing technical events removes only technical events, and a server without the address stops them. */
class DiagnosticsStorageTest {
    @Test fun onlyTechnicalEventItemsAreCleared() {
        for (name in listOf("diagnostic-outbox-0f1e", "diagnostic-job-u1-e1", "diagnostic-generation-u1-e1")) assertTrue(name, isDiagnosticStorageName(name))
        for (name in listOf("session", "session-refresh", "cached-account", "consent-record-e1", "recording-owner-a.m4a",
            "recording-context-u1_e1", "draft-u1-e1", "capture-u1-e1-audio", "diagnostics-unsupported-1a2b", "pin", "biometric-auto")) {
            assertFalse(name, isDiagnosticStorageName(name))
        }
    }
    @Test fun aServerWithoutTheAddressStopsSending() {
        assertTrue(diagnosticsNotAccepted(404))
        assertTrue(diagnosticsNotAccepted(405))
        for (status in listOf(200, 401, 403, 429, 500, 502, 503)) assertFalse("$status", diagnosticsNotAccepted(status))
    }
}
