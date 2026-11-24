package com.woundcopilot.android

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Exercise the Notes recovery UI and real device vault in a disposable synthetic sample visit. */
@RunWith(AndroidJUnit4::class)
class NotesReportRecoveryTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun interruptedTypedNoteIsReviewedAndSavedOnce() {
        assumeTrue(BuildConfig.NOTES_ONLY)
        lateinit var state: AppState
        compose.runOnIdle { state = ViewModelProvider(compose.activity)[AppState::class.java] }
        compose.waitUntil(30_000) { !state.loading }
        // Instrumentation must never use a signed-in workspace.
        compose.runOnIdle { assumeTrue(state.user == null && state.vault.read("session") == null) }
        val words = "Practice visit. Dressing changed. Pain 0/10."
        val finalWords = "Practice visit. Dressing replaced. Pain 0/10."
        var formKey = ""
        try {
            compose.runOnIdle { state.startDemo(); state.startQuick("document") }
            compose.waitUntil(10_000) { !state.loading && state.screen == "document_capture" }
            val encounter = compose.runOnIdle { state.encounterId }
            compose.runOnIdle {
                formKey = state.notesTypedFormKey()
                // This is the same snapshot EvidenceForm writes before its source submission.
                state.vault.write(formKey, obj("text" to words, "edit_id" to "practice-edit").toString())
                state.screen = "evidence:typed"
                state.runTask("Saving note") { throw java.net.ConnectException("Synthetic connection failure") }
            }
            compose.waitUntil(10_000) { !state.loading && state.error != null }
            compose.onNodeWithText("Review my notes").performScrollTo().performClick()
            compose.onNodeWithText("Review your words").assertIsDisplayed()
            compose.runOnIdle {
                assertTrue(state.editor.isBlank())
                assertTrue(state.case!!.items("documents").isEmpty())
            }
            compose.onNodeWithText("Use in draft").performClick()
            compose.waitUntil(10_000) { state.screen == "editor" && !state.loading }
            compose.runOnIdle {
                assertEquals(words, state.editor)
                assertTrue(state.case!!.items("documents").isEmpty())
                state.edit(finalWords)
                state.saveDocument()
            }
            compose.waitUntil(10_000) { !state.loading && state.savedId != null }
            compose.runOnIdle { state.openEncounter(encounter) }
            compose.waitUntil(10_000) { !state.loading }
            compose.runOnIdle {
                assertNull(state.error)
                assertEquals(finalWords, state.editor)
                assertEquals(finalWords, state.case!!.items("documents").single().str("text"))
                assertNull(state.vault.read(formKey))
            }
        } finally {
            compose.runOnIdle {
                if (formKey.isNotEmpty()) state.vault.remove(formKey)
                if (state.demo) state.logout()
            }
        }
    }

    @Test fun acceptedRecordingContinuesToAReportWithoutAddingAnotherTranscript() {
        assumeTrue(BuildConfig.NOTES_ONLY)
        lateinit var state: AppState
        compose.runOnIdle { state = ViewModelProvider(compose.activity)[AppState::class.java] }
        compose.waitUntil(30_000) { !state.loading }
        compose.runOnIdle { assumeTrue(state.user == null && state.vault.read("session") == null) }
        val directory = File(compose.activity.filesDir, "recordings").apply { mkdirs() }
        val file = File(directory, "practice-${UUID.randomUUID()}.m4a")
        val bytes = "Synthetic recording identity for the completed transcript.".toByteArray()
        file.writeBytes(bytes)
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        val words = "Practice visit. Dressing changed."
        try {
            compose.runOnIdle { state.startDemo(); state.startQuick("document") }
            compose.waitUntil(10_000) { !state.loading && state.screen == "document_capture" }
            compose.runOnIdle {
                state.case!!.getJSONArray("evidence").put(obj(
                    "id" to "accepted-practice-recording", "kind" to "audio", "created_by" to "sample",
                    "encounter_id" to state.encounterId, "text" to words, "metadata" to obj("media_hash" to hash)
                ))
                state.upload(file, "audio/mp4", "audio")
            }
            compose.waitUntil(10_000) { !state.loading }
            compose.runOnIdle {
                assertNull(state.error)
                assertEquals("editor", state.screen)
                assertEquals(words, state.editor)
                assertEquals("accepted-practice-recording", state.case!!.items("evidence").single().str("id"))
                assertEquals("REPORT_READY", state.recordingRecovery.read(file)?.str("phase"))
                assertTrue(state.case!!.items("documents").isEmpty())
            }
        } finally {
            compose.runOnIdle {
                state.recordingRecovery.read(file)?.let { state.recordingRecovery.discard(it.str("id"), "sample") }
                file.delete()
                if (state.demo) state.logout()
            }
        }
    }
}
