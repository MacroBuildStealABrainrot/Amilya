package com.woundcopilot.android

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.woundcopilot.android.ui.WcpButton

/** Notes has no entry points into assessment, photo or consultant workflows. */
internal fun notesRouteAllowed(route: String): Boolean = route in setOf(
    "welcome", "login", "signup", "reconnect", "home", "saved", "account",
    "shift_summary", "document_capture", "record", "evidence:typed",
    "editor", "documents", "sources", "history", "voice_correction",
)

internal fun notesVisitAllowed(mode: String): Boolean =
    mode.lowercase() in setOf("document", "report", "nursing")

internal fun notesCanEditTemplate(template: String): Boolean = template == "nursing"

@androidx.compose.runtime.Composable
internal fun NotesSourcesScreen(s: AppState) {
    Page {
        com.woundcopilot.android.ui.InstrumentTitle("Your notes")
        s.case?.items("evidence")?.forEach { EvidenceItem(s, it) }
        com.woundcopilot.android.ui.WcpButton("Return to report", { s.screen = "editor" })
    }
}

/** A clinician opens a blank draft or explicitly places the reviewed words in the editor. */
@Composable
internal fun NotesReportRecoveryAction(s: AppState, typedText: String? = null, typedEditId: String? = null) {
    if (!BuildConfig.NOTES_ONLY || !s.canReviewNotesLocally()) return
    val owner = s.user?.str("id").orEmpty()
    val visit = s.encounterId
    val revision = s.revision
    val refused = s.notesWritingRefusal?.takeIf { typedText == null && it.matches(owner, visit, revision) }
    // The preview stays in memory and clears if the account or visit changes.
    var preview by remember(owner, visit, s.revision) { mutableStateOf<String?>(null) }
    val source = typedText ?: remember(owner, visit, s.revision, s.errorMessageSequence) { s.notesRecoverySource() }
    WcpButton(if (source.isBlank()) "Write report without AI" else "Review my notes", {
        if (source.isBlank()) s.useReviewedNotes("", visit, owner) else preview = source
    }, Modifier.fillMaxWidth(), secondary = s.error == null)

    // An automatic offer uses only the text accepted for this visit. Unsent forms are reviewed separately.
    val wordsToReview = if (refused == null) preview else remember(owner, visit, revision, refused) {
        notesReviewText(s.case?.items("evidence").orEmpty(), emptyList()).takeIf(String::isNotBlank)
    }
    fun closeReview() {
        preview = null
        if (refused != null) s.closeNotesWritingReview()
    }
    wordsToReview?.let { reviewedWords ->
        AlertDialog(
            onDismissRequest = ::closeReview,
            title = { Text(if (refused != null) "AI couldn’t write this report" else "Review your words") },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    if (refused != null) {
                        Text(refused.reason)
                    }
                    Text(
                        if (refused != null) "These are the original notes. Check who said what, then edit and save your own report."
                        else if (typedText != null) "Only the note in this text box will be used. You can edit it before saving."
                        else "If these words came from a recording, check who said what. Edit the draft before saving.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(reviewedWords, style = MaterialTheme.typography.bodyLarge)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    closeReview()
                    if (s.revision == revision) s.useReviewedNotes(reviewedWords, visit, owner, typedEditId)
                }) {
                    Text("Use in draft")
                }
            },
            dismissButton = {
                Column {
                    TextButton(onClick = { closeReview(); s.useReviewedNotes("", visit, owner) }) {
                        Text("Start blank")
                    }
                    TextButton(onClick = ::closeReview) { Text("Cancel") }
                }
            },
        )
    }
}
