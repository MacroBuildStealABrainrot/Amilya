package com.woundcopilot.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Build guard (1.8.5-test10): the release app keeps the original recorder and the live service until the owner approves a change. */
class ReleaseSafetyFlagsTest {
    @Test fun releaseKeepsTheOriginalRecorderAndRetention() {
        assertFalse("crash-resistant recorder must stay OFF in release until approved", BuildConfig.CRASH_SAFE_RECORDING)
        assertFalse(BuildConfig.RETAIN_UNRESOLVED)
        assertFalse(BuildConfig.SANDBOX)
        assertFalse(BuildConfig.DEBUG)
        assertFalse(BuildConfig.ALLOW_SCREEN_CAPTURE)
    }

    @Test fun releaseApplicationIdAndServiceAreUnchanged() {
        assertEquals("com.woundcopilot.android.release", BuildConfig.APPLICATION_ID)
        assertEquals(PRODUCTION_API_ORIGIN, BuildConfig.API_BASE_URL)
    }
}
