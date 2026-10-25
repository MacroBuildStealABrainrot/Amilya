package com.woundcopilot.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Build guard (1.8.5-test10): the debug app is unchanged (original recorder) unless the build is asked otherwise. */
class DebugSafetyFlagsTest {
    @Test fun debugKeepsTheOriginalRecorder() {
        assertFalse(BuildConfig.CRASH_SAFE_RECORDING)
        assertFalse(BuildConfig.SANDBOX)
        assertEquals("com.woundcopilot.android", BuildConfig.APPLICATION_ID)
    }
}
