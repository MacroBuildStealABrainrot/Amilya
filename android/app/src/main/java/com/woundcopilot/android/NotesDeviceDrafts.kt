package com.woundcopilot.android

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun NotesDeviceDrafts(s: AppState) {
    val owner = s.user?.str("id") ?: return
    val recorder by RecordingService.state.collectAsState()
    val rows by produceState(emptyList<NotesDeviceDraft>(), owner, s.deleteWorkId, s.loading) {
        value = withContext(Dispatchers.IO) { s.deviceDrafts(owner) }
    }
    val current = rows.filter { it.owner == owner }
    if (current.isEmpty()) return
    Text("Drafts on this device", style = MaterialTheme.typography.titleMedium)
    current.forEach { row ->
        val label = when (row.kind) {
            NotesDraftKind.REPORT -> "Report draft"
            NotesDraftKind.TYPED -> "Typed note"
            NotesDraftKind.SOURCE -> "Notes to review"
        }
        TextButton(onClick = { s.openDeviceDraft(row) }, enabled = !s.loading && !recorder.recording) {
            Text("$label · ${localTime(Instant.ofEpochMilli(row.modified).toString())}")
        }
    }
}
