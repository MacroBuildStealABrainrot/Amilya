package com.woundcopilot.android

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.woundcopilot.android.ui.FindingCorrection
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Synthetic workflow tests initialize a local sample directly; no public demo entry is available. */
@RunWith(AndroidJUnit4::class)
class WorkflowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var state: AppState

    @Before fun enterSample() {
        compose.runOnIdle { state = ViewModelProvider(compose.activity)[AppState::class.java] }
        awaitTask()
        // Refuse to operate on a signed-in workspace if this is run on a non-test installation.
        compose.runOnIdle { check(state.user == null) { "Use a fresh test installation, with no signed-in account." } }
        compose.runOnIdle { state.vault.remove("sample-cases-v1"); state.vault.remove("draft-sample-demo-encounter"); state.vault.remove("draft-recovery-sample-demo-encounter") }
        compose.runOnIdle { state.startDemo() }
        compose.waitUntil(10_000) { state.demo && state.screen == "home" }
        compose.runOnUiThread { state.openEncounter("demo-encounter") }
        awaitTask()
        compose.waitUntil(10_000) { state.screen == "encounter" }
        compose.runOnIdle {
            assertTrue(state.demo)
            assertEquals("demo-encounter", state.encounterId)
        }
    }

    @Test fun failedTaskClearsOldSuccessWithoutLosingDraft(){
        compose.runOnIdle {
            state.newDocument();state.edit("Synthetic unsaved wording")
            state.notice="Exact document saved to your workspace."
            state.runTask { throw ApiException(503,"Synthetic service unavailable") }
        }
        awaitTask()
        compose.runOnIdle {
            assertNull(state.notice);assertEquals("Request: Synthetic service unavailable\nHTTP 503",state.error)
            assertEquals("Synthetic unsaved wording",state.editor)
        }
    }
    @Test fun changingEncounterClearsPreviousNotice(){
        compose.runOnIdle {state.notice="Old case warning";state.openEncounter(state.encounterId)}
        awaitTask()
        compose.runOnIdle {assertNull(state.notice)}
    }
    @Test fun creatingEncounterClearsPreviousKosmoAnswer() {
        compose.runOnIdle { state.ask("What is the length?") }
        awaitTask()
        compose.runOnIdle { assertNotNull(state.answer);state.createEncounter(null,"New synthetic patient","",null,"","New case",{}, {}) }
        awaitTask()
        compose.runOnIdle { assertNull(state.answer);assertEquals("New synthetic patient",state.case!!.getJSONObject("patient").str("display_name")) }
    }

    @Test fun confirmingReportedFindingPreservesItsSourceAndRunsLocalChecks() {
        compose.onNodeWithContentDescription("Review").performClick()
        compose.waitForIdle()
        compose.onNode(hasText("Unconfirmed") and hasClickAction()).performScrollTo().performClick()
        compose.onNodeWithText("Pain").assertExists()
        clickInPage("Source & history")
        compose.onNodeWithText("“Patient reports discomfort of 3/10 during the dressing change.”").assertExists()
        clickInPage("Confirm")
        awaitTask()
        compose.onNodeWithText("No findings here yet").assertExists()
        compose.runOnIdle {
            val fact = state.case!!.items("facts").single { it.str("id") == "demo-fact-pain" }
            assertEquals("confirmed", fact.str("confirmation_state"))
            assertEquals("demo-evidence-narrative", fact.str("source_ref"))
            assertEquals("patient_report", fact.str("source_type"))
            assertEquals("Patient reports discomfort of 3/10 during the dressing change.", fact.str("source_quote"))
            assertEquals(2, fact.optInt("revision"))
        }
        compose.onNodeWithContentDescription("Open case review").performScrollTo().performClick()
        clickInPage("Run checks")
        awaitTask()
        compose.onNodeWithText("Synthetic preview. This is not a clinical assessment.").assertExists()
        compose.runOnIdle {
            assertNotNull(state.case!!.optJSONObject("review"))
            assertEquals(state.revision, state.case!!.getJSONObject("review").optInt("case_revision"))
            assertTrue(state.demo)
        }
    }

    @Test fun measurementRejectsZeroLengthAndKeepsUnmeasuredDepthAbsent() {
        clickInPage("Measure")
        replaceField("Length (cm)", "0")
        replaceField("Width (cm)", "1.25")
        Espresso.closeSoftKeyboard()
        clickInPage("Add confirmed finding")
        compose.onNodeWithText("Length must be greater than 0", substring = true).assertExists()
        compose.runOnIdle { assertEquals(2, state.case!!.items("evidence").size) }
        replaceField("Length (cm)", "2.75")
        Espresso.closeSoftKeyboard()
        clickInPage("Add confirmed finding")
        awaitTask()
        compose.waitUntil(10_000) { state.screen == "encounter" }
        compose.runOnIdle {
            val sourceIds = state.case!!.items("evidence").map { it.str("id") }
            assertEquals(3, sourceIds.size)
            val fact = state.case!!.items("facts").single {
                it.str("name") == "wound_measurement" && it.getJSONObject("value").optDouble("length_cm") == 2.75
            }
            assertEquals(1.25, fact.getJSONObject("value").getDouble("width_cm"), 0.0)
            assertFalse("Unmeasured depth must not silently become zero", fact.getJSONObject("value").has("depth_cm"))
            assertEquals("confirmed", fact.str("confirmation_state"))
            assertTrue(sourceIds.contains(fact.str("source_ref")))
        }
    }

    @Test fun exactDocumentSurvivesReopenSigningAndLinkedAmendment() {
        val exact = "SYNTHETIC TEST\n\nExact wording: 2.75 × 1.25 cm.\nUnicode: naïve café — 日本語.\nClinician-authored line.\n"
        compose.onNodeWithContentDescription("Documents").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Create a document").performClick()
        clickInPage("Source-based draft")
        awaitTask()
        compose.runOnIdle {
            assertTrue(state.editor.contains("2.4"))
            assertTrue("Supplied symptoms remain usable in a reviewable draft", state.editor.contains("3/10"))
            assertTrue("Drafting must not confirm the source finding",state.case!!.items("facts").any{it.str("name")=="pain" && it.str("confirmation_state")=="unconfirmed"})
        }
        replaceField("Clinical document", exact)
        Espresso.closeSoftKeyboard()
        clickInPage("Save exact document")
        awaitTask()
        var firstId = ""
        compose.runOnIdle {
            firstId = state.savedId!!
            assertEquals(exact, state.case!!.items("documents").single { it.str("id") == firstId }.str("text"))
        }
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithContentDescription("Documents").performClick()
        compose.waitForIdle()
        clickInPage("Version 2")
        compose.onNode(hasSetTextAction() and hasText("Clinical document")).assertTextContains(exact)
        clickInPage("Review and sign")
        compose.onNodeWithText("Sign this exact version?").assertExists()
        compose.onNodeWithText("Confirm and sign").performClick()
        awaitTask()
        compose.onAllNodesWithText("Signed · Version 2").onFirst().assertExists()
        compose.onNode(hasSetTextAction() and hasText("Clinical document")).assertDoesNotExist()
        compose.runOnIdle {
            val signed = state.case!!.items("documents").single { it.str("id") == firstId }
            assertEquals("signed", signed.str("status"))
            assertEquals(exact, signed.str("text"))
        }
        clickInPage("Create amendment")
        replaceField("Reason for amendment", "Synthetic correction exercise")
        replaceField("Clinical document", exact + "Amendment: additional synthetic context.")
        Espresso.closeSoftKeyboard()
        clickInPage("Save exact document")
        awaitTask()
        compose.runOnIdle {
            val documents = state.case!!.items("documents")
            val original = documents.single { it.str("id") == firstId }
            val amendment = documents.single { it.str("id") == state.savedId }
            assertEquals("signed", original.str("status"))
            assertEquals(exact, original.str("text"))
            assertEquals(firstId, amendment.str("amends_id"))
            assertEquals("Synthetic correction exercise", amendment.str("reason"))
            assertEquals(exact + "Amendment: additional synthetic context.", amendment.str("text"))
        }
    }

    @Test fun swipingWorkspaceAndTappingDockKeepTheSameEncounter() {
        compose.onNodeWithTag("encounter-pager").performTouchInput { swipeLeft() }
        compose.waitUntil(10_000) { state.screen == "findings" }
        compose.onNodeWithContentDescription("Review").assertIsSelected()
        compose.onNodeWithTag("encounter-pager").performTouchInput { swipeLeft() }
        compose.waitUntil(10_000) { state.screen == "documents" }
        compose.onNodeWithContentDescription("Documents").assertIsSelected()
        compose.onNodeWithContentDescription("Capture").performClick()
        compose.waitUntil(10_000) { state.screen == "encounter" }
        compose.onNodeWithContentDescription("Capture").assertIsSelected()
        compose.runOnIdle { assertEquals("demo-encounter",state.encounterId); assertEquals(2,state.case!!.items("evidence").size) }
    }

    @Test fun leavingAndReopeningQuickNoteRestoresUnsubmittedText() {
        val note="Unsubmitted synthetic observation — keep this wording."
        clickInPage("Write note")
        replaceField("Source notes",note)
        Espresso.closeSoftKeyboard()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitUntil(10_000) { state.screen == "encounter" }
        clickInPage("Write note")
        compose.onNode(hasSetTextAction() and hasText("Source notes")).assertTextContains(note)
        compose.runOnIdle { assertEquals(2,state.case!!.items("evidence").size) }
    }

    private fun awaitTask() {
        compose.waitUntil(30_000) { !state.loading }
        compose.waitForIdle()
    }

    private fun clickInPage(text: String) {
        compose.onNodeWithText(text).performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun replaceField(label: String, text: String) {
        compose.onNode(hasSetTextAction() and hasText(label)).performScrollTo().performTextReplacement(text)
        compose.waitForIdle()
    }
}

