package com.woundcopilot.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.woundcopilot.android.obj
import com.woundcopilot.android.str
import org.json.JSONObject
import java.time.LocalDate

private val correctionLaterality = listOf(
    "" to "Unknown / not recorded", "left" to "Left", "right" to "Right", "bilateral" to "Bilateral",
    "midline" to "Midline", "not_applicable" to "Not applicable",
)
private val correctionPresence = listOf("" to "Unknown / not assessed", "present" to "Present", "absent" to "Absent")

/** Every field accepted by the current clinical value schemas has a corresponding control. */
internal data class FindingCorrection(
    val text: String = "",
    val length: String = "",
    val width: String = "",
    val depth: String = "",
    val site: String = "",
    val laterality: String = "",
    val granulation: String = "",
    val slough: String = "",
    val eschar: String = "",
    val epithelial: String = "",
    val score: String = "",
    val presence: String = "",
    val observedOn: String = "",
) {
    fun toValue(name: String): JSONObject = when (name) {
        "wound_measurement" -> obj(
            "length_cm" to finiteNumber(length, "Length", 200.0, positive = true),
            "width_cm" to finiteNumber(width, "Width", 200.0, positive = true),
        ).apply { if (depth.isNotBlank()) put("depth_cm", finiteNumber(depth, "Depth", 100.0)) }
        "wound_location" -> {
            require(site.trim().length in 1..200) { "Enter an anatomical site in 1–200 characters." }
            require(correctionLaterality.any { it.first == laterality }) { "Choose a supported laterality." }
            obj("site" to site.trim()).apply { if (laterality.isNotBlank()) put("laterality", laterality) }
        }
        "wound_tissue" -> {
            require(text.trim().length <= 4000) { "Keep the tissue description within 4,000 characters." }
            val result = JSONObject()
            if (text.isNotBlank()) result.put("text", text.trim())
            var total = 0.0
            listOf("granulation" to granulation, "slough" to slough, "eschar" to eschar, "epithelial" to epithelial).forEach { (key, raw) ->
                if (raw.isNotBlank()) {
                    val value = finiteNumber(raw, key.replaceFirstChar { it.uppercase() }, 100.0)
                    total += value
                    result.put(key, value)
                }
            }
            require(total <= 100.001) { "Tissue percentages cannot total more than 100%." }
            require(result.length() > 0) { "Describe the tissue or enter an assessed percentage." }
            result
        }
        else -> {
            require(text.trim().length <= 8000) { "Keep the observation within 8,000 characters." }
            require(correctionPresence.any { it.first == presence }) { "Choose present, absent or unknown." }
            val result = JSONObject()
            if (text.isNotBlank()) result.put("text", text.trim())
            if (presence == "present") result.put("present", true)
            if (presence == "absent") result.put("present", false)
            if (name == "pain" && score.isNotBlank()) result.put("score_0_10", finiteNumber(score, "Pain score", 10.0))
            require(result.length() > 0) { "Add an observation${if (name == "pain") ", an assessed pain score," else ""} or explicit presence. Unknown does not mean absent." }
            if (observedOn.isNotBlank()) {
                val date = observedOn.trim()
                require(Regex("\\d{4}-\\d{2}-\\d{2}").matches(date) && runCatching { LocalDate.parse(date) }.isSuccess) {
                    "Enter a valid date in YYYY-MM-DD format, or leave it empty."
                }
                result.put("observed_on", date)
            }
            result
        }
    }

    companion object {
        fun from(value: JSONObject) = FindingCorrection(
            text = value.str("text"), length = value.str("length_cm"), width = value.str("width_cm"), depth = value.str("depth_cm"),
            site = value.str("site"), laterality = value.str("laterality"), granulation = value.str("granulation"),
            slough = value.str("slough"), eschar = value.str("eschar"), epithelial = value.str("epithelial"),
            score = value.str("score_0_10"),
            presence = when (value.opt("present")) { true -> "present"; false -> "absent"; else -> "" },
            observedOn = value.str("observed_on"),
        )

        private fun finiteNumber(raw: String, label: String, maximum: Double, positive: Boolean = false): Double {
            val number = raw.trim().replace(',', '.').toDoubleOrNull()
            require(number != null && number.isFinite() && number <= maximum && if (positive) number > 0.0 else number >= 0.0) {
                "$label must be ${if (positive) "greater than 0" else "0 or greater"} and no more than ${maximum.toInt()}."
            }
            return number!!
        }
    }
}

