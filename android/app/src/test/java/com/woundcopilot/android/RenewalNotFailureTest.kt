package com.woundcopilot.android

import org.junit.Assert.assertTrue
import org.junit.Test

class RenewalNotFailureTest {
    // AMILYA-ANDROID-3: an automatic sign-in renewal was reported as "generation_request failed".
    @Test fun automaticResendsAreNotReportedAsFailures() {
        assertTrue("SESSION_RENEWED" in DeveloperDiagnostics.renewals)
        assertTrue("CSRF_REFRESHED" in DeveloperDiagnostics.renewals)
    }
}