@RunWith(AndroidJUnit4::class)
class LocalStorageAndExportTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun vaultEncryptsDurablyUsesFreshNoncesAndRejectsTampering() {
        val name = "instrumentation-${UUID.randomUUID()}"
        val original = "Synthetic draft only. Unicode: café — 日本語.\nExact trailing whitespace.  "
        val vault = Vault(context)
        val file = File(context.noBackupFilesDir, "vault/$name")
        try {
            vault.write(name, original)
            val first = file.readBytes()
            assertEquals(original, Vault(context).read(name))
            assertFalse(first.toString(Charsets.UTF_8).contains("Synthetic draft only"))
            vault.write(name, original)
            val second = file.readBytes()
            assertFalse("Repeated writes must use a fresh encryption nonce", first.contentEquals(second))
            val corrupted = second.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
            file.writeBytes(corrupted)
            assertNull("Modified ciphertext must not be accepted as a recovered draft", vault.read(name))
            file.writeBytes(second)
            assertEquals(original, vault.read(name))
        } finally { vault.remove(name) }
    }

    @Test fun textExportPreservesExactUnicodeAndWhitespace() {
        val text = "SYNTHETIC\r\nCafé — 日本語 — العربية\n2.75 × 1.25 cm\nExact spaces.  \n"
        val uri = ExportTools.createText(context, text, "Instrumentation synthetic export")
        try {
            assertEquals("content", uri.scheme)
            val exported = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            assertArrayEquals(text.toByteArray(Charsets.UTF_8), exported)
        } finally { context.contentResolver.delete(uri, null, null) }
    }

    @Test fun longUnicodePdfIsReadableAndHasContentOnItsLastPage() {
        val body = (1..200).joinToString("\n") { "Synthetic line $it: café — 日本語 — العربية. Measurement 2.75 × 1.25 cm." }
        val uri = ExportTools.createPdf(context, body, "Synthetic paginated export")
        try {
            val header = context.contentResolver.openInputStream(uri)!!.use { input -> ByteArray(5).also { assertEquals(5, input.read(it)) } }
            assertEquals("%PDF-", header.toString(Charsets.US_ASCII))
            PdfRenderer(context.contentResolver.openFileDescriptor(uri, "r")!!).use { renderer ->
                assertTrue("Long text must paginate", renderer.pageCount >= 3)
                renderer.openPage(renderer.pageCount - 1).use { page ->
                    val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                    try {
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        var bodyInk = 0
                        for (y in 58 until minOf(400, page.height - 61)) {
                            for (x in 48 until page.width - 48) {
                                val color = bitmap.getPixel(x, y)
                                if (Color.red(color) < 180 && Color.green(color) < 180 && Color.blue(color) < 180) bodyInk++
                            }
                        }
                        assertTrue("The final page must contain rendered body text, not only headers or footers", bodyInk > 50)
                    } finally { bitmap.recycle() }
                }
            }
        } finally { context.contentResolver.delete(uri, null, null) }
    }
}

