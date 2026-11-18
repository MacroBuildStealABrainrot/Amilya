package com.woundcopilot.android

import android.content.ClipData
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.woundcopilot.android.ui.InstrumentTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

/** Local screen. No runTask, API, diagnostics, upload, AI or billing path. Share only opens the phone's own share menu. */
@Composable
fun ShiftSummaryScreen(s: AppState) {
    val context = LocalContext.current
    val owner = s.user?.str("id")
    var clock by remember { mutableStateOf(ZoneId.systemDefault().let { LocalDate.now(it) to it }) }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            clock = ZoneId.systemDefault().let { LocalDate.now(it) to it }
        }
    }
    val plan = remember(owner, clock, refresh) { s.shiftSummarySnapshot(clock.first, clock.second) }
    // Every report written today starts ticked, signed or not. Untick any you do not want to send.
    var selected by remember(owner, plan) { mutableStateOf(plan?.reports?.map { it.first.id }?.toSet().orEmpty()) }
    var feedback by remember(owner) { mutableStateOf<String?>(null) }
    var showPreview by remember { mutableStateOf(false) }
    val combined = remember(plan, selected) { plan?.let { renderShiftSummary(it, selected, compactEmpty = true) } }
    val scope = rememberCoroutineScope()
    val title = "End-of-shift summary ${clock.first}"

    /** The date, account or saved visits must not have changed since the list was shown. */
    fun stillCurrent(): Boolean {
        val zone = ZoneId.systemDefault()
        val currentClock = LocalDate.now(zone) to zone
        if (s.user?.str("id") != owner || currentClock != clock || s.shiftSummarySnapshot(currentClock.first, zone) != plan) {
            clock = currentClock; refresh++
            feedback = "Your visits or the date changed. Check the list again, then tap again."
            return false
        }
        return true
    }

    Page {
        InstrumentTitle("End-of-shift summary")
        Text("${clock.first} · ${clock.second.id}")
        Text("Today's reports from this phone, copied exactly as written. No AI is used.")
        if (plan == null) {
            Text("The saved visits or their dates could not be checked. Nothing was copied. Check your visits and the phone’s free space, then restart the app before using the summary.", color = MaterialTheme.colorScheme.error)
        } else {
            Text("Today's reports", style = MaterialTheme.typography.titleMedium)
            if (plan.reports.isEmpty()) Text("No reports from today are saved on this phone yet.")
            else {
                val allTicked = plan.reports.all { it.first.id in selected }
                Row(Modifier.fillMaxWidth().toggleable(value = allTicked, role = Role.Checkbox, onValueChange = { all ->
                    selected = if (all) plan.reports.map { it.first.id }.toSet() else emptySet()
                }).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = allTicked, onCheckedChange = null)
                    Text("Select all (${selected.size} of ${plan.reports.size} selected)", Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelLarge)
                }
                HorizontalDivider()
            }
            plan.reports.forEach { (visit, report) ->
                val checked = visit.id in selected
                Row(Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Checkbox, onValueChange = { include ->
                    selected = if (include) selected + visit.id else selected - visit.id
                }).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = checked, onCheckedChange = null)
                    Column(Modifier.weight(1f).padding(start = 8.dp)) {
                        Text(shiftVisitHeading(visit, plan.zone).substringBefore('\n'))
                        val status = when {
                            report.status == "signed" -> "Signed"
                            report.editedOnPhone -> "Saved, not signed · includes your latest edits"
                            else -> "Saved, not signed"
                        }
                        Text(status, style = MaterialTheme.typography.bodySmall,
                            color = if (report.status == "signed") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            val empty = plan.withoutReport.size
            if (empty > 0) Text("$empty other visit(s) today have no report (for example a failed or consultant-only visit).", style = MaterialTheme.typography.bodySmall)
            if (plan.unavailable.isNotEmpty()) {
                Text("Not available on this phone", style = MaterialTheme.typography.titleMedium)
                Text("Open each of these visits while online, then come back here.", color = MaterialTheme.colorScheme.error)
                plan.unavailable.forEach { Text(shiftVisitHeading(it, plan.zone).substringBefore('\n')) }
            }
            val ready = selected.isNotEmpty() && plan.reports.isNotEmpty()
            Button(onClick = {
                if (stillCurrent()) try {
                    copySensitive(context, combined.orEmpty(), "Amilya end-of-shift summary")
                    feedback = "Copied ${selected.size} report(s). Nothing was sent by Amilya."
                } catch (_: Exception) {
                    feedback = "The reports could not be copied. Nothing was sent. Try again."
                }
            }, enabled = ready, modifier = Modifier.fillMaxWidth()) { Text("Copy") }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                listOf("Text", "PDF").forEach { format ->
                    OutlinedButton(onClick = {
                        if (stillCurrent()) scope.launch {
                            // You choose where it goes in the phone's own share menu; Amilya sends nothing by itself.
                            try {
                                val text = combined.orEmpty()
                                val intent = if (format == "PDF") {
                                    val uri = withContext(Dispatchers.IO) { ExportTools.createPdf(context, text, title) }
                                    Intent(Intent.ACTION_SEND).apply {
                                        type = "application/pdf"; putExtra(Intent.EXTRA_STREAM, uri); putExtra(Intent.EXTRA_SUBJECT, title)
                                        clipData = ClipData.newRawUri(title, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                } else Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_SUBJECT, title); putExtra(Intent.EXTRA_TEXT, text) }
                                context.startActivity(Intent.createChooser(intent, "Share ${selected.size} report(s)").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            } catch (_: Exception) {
                                feedback = "The share menu could not open. Use Copy instead."
                            }
                        }
                    }, enabled = ready, modifier = Modifier.weight(1f)) { Text("Share as $format") }
                }
            }
            feedback?.let { Text(it); TextButton(onClick = { feedback = null }) { Text("Dismiss") } }
            TextButton(onClick = { showPreview = !showPreview }) { Text(if (showPreview) "Hide combined text" else "Show combined text") }
            if (showPreview) SelectionContainer { Text(combined.orEmpty()) }
        }
        TextButton(onClick = { refresh++ }) { Text("Reload from this phone") }
    }
}
