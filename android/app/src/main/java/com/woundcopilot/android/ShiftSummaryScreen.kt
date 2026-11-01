package com.woundcopilot.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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

/** Read-only local screen. No runTask, API, diagnostics, sending, upload, AI or billing path. */
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
    val combined = remember(plan, selected) { plan?.let { renderShiftSummary(it, selected) } }

    Page {
        InstrumentTitle("End-of-shift summary")
        Text("${clock.first} · ${clock.second.id}")
        Text("Only visits already saved or loaded on this phone are available. This works offline and uses no AI. Reports are copied exactly as saved.")
        Text("Finished means Review and sign is complete. Saved drafts are listed separately. The latest saved version is used.")
        if (plan == null) {
            Text("The saved visit list could not be checked. Nothing was copied. Check the phone’s free space and restart the app. Check your visits before using the summary.", color = MaterialTheme.colorScheme.error)
        } else {
            // These are part of the document, not fading notifications.
            Text("No finished report", style = MaterialTheme.typography.titleMedium)
            if (plan.unfinished.isEmpty()) Text("None among the visits available on this phone.")
            plan.unfinished.forEach {
                Text(shiftVisitHeading(it, plan.zone))
                Text(if (it.reports.isEmpty()) "No report is saved." else "Only an unfinished report is saved. Review and sign it when ready.")
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
            feedback?.let { Text(it); TextButton(onClick = { feedback = null }) { Text("Dismiss") } }
            SelectionContainer { Text(combined.orEmpty()) }
        }
        TextButton(onClick = { refresh++ }) { Text("Reload from this phone") }
    }
}
