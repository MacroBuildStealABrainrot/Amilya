package com.woundcopilot.android

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.woundcopilot.android.ui.*

/** Presentation models deliberately contain no network or persistence behavior. */
data class SimpleSavedWork(val id: String, val title: String, val detail: String = "")
data class PhotoAssessmentFinding(
    val title: String,
    val text: String,
    val sourceLabel: String = "Photo",
    val sourceQuote: String = "",
    val sourceId: String = "",
)
data class ConsultantMessage(
    val id: String,
    val text: String,
    val fromClinician: Boolean,
    val effort: String = "",
    val sourceSummary: String = "",
)
data class ConsultantAttachments(
    val photoCount: Int = 0,
    val fileCount: Int = 0,
    val recordingCount: Int = 0,
    val otherCount: Int = 0,
)

@Composable
fun StartScreen(
    savedWork: List<SimpleSavedWork> = emptyList(),
    busy: Boolean = false,
    onDocument: () -> Unit,
    onPhoto: () -> Unit,
    onConsultant: () -> Unit,
    onOpenWork: (String) -> Unit,
    onAccount: () -> Unit,
    onSavedWork: (() -> Unit)? = null,
    onSendAll: (() -> Unit)? = null,
    recovery: @Composable () -> Unit = {},
) {
    val c = MaterialTheme.colorScheme
    Page {
        // Three icon-only actions. Unfinished recordings stay reachable in Saved work > Recovery.
        Spacer(Modifier.height(40.dp))
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            StartAction("Document", "Speak or write. Get your report.", "record", c.primary, !busy, onDocument)
            StartAction("Photo Assessment", "Add a photo. See visible findings.", "photo", c.primary, !busy, onPhoto)
            StartAction("Ask Consultant", "Ask a question. Talk it through.", "kosmo", c.primary, !busy, onConsultant)
        }
        if (false && (onSavedWork != null || onSendAll != null)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                if (onSavedWork == null) Spacer(Modifier.weight(1f))
                onSavedWork?.let { TextButton(onClick = it, enabled = !busy) { InstrumentIcon("document", Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Saved work") } }
                onSendAll?.let { TextButton(onClick = it, enabled = !busy) { Text("Send All") } }
            }
        }
        if (false && savedWork.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text("Recent work", style = MaterialTheme.typography.titleSmall, color = c.onSurfaceVariant, modifier = Modifier.semantics { heading() })
            // Home is a preview; the complete history remains in Saved work.
            savedWork.take(1).forEach { work ->
                Surface(
                    onClick = { onOpenWork(work.id) },
                    enabled = !busy,
                    color = Color.Transparent,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().testTag("recent-work-${work.id}"),
                ) {
                    Row(Modifier.padding(vertical = 12.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        InstrumentIcon("document", Modifier.size(23.dp), c.onSurfaceVariant)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(work.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (work.detail.isNotBlank()) Text(work.detail, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        InstrumentIcon("arrow", Modifier.size(19.dp), c.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun StartAction(title: String, detail: String, icon: String, accent: Color, enabled: Boolean, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .98f else 1f, spring(dampingRatio = .85f, stiffness = 700f), label = "Start action press")
    GlassPanel(
        Modifier.fillMaxWidth().graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (enabled) 1f else .55f }
            .clip(RoundedCornerShape(26.dp))
            .clickable(interactionSource = interaction, indication = ripple(), enabled = enabled, role = Role.Button, onClick = onClick),
        accent = accent,
    ) {
        // Icon only, centred; the title and description stay as the screen-reader label.
        Box(Modifier.fillMaxWidth().heightIn(min = 112.dp).semantics(mergeDescendants = true) { contentDescription = "$title. $detail" },
            contentAlignment = Alignment.Center) {
            InstrumentIcon(icon, Modifier.size(52.dp), accent)
        }
    }
}

@Composable
fun PhotoAssessmentScreen(
    findings: List<PhotoAssessmentFinding>,
    warnings: List<String> = emptyList(),
    sourceLabel: String = "Photo",
    busy: Boolean = false,
    onDone: () -> Unit,
    onAddToReport: () -> Unit,
    onAskConsultant: () -> Unit,
    onAddPhoto: (() -> Unit)? = null,
    photoPreview: (@Composable () -> Unit)? = null,
    onViewSource: ((String) -> Unit)? = null,
    qualityInsufficient: Boolean = false,
    retakeReason: String = "",
    onRetake: (() -> Unit)? = null,
) {
    val c = MaterialTheme.colorScheme
    val validFindings = findings.filter { it.text.isNotBlank() }
    val hasAssessment = validFindings.isNotEmpty() && !qualityInsufficient
    val needsAnotherPhoto = qualityInsufficient || (!hasAssessment && warnings.any { it.isNotBlank() })
    Page {
        InstrumentTitle("Photo assessment")
        photoPreview?.invoke()
        if (needsAnotherPhoto) {
            GlassPanel(Modifier.fillMaxWidth()) {
                InstrumentIcon("photo", Modifier.size(32.dp), c.onSurfaceVariant)
                Text("Photo isn't clear enough for assessment.", style = MaterialTheme.typography.titleLarge)
                if (retakeReason.isNotBlank()) Text(retakeReason, style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
                (onRetake ?: onAddPhoto)?.let { WcpButton("Retake", it, Modifier.fillMaxWidth(), enabled = !busy) }
            }
            TextButton(onClick = onDone, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Done") }
        } else if (hasAssessment) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InstrumentIcon("photo", Modifier.size(18.dp), c.onSurfaceVariant)
                Text(sourceLabel, style = MaterialTheme.typography.labelMedium, color = c.onSurfaceVariant)
            }
            if (validFindings.isNotEmpty()) {
                GlassPanel(Modifier.fillMaxWidth()) {
                    Text("Suggested visible findings", style = MaterialTheme.typography.labelLarge, color = c.primary)
                    validFindings.forEachIndexed { index, finding ->
                        if (index > 0) HorizontalDivider(color = c.outlineVariant.copy(alpha = .6f))
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(finding.title, style = MaterialTheme.typography.titleMedium)
                            SelectionContainer { Text(finding.text, style = MaterialTheme.typography.bodyLarge) }
                            if (finding.sourceQuote.isNotBlank() || (onViewSource != null && finding.sourceId.isNotBlank())) {
                                DetailDisclosure("Source", finding.sourceLabel) {
                                    if (finding.sourceQuote.isNotBlank()) Text(finding.sourceQuote, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                                    if (onViewSource != null && finding.sourceId.isNotBlank()) TextButton(onClick = { onViewSource(finding.sourceId) }) { Text("View photo") }
                                }
                            }
                        }
                    }
                }
            }
            if (warnings.isNotEmpty()) Surface(color = c.surfaceContainerLow, shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, c.outlineVariant)) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Limits & concerns", style = MaterialTheme.typography.titleSmall, color = c.onSurface)
                    warnings.filter { it.isNotBlank() }.distinct().forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant) }
                }
            }
            WcpButton("Done", onDone, Modifier.fillMaxWidth())
            WcpButton("Add to Report", onAddToReport, Modifier.fillMaxWidth(), enabled = !busy, secondary = true)
            WcpButton("Ask Consultant", onAskConsultant, Modifier.fillMaxWidth(), enabled = !busy, secondary = true)
        } else if (busy) {
            GlassPanel(Modifier.fillMaxWidth(), accent = c.tertiary) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("Assessing the photo", style = MaterialTheme.typography.titleMedium)
            }
            TextButton(onClick = onDone, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Done") }
        } else {
            GlassPanel(Modifier.fillMaxWidth(), accent = c.tertiary) {
                InstrumentIcon("photo", Modifier.size(40.dp), c.tertiary)
                Text("Start with a photo", style = MaterialTheme.typography.headlineSmall)
                Text("Visible findings only. No history or report required.", style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
                onAddPhoto?.let { WcpButton("Add photo", it, Modifier.fillMaxWidth()) }
            }
            TextButton(onClick = onDone, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Done") }
        }
    }
}

/** Increment draftResetKey only after a successful send and persist drafts through onDraftChange. */
@Composable
fun ConsultantChatScreen(
    conversationKey: String,
    messages: List<ConsultantMessage>,
    initialDraft: String = "",
    draftResetKey: Int = 0,
    busy: Boolean = false,
    attachmentSummary: String = "",
    onDraftChange: (String) -> Unit = {},
    onSend: (String) -> Unit,
    onAddPhoto: () -> Unit,
    onAddFiles: () -> Unit,
    onRecord: () -> Unit,
    onDone: () -> Unit,
    onOpenReport: (() -> Unit)? = null,
    attachments: ConsultantAttachments = ConsultantAttachments(),
    onVoiceMessage: (() -> Unit)? = null,
    errorText: String? = null,
    onRetry: (() -> Unit)? = null,
) {
    val c = MaterialTheme.colorScheme
    var draft by rememberSaveable(conversationKey, draftResetKey) { mutableStateOf(initialDraft) }
    var attachmentsOpen by rememberSaveable(conversationKey) { mutableStateOf(false) }
    val scroll = rememberLazyListState()
    // Do not pull the reader away from an older message when a response finishes.
    LaunchedEffect(messages.size, busy) {
        val lastVisible = scroll.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        if (messages.isNotEmpty() && (messages.size <= 2 || lastVisible >= messages.size - 2)) {
            scroll.animateScrollToItem(messages.lastIndex)
        }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 780.dp).fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 12.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(42.dp).background(c.primaryContainer, CircleShape), contentAlignment = Alignment.Center) { InstrumentIcon("kosmo", Modifier.size(26.dp), c.primary) }
                Text("Ask Consultant", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).semantics { heading() })
                onOpenReport?.let { TextButton(onClick = it, enabled = !busy) { Text("Report") } }
                TextButton(onClick = onDone) { Text("Done") }
            }
            HorizontalDivider(color = c.outlineVariant.copy(alpha = .5f))
            if (messages.isEmpty()) {
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.Center) {
                    Text("Talk it through.", style = MaterialTheme.typography.headlineLarge.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Normal, fontSize = 34.sp))
                    Spacer(Modifier.height(10.dp))
                    Text("Ask your question. Add only the information you want to share.", style = MaterialTheme.typography.bodyLarge, color = c.onSurfaceVariant)
                    Spacer(Modifier.height(22.dp))
                    listOf("Why might this wound not be healing?", "What should I consider next?").forEach { prompt ->
                        Surface(onClick = { draft = prompt; onDraftChange(prompt) }, enabled = !busy, color = c.surfaceContainerLow, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                            Row(Modifier.padding(15.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(prompt, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                InstrumentIcon("arrow", Modifier.size(18.dp), c.onSurfaceVariant)
                            }
                        }
                    }
                }
            } else {
                LazyColumn(state = scroll, modifier = Modifier.weight(1f).fillMaxWidth().testTag("consultant-conversation"), contentPadding = PaddingValues(horizontal = 18.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(messages, key = { it.id }) { message -> ConsultantBubble(message) }
                    if (busy) item("consultant-progress") {
                        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 2.dp)
                            Text("Thinking through your question", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                        }
                    }
                }
            }
            Surface(color = c.surfaceContainerLow, tonalElevation = 2.dp) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (!busy && !errorText.isNullOrBlank()) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp).semantics { liveRegion = LiveRegionMode.Polite }) {
                            Text(errorText, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                            onRetry?.let { TextButton(onClick = it) { Text("Try Again") } }
                        }
                    }
                    AttachmentIndicators(attachments, attachmentSummary)
                    if (attachmentsOpen) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        AttachmentAction("Photo", "photo", !busy, onAddPhoto, Modifier.weight(1f))
                        AttachmentAction("Add Files", "document", !busy, onAddFiles, Modifier.weight(1f))
                        AttachmentAction("Record", "record", !busy, onRecord, Modifier.weight(1f))
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TouchIcon(if (attachmentsOpen) "close" else "add", if (attachmentsOpen) "Close attachments" else "Add photo, files or recording", { attachmentsOpen = !attachmentsOpen })
                        OutlinedTextField(
                            value = draft,
                            onValueChange = { draft = it; onDraftChange(draft) },
                            enabled = !busy,
                            placeholder = { Text("Message…") },
                            modifier = Modifier.weight(1f).testTag("consultant-composer"),
                            shape = RoundedCornerShape(24.dp),
                            maxLines = 5,
                            isError = draft.length > 2000,
                            supportingText = if(draft.length > 2000) {{Text("${draft.length}/2,000 — shorten this message to send.")}} else null,
                        )
                        onVoiceMessage?.let { TouchIcon("record", "Voice message", it, enabled = !busy) }
                        TouchIcon("arrow", "Send message", { onSend(draft.trim()) }, enabled = draft.isNotBlank() && draft.length <= 2000 && !busy)
                    }
                }
            }
        }
    }
}