@RunWith(AndroidJUnit4::class)
class FindingCorrectionTest {
    @Test fun openingAndSavingPreservesAllExistingStructuredFields() {
        val fixtures = listOf(
            "wound_measurement" to obj("length_cm" to 2.75, "width_cm" to 1.25, "depth_cm" to 0.0),
            "wound_location" to obj("site" to "Heel", "laterality" to "left"),
            "wound_tissue" to obj("text" to "Synthetic description", "granulation" to 55.0, "slough" to 20.0, "eschar" to 0.0, "epithelial" to 25.0),
            "pain" to obj("text" to "Synthetic report", "score_0_10" to 3.5, "present" to true, "observed_on" to "2026-09-05"),
            "fever" to obj("text" to "Synthetic observation", "present" to false, "observed_on" to "2026-09-04"),
        )
        fixtures.forEach { (name, original) ->
            val saved = FindingCorrection.from(original).toValue(name)
            assertEquals(original.keys().asSequence().toSet(), saved.keys().asSequence().toSet())
            original.keys().forEach { key ->
                val expected = original.get(key)
                if (expected is Number) assertEquals(expected.toDouble(), saved.getDouble(key), 0.0)
                else assertEquals("$name.$key", expected, saved.get(key))
            }
        }
    }

    @Test fun explicitlyClearingPresenceDoesNotInventAnAbsentObservation() {
        val original = obj("text" to "Synthetic observation retained", "present" to false, "observed_on" to "2026-09-05")
        val saved = FindingCorrection.from(original).copy(presence = "").toValue("fever")
        assertFalse(saved.has("present"))
        assertEquals(original.str("text"), saved.str("text"))
        assertEquals("2026-09-05", saved.str("observed_on"))
    }

    @Test fun invalidDimensionsTissueTotalsDatesAndLateralityAreRejected() {
        fun rejected(block: () -> Unit) {
            try { block(); fail("Invalid clinical value was accepted") }
            catch (_: IllegalArgumentException) { }
        }
        val measurement = FindingCorrection(length = "2", width = "1")
        listOf("NaN", "Infinity", "0", "-1", "201").forEach { value -> rejected { measurement.copy(length = value).toValue("wound_measurement") } }
        rejected { measurement.copy(depth = "101").toValue("wound_measurement") }
        rejected { FindingCorrection(granulation = "70", slough = "40").toValue("wound_tissue") }
        rejected { FindingCorrection(score = "10.1").toValue("pain") }
        rejected { FindingCorrection(text = "Synthetic", observedOn = "2026-02-30").toValue("fever") }
        rejected { FindingCorrection(site = "Heel", laterality = "unknown-value").toValue("wound_location") }
    }
}

