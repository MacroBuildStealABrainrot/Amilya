package com.woundcopilot.android

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Sample-only capture/navigation checks. No microphone or provider request is made. */
@RunWith(AndroidJUnit4::class)
class CaptureNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var state: AppState
    private var previousRecording = RecordingState()
    private var previousDark = true
    private var enteredSample = false
    private val files = mutableListOf<File>()
    private val vaultValues = linkedMapOf<String, String?>()

    @Before fun enterIsolatedSample() {
        compose.runOnIdle { state = ViewModelProvider(compose.activity)[AppState::class.java] }
        awaitTask()
        compose.runOnIdle {
            assumeFalse("Do not disturb a real recording", RecordingService.state.value.recording)
            check(state.user == null) { "Use an isolated installation without a signed-in account." }
            previousRecording = RecordingService.state.value
            previousDark = state.dark
            state.startDemo()
            enteredSample = true
        }
        compose.waitForIdle()
    }

    @After fun restoreOnlyTestState() {
        if (::state.isInitialized && enteredSample) {
            compose.runOnIdle { RecordingService.state.value = previousRecording }
            awaitTask()
            compose.runOnIdle {
                vaultValues.forEach { (key, value) -> if (value == null) state.vault.remove(key) else state.vault.write(key, value) }
                if (state.dark != previousDark) state.toggleTheme()
                if (state.demo) state.logout()
            }
            awaitTask()
        }
        files.forEach { it.delete() }
    }

    @Test fun backKeepsVoiceMessagePurposeUntilRecordingStops() {
        compose.runOnIdle { state.startQuick("consultant") }
        awaitTask()
        val file = File(compose.activity.cacheDir, "capture-navigation-" + UUID.randomUUID() + ".m4a")
        files += file
        file.writeBytes(byteArrayOf(0, 0, 0, 24) + "ftypM4A ".toByteArray() + ByteArray(24))
        compose.runOnIdle {
            val owner = state.user!!.str("id") + ":" + state.encounterId + ":message"
            val key = "capture-" + state.user!!.str("id") + "-" + state.encounterId + "-message"
            rememberVault("recording-owner-" + file.name, owner)
            rememberVault(key, null)
            rememberVault(key + "-mime", null)
            state.recordConsultantMessage()
            RecordingService.state.value = RecordingState(recording = true, elapsedSeconds = 5, filePath = file.absolutePath)
        }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.runOnIdle {
            assertTrue(state.voiceMessage)
            assertEquals("record", state.screen)
            assertTrue(RecordingService.state.value.recording)
        }
        compose.onNodeWithText("Stop the recording before leaving this screen.").assertExists()
        // Finalization is simulated; no service or microphone is started/stopped.
        compose.runOnIdle { RecordingService.state.value = RecordingState(elapsedSeconds = 5, filePath = file.absolutePath) }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.runOnIdle {
            assertFalse(state.voiceMessage)
            assertEquals("consultant", state.screen)
            assertTrue(state.case!!.items("evidence").isEmpty())
            assertTrue(state.consultantMessages.isEmpty())
        }
    }

    @Test fun restoredPhotoWaitsForExplicitAnalysisAcrossRecompositionAndRecreation() {
        compose.runOnIdle { state.startQuick("photo") }
        awaitTask()
        compose.runOnIdle { state.screen = "home" }
        compose.waitForIdle()
        val photo = File(compose.activity.cacheDir, "restored-photo-" + UUID.randomUUID() + ".png")
        files += photo
        val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.GRAY)
            photo.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
        var originalRevision = 0
        compose.runOnIdle {
            val key = "capture-" + state.user!!.str("id") + "-" + state.encounterId + "-photo"
            rememberVault(key, photo.absolutePath)
            rememberVault(key + "-mime", "image/png")
            originalRevision = state.revision
            state.screen = "photo"
        }
        compose.waitForIdle()
        compose.onNodeWithText("Analyze photo").assertExists()
        compose.runOnIdle { state.toggleTheme() }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(originalRevision, state.revision)
            assertTrue(state.case!!.items("evidence").isEmpty())
        }
        compose.activityRule.scenario.recreate()
        compose.runOnIdle { state = ViewModelProvider(compose.activity)[AppState::class.java] }
        awaitTask()
        compose.runOnIdle {
            assertTrue(state.demo)
            assertEquals("photo", state.screen)
            assertEquals(originalRevision, state.revision)
            assertTrue(state.case!!.items("evidence").isEmpty())
        }
        compose.onNodeWithText("Analyze photo").performScrollTo().performClick()
        awaitTask()
        compose.runOnIdle {
            assertNull(state.error)
            assertEquals(1, state.case!!.items("evidence").size)
            assertEquals("photo_assessment", state.screen)
        }
    }

    private fun rememberVault(key: String, value: String?) {
        if (!vaultValues.containsKey(key)) vaultValues[key] = state.vault.read(key)
        if (value == null) state.vault.remove(key) else state.vault.write(key, value)
    }

    private fun awaitTask() {
        compose.waitUntil(30_000) { !state.loading }
        compose.waitForIdle()
    }
}