@Composable
private fun ConsultantBubble(message: ConsultantMessage) {
    val c = MaterialTheme.colorScheme
    val paragraphs = remember(message.text, message.fromClinician) {
        if (message.fromClinician) listOf(message.text) else consultantDisplayChunks(message.text)
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (message.fromClinician) Arrangement.End else Arrangement.Start) {
        Column(Modifier.fillMaxWidth(if (message.fromClinician) .89f else .96f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!message.fromClinician) Text("Consultant", Modifier.padding(start = 4.dp), style = MaterialTheme.typography.labelSmall, color = c.primary)
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    paragraphs.forEachIndexed { index, paragraph ->
                        Surface(
                            modifier = Modifier.fillMaxWidth().testTag("chat-bubble-${message.id}-$index"),
                            shape = if (message.fromClinician) RoundedCornerShape(22.dp, 22.dp, 6.dp, 22.dp) else RoundedCornerShape(6.dp, 22.dp, 22.dp, 22.dp),
                            color = if (message.fromClinician) c.primaryContainer else c.surfaceContainerLow,
                        ) {
                            Text(paragraph.trim(), Modifier.padding(horizontal = 17.dp, vertical = 13.dp),
                                style = MaterialTheme.typography.bodyLarge, color = if (message.fromClinician) c.onPrimaryContainer else c.onSurface)
                        }
                    }
                }
            }
        }
    }
}

