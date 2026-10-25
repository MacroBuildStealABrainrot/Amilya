package com.woundcopilot.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.woundcopilot.android.AppState
import com.woundcopilot.android.items
import com.woundcopilot.android.obj
import com.woundcopilot.android.str
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.time.LocalDate

private val clinicalFields = listOf(
    "narrative", "wound_measurement", "wound_location", "pain", "exudate", "odour",
    "undermining", "tunnelling", "periwound", "wound_edges", "wound_tissue",
    "perfusion_assessment", "offloading_assessment", "compression", "treatment_performed",
    "patient_education", "vascular_test", "lab", "culture", "imaging", "history", "allergy",
    "medication", "nutrition", "mobility", "infection_findings", "fever", "chills", "diabetes",
    "renal_disease", "edema", "smoking", "pressure_exposure", "glucose_control"
)

private val lateralityOptions = listOf("" to "Not recorded", "left" to "Left", "right" to "Right",
    "bilateral" to "Bilateral", "midline" to "Midline", "not_applicable" to "Not applicable")

private fun fieldLabel(name: String): String = when (name) {
    "narrative" -> "Clinical observation"
    "wound_measurement" -> "Wound dimensions"
    "wound_tissue" -> "Wound bed tissue"
    "offloading_assessment" -> "Pressure and offloading"
    "lab" -> "Laboratory result"
    else -> name.replace('_', ' ').replaceFirstChar { it.uppercase() }
}

private data class FindingInput(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "narrative",
    val text: String = "",
    val presence: String = "",
    val observedOn: String = "",
    val length: String = "",
    val width: String = "",
    val depth: String = "",
    val score: String = "",
    val site: String = "",
    val laterality: String = "",
    val granulation: String = "",
    val slough: String = "",
    val eschar: String = "",
    val epithelial: String = ""
) {
    fun json() = obj("id" to id, "name" to name, "text" to text, "presence" to presence, "observed_on" to observedOn,
        "length" to length, "width" to width, "depth" to depth, "score" to score,
        "site" to site, "laterality" to laterality, "granulation" to granulation,
        "slough" to slough, "eschar" to eschar, "epithelial" to epithelial)

    fun value(): JSONObject {
        require(name in clinicalFields) { "Choose a supported clinical field." }
        return when (name) {
            "wound_measurement" -> obj(
                "length_cm" to number(length, "Length", 200.0, false),
                "width_cm" to number(width, "Width", 200.0, false)
            ).apply { if (depth.isNotBlank()) put("depth_cm", number(depth, "Depth", 100.0)) }
            "wound_location" -> {
                require(site.trim().isNotEmpty() && site.trim().length <= 200) { "Enter a location in 1-200 characters." }
                require(lateralityOptions.any { it.first == laterality }) { "Choose a supported laterality." }
                obj("site" to site.trim()).apply { if (laterality.isNotBlank()) put("laterality", laterality) }
            }
            "wound_tissue" -> {
                require(text.trim().length <= 4000) { "Keep the tissue description within 4,000 characters." }
                val result = obj()
                if (text.isNotBlank()) result.put("text", text.trim())
                var total = 0.0
                listOf("granulation" to granulation, "slough" to slough, "eschar" to eschar,
                    "epithelial" to epithelial).forEach { (name, raw) ->
                    if (raw.isNotBlank()) {
                        val amount = number(raw, fieldLabel(name), 100.0)
                        total += amount
                        result.put(name, amount)
                    }
                }
                require(total <= 100.001) { "Tissue percentages cannot total more than 100%." }
                require(result.length() > 0) { "Describe the tissue or enter an assessed percentage." }
                result
            }
            else -> {
                require(text.trim().length <= 8000) { "Keep this observation within 8,000 characters." }
                val result = obj()
                if (text.isNotBlank()) result.put("text", text.trim())
                when (presence) {
                    "present" -> result.put("present", true)
                    "absent" -> result.put("present", false)
                }
                if (name == "pain" && score.isNotBlank()) result.put("score_0_10", number(score, "Pain score", 10.0))
                require(result.length() > 0) {
                    "Add an observation${if (name == "pain") ", pain score," else ""} or explicitly select present or absent. Unknown remains unrecorded."
                }
                if (observedOn.isNotBlank()) {
                    val date = observedOn.trim()
                    require(Regex("\\d{4}-\\d{2}-\\d{2}").matches(date) && runCatching { LocalDate.parse(date) }.isSuccess) {
                        "Use a valid observation date in YYYY-MM-DD format, or leave it empty."
                    }
                    result.put("observed_on", date)
                }
                result
            }
        }
    }

    companion object {
        fun restore(value: JSONObject) = FindingInput(
            id = value.str("id").ifBlank { UUID.randomUUID().toString() },
            name = value.str("name", "narrative").takeIf { it in clinicalFields } ?: "narrative",
            text = value.str("text"), presence = value.str("presence"), observedOn = value.str("observed_on"), length = value.str("length"),
            width = value.str("width"), depth = value.str("depth"), score = value.str("score"),
            site = value.str("site"), laterality = value.str("laterality"), granulation = value.str("granulation"),
            slough = value.str("slough"), eschar = value.str("eschar"), epithelial = value.str("epithelial")
        )

        private fun number(raw: String, label: String, maximum: Double, zeroAllowed: Boolean = true): Double {
            val value = raw.trim().replace(',', '.').toDoubleOrNull()
            require(value != null && value.isFinite() && value <= maximum &&
                if (zeroAllowed) value >= 0.0 else value > 0.0) {
                "$label must be ${if (zeroAllowed) "0 or more" else "greater than 0"} and no more than ${maximum.toInt()}."
            }
            return value!!
        }
    }
}

