package com.woundcopilot.android

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Integrated navigation/state tests use only the local sample and synthetic fixtures. */
@RunWith(AndroidJUnit4::class)
class QuickWorkflowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var state: AppState
    private val photos = mutableListOf<File>()
    private var previousFormat: String? = null
    private var enteredSample = false

    @Before fun enterSample() {
        compose.runOnIdle { state = ViewModelProvider(compose.activity)[AppState::class.java] }
        awaitTask()
        compose.runOnIdle {
            check(state.user == null) { "Use an isolated test installation without a signed-in account." }
            previousFormat = state.vault.read("preferred-format-sample")
            state.startDemo()
            enteredSample = true
        }
        compose.waitUntil(10_000) { state.demo && state.screen == "home" }
        compose.waitForIdle()
    }

    @After fun leaveSample() {
        if (::state.isInitialized && enteredSample) {
            awaitTask()
            compose.runOnIdle {
                if (previousFormat == null) state.vault.remove("preferred-format-sample")
                else state.vault.write("preferred-format-sample", previousFormat!!)
                if (state.demo) state.logout()
            }
            awaitTask()
        }
        photos.forEach { it.delete() }
    }

    @Test fun documentTypesUseOriginalSourcesAndKeepReviewTextOutOfReports() {
        start("document")
        compose.runOnIdle { assertEquals("nursing",state.editorTemplate);state.addEvidence(obj("kind" to "typed","text" to "Client reports pain 6/10.")) }
        awaitTask()
        compose.runOnIdle { state.edit("This edit is not an original visit source.");state.reformatQuick("soap") }
        awaitTask()
        compose.runOnIdle {
            assertEquals("Subjective\nClient reports pain 6/10.",state.editor)
            assertEquals("soap",state.editorTemplate)
            assertFalse(state.editor.contains("Draft"))
            assertEquals(6,quickFormats.size)
            assertEquals("Physician Update",documentTypeTitle("referral"))
            assertEquals(1,state.case!!.items("evidence").size)
        }
        compose.onNodeWithText("Draft — Clinician Review Required").assertExists()
        compose.onNodeWithText("Improve Report").performScrollTo().assertExists()
        compose.onNodeWithText("Improve Assessment").assertExists()
    }

    @Test fun assessmentQuestionsNeverChangeTheReportOrSources() {
        start("document")
        compose.runOnIdle { state.addEvidence(obj("kind" to "typed","text" to "Client denies pain.")) }
        awaitTask()
        var original="";var sources="";var revision=0
        compose.runOnIdle { original=state.editor;sources=state.case!!.getJSONArray("evidence").toString();revision=state.revision;state.improveAssessment() }
        awaitTask()
        compose.onNodeWithText("Your report and sources have not changed.").assertExists()
        compose.runOnIdle {
            assertEquals(original,state.editor);assertEquals(sources,state.case!!.getJSONArray("evidence").toString());assertEquals(revision,state.revision)
            state.assessmentReview=null
        }
    }

    @Test fun unsupportedReferralPreservesExistingEditorAndShowsUiMessage() {
        start("document")
        compose.runOnIdle { state.addEvidence(obj("kind" to "typed","text" to "Client reports pain 6/10.")) }
        awaitTask()
        var original=""
        compose.runOnIdle { original=state.editor;state.reformatQuick("referral_letter") }
        awaitTask()
        compose.runOnIdle {
            assertEquals(original,state.editor)
            assertEquals("nursing",state.editorTemplate)
            assertEquals("Referral reason not found in visit information.",state.notice)
            assertFalse(state.editor.contains("Referral reason not found"))
        }
    }

    @Test fun documentFromHomeOpensWithoutPatientIntake() {
        click("Document")
        awaitTask()
        compose.runOnIdle {
            assertEquals("document", state.quickMode)
            assertEquals("document_capture", state.screen)
            assertTrue(state.case!!.items("evidence").isEmpty())
            assertTrue(state.case!!.items("documents").isEmpty())
            assertEquals("Untitled visit", state.case!!.getJSONObject("patient").str("display_name"))
        }
        compose.onNodeWithText("Record").assertExists()
        compose.onNodeWithText("Write instead").assertExists()
        compose.onNodeWithText("Patient name").assertDoesNotExist()
    }

    @Test fun writtenNoteCreatesReportWithoutPhotoOrConfirmedFindings() {
        click("Document")
        awaitTask()
        click("Write instead")
        val note = "Synthetic visit: clinician documented a clean dressing and planned reassessment."
        compose.onNode(hasSetTextAction() and hasText("Source notes")).performScrollTo().performTextReplacement(note)
        click("Create report")
        awaitTask()
        compose.runOnIdle {
            assertNull(state.error)
            assertEquals("editor", state.screen)
            assertNull("Saving the source must not label the unsaved report as saved", state.notice)
            assertTrue(state.editor.contains(note))
            assertTrue(state.case!!.items("facts").isEmpty())
            assertEquals(listOf("typed"), state.case!!.items("evidence").map { it.str("kind") })
        }
        compose.onNodeWithTag("quick-report-editor").assertExists()
        compose.onNodeWithText("Your report").assertExists()
    }

    @Test fun laterSourceDoesNotReplaceEditedReport() {
        start("document")
        compose.runOnIdle { state.addEvidence(obj("kind" to "typed", "text" to "Synthetic first source.")) }
        awaitTask()
        val edited = "Clinician corrected this synthetic report. Preserve this exact wording."
        compose.runOnIdle { state.edit(edited) }
        compose.runOnIdle { state.addEvidence(obj("kind" to "typed", "text" to "Synthetic additional source.")) }
        awaitTask()
        compose.runOnIdle {
            assertNull(state.error)
            assertEquals(edited, state.editor)
            assertEquals(2, state.case!!.items("evidence").size)
            assertTrue("Added evidence must not silently mark the old report current", state.editorCaseRevision < state.revision)
        }
    }

    @Test fun photoOnlyCanFinishWithoutCreatingReport() {
        start("photo")
        val photo = File(compose.activity.cacheDir, "quick-workflow-${UUID.randomUUID()}.png")
        photos += photo
        val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.GRAY)
            photo.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
        compose.runOnIdle { state.upload(photo, "image/png", "photo") }
        awaitTask()
        compose.runOnIdle {
            assertNull(state.error)
            assertEquals("photo_assessment", state.screen)
            assertEquals(listOf("photo"), state.case!!.items("evidence").map { it.str("kind") })
            assertTrue(state.case!!.items("documents").isEmpty())
        }
        click("Done")
        awaitTask()
        compose.runOnIdle {
            assertEquals("home", state.screen)
            assertNull(state.quickMode)
            assertTrue(state.editor.isEmpty())
            assertNull("Finished unsaved photo work must be removed",state.case)
        }
    }

    @Test fun consultantStartsWithQuestionAndNoEvidence() {
        click("Wound Consultant")
        awaitTask()
        val question = "What information would help explain delayed healing?"
        compose.onNodeWithTag("consultant-composer").performTextReplacement(question)
        compose.onNodeWithContentDescription("Send message").performClick()
        awaitTask()
        compose.runOnIdle {
            assertNull(state.error)
            assertEquals("consultant", state.screen)
            assertTrue(state.case!!.items("evidence").isEmpty())
            assertTrue(state.case!!.items("documents").isEmpty())
            assertEquals(listOf("user", "assistant"), state.consultantMessages.map { it.str("role") })
            assertEquals(question, state.consultantMessages.first().str("content"))
            assertTrue(state.consultantMessages.last().str("content").contains("No model was called"))
            assertEquals("", state.consultantDraft)
        }
        compose.onNodeWithContentDescription("Send message").assertIsNotEnabled()
    }

    @Test fun switchingQuickVisitClearsVoiceCorrectionMode() {
        start("document")
        compose.runOnIdle {
            state.edit("Synthetic report to correct.")
            state.fixByVoice()
            assertTrue(state.voiceCorrection)
        }
        start("consultant")
        compose.runOnIdle {
            assertFalse(state.voiceCorrection)
            assertEquals("consultant", state.screen)
            assertTrue(state.editor.isEmpty())
        }
    }

    @Test fun customPreferenceWithoutCustomInstructionsFallsBackToNursing() {
        compose.runOnIdle { state.vault.write("preferred-format-sample", "custom") }
        start("document")
        compose.runOnIdle {
            assertEquals("nursing", state.editorTemplate)
            assertTrue(state.customInstructions.isEmpty())
        }
    }

    @Test fun voiceMessageIsReviewableAndDoesNotBecomeEvidenceOrSendItself() {
        start("consultant")
        val audio=File(compose.activity.cacheDir,"message-${UUID.randomUUID()}.m4a").apply{writeText("Synthetic fixture: demo performs no decoding or transcription.")}
        photos+=audio
        var originalRevision=0
        compose.runOnIdle{
            originalRevision=state.revision
            state.updateConsultantDraft("Existing question.")
            state.recordConsultantMessage()
            assertTrue(state.voiceMessage)
            assertFalse(state.voiceCorrection)
            state.upload(audio,"audio/mp4","audio")
        }
        awaitTask()
        compose.runOnIdle{
            assertNull(state.error)
            assertEquals("consultant",state.screen)
            assertFalse(state.voiceMessage)
            assertEquals(originalRevision,state.revision)
            assertTrue(state.case!!.items("evidence").isEmpty())
            assertTrue(state.case!!.items("facts").isEmpty())
            assertTrue(state.consultantMessages.isEmpty())
            assertEquals("Existing question.\n\nWhat else would you want to know?",state.consultantDraft)
        }
        compose.onNodeWithTag("consultant-composer").assertTextContains("Existing question.\n\nWhat else would you want to know?")
    }

    @Test fun overlengthVoiceDraftPreservesTrailingNegationAndRequiresEditing() {
        start("consultant")
        val longText="Synthetic history. ".repeat(140)+"There is NO new pain."
        compose.runOnIdle{
            state.updateConsultantDraft(longText)
            state.doneQuick()
            state.openEncounter(state.encounterId)
        }
        awaitTask()
        compose.runOnIdle{assertEquals(longText,state.consultantDraft)}
        compose.onNodeWithTag("consultant-composer").assertTextContains(longText)
        compose.onNodeWithContentDescription("Send message").assertIsNotEnabled()
        compose.onNodeWithTag("consultant-composer").performTextReplacement("There is NO new pain.")
        compose.onNodeWithContentDescription("Send message").assertIsEnabled()
        compose.runOnIdle{assertEquals("There is NO new pain.",state.consultantDraft)}
    }

    @Test fun retryIsExplicitRespectsDelayAndDoesNotFollowAChangedVisit() {
        start("consultant")
        var retries=0
        compose.runOnIdle{state.runTask(purpose="photo",retry={retries++}){throw ApiException(429,"provider rate limit",retryAfterSeconds=60)}}
        awaitTask()
        compose.runOnIdle{
            assertTrue(state.retryAvailable)
            assertEquals("Photo analysis is temporarily unavailable. Try again shortly.",state.error)
            state.retryFailed()
            assertEquals(0,retries)
        }
        start("document")
        compose.runOnIdle{
            assertFalse(state.retryAvailable)
            state.retryFailed()
            assertEquals(0,retries)
        }
    }

    @Test fun neutralFeedbackSeparatesAccountAndTransientFailure() {
        assertTrue(canRetryRequest(ApiException(429,"provider limit")))
        assertFalse(canRetryRequest(ApiException(402,"insufficient credits")))
        assertTrue(clinicianError(ApiException(402,"provider credits"),"photo").contains("app credits"))
        assertTrue(clinicianError(ApiException(502,"faithfulness review failed"),"report").contains("faithfulness review failed"))
        assertTrue(clinicianError(IllegalArgumentException("Enter a question within 2,000 characters.")).contains("Enter a question within 2,000 characters."))
    }

    @Test fun consultantHistoryRespectsServerBoundsAndKeepsRecentTurns() {
        val history = (0 until 50).map { index ->
            obj("id" to "turn-$index", "role" to if (index % 2 == 0) "user" else "assistant", "content" to "turn-$index:" + "x".repeat(9500))
        }
        val bounded = boundedConsultHistory(history)
        assertTrue(bounded.isNotEmpty())
        assertTrue(bounded.size <= 20)
        assertTrue(bounded.sumOf { it.str("content").length } <= 40000)
        assertTrue(bounded.all { it.str("content").length in 1..10000 })
        assertTrue(bounded.all { it.str("role") in listOf("user", "assistant") })
        assertTrue(bounded.last().str("content").startsWith("turn-49:"))
        val indexes = bounded.map { it.str("content").substringAfter("turn-").substringBefore(':').toInt() }
        assertEquals(indexes.sorted(), indexes)
        assertEquals(history.last().str("content"), bounded.last().str("content"))
        val shortHistory = (0 until 50).map { obj("role" to if (it % 2 == 0) "user" else "assistant", "content" to "Short turn $it") }
        val shortBounded = boundedConsultHistory(shortHistory)
        assertEquals(20, shortBounded.size)
        assertEquals("Short turn 30", shortBounded.first().str("content"))
        assertEquals("Short turn 49", shortBounded.last().str("content"))
    }

    private fun start(mode: String) {
        compose.runOnIdle { state.startQuick(mode) }
        awaitTask()
        compose.runOnIdle { assertNull(state.error) }
    }

    private fun click(text: String) {
        // A previous sample consultation can share its title with the primary home action.
        compose.onAllNodesWithText(text).onFirst().performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun awaitTask() {
        compose.waitUntil(30_000) { !state.loading }
        compose.waitForIdle()
    }
}