/** Authored paragraphs stay together where practical. Every character is retained.
 * The bound prevents pathological imported text creating hundreds of UI elements. */
internal fun consultantDisplayChunks(text: String): List<String> {
    if (text.isEmpty()) return listOf("")
    val chunks = mutableListOf<String>()
    var start = 0
    val paragraphBreak = Regex("""\n[ \t]*\n+""")
    val sentenceBreak = Regex("""[.!?]["')]*\s+""")
    while (start < text.length && chunks.size < 31) {
        var limit = minOf(start + 850, text.length)
        if (limit < text.length && Character.isHighSurrogate(text[limit - 1]) && Character.isLowSurrogate(text[limit])) limit--
        val authored = paragraphBreak.find(text, start)
        val end = when {
            authored != null && authored.range.last + 1 <= limit -> authored.range.last + 1
            limit == text.length -> text.length
            else -> {
                val window = text.substring(start, limit)
                val sentence = sentenceBreak.findAll(window).lastOrNull { it.range.last >= 300 }
                if (sentence != null) start + sentence.range.last + 1
                else {
                    val whitespace = window.indexOfLast { it.isWhitespace() }
                    if (whitespace >= 300) start + whitespace + 1 else limit
                }
            }
        }
        chunks += text.substring(start, end)
        start = end
    }
    if (start < text.length) chunks += text.substring(start)
    return chunks
}