@Composable
fun EvidenceForm(state: AppState, kind: String) {
    val owner = state.user?.str("id") ?: "signed-out"
    val storageKey = "evidence-form-$owner-${state.encounterId}-$kind"
    key(storageKey) { EvidenceFormContent(state, kind, storageKey) }
}

@Composable
private fun EvidenceFormContent(state: AppState, kind: String, storageKey: String) {
    val restored = remember { state.vault.read(storageKey)?.let { runCatching { JSONObject(it) }.getOrNull() } }
    var sourceText by remember { mutableStateOf(restored?.str("text") ?: "") }
    var fields by remember {
        mutableStateOf(restored?.items("fields")?.map { FindingInput.restore(it) }?.takeIf { it.isNotEmpty() }
            ?: listOf(FindingInput(name = if (kind == "measurement") "wound_measurement" else "narrative")))
    }
    var validation by remember { mutableStateOf<String?>(null) }
    var recoveryError by remember { mutableStateOf(false) }
    var recovered by remember { mutableStateOf(restored != null) }
    var localSaved by remember { mutableStateOf(restored != null) }
    val submissionRevision = remember { mutableStateOf<Int?>(null) }
    val attested = kind == "manual" || kind == "measurement"
    val supported = kind in listOf("manual", "measurement", "typed", "transcript", "result")
    val snapshot = obj("text" to sourceText, "fields" to JSONArray().apply { fields.forEach { put(it.json()) } }).toString()
    val latestSnapshot by rememberUpdatedState(snapshot)

    LaunchedEffect(snapshot) {
        localSaved = false
        delay(180)
        try {
            withContext(Dispatchers.IO) { state.vault.write(storageKey, snapshot) }
            localSaved = true
            recoveryError = false
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            recoveryError = true
        }
    }
    DisposableEffect(storageKey) {
        onDispose {
            // A successful add navigates away after advancing the encounter revision.
            // A failed request or a back action retains the encrypted recovery copy.
            runCatching {
                val submitted = submissionRevision.value
                if (submitted != null && state.revision > submitted) {
                    state.vault.remove(storageKey)
                } else state.vault.write(storageKey, latestSnapshot)
            }.onFailure { state.notice = "The recovery copy could not be saved on this device." }
        }
    }

    fun updateField(value: FindingInput) {
        fields = fields.map { if (it.id == value.id) value else it }
        validation = null
        localSaved = false
        recovered = false
    }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        InstrumentTitle(when(kind){"measurement"->"Measurements";"manual"->"Assessment";"transcript"->"Transcript";"result"->"Clinical result";else->"Written note"})
        val wound = state.case?.optJSONObject("wound")
        if (recovered) StatusTag("Recovered unsent entry", "info")
        if (validation != null) ErrorBanner(validation!!) { validation = null }
        if (recoveryError) ErrorBanner("Local recovery could not be saved. Keep this screen open until your entry is saved to the encounter.") { recoveryError = false }
        if (!supported) {
            EmptyState("Unsupported evidence type", "Return to the encounter and choose a supported entry type.")
        } else if (attested) {
            fields.forEachIndexed { index, field ->
                key(field.id) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (kind == "manual") {
                            SectionLabel("Finding ${index + 1}", if (fields.size > 1) "Remove" else null) {
                                if (!state.loading) { fields = fields.filterNot { it.id == field.id }; localSaved = false }
                            }
                            ChoiceField("Clinical field", field.name, clinicalFields.map { it to fieldLabel(it) }, !state.loading, true) {
                                updateField(field.copy(name = it))
                            }
                        }
                        FindingFields(field, !state.loading, ::updateField)
                    }
                }
                if (index < fields.lastIndex) HorizontalDivider(Modifier.padding(vertical = 8.dp))
            }
            if (kind == "manual") WcpButton("Add another finding", {
                fields = fields + FindingInput()
                localSaved = false
            }, Modifier.fillMaxWidth(), enabled = !state.loading && fields.size < 100, secondary = true)
            Text("By adding ${if (fields.size == 1) "this finding" else "these findings"}, you confirm this reflects your assessment.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            OutlinedTextField(sourceText, {
                sourceText = it; validation = null; localSaved = false; recovered = false
            }, Modifier.fillMaxWidth(), enabled = !state.loading,
                label = { Text(if (kind == "transcript") "Transcript text" else if (kind == "result") "Result or report text" else "Source notes") },
                minLines = 8, maxLines = 16, isError = sourceText.length > 40000,
                supportingText = { if(sourceText.length>39000)Text("${sourceText.length.toLocaleStringSafe()} / 40,000 characters") })
        }
        if (supported) {
            if (state.loading) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator()
                Text("Adding evidence…", style = MaterialTheme.typography.bodyMedium)
            }
            WcpButton(if (state.loading) "Saving…" else if (attested) "Add confirmed ${if (fields.size == 1) "finding" else "findings"}" else if(state.quickMode=="document")"Create report" else if(state.quickMode=="consultant")"Add to conversation" else "Add source evidence", {
                try {
                    val payload = obj("kind" to kind, "wound_id" to wound?.str("id")?.ifBlank { null })
                    if (attested) {
                        val findings = JSONArray()
                        fields.forEachIndexed { index, field ->
                            val value = try { field.value() } catch (error: IllegalArgumentException) {
                                throw IllegalArgumentException("${if (fields.size > 1) "Finding ${index + 1} · " else ""}${error.message}")
                            }
                            findings.put(obj("name" to field.name, "value" to value))
                        }
                        payload.put("findings", findings)
                    } else {
                        require(sourceText.isNotBlank()) { "Enter the source text before saving." }
                        require(sourceText.length <= 40000) { "Keep source text within 40,000 characters." }
                        payload.put("text", sourceText)
                    }
                    state.vault.write(storageKey, latestSnapshot)
                    submissionRevision.value = state.revision
                    state.addEvidence(payload)
                } catch (error: IllegalArgumentException) { validation = error.message }
                catch (_: Exception) { recoveryError = true }
            }, Modifier.fillMaxWidth(), enabled = !state.loading)
        }
        Text(if (localSaved) "Draft saved on this device" else "Saving draft…",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
    }
}

