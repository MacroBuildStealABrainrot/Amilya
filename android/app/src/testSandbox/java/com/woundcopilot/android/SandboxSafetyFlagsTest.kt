package com.woundcopilot.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Build guard (1.8.5-test10): the sandbox app runs the crash-resistant recorder and can reach only the PC test server. */
class SandboxSafetyFlagsTest {
    @Test fun sandboxUsesTheCrashResistantRecorderAndTheLocalServerOnly() {
        assertTrue(BuildConfig.SANDBOX)
        assertTrue(BuildConfig.CRASH_SAFE_RECORDING)
        assertEquals("com.woundcopilot.android.sandbox", BuildConfig.APPLICATION_ID)
        assertEquals(LOCAL_TEST_API_ORIGIN, BuildConfig.API_BASE_URL)
        assertFalse(isAllowedApiOrigin(PRODUCTION_API_ORIGIN, debug = true, sandbox = true))
    }
}
