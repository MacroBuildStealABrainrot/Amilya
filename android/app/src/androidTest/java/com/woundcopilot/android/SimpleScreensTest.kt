package com.woundcopilot.android

import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.woundcopilot.android.ui.WoundTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Presentation-only checks: no patient data, authentication, provider calls or storage writes. */
@RunWith(AndroidJUnit4::class)
class SimpleScreensTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun allThreeStartingActionsAreIndependent() {
        val actions = mutableListOf<String>()
        compose.runOnUiThread {
            compose.activity.setContent {
                WoundTheme {
                    StartScreen(
                        onDocument = { actions += "document" },
                        onPhoto = { actions += "photo" },
                        onConsultant = { actions += "consultant" },
                        onOpenWork = {},
                        onAccount = {},
                    )
                }
            }
        }
        listOf("Document", "Photo Assessment", "Wound Consultant").forEach {
            compose.onNodeWithText(it).performScrollTo().performClick()
        }
        compose.runOnIdle { assertEquals(listOf("document", "photo", "consultant"), actions) }
    }

    @Test fun photoDoneDoesNotStartReportOrConsultant() {
        val actions = mutableListOf<String>()
        compose.runOnUiThread {
            compose.activity.setContent {
                WoundTheme {
                    PhotoAssessmentScreen(
                        findings = listOf(PhotoAssessmentFinding("Wound bed", "Synthetic visible finding")),
                        warnings = listOf("Depth cannot be determined from this image."),
                        onDone = { actions += "done" },
                        onAddToReport = { actions += "report" },
                        onAskConsultant = { actions += "consultant" },
                    )
                }
            }
        }
        compose.onNodeWithText("Done").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("done"), actions) }
        compose.onNodeWithText("Add to Report").assertExists()
        compose.onNodeWithText("Ask Wound Consultant").assertExists()
    }

    @Test fun consultantDoesNotLoseDraftBeforeSendAcknowledgement() {
        val busy = mutableStateOf(false)
        val reset = mutableStateOf(0)
        val recovered = mutableStateOf("Synthetic question")
        val sent = mutableListOf<String>()
        compose.runOnUiThread {
            compose.activity.setContent {
                WoundTheme {
                    ConsultantChatScreen(
                        conversationKey = "presentation-test",
                        messages = emptyList(),
                        initialDraft = recovered.value,
                        draftResetKey = reset.value,
                        busy = busy.value,
                        onDraftChange = { recovered.value = it },
                        onSend = { sent += it; busy.value = true },
                        onAddPhoto = {}, onAddFiles = {}, onRecord = {}, onDone = {},
                    )
                }
            }
        }
        compose.onNodeWithContentDescription("Send message").performClick()
        compose.runOnIdle { assertEquals(listOf("Synthetic question"), sent) }
        compose.onNodeWithContentDescription("Send message").assertIsNotEnabled()
        compose.onNodeWithTag("consultant-composer").assertTextContains("Synthetic question")
        // A failed request only releases the busy state; its question remains retryable.
        compose.runOnIdle { busy.value = false }
        compose.onNodeWithTag("consultant-composer").assertTextContains("Synthetic question")
        // Only an explicit successful acknowledgement clears the saved composer.
        compose.runOnIdle { recovered.value = ""; reset.value += 1 }
        compose.onNodeWithTag("consultant-composer").assertTextContains("")
        compose.onNodeWithContentDescription("Send message").assertIsNotEnabled()
    }

    @Test fun attachmentsRemainOptionalAndAreExplicitlyChosen() {
        val actions = mutableListOf<String>()
        compose.runOnUiThread {
            compose.activity.setContent {
                WoundTheme {
                    ConsultantChatScreen(
                        conversationKey = "attachments-test", messages = emptyList(),
                        onSend = { actions += "send" },
                        onAddPhoto = { actions += "photo" },
                        onAddFiles = { actions += "files" },
                        onRecord = { actions += "record" },
                        onDone = { actions += "done" },
                    )
                }
            }
        }
        compose.onNodeWithText("Add Files").assertDoesNotExist()
        compose.runOnIdle { assertTrue(actions.isEmpty()) }
        compose.onNodeWithContentDescription("Add photo, files or recording").performClick()
        compose.onNodeWithText("Add Files").performClick()
        compose.runOnIdle { assertEquals(listOf("files"), actions) }
    }

    @Test fun inadequatePhotoOffersRetakeAndDoneWithoutClinicalNextSteps() {
        val actions = mutableListOf<String>()
        showScreen {
            PhotoAssessmentScreen(
                findings = listOf(PhotoAssessmentFinding("Not valid", "This must not be shown when quality fails.")),
                qualityInsufficient = true, retakeReason = "Improve the lighting.",
                onRetake = { actions += "retake" }, onDone = { actions += "done" },
                onAddToReport = { actions += "report" }, onAskConsultant = { actions += "consultant" },
            )
        }
        compose.onNodeWithText("Improve the lighting.").assertExists()
        compose.onNodeWithText("This must not be shown when quality fails.").assertDoesNotExist()
        compose.onNodeWithText("Add to Report").assertDoesNotExist()
        compose.onNodeWithText("Ask Wound Consultant").assertDoesNotExist()
        compose.onNodeWithText("Retake").performScrollTo().performClick()
        compose.onNodeWithText("Done").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("retake", "done"), actions) }
    }

    @Test fun warningWithoutFindingsDoesNotBecomeAValidAssessment() {
        showScreen {
            PhotoAssessmentScreen(
                findings = emptyList(), warnings = listOf("No assessable findings."),
                onRetake = {}, onDone = {}, onAddToReport = {}, onAskConsultant = {},
            )
        }
        compose.onNodeWithText("Retake").assertExists()
        compose.onNodeWithText("Done").assertExists()
        compose.onNodeWithText("Add to Report").assertDoesNotExist()
        compose.onNodeWithText("Ask Wound Consultant").assertDoesNotExist()
    }

    @Test fun voiceMessageHasItsOwnVisibleActionWithoutOpeningAttachments() {
        val actions = mutableListOf<String>()
        showScreen {
            ConsultantChatScreen(
                conversationKey = "visible-mic-test", messages = emptyList(),
                onSend = { actions += "send" }, onAddPhoto = {}, onAddFiles = {},
                onRecord = { actions += "evidence-recording" },
                onVoiceMessage = { actions += "voice-message" }, onDone = {},
            )
        }
        compose.onNodeWithContentDescription("Voice message").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("Send message").assertIsNotEnabled()
        compose.onNodeWithText("Record").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf("voice-message"), actions) }
    }

    @Test fun answersUseParagraphBubblesAndCompactAttachmentsWithoutModelTerms() {
        showScreen {
            ConsultantChatScreen(
                conversationKey = "paragraph-test",
                messages = listOf(ConsultantMessage("answer", "First clinical point.\n\nOne focused question?", false, "medium")),
                attachmentSummary = "3 sources attached to this conversation",
                attachments = ConsultantAttachments(photoCount = 1, fileCount = 2),
                onSend = {}, onAddPhoto = {}, onAddFiles = {}, onRecord = {}, onDone = {},
            )
        }
        compose.onNodeWithTag("chat-bubble-answer-0").assertExists()
        compose.onNodeWithTag("chat-bubble-answer-1").assertExists()
        compose.onNodeWithText("First clinical point.").assertExists()
        compose.onNodeWithText("One focused question?").assertExists()
        compose.onAllNodesWithText("Wound Consultant").assertCountEquals(2)
        compose.onNodeWithText("effort", substring = true).assertDoesNotExist()
        compose.onNodeWithText("3 sources attached to this conversation").assertDoesNotExist()
        compose.onNodeWithContentDescription("1 photo attached").assertExists()
        compose.onNodeWithContentDescription("2 files attached").assertExists()
    }

    @Test fun paragraphDisplayPreservesEveryCharacterAndFinalClinicalInstruction() {
        val authored = "A first observation.\n\nA second observation.\n\nA focused question?"
        assertEquals(authored, consultantDisplayChunks(authored).joinToString(""))
        assertEquals(3, consultantDisplayChunks(authored).size)
        val long = (1..400).joinToString("\n\n") { "Synthetic paragraph $it remains visible." } +
            "\n\nFinal instruction: seek urgent assessment if the documented warning develops."
        val chunks = consultantDisplayChunks(long)
        assertEquals(long, chunks.joinToString(""))
        assertTrue(chunks.size <= 32)
        assertTrue(chunks.last().endsWith("seek urgent assessment if the documented warning develops."))
        val unicode = "x".repeat(849) + "😀" + "y".repeat(1200)
        val unicodeChunks = consultantDisplayChunks(unicode)
        assertEquals(unicode, unicodeChunks.joinToString(""))
        assertTrue(unicodeChunks.none { it.isNotEmpty() && Character.isHighSurrogate(it.last()) })
    }

    @Test fun recoveryListShowsOnlyStatusAndResumeAction() {
        val finished=mutableListOf<String>()
        val job=org.json.JSONObject("""{"id":"synthetic-job","owner":"synthetic-owner","file":"/synthetic/audio.m4a","phase":"REPORT_READY","resume_from":"REPORT_READY","updated_at":0,"report":"PRIVATE_REPORT_SENTINEL","case":{"patient":{"display_name":"PRIVATE_PATIENT_SENTINEL"}}}""")
        showScreen { RecordingRecoveryList(listOf(job),null,false,{finished+=it.str("id")},{}) }
        compose.onNodeWithText("Review report").performClick()
        compose.runOnIdle { assertEquals(listOf("synthetic-job"),finished) }
        compose.onNodeWithText("PRIVATE_PATIENT_SENTINEL").assertDoesNotExist()
        compose.onNodeWithText("PRIVATE_REPORT_SENTINEL").assertDoesNotExist()
    }

    @Test fun recoveryDiscardRequiresExplicitConfirmation() {
        val discarded=mutableListOf<String>()
        val job=org.json.JSONObject("""{"id":"synthetic-job","owner":"synthetic-owner","file":"/synthetic/audio.m4a","phase":"RECORDED_LOCALLY","resume_from":"RECORDED_LOCALLY","updated_at":0}""")
        showScreen { RecordingRecoveryList(listOf(job),null,false,{}, {discarded+=it.str("id")}) }
        compose.onNodeWithText("Discard").performClick()
        compose.onNodeWithText("Discard permanently").assertExists()
        compose.runOnIdle { assertTrue(discarded.isEmpty()) }
        compose.onNodeWithText("Discard permanently").performClick()
        compose.runOnIdle { assertEquals(listOf("synthetic-job"),discarded) }
    }

    private fun showScreen(content: @Composable () -> Unit) {
        compose.runOnUiThread { compose.activity.setContent { WoundTheme { content() } } }
    }
}
