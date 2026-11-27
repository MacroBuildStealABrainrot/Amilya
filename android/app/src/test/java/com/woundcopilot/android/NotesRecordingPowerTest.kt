package com.woundcopilot.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotesRecordingPowerTest {
    @Test fun repeatedStartDoesNotDuplicateTheLockAndResumeUsesOnlyRemainingActiveTime() {
        val timeouts = mutableListOf<Long>()
        var releases = 0
        val power = NotesRecordingPower { timeout ->
            timeouts.add(timeout)
            val release = { releases += 1 }
            release
        }

        assertTrue(power.hold(0))
        assertTrue(power.hold(0))
        assertEquals(listOf(3_630_000L), timeouts)
        power.release()
        power.release()
        assertEquals(1, releases)
        assertTrue(power.hold(3_000_000))
        assertEquals(listOf(3_630_000L, 630_000L), timeouts)
        power.release()
        assertEquals(2, releases)
    }

    @Test fun failedAcquisitionLeavesNoPhantomLockAndAResumeCanTryAgain() {
        var attempts = 0
        var released = false
        val power = NotesRecordingPower {
            attempts += 1
            if (attempts == 1) throw SecurityException("permission unavailable")
            val release = { released = true }
            release
        }

        assertFalse(power.hold(0))
        power.release()
        assertFalse(released)
        assertTrue(power.hold(1_000))
        power.release()
        assertTrue(released)
    }

    @Test fun aPlatformReleaseFailureDoesNotBlockStoppingOrTheNextRecording() {
        var acquisitions = 0
        val power = NotesRecordingPower {
            acquisitions += 1
            val release = { throw IllegalStateException("platform failure") }
            release
        }

        assertTrue(power.hold(0))
        power.release()
        power.release()
        assertTrue(power.hold(0))
        assertEquals(2, acquisitions)
        power.release()
    }

    @Test fun aLateStopCallbackCanOnlyHoldPowerForTheFinalizationGrace() {
        var timeout = 0L
        val power = NotesRecordingPower { millis -> timeout = millis; {} }
        assertTrue(power.hold(3_620_000))
        assertEquals(30_000L, timeout)
    }
}