@Composable
private fun AttachmentIndicators(attachments: ConsultantAttachments, legacySummary: String) {
    val counts = listOf(
        Triple("photo", "photo", attachments.photoCount),
        Triple("document", "file", attachments.fileCount),
        Triple("record", "recording", attachments.recordingCount),
        Triple("add", "attachment", attachments.otherCount),
    ).filter { it.third > 0 }
    val shown = if (counts.isNotEmpty() || legacySummary.isBlank()) counts else {
        val count = Regex("""^\s*(\d+)""").find(legacySummary)?.groupValues?.get(1)?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        listOf(Triple("document", "attachment", count))
    }
    if (shown.isNotEmpty()) Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        shown.forEach { (icon, label, count) ->
            Row(Modifier.clearAndSetSemantics { contentDescription = "$count $label${if (count == 1) "" else "s"} attached" },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                InstrumentIcon(icon, Modifier.size(17.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                Text(count.toString(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun AttachmentAction(label: String, icon: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    TextButton(onClick = onClick, enabled = enabled, modifier = modifier) {
        InstrumentIcon(icon, Modifier.size(18.dp), if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(7.dp))
        Text(label)
    }
}

@Composable
fun TransferDialog(url: String, pin: String, expiresLabel: String, onDismiss: () -> Unit, onShare: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Open on your work phone") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Open the link in a browser, then enter this one-time code.")
                SelectionContainer { Text(pin, style = MaterialTheme.typography.headlineLarge.copy(fontFamily = FontFamily.Monospace, letterSpacing = 4.sp)) }
                SelectionContainer { Text(url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                if (expiresLabel.isNotBlank()) Text(expiresLabel, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onShare) { Text("Share link") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}