@Composable
fun FactEditorDialog(f: JSONObject, onDismiss: () -> Unit, onSave: (JSONObject) -> Unit) {
    val name = f.str("name")
    var edit by remember(f.str("id"), f.optInt("revision")) { mutableStateOf(FindingCorrection.from(f.optJSONObject("value") ?: JSONObject())) }
    var error by remember { mutableStateOf<String?>(null) }
    fun update(value: FindingCorrection) { edit = value; error = null }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Correct ${name.replace('_', ' ')}") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Review the complete finding. Your saved correction becomes a clinician-confirmed observation.", style = MaterialTheme.typography.bodyMedium)
                when (name) {
                    "wound_measurement" -> {
                        CorrectionNumber("Length (cm)", edit.length) { update(edit.copy(length = it)) }
                        CorrectionNumber("Width (cm)", edit.width) { update(edit.copy(width = it)) }
                        CorrectionNumber("Depth (cm) · optional", edit.depth) { update(edit.copy(depth = it)) }
                        Text("Leave unmeasured depth empty. Enter 0 only for an explicitly assessed zero.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    "wound_location" -> {
                        OutlinedTextField(edit.site, { update(edit.copy(site = it)) }, modifier = Modifier.fillMaxWidth(), label = { Text("Anatomical site") }, isError = edit.site.trim().length > 200, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
                        CorrectionChoice("Laterality", edit.laterality, correctionLaterality) { update(edit.copy(laterality = it)) }
                    }
                    "wound_tissue" -> {
                        CorrectionObservation("Tissue description", edit.text, 4000) { update(edit.copy(text = it)) }
                        Text("Assessed tissue percentages", style = MaterialTheme.typography.titleSmall)
                        CorrectionNumber("Granulation %", edit.granulation) { update(edit.copy(granulation = it)) }
                        CorrectionNumber("Slough %", edit.slough) { update(edit.copy(slough = it)) }
                        CorrectionNumber("Eschar %", edit.eschar) { update(edit.copy(eschar = it)) }
                        CorrectionNumber("Epithelial %", edit.epithelial) { update(edit.copy(epithelial = it)) }
                        Text("Preserve assessed percentages. Leave unassessed tissue empty; the recorded total must not exceed 100%.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    else -> {
                        CorrectionObservation("Observation", edit.text, 8000) { update(edit.copy(text = it)) }
                        if (name == "pain") CorrectionNumber("Pain score / 10 · optional", edit.score) { update(edit.copy(score = it)) }
                        CorrectionChoice("Presence, when applicable", edit.presence, correctionPresence) { update(edit.copy(presence = it)) }
                        Text("Unknown or not assessed leaves presence unrecorded. It does not record an absent finding.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedTextField(edit.observedOn, { update(edit.copy(observedOn = it)) }, modifier = Modifier.fillMaxWidth(), label = { Text("Observed on · optional") }, placeholder = { Text("YYYY-MM-DD") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii))
                    }
                }
                error?.let { ErrorBanner(it) { error = null } }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                try { onSave(edit.toValue(name)) }
                catch (exception: IllegalArgumentException) { error = exception.message ?: "Review this finding before saving." }
            }) { Text("Save correction") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun CorrectionNumber(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, modifier = Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
}

@Composable
private fun CorrectionObservation(label: String, value: String, maximum: Int, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, modifier = Modifier.fillMaxWidth(), label = { Text(label) }, minLines = 3, maxLines = 7, isError = value.trim().length > maximum, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
}

@Composable
private fun CorrectionChoice(label: String, selected: String, options: List<Pair<String, String>>, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Box {
            WcpButton(options.firstOrNull { it.first == selected }?.second ?: "Choose", { expanded = true }, Modifier.fillMaxWidth(), secondary = true)
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { (value, title) -> DropdownMenuItem(text = { Text(title) }, onClick = { onSelect(value); expanded = false }) }
            }
        }
    }
}
