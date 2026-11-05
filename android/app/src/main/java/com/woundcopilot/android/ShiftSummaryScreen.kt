package com.woundcopilot.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.PersistableBundle
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
import kotlinx.coroutines.delay
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
    var selected by remember(owner, plan) { mutableStateOf(plan?.finished?.map { it.first.id }?.toSet().orEmpty()) }
    var feedback by remember(owner) { mutableStateOf<String?>(null) }
    var chosenDrafts by remember(owner, plan) { mutableStateOf(emptySet<String>()) }
    val combined = remember(plan, selected, chosenDrafts) { plan?.let { renderShiftSummary(it, selected, chosenDrafts, compactEmpty = true) } }

    Page {
        InstrumentTitle("End-of-shift summary")
        Text("${clock.first} · ${clock.second.id}")
        Text("Only visits already saved or loaded on this phone are available. This works offline and uses no AI. Reports are copied exactly as saved.")
        Text("Finished means Review and sign is complete. Saved drafts are listed separately. The latest saved version is used.")
        if (plan == null) {
            Text("The saved visits or their dates could not be checked. Nothing was copied. Check your visits and the phone’s free space, then restart the app before using the summary.", color = MaterialTheme.colorScheme.error)
        } else {
            // These are part of the document, not fading notifications.
            Text("No finished report", style = MaterialTheme.typography.titleMedium)
            if (plan.unfinished.isEmpty()) Text("None among the visits available on this phone.")
            val noReport = plan.unfinished.count { it.reports.isEmpty() }
            if (noReport > 0) Text("$noReport visit(s) today have no report saved (for example a failed or consultant-only visit).")
            plan.unfinished.filter { it.reports.isNotEmpty() }.forEach {
                Text(shiftVisitHeading(it, plan.zone))
                Text("Only an unfinished report is saved. Review and sign it when ready.")
            }
            if (plan.drafts.isNotEmpty()) {
                Text("Unsigned drafts (optional)", style = MaterialTheme.typography.titleMedium)
                Text("Only tick these if you want to send them. Each one is stamped UNSIGNED DRAFT in the document.")
                plan.drafts.forEach { (visit, report) ->
                    val checked = visit.id in chosenDrafts
                    Row(Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Checkbox, onValueChange = { include ->
                        chosenDrafts = if (include) chosenDrafts + visit.id else chosenDrafts - visit.id
                    }).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = checked, onCheckedChange = null)
                        Column(Modifier.weight(1f).padding(start = 8.dp)) {
                            Text(shiftVisitHeading(visit, plan.zone))
                            Text("Unsigned draft · version ${report.version}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            if (plan.unavailable.isNotEmpty()) {
                Text("Report not available on this phone", style = MaterialTheme.typography.titleMedium)
                Text("The phone cannot confirm whether these visits have a finished report. Open each visit while online, then return here.", color = MaterialTheme.colorScheme.error)
                plan.unavailable.forEach { Text(shiftVisitHeading(it, plan.zone)) }
            }
            Text("Choose finished reports", style = MaterialTheme.typography.titleMedium)
            if (plan.finished.isEmpty()) Text("No finished reports from today are available on this phone.")
            plan.finished.forEach { (visit, report) ->
                val checked = visit.id in selected
                Row(Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Checkbox, onValueChange = { include ->
                    selected = if (include) selected + visit.id else selected - visit.id
                }).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = checked, onCheckedChange = null)
                    Column(Modifier.weight(1f).padding(start = 8.dp)) {
                        Text(shiftVisitHeading(visit, plan.zone))
                        Text("Signed report · version ${report.version}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Text("Combined document", style = MaterialTheme.typography.titleMedium)
            Button(onClick = {
                val zone = ZoneId.systemDefault()
                val currentClock = LocalDate.now(zone) to zone
                // Date/account/source changes must be reviewed before anything reaches the clipboard.
                if (s.user?.str("id") != owner || currentClock != clock || s.shiftSummarySnapshot(currentClock.first, zone) != plan) {
                    clock = currentClock; refresh++
                    feedback = "The visit list or date changed. Review your choices and tap Copy again."
                } else try {
                    val clip = ClipData.newPlainText("Amilya end-of-shift summary", combined.orEmpty())
                    clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
                    feedback = "Copied. Nothing was sent by Amilya."
                } catch (_: Exception) {
                    feedback = "The document could not be copied. Nothing was sent. Try Copy again."
                }
            }, modifier = Modifier.fillMaxWidth()) { Text("Copy") }
            OutlinedButton(onClick = {
                // You choose where it goes in the phone's own share sheet; Amilya sends nothing by itself.
                runCatching {
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"; putExtra(Intent.EXTRA_SUBJECT, "End-of-shift summary ${clock.first}"); putExtra(Intent.EXTRA_TEXT, combined.orEmpty())
                    }, "Share end-of-shift summary").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }.onFailure { feedback = "The share menu could not open. Use Copy instead." }
            }, modifier = Modifier.fillMaxWidth()) { Text("Share") }
            feedback?.let { Text(it); TextButton(onClick = { feedback = null }) { Text("Dismiss") } }
            SelectionContainer { Text(combined.orEmpty()) }
        }
        TextButton(onClick = { refresh++ }) { Text("Reload from this phone") }
    }
}
