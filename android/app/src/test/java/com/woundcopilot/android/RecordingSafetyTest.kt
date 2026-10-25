package com.woundcopilot.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.8.5-test10: microphone taken by a call or another app, and notification permission before recording. */
class RecordingSafetyTest {
    @Test fun noInterruptionMeansNoMessage() {
        val mic = MicInterruption()
        for (s in 0..120) assertNull(mic.update(false, s * 1000L))
        assertNull(mic.summary(120_000L))
    }

    @Test fun aCallDuringRecordingIsShownWhileItLastsAndSummarisedAfter() {
        val mic = MicInterruption()
        mic.update(false, 60_000L)
        val during = mic.update(true, 61_000L)!!
        assertTrue(during, during.contains("since 1:01") && during.contains("continues by itself"))
        mic.update(true, 90_000L)
        val after = mic.update(false, 106_000L)!!
        assertTrue(after, after.contains("once") && after.contains("first at 1:01") && after.contains("45 s"))
        assertEquals(after, mic.summary(200_000L))
    }

    @Test fun severalInterruptionsAreCountedAndAnOngoingOneCountsAtStop() {
        val mic = MicInterruption()
        mic.update(true, 10_000L); mic.update(false, 20_000L)
        mic.update(true, 70_000L)
        val summary = mic.summary(200_000L)!!
        assertTrue(summary, summary.contains("2 times") && summary.contains("2 min 20 s"))
    }

    @Test fun notificationsAreAskedOnceOnAndroid13AndNeverBlockRecording() {
        assertEquals(listOf(RecordingPermissions.RECORD_AUDIO, RecordingPermissions.POST_NOTIFICATIONS),
            RecordingPermissions.toRequest(34, microphoneGranted = false, notificationsGranted = false, notificationsAskedBefore = false))
        assertEquals(listOf(RecordingPermissions.POST_NOTIFICATIONS), RecordingPermissions.toRequest(34, true, false, false))
        assertEquals(emptyList<String>(), RecordingPermissions.toRequest(34, true, false, true))   // asked before: never nag
        assertEquals(emptyList<String>(), RecordingPermissions.toRequest(32, true, false, false))  // Android 12 and older
        assertEquals(listOf(RecordingPermissions.RECORD_AUDIO), RecordingPermissions.toRequest(34, false, false, true))
        assertTrue(RecordingPermissions.mayRecord(true))
        assertTrue(!RecordingPermissions.mayRecord(false))
        assertTrue(RecordingPermissions.notificationHint(34, false)!!.contains("Use Stop on this screen"))
        assertNull(RecordingPermissions.notificationHint(34, true))
        assertNull(RecordingPermissions.notificationHint(30, false))
    }
}