private fun Int.toLocaleStringSafe(): String = java.text.NumberFormat.getIntegerInstance().format(this)

@Composable
private fun FindingFields(field: FindingInput, enabled: Boolean, onChange: (FindingInput) -> Unit) {
    when (field.name) {
        "wound_measurement" -> {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NumberField("Length (cm)", field.length, enabled, Modifier.weight(1f)) { onChange(field.copy(length = it)) }
                NumberField("Width (cm)", field.width, enabled, Modifier.weight(1f)) { onChange(field.copy(width = it)) }
            }
            NumberField("Depth (cm) · optional", field.depth, enabled) { onChange(field.copy(depth = it)) }
            Text("Leave depth blank if not measured.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        "wound_location" -> {
            OutlinedTextField(field.site, { onChange(field.copy(site = it)) }, Modifier.fillMaxWidth(), enabled = enabled,
                label = { Text("Anatomical site") }, singleLine = true)
            ChoiceField("Laterality", field.laterality, lateralityOptions, enabled) { onChange(field.copy(laterality = it)) }
        }
        "wound_tissue" -> {
            ObservationText(field.text, enabled, "Tissue description", 4000) { onChange(field.copy(text = it)) }
            Text("Assessed percentages · optional", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NumberField("Granulation %", field.granulation, enabled, Modifier.weight(1f)) { onChange(field.copy(granulation = it)) }
                NumberField("Slough %", field.slough, enabled, Modifier.weight(1f)) { onChange(field.copy(slough = it)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NumberField("Eschar %", field.eschar, enabled, Modifier.weight(1f)) { onChange(field.copy(eschar = it)) }
                NumberField("Epithelial %", field.epithelial, enabled, Modifier.weight(1f)) { onChange(field.copy(epithelial = it)) }
            }
            Text("Leave unassessed percentages empty. The entered total cannot exceed 100%.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        else -> {
            ObservationText(field.text, enabled, "Your observation", 8000) { onChange(field.copy(text = it)) }
            if (field.name == "pain") NumberField("Pain score / 10 · optional", field.score, enabled) { onChange(field.copy(score = it)) }
            DetailDisclosure("Additional details",initiallyOpen=field.presence.isNotBlank()||field.observedOn.isNotBlank()){
            ChoiceField("Presence, when applicable", field.presence,
                listOf("" to "Not recorded", "present" to "Present", "absent" to "Absent", "unknown" to "Unknown"), enabled) {
                onChange(field.copy(presence = it))
            }
            if (field.presence == "unknown") Text("Unknown adds no presence value. Describe what is known, or remove this finding if it has not been assessed.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(field.observedOn, { onChange(field.copy(observedOn = it)) }, Modifier.fillMaxWidth(),
                enabled = enabled, label = { Text("Observed on · optional") }, placeholder = { Text("YYYY-MM-DD") },
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii))
            }
        }
    }
}

@Composable
private fun ObservationText(value: String, enabled: Boolean, label: String, maximum: Int, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, Modifier.fillMaxWidth(), enabled = enabled, label = { Text(label) },
        minLines = 3, maxLines = 8, isError = value.trim().length > maximum,
        supportingText = { if (value.length > maximum - 500) Text("${value.length.toLocaleStringSafe()} / ${maximum.toLocaleStringSafe()} characters") })
}

@Composable
private fun NumberField(label: String, value: String, enabled: Boolean, modifier: Modifier = Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, modifier.fillMaxWidth(), enabled = enabled, label = { Text(label) },
        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
}

@Composable
private fun ChoiceField(label: String, selected: String, options: List<Pair<String, String>>, enabled: Boolean,
    searchable: Boolean = false, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        WcpButton(options.firstOrNull { it.first == selected }?.second ?: "Choose", { open = true; query = "" },
            Modifier.fillMaxWidth(), enabled = enabled, secondary = true)
    }
    if (open) AlertDialog(onDismissRequest = { open = false }, title = { Text(label) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (searchable) OutlinedTextField(query, { query = it }, label = { Text("Find a field") }, singleLine = true)
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                val filtered = options.filter { it.second.contains(query, ignoreCase = true) || it.first.contains(query, ignoreCase = true) }
                filtered.forEach { (value, title) ->
                    TextButton(onClick = { onSelect(value); open = false }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(if (selected == value) "$title · selected" else title, Modifier.fillMaxWidth())
                    }
                }
                if (filtered.isEmpty()) Text("No matching fields", Modifier.padding(vertical = 16.dp))
            }
        }
    }, confirmButton = { TextButton(onClick = { open = false }) { Text("Close") } })
}
