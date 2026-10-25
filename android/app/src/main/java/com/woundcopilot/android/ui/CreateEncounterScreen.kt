package com.woundcopilot.android.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.woundcopilot.android.ApiException
import com.woundcopilot.android.AppState
import com.woundcopilot.android.DemoData
import com.woundcopilot.android.obj
import com.woundcopilot.android.objects
import com.woundcopilot.android.str
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

/** Patient and wound creation are separate server operations; retain each acknowledged ID on retry. */
@Composable
fun CreateEncounterScreen(state: AppState) {
    val focusManager = LocalFocusManager.current
    val owner = state.user?.str("id") ?: "sample"
    val draftKey = "new-encounter-$owner"
    val priorCase = remember(draftKey) { state.case }
    val restored = remember(draftKey) {
        state.vault.read(draftKey)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
    }
    var patientMode by rememberSaveable(draftKey) { mutableStateOf(restored.str("patient_mode", "existing")) }
    var selectedPatientId by rememberSaveable(draftKey) { mutableStateOf(restored.str("patient_id")) }
    var patientName by rememberSaveable(draftKey) { mutableStateOf(restored.str("patient_name")) }
    var externalRef by rememberSaveable(draftKey) { mutableStateOf(restored.str("external_ref")) }
    var patientQuery by rememberSaveable(draftKey) { mutableStateOf("") }
    var woundMode by rememberSaveable(draftKey) { mutableStateOf(restored.str("wound_mode", "none")) }
    var selectedWoundId by rememberSaveable(draftKey) { mutableStateOf(restored.str("wound_id")) }
    var woundLabel by rememberSaveable(draftKey) { mutableStateOf(restored.str("wound_label")) }
    var title by rememberSaveable(draftKey) { mutableStateOf(restored.str("title")) }
    var patientCreated by rememberSaveable(draftKey) { mutableStateOf(restored.optBoolean("patient_created")) }
    var woundCreated by rememberSaveable(draftKey) { mutableStateOf(restored.optBoolean("wound_created")) }
    var attempted by rememberSaveable(draftKey) { mutableStateOf(false) }
    var storageError by remember { mutableStateOf<String?>(null) }
    var wounds by remember(selectedPatientId) { mutableStateOf<List<JSONObject>>(emptyList()) }
    var woundsLoading by remember { mutableStateOf(false) }
    var woundsError by remember { mutableStateOf<String?>(null) }
    var woundReload by remember { mutableIntStateOf(0) }

    fun draftJson() = obj(
        "patient_mode" to patientMode,
        "patient_id" to selectedPatientId,
        "patient_name" to patientName,
        "external_ref" to externalRef,
        "wound_mode" to woundMode,
        "wound_id" to selectedWoundId,
        "wound_label" to woundLabel,
        "title" to title,
        "patient_created" to patientCreated,
        "wound_created" to woundCreated,
    )

    fun persistNow() {
        runCatching { state.vault.write(draftKey, draftJson().toString()) }
            .onSuccess { storageError = null }
            .onFailure { storageError = "This form could not be saved on the device. Keep the app open until the encounter is created." }
    }

    val draftSnapshot = draftJson().toString()
    val currentDraft by rememberUpdatedState(draftSnapshot)
    LaunchedEffect(draftSnapshot) {
        delay(250)
        persistNow()
    }
    DisposableEffect(draftKey) {
        onDispose {
            if (state.screen == "encounter" && state.case !== priorCase) state.vault.remove(draftKey)
            else runCatching { state.vault.write(draftKey, currentDraft) }
        }
    }

    LaunchedEffect(selectedPatientId, woundReload, state.demo) {
        val requestedPatientId = selectedPatientId
        val requestedReload = woundReload
        woundsError = null
        if (selectedPatientId.isBlank()) {
            wounds = emptyList()
            woundsLoading = false
            return@LaunchedEffect
        }
        woundsLoading = true
        try {
            wounds = if (state.demo) {
                val sample = state.case?.optJSONObject("wound") ?: DemoData.caseData().optJSONObject("wound")
                listOfNotNull(sample?.takeIf { it.str("patient_id") == selectedPatientId })
            } else {
                (state.api.call("/patients/$selectedPatientId/wounds") as JSONArray).objects()
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            woundsError = if (error is ApiException) error.message else "Existing wounds could not be loaded. Check your connection and retry."
            if (error is ApiException && error.status == 401) {
                state.error = "Your session expired. Sign in again; this encounter form is kept on this device."
                state.screen = "login"
            }
        } finally {
            if (requestedPatientId == selectedPatientId && requestedReload == woundReload) woundsLoading = false
        }
    }

    fun choosePatient(patient: JSONObject) {
        selectedPatientId = patient.str("id")
        patientName = patient.str("display_name")
        externalRef = patient.str("external_ref")
        patientMode = "existing"
        selectedWoundId = ""
        woundLabel = ""
        woundMode = "none"
        patientCreated = false
        woundCreated = false
        attempted = false
        persistNow()
    }

    fun changePatient(mode: String) {
        patientMode = mode
        selectedPatientId = ""
        patientName = ""
        externalRef = ""
        selectedWoundId = ""
        woundLabel = ""
        woundMode = "none"
        patientCreated = false
        woundCreated = false
        attempted = false
    }

    val patientError = when {
        selectedPatientId.isNotBlank() -> null
        patientMode == "existing" -> "Choose a patient below, or select Add a patient."
        patientName.isBlank() -> "Enter the patient's display name."
        patientName.trim().length > 200 -> "Use 200 characters or fewer for the name."
        else -> null
    }
    val woundError = when {
        woundMode == "existing" && selectedWoundId.isBlank() -> "Choose an existing wound, add a new wound, or continue without one."
        woundMode == "new" && selectedWoundId.isBlank() && woundLabel.isBlank() -> "Give the wound a short label, such as Left heel."
        woundLabel.trim().length > 200 -> "Use 200 characters or fewer for the wound label."
        else -> null
    }
    val query = patientQuery.trim()
    val matchingPatients = state.patients.filter {
        query.isBlank() || it.str("display_name").contains(query, ignoreCase = true) ||
            it.str("external_ref").contains(query, ignoreCase = true)
    }
    val colors = MaterialTheme.colorScheme

    Column(
        Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        InstrumentTitle("New encounter")
        if (state.demo) {
            StatusTag("Synthetic preview", "info")
        }
        if (storageError != null) ErrorBanner(storageError!!, onDismiss = { storageError = null })

        CreateSectionHeading("01", "Patient")
        if (selectedPatientId.isNotBlank()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = colors.surfaceContainer,
                shape = MaterialTheme.shapes.large,
                border = BorderStroke(1.dp, colors.primary.copy(alpha = .35f)),
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(patientName.ifBlank { "Selected patient" }, style = MaterialTheme.typography.titleLarge)
                    Text(externalRef.ifBlank { "No external reference recorded" }, color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                    StatusTag(if (patientCreated) "Patient created" else "Patient selected", "confirmed")
                    if (patientCreated) {
                        Text("This patient is already saved. Retrying will use the same record.", color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { changePatient("existing") }, enabled = !state.loading, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("Choose another patient")
                    }
                }
            }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                WcpButton("Existing patient", { changePatient("existing") }, Modifier.weight(1f), enabled = !state.loading, secondary = patientMode != "existing")
                WcpButton("Add a patient", { changePatient("new") }, Modifier.weight(1f), enabled = !state.loading, secondary = patientMode != "new")
            }
            if (patientMode == "existing") {
                OutlinedTextField(
                    value = patientQuery,
                    onValueChange = { patientQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Search name or reference") },
                    singleLine = true,
                    enabled = !state.loading,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                )
                when {
                    state.patients.isEmpty() -> EmptyState("No patients yet", "Add a patient to create the first encounter in this workspace.", "Add a patient", { changePatient("new") })
                    matchingPatients.isEmpty() -> Text("No patients match \"$query\". Check the reference or add a patient.", color = colors.onSurfaceVariant)
                    else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        matchingPatients.take(8).forEach { patient ->
                            CreateSelectionRow(
                                title = patient.str("display_name", "Patient"),
                                subtitle = patient.str("external_ref").ifBlank { "No external reference" },
                                selected = false,
                                enabled = !state.loading,
                                onSelect = { choosePatient(patient) },
                            )
                        }
                        if (matchingPatients.size > 8) {
                            Text("Showing 8 of ${matchingPatients.size} patients. Narrow your search to find another.", color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            } else {
                OutlinedTextField(
                    value = patientName,
                    onValueChange = { patientName = it.take(200) },
                    label = { Text("Patient display name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !state.loading,
                    isError = attempted && patientError != null,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                )
                OutlinedTextField(
                    value = externalRef,
                    onValueChange = { externalRef = it.take(200) },
                    label = { Text("External reference · optional") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !state.loading,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                )
            }
        }
        if (attempted && patientError != null) CreateFieldError(patientError)

        HorizontalDivider(color = colors.outlineVariant)
        CreateSectionHeading("02", "Wound")
        if (selectedPatientId.isNotBlank()) {
            CreateSelectionRow(
                title = "Existing wound",
                subtitle = "",
                selected = woundMode == "existing",
                enabled = !state.loading,
                onSelect = { if (woundMode != "existing" && !woundCreated) { selectedWoundId = ""; woundLabel = "" }; woundMode = "existing" },
            )
            if (woundMode == "existing") {
                when {
                    woundsLoading -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        Text("Loading this patient's wounds…", color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                    }
                    woundsError != null -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        CreateFieldError(woundsError!!)
                        WcpButton("Retry loading wounds", { woundReload++ }, secondary = true, enabled = !state.loading)
                    }
                    wounds.isEmpty() && selectedWoundId.isBlank() -> Text("No wounds are recorded for this patient. Add a wound or continue without one.", color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                    else -> Column(Modifier.padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (selectedWoundId.isNotBlank() && wounds.none { it.str("id") == selectedWoundId }) {
                            CreateSelectionRow(woundLabel.ifBlank { "Saved wound" }, "Created for this encounter", true, !state.loading, {})
                        }
                        wounds.forEach { wound ->
                            CreateSelectionRow(
                                title = wound.str("label", "Wound"),
                                subtitle = listOf(wound.str("laterality").replace('_', ' '), wound.str("location")).filter { it.isNotBlank() }.joinToString(" · "),
                                selected = wound.str("id") == selectedWoundId,
                                enabled = !state.loading,
                                onSelect = { selectedWoundId = wound.str("id"); woundLabel = wound.str("label"); woundCreated = false },
                            )
                        }
                    }
                }
            }
        }
        CreateSelectionRow(
            title = if (woundCreated) "Wound saved" else "Add a wound",
            subtitle = if (woundCreated) "A saved record will be reused on retry" else "Give it a clear location or identifying label",
            selected = woundMode == "new",
            enabled = !state.loading,
            onSelect = { if (woundMode != "new" && !woundCreated) { selectedWoundId = ""; woundLabel = "" }; woundMode = "new" },
        )
        if (woundMode == "new") {
            OutlinedTextField(
                value = woundLabel,
                onValueChange = { woundLabel = it.take(200) },
                label = { Text("Wound label") },
                placeholder = { Text("For example, Left heel") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !state.loading,
                readOnly = woundCreated,
                isError = attempted && woundError != null,
                supportingText = if (woundCreated) ({ Text("Already saved. Retrying will use this wound.") }) else null,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
            )
        }
        CreateSelectionRow(
            title = "Continue without a wound",
            subtitle = "Keep the encounter at patient level",
            selected = woundMode == "none",
            enabled = !state.loading,
            onSelect = { woundMode = "none" },
        )
        if (attempted && woundError != null) CreateFieldError(woundError)

        HorizontalDivider(color = colors.outlineVariant)
        DetailDisclosure("Encounter title",initiallyOpen=title.isNotBlank()){
        OutlinedTextField(
            value = title,
            onValueChange = { title = it.take(200) },
            label = { Text("Encounter title · optional") },
            placeholder = { Text("Wound care encounter") },
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.loading,
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
        )
        }
        if (attempted && (patientError != null || woundError != null)) {
            CreateFieldError(listOfNotNull(patientError, woundError).joinToString("\n"))
        }
        WcpButton(
            text = if (state.loading) "Creating encounter…" else "Create encounter",
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.loading,
            onClick = {
                attempted = true
                if (patientError == null && woundError == null) {
                    focusManager.clearFocus()
                    persistNow()
                    state.createEncounter(
                        patientId = selectedPatientId.ifBlank { null },
                        name = patientName.trim(),
                        external = externalRef.trim(),
                        woundId = if (woundMode == "none") null else selectedWoundId.ifBlank { null },
                        woundLabel = if (woundMode == "new") woundLabel.trim() else "",
                        title = title.trim(),
                        onPatient = { id -> selectedPatientId = id; patientCreated = true; persistNow() },
                        onWound = { id -> selectedWoundId = id; woundCreated = true; persistNow() },
                    )
                }
            },
        )
        if (state.loading) {
            Text("Saving your selections. If the connection fails, retry here to reuse records already created.", color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun CreateSectionHeading(number: String, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(13.dp)) {
        Text(number, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
    }
}

@Composable
private fun CreateSelectionRow(title: String, subtitle: String, selected: Boolean, enabled: Boolean, onSelect: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = if (selected) colors.primary.copy(alpha = .08f) else colors.surfaceContainerLow,
        border = BorderStroke(1.dp, if (selected) colors.primary.copy(alpha = .5f) else colors.outlineVariant),
    ) {
        Row(
            Modifier.selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
                .heightIn(min = 64.dp).padding(start = 9.dp, end = 16.dp, top = 11.dp, bottom = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RadioButton(selected = selected, onClick = null, enabled = enabled)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = colors.onSurface)
                if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun CreateFieldError(message: String) {
    Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
}

