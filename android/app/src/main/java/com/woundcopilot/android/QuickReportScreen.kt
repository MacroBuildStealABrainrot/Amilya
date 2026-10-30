package com.woundcopilot.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.PersistableBundle
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.woundcopilot.android.ui.*

val quickFormats = linkedMapOf(
    "nursing" to "Nursing Note",
    "soap" to "SOAP Note",
    "sbar" to "SBAR",
    "physician_update" to "Physician Update",
    "referral_letter" to "Referral Letter",
    "handoff" to "Handoff Report",
)

/** Editing is a report action. Signing always applies to an exact saved version. */
@Composable
fun QuickReportScreen(s: AppState) {
    LaunchedEffect(s.encounterId){withFrameNanos{};Performance.end("report_ui");Performance.end("stop_to_report")}
    val context = LocalContext.current
    val c = MaterialTheme.colorScheme
    val document = s.case?.items("documents")?.firstOrNull { it.str("id") == s.savedId }
    val signed = document?.str("status") == "signed"
    val exactSaved = document != null && document.str("text") == s.editor && document.str("template") == s.editorTemplate &&
        document.str("amends_id") == s.amendId.orEmpty() && document.str("reason") == s.amendReason
    val current = s.editorCaseRevision == s.revision
    var formatOpen by rememberSaveable(s.encounterId) { mutableStateOf(false) }
    var pendingFormat by rememberSaveable(s.encounterId) { mutableStateOf<String?>(null) }
    var regenerate by rememberSaveable(s.encounterId) { mutableStateOf(false) }
    var signConfirm by rememberSaveable(s.encounterId) { mutableStateOf(false) }

    fun changeFormat(format: String) {
        s.persistEditor()
        s.reformatQuick(format)
    }

    Page {
        InstrumentTitle(if (signed) "Signed report" else "Your report", action = {
            TextButton(onClick = { s.doneQuick() }, enabled = !s.loading) { Text("Done") }
        })
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Box {
                TextButton(onClick = { formatOpen = true }, enabled = !signed && !s.loading) {
                    Text("Document Type · ${documentTypeTitle(s.editorTemplate)}")
                    Spacer(Modifier.width(7.dp))
                    InstrumentIcon("down", Modifier.size(17.dp), c.primary)
                }
                DropdownMenu(expanded = formatOpen, onDismissRequest = { formatOpen = false }) {
                    quickFormats.forEach { (value, title) ->
                        DropdownMenuItem(
                            text = { Text(title) },
                            onClick = {
                                formatOpen = false
                                if (value != s.editorTemplate) {
                                    if (s.editor.isBlank()) changeFormat(value) else pendingFormat = value
                                }
                            },
                            trailingIcon = { if (value == s.editorTemplate) InstrumentIcon("check", Modifier.size(19.dp), c.primary) },
                        )
                    }
                }
            }
            if (document != null) Text("Version ${document.optInt("version")}", style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant)
        }
        if (!signed) MissedReminders(s)
        if (signed) {
            Surface(color = c.surface, shape = RoundedCornerShape(22.dp)) {
                SelectionContainer { Text(s.editor, Modifier.fillMaxWidth().padding(22.dp), style = MaterialTheme.typography.bodyLarge) }
            }
        } else {
            if (s.amendId != null) OutlinedTextField(
                value = s.amendReason, onValueChange = s::updateAmendmentReason,
                label = { Text("Reason for amendment") }, enabled = !s.loading,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = s.editor,
                onValueChange = s::edit,
                modifier = Modifier.fillMaxWidth().heightIn(min = 280.dp).testTag("quick-report-editor"),
                textStyle = MaterialTheme.typography.bodyLarge,
                label = { Text("Review & edit") },
                enabled = !s.loading,
                shape = RoundedCornerShape(22.dp),
            )
        }
        Text(
            when {
                signed -> "Signed · Exact wording preserved"
                exactSaved -> "Saved to your workspace"
                s.editor.isBlank() -> "Add a recording or written note to create your report."
                else -> "Draft — Clinician Review Required"
            },
            style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant,
        )
        if (!signed) DocumentReviewTools(s)
        if (!signed && !current && s.editor.isNotBlank()) {
            GlassPanel(Modifier.fillMaxWidth()) {
                Text("Your sources changed", style = MaterialTheme.typography.titleMedium)
                Text("Check the report against the current sources before saving.", style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { s.persistEditor(); s.screen = "sources" }, enabled = !s.loading) { Text("View sources") }
                WcpButton("I reviewed the current sources", { s.useCurrentRevision() }, Modifier.fillMaxWidth(), enabled = !s.loading, secondary = true)
            }
        }
        if (!signed) WcpButton("Fix by Voice", { s.fixByVoice() }, Modifier.fillMaxWidth(), enabled = s.editor.isNotBlank() && !s.loading, secondary = true)
        if (!signed && !exactSaved) WcpButton(
            "Save report", { s.persistEditor(); s.saveDocument() }, Modifier.fillMaxWidth(),
            enabled = s.editor.isNotBlank() && current && !s.loading && (s.amendId == null || s.amendReason.isNotBlank()),
        )
        // One Send button: text message, another app, computer (one-time link) or copy.
        SendOptions(s.editor, documentTypeTitle(s.editorTemplate), s.loading,
            onComputer = { if (s.officeComputerOn) s.sendToDesk() else s.sendToComputer() }, onMessage = { s.notice = it },
            computerLabel = if (s.officeComputerOn) "Office computer" else "Computer",
            computerDetail = if (s.officeComputerOn) "Office computer puts it on your signed-in computer, ready to copy." else "Computer gives a one-time link and code to open on any computer.")

        DetailDisclosure("More options") {
            TextButton(onClick = { s.screen = "document_capture" }, enabled = !s.loading) { Text("Add photo, recording or files") }
            TextButton(onClick = { s.quickConsultant() }, enabled = !s.loading) { Text("Ask Consultant") }
            TextButton(onClick = { s.screen = "documents" }, enabled = !s.loading) { Text("Saved versions") }
            if (signed && document != null) WcpButton("Create amendment", { s.startAmendment(document) }, Modifier.fillMaxWidth(), enabled = !s.loading, secondary = true)
            if (!signed) {
                WcpButton("Review and sign", { signConfirm = true }, Modifier.fillMaxWidth(), enabled = exactSaved && current && !s.loading, secondary = true)
                TextButton(onClick = { regenerate = true }, enabled = !s.loading) { Text("Generate again from sources") }
                if (s.hasRecoverableDraft) TextButton(onClick = { s.restorePreviousDraft() }, enabled = !s.loading) { Text("Recover previous wording") }
            }
            if (false) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("PDF", "Text").forEach { format ->
                    WcpButton("Share $format", {
                        s.runTask("Preparing ${format.lowercase()} export") {
                            val title = documentTypeTitle(s.editorTemplate)
                            val uri = if (format == "PDF") ExportTools.createPdf(context, s.editor, title) else ExportTools.createText(context, s.editor, title)
                            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                type = if (format == "PDF") "application/pdf" else "text/plain"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }, "Share ${format.lowercase()}"))
                        }
                    }, Modifier.weight(1f), enabled = s.editor.isNotBlank() && !s.loading, secondary = true)
                }
            }
        }
    }
    pendingFormat?.let { format ->
        AlertDialog(
            onDismissRequest = { pendingFormat = null },
            title = { Text("Change to ${quickFormats[format]}?") },
            text = { Text("Create this format from the same sources. Your current wording may change; saved versions stay available.") },
            confirmButton = { TextButton(onClick = { pendingFormat = null; changeFormat(format) }, enabled = !s.loading) { Text("Change format") } },
            dismissButton = { TextButton(onClick = { pendingFormat = null }) { Text("Keep current report") } },
        )
    }
    if (regenerate) AlertDialog(
        onDismissRequest = { regenerate = false },
        title = { Text("Generate again from sources?") },
        text = { Text("This replaces the working wording. Saved versions remain available.") },
        confirmButton = { TextButton(onClick = { regenerate = false; s.quickReport() }, enabled = !s.loading) { Text("Generate report") } },
        dismissButton = { TextButton(onClick = { regenerate = false }) { Text("Keep current report") } },
    )
    if (signConfirm && document != null) AlertDialog(
        onDismissRequest = { signConfirm = false },
        title = { Text("Sign this exact report?") },
        text = { Text("Version ${document.optInt("version")}\n\nConfirm you have reviewed the complete report. Signing preserves this wording; later changes require an amendment.") },
        confirmButton = { TextButton(onClick = { signConfirm = false; s.sign(document) }, enabled = !s.loading) { Text("Confirm and sign") } },
        dismissButton = { TextButton(onClick = { signConfirm = false }) { Text("Keep reviewing") } },
    )
}

