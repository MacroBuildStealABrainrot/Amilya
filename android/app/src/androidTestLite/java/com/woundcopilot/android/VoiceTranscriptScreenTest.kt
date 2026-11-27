package com.woundcopilot.android

import android.os.Build
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VoiceTranscriptScreenTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun transcriptStaysAvailableWhenReportOrCaptureRoutesAreRequested() {
        assertTrue(BuildConfig.LITE_VOICE && Build.HARDWARE in setOf("ranchu", "goldfish"))
        val state = compose.runOnIdle { ViewModelProvider(compose.activity)[AppState::class.java] }
        compose.waitUntil(30_000) { !state.loading }
        compose.runOnIdle {
            assertNull(state.user)
            assertNull(state.vault.read("session"))
            state.startDemo()
        }
        try {
            val words = "Hello Maya. Hello Alex."
            compose.onNode(hasSetTextAction()).performScrollTo().performTextReplacement(words)
            for (route in listOf("editor", "record")) {
                compose.runOnIdle { state.screen = route }
                compose.runOnIdle { assertEquals("home", state.screen) }
                compose.onNodeWithText(words).performScrollTo().assertIsDisplayed()
                compose.onNodeWithText("Copy text").performScrollTo().assertIsDisplayed()
                compose.onNodeWithText("Share text").performScrollTo().assertIsDisplayed()
                compose.onNodeWithText("Your report").assertDoesNotExist()
                compose.onNodeWithText("Check before saving or sending:").assertDoesNotExist()
            }
        } finally {
            compose.runOnIdle {
                state.vault.remove("lite-draft-sample")
                state.vault.remove("lite-text-sample")
                state.logout()
            }
        }
    }
}
