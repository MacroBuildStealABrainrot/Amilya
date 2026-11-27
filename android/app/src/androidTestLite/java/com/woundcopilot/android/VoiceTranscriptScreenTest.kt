package com.woundcopilot.android

import android.Manifest
import android.os.Build
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VoiceTranscriptScreenTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private lateinit var state: AppState

    @Before fun enterOfflineSample() {
        assertTrue(BuildConfig.LITE_VOICE && Build.HARDWARE in setOf("ranchu", "goldfish"))
        state = compose.runOnIdle { ViewModelProvider(compose.activity)[AppState::class.java] }
        compose.waitUntil(30_000) { !state.loading }
        compose.runOnIdle {
            assertNull(state.user)
            assertNull(state.vault.read("session"))
            state.startDemo()
        }
    }

    @After fun leaveSample() {
        if (!::state.isInitialized || !state.demo) return
        compose.runOnIdle {
            state.vault.remove("lite-draft-sample")
            state.vault.remove("lite-text-sample")
            state.logout()
        }
    }

    @Test fun tappingStopOpensTheEditableTranscript() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val pkg = inst.targetContext.packageName
        inst.uiAutomation.grantRuntimePermission(pkg, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) inst.uiAutomation.grantRuntimePermission(pkg, Manifest.permission.POST_NOTIFICATIONS)
        val initialDocuments = compose.runOnIdle { state.case?.items("documents")?.size }
        val words = "Sample only. No transcription was performed."
        try {
            compose.onNodeWithText("Start recording").performScrollTo().performClick()
            compose.waitUntil(15_000) { RecordingService.state.value.let { it.recording && it.elapsedSeconds >= 2 } }
            compose.onNodeWithText("Stop").performScrollTo().performClick()
            compose.waitUntil(15_000) { compose.onAllNodesWithText(words).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(words).performScrollTo().assertIsDisplayed()
            compose.onNode(hasSetTextAction()).performTextReplacement("Edited sample.")
            compose.onNodeWithText("Edited sample.").assertIsDisplayed()
            assertVoiceControls()
            compose.runOnIdle {
                assertEquals("home", state.screen)
                assertEquals(initialDocuments, state.case?.items("documents")?.size)
            }
        } finally {
            if (RecordingService.state.value.recording) {
                RecordingService.stop(inst.targetContext)
                compose.waitUntil(10_000) { !RecordingService.state.value.recording }
            }
        }
    }

    @Test fun transcriptStaysAvailableWhenReportOrCaptureRoutesAreRequested() {
        val words = "Hello Maya. Hello Alex."
        compose.onNode(hasSetTextAction()).performScrollTo().performTextReplacement(words)
        for (route in listOf("editor", "record")) {
            compose.runOnIdle { state.screen = route }
            compose.runOnIdle { assertEquals("home", state.screen) }
            compose.onNodeWithText(words).performScrollTo().assertIsDisplayed()
            assertVoiceControls()
        }
    }

    private fun assertVoiceControls() {
        compose.onNodeWithText("Copy text").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Share text").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Your report").assertDoesNotExist()
        compose.onNodeWithText("Check before saving or sending:").assertDoesNotExist()
    }
}
