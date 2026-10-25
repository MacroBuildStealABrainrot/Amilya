package com.woundcopilot.android

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Item 3: a recording start that cannot finish must never leave a half-started foreground service behind. */
@RunWith(AndroidJUnit4::class)
class RecordingServiceStartTest {
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = inst.targetContext
    private val original = RecordingService.microphoneGranted

    @After fun restore() {
        RecordingService.microphoneGranted = original
        val path = RecordingService.state.value.filePath
        RecordingService.stop(context)
        waitUntil(5_000) { !RecordingService.state.value.recording }
        // Remove only the synthetic audio this test created.
        if (path != null) runCatching { RecordingService.clearPending(context, path, deleteFile = true) }
        RecordingService.state.value = RecordingState()
    }

    private fun waitUntil(ms: Long, condition: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (condition()) return true; Thread.sleep(50) }
        return condition()
    }

    private fun serviceRunning() = context.getSystemService(ActivityManager::class.java)
        .getRunningServices(200).any { it.service.className == RecordingService::class.java.name }

    @Test fun withoutMicrophonePermissionNoServiceStartsAndTheUserSeesWhy() {
        RecordingService.microphoneGranted = { false }
        RecordingService.state.value = RecordingState()
        RecordingService.start(context, "owner:encounter")
        assertEquals("Microphone permission is required to record.", RecordingService.state.value.error)
        assertFalse(RecordingService.state.value.recording)
        Thread.sleep(1_500)
        assertFalse("no foreground service may be left starting", serviceRunning())
    }

    @Test fun aRefusedStartDoesNotBlockTheNextAttempt() {
        inst.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        RecordingService.microphoneGranted = { false }
        RecordingService.start(context)
        RecordingService.microphoneGranted = original
        RecordingService.start(context)
        assertTrue("recording started on the second attempt", waitUntil(8_000) { RecordingService.state.value.recording })
        assertTrue("service is running in the foreground", serviceRunning())
    }
}
