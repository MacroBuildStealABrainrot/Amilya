package com.woundcopilot.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.woundcopilot.android.ui.*
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Nurse / NP / Doctor mode. Plain logic, tested on a PC. */
internal object ClinicianModes {
    val all = listOf("nurse", "np", "doctor")
    fun isValid(mode: String) = mode in all
    fun title(mode: String) = when (mode) { "nurse" -> "Nurse"; "np" -> "NP"; "doctor" -> "Doctor"; else -> "" }
    /** The note a new report starts as. Nurses keep today's Nursing Note. */
    fun defaultFormat(mode: String) = if (mode == "np" || mode == "doctor") "soap" else "nursing"
    /** What an older server still reads to pick the writing style. */
    fun profession(mode: String) = when (mode) { "np" -> "NP"; "doctor" -> "MD"; else -> "RN" }
    /** Accounts set up before the switch: read the typed profession the same way the server does. */
    fun fromProfession(value: String): String = when (value.trim().uppercase().replace(' ', '_')) {
        "RN", "RPN", "NURSE", "REGISTERED_NURSE" -> "nurse"
        "NP", "NURSE_PRACTITIONER" -> "np"
        "MD", "DOCTOR", "PHYSICIAN" -> "doctor"
        else -> ""
    }
}

/** Office computer inbox. Plain logic, tested on a PC. */
internal object OfficeInbox {
    const val SERVER_UPDATE_NEEDED = "Your Amilya server needs the office computer update first. Use Copy or the one-time computer link for now."
    /** Local midnight tonight, with this phone's time-zone offset, so a note never waits past the clinician's day. */
    fun endOfToday(now: ZonedDateTime = ZonedDateTime.now(ZoneId.systemDefault())): String =
        now.toLocalDate().plusDays(1).atStartOfDay(now.zone).toOffsetDateTime().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
    fun title(format: String, now: ZonedDateTime = ZonedDateTime.now(ZoneId.systemDefault())): String =
        (documentTypeTitle(format) + " · " + now.format(DateTimeFormatter.ofPattern("h:mm a"))).take(80)
    /** Visit ids the inbox accepts: plain letters, digits and dashes, up to 36 characters. */
    fun acceptsVisit(id: String) = id.isNotBlank() && id.length <= 36 && id.all { it.isLetterOrDigit() || it == '-' } && !id.startsWith("local-")
}

/** Small switch at the top of Home. */
@Composable
fun ModeBar(s: AppState) {
    if (s.demo || s.user == null) return
    val current = s.clinicianMode
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("I am a", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ClinicianModes.all.forEach { mode ->
                FilterChip(
                    selected = current == mode,
                    onClick = { if (current != mode) s.setClinicianMode(mode) },
                    label = { Text(ClinicianModes.title(mode)) },
                    enabled = !s.loading,
                )
            }
        }
    }
}

/** Asked once, when no mode has been chosen yet. */
@Composable
fun FirstModeChooser(s: AppState) {
    if (s.demo || s.user == null || s.clinicianMode.isNotBlank() || s.modeChooserDismissed || s.legalRequired) return
    AlertDialog(
        onDismissRequest = { s.modeChooserDismissed = true },
        title = { Text("Which are you?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Amilya writes your notes and answers your questions the way your profession charts. You can change this anytime on Home.")
                WcpButton("Nurse", { s.setClinicianMode("nurse") }, Modifier.fillMaxWidth(), enabled = !s.loading)
                WcpButton("Nurse practitioner", { s.setClinicianMode("np") }, Modifier.fillMaxWidth(), enabled = !s.loading)
                WcpButton("Doctor", { s.setClinicianMode("doctor") }, Modifier.fillMaxWidth(), enabled = !s.loading)
            }
        },
        confirmButton = {},
        dismissButton = { TextButton({ s.modeChooserDismissed = true }) { Text("Later") } },
    )
}

/** "Anything missed?" reminders above the note. Never written into the note by themselves. */
@Composable
fun MissedReminders(s: AppState) {
    val items = s.missedItems
    if (items.isEmpty() || !s.missedCheckOn) return
    GlassPanel(Modifier.fillMaxWidth()) {
        Text("Anything missed?", style = MaterialTheme.typography.titleSmall)
        Text("Not mentioned in this visit. Tap one to add it by voice, or skip.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        items.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { item ->
                    AssistChip(onClick = { s.addMissed(item) }, label = { Text(item.str("label")) },
                        leadingIcon = { InstrumentIcon("add", Modifier.size(16.dp)) }, enabled = !s.loading)
                }
            }
        }
        TextButton({ s.skipMissed() }, enabled = !s.loading) { Text("Skip") }
    }
}

/** Account settings for the two new features. */
@Composable
fun NoteHelperSettings(s: AppState) {
    if (s.demo) return
    SectionLabel("Note helpers")
    SettingSwitch("\"Anything missed?\" reminders", "After each note, list topics you didn't mention. Nothing is added unless you say it.",
        s.missedCheckOn, !s.loading) { s.setMissedCheck(it) }
    SettingSwitch("Send notes to my office computer", "Finished notes appear on your signed-in computer. Erased there after you copy them, or at the end of the day.",
        s.officeComputerOn, !s.loading) { s.setOfficeComputer(it) }
    if (s.officeComputerOn) Text("On your computer, open ${s.deskAddress} and sign in once.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SettingSwitch(title: String, detail: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}
