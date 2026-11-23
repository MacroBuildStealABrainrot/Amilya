package com.woundcopilot.android

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
