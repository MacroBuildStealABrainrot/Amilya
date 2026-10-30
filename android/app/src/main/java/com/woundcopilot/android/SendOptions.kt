package com.woundcopilot.android

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.PersistableBundle
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.woundcopilot.android.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One Send button for a report or an assessment. Every option opens the phone's own app with the
 * text filled in; nothing is sent until the clinician taps Send there.
 */
@Composable
fun SendOptions(
    text: String,
    title: String,
    busy: Boolean,
    onComputer: (() -> Unit)?,
    onMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
    computerLabel: String = "Computer",
    computerDetail: String = "Computer gives a one-time link and code to open on any computer.",
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var phone by remember { mutableStateOf(false) }
    var number by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    WcpButton("Send", { open = true; phone = false; problem = null }, modifier.fillMaxWidth(), enabled = text.isNotBlank() && !busy)
    if (!open) return
    AlertDialog(
        onDismissRequest = { open = false; number = "" },
        title = { Text(if (phone) "Send by text message" else "Send $title") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (phone) {
                    OutlinedTextField(number, { number = it; problem = null }, label = { Text("Phone number") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth())
                    Text("Messages opens with the text filled in. Check it, then tap Send there.", style = MaterialTheme.typography.bodySmall)
                } else {
                    WcpButton("Text message", { phone = true; problem = null }, Modifier.fillMaxWidth(), secondary = true)
                    WcpButton("Another app", {
                        scope.launch {
                            try {
                                val uri = withContext(Dispatchers.IO) { ExportTools.createPdf(context, text, title) }
                                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                    type = "application/pdf"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    putExtra(Intent.EXTRA_TEXT, text)
                                    putExtra(Intent.EXTRA_SUBJECT, title)
                                    clipData = ClipData.newRawUri(title, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }, "Send $title"))
                                open = false
                            } catch (_: ActivityNotFoundException) {
                                problem = "No app on this phone can receive it. Use Copy instead."
                            } catch (_: Exception) {
                                problem = "The file could not be prepared. Use Copy instead."
                            }
                        }
                    }, Modifier.fillMaxWidth(), secondary = true)
                    if (onComputer != null) WcpButton(computerLabel, { open = false; onComputer() }, Modifier.fillMaxWidth(), secondary = true)
                    WcpButton("Copy", { copySensitive(context, text, title); open = false; onMessage("$title copied.") }, Modifier.fillMaxWidth(), secondary = true)
                    if (onComputer != null) Text(computerDetail,
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            if (phone) TextButton(enabled = phoneRecipient(number) != null, onClick = {
                try { context.startActivity(reportMessageIntent(number, text)); open = false; number = "" }
                catch (_: ActivityNotFoundException) { problem = "No text messaging app is installed. Use Another app instead." }
                catch (_: Exception) { problem = "Messages could not be opened. Nothing was lost." }
            }) { Text("Open Messages") }
        },
        dismissButton = {
            TextButton({ if (phone) { phone = false; problem = null } else { open = false; number = "" } }) { Text(if (phone) "Back" else "Cancel") }
        },
    )
}

internal fun copySensitive(context: Context, text: String, label: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText(label, text)
    clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
    clipboard.setPrimaryClip(clip)
}