@Composable
fun VoiceCorrectionScreen(s: AppState) {
    val c = MaterialTheme.colorScheme
    Page {
        InstrumentTitle(s.pendingMissed?.let { "Add: ${it.str("label")}" } ?: "Fix by Voice")
        s.pendingMissed?.let { Text("This is added to the note as you said it. Check the wording, then tap Apply.", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant) }
        OutlinedTextField(
            value = s.voiceInstruction,
            onValueChange = { s.voiceInstruction = it },
            label = { Text("Your correction") },
            modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp),
            enabled = !s.loading,
            textStyle = MaterialTheme.typography.bodyLarge,
            shape = RoundedCornerShape(22.dp),
        )
        Text("Check the transcription. Apply it when it says what you intended.", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
        WcpButton("Apply correction", { s.applyVoiceCorrection() }, Modifier.fillMaxWidth(), enabled = s.voiceInstruction.isNotBlank() && !s.loading)
        WcpButton("Record again", { s.fixByVoice() }, Modifier.fillMaxWidth(), enabled = !s.loading, secondary = true)
        TextButton(onClick = { s.cancelVoiceCorrection() }, enabled = !s.loading, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Keep original report") }
    }
}

/** Marks clinical wording sensitive so supported Android versions suppress clipboard previews. */
private fun copySensitiveReport(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText("WoundCoPilot report", text)
    clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
    clipboard.setPrimaryClip(clip)
}
