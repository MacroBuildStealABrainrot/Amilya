package com.woundcopilot.android.ui
import com.woundcopilot.android.localTime

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.woundcopilot.android.ApiException
import com.woundcopilot.android.AppState
import com.woundcopilot.android.items
import com.woundcopilot.android.obj
import com.woundcopilot.android.objects
import com.woundcopilot.android.str
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.NumberFormat

@Composable
fun AdminScreen(state: AppState) {
    if (state.demo || state.user?.optBoolean("is_platform_admin") != true) {
        Column(Modifier.fillMaxWidth().padding(24.dp)) {
            EmptyState("Administrator access required", "Sign in with an authorized administrator account to open this area.")
        }
        return
    }
    var overview by remember { mutableStateOf<JSONObject?>(null) }
    var costs by remember { mutableStateOf<JSONObject?>(null) }
    var providers by remember { mutableStateOf<JSONObject?>(null) }
    var contacts by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var errors by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var loading by remember { mutableStateOf(false) }
    var contactLoading by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var contactRefresh by remember { mutableIntStateOf(0) }
    var filter by remember { mutableStateOf("all") }
    var offset by remember { mutableIntStateOf(0) }
    var moreContacts by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<JSONObject?>(null) }
    var operationLimit by remember { mutableIntStateOf(10) }
    var showModels by remember { mutableStateOf(false) }

    fun recordError(section: String, exception: Exception) {
        if (exception is CancellationException) throw exception
        val message = if (exception is ApiException) exception.message ?: "The request failed." else "Connection interrupted. Try loading this section again."
        errors = errors + (section to message)
        if (exception is ApiException && exception.status == 401) {
            state.error = "Your session expired. Sign in to continue."
            state.screen = "login"
        }
    }
    LaunchedEffect(state.user?.str("id"), refresh) {
        loading = true
        errors = errors.filterKeys { it == "contacts" }
        try {
            coroutineScope {
                launch { try { overview = state.api.call("/admin/overview") as JSONObject } catch (e: Exception) { recordError("overview", e) } }
                launch { try { costs = state.api.call("/admin/costs") as JSONObject } catch (e: Exception) { recordError("costs", e) } }
                launch { try { providers = state.api.call("/admin/providers") as JSONObject } catch (e: Exception) { recordError("providers", e) } }
            }
        } finally { loading = false }
    }
    LaunchedEffect(filter, offset, refresh, contactRefresh) {
        contactLoading = true
        errors = errors - "contacts"
        try {
            val statusQuery = if (filter == "all") "" else "&status=$filter"
            contacts = (state.api.call("/admin/contacts?limit=50&offset=$offset$statusQuery") as JSONArray).objects()
            moreContacts = contacts.size == 50
        } catch (e: Exception) { recordError("contacts", e) }
        finally { contactLoading = false }
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Eyebrow("WORKSPACE / ADMINISTRATION")
        WcpButton("Developer Diagnostics",{state.screen="developer_diagnostics"},Modifier.fillMaxWidth(),secondary=true)
        PageHeading("The operating view", "Accounts, service configuration, cost records and the contact inbox.")
        WcpButton(if (loading || contactLoading) "Refreshing…" else "Refresh administration", { refresh++ }, Modifier.fillMaxWidth(), enabled = !loading && !contactLoading, secondary = true)
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        errors["overview"]?.let { ErrorBanner("Overview: $it") { errors = errors - "overview" } }
        overview?.let { data ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                AdminMetric("Workspaces", data.optInt("workspaces").toString(), Modifier.weight(1f))
                AdminMetric("Users", data.optInt("users").toString(), Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                AdminMetric("Active subscriptions", data.optInt("active_subscriptions").toString(), Modifier.weight(1f))
                AdminMetric("New contacts", data.optInt("new_contacts").toString(), Modifier.weight(1f))
            }
            data.optJSONObject("payments")?.let { payments ->
                SectionLabel("Recorded payments")
                AdminKeyValue("Paid", adminAmount(payments, "recorded_paid_cents", 100.0, payments.str("currency", "CAD")))
                AdminKeyValue("Refunded", adminAmount(payments, "refunded_cents", 100.0, payments.str("currency", "CAD")))
                Text(payments.str("note"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            data.optJSONObject("costs")?.let { summary -> SectionLabel("Recorded usage totals"); AdminCostValues(summary, summary.str("currency", "USD")) }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        SectionLabel("Provider configuration")
        errors["providers"]?.let { ErrorBanner(it) { errors = errors - "providers" } }
        providers?.let { registry ->
            StatusTag(if (registry.optBoolean("execution_enabled")) "Execution enabled" else "Execution paused", if (registry.optBoolean("execution_enabled")) "success" else "neutral")
            Text(if (registry.optBoolean("clinical_data_enabled")) "Clinical data is enabled by server configuration." else "This deployment is restricted to synthetic data.", style = MaterialTheme.typography.bodyMedium)
            registry.items("providers").forEach { provider ->
                EvidenceRail {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(provider.str("name").replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.titleMedium)
                        StatusTag(if (provider.optBoolean("configured")) "Configured" else "Not configured", if (provider.optBoolean("configured")) "success" else "neutral")
                        Text(if (provider.optBoolean("clinical_approved")) "Marked approved for clinical data on the server." else "Not marked approved for clinical data.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Text("Configured status does not verify provider funding or request availability.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { showModels = !showModels }, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (showModels) "Hide registry details" else "View registry details") }
            if (showModels) {
                Text("Price registry: ${registry.str("price_registry_version")}", style = MaterialTheme.typography.labelMedium)
                val models = registry.optJSONArray("models") ?: JSONArray()
                SelectionContainer { Text((0 until models.length()).joinToString("\n") { models.optString(it) }, style = MaterialTheme.typography.bodySmall) }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        SectionLabel("Provider costs")
        errors["costs"]?.let { ErrorBanner(it) { errors = errors - "costs" } }
        costs?.let { ledger ->
            val currency = ledger.str("currency", "USD")
            if (ledger.items("providers").isEmpty()) Text("No provider usage has been recorded.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            ledger.items("providers").forEach { provider ->
                EvidenceRail {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(provider.str("provider").ifBlank { "Unspecified provider" }, style = MaterialTheme.typography.titleMedium)
                        Text("${provider.optInt("operations")} recorded operations", style = MaterialTheme.typography.bodySmall)
                        AdminCostValues(provider, currency)
                    }
                }
            }
            val operations = ledger.items("operations")
            if (operations.isNotEmpty()) {
                SectionLabel("Recent operations")
                Text("Latest ${operations.size} records returned by the server.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                operations.take(operationLimit).forEach { operation ->
                    EvidenceRail {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(operation.str("task").replace('_', ' '), style = MaterialTheme.typography.titleSmall)
                            StatusTag(operation.str("status"))
                            Text(listOf(operation.str("provider"), operation.str("model"), localTime(operation.str("created_at"))).filter { it.isNotBlank() }.joinToString("\n"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            AdminKeyValue("Provider-reported cost", adminAmount(operation, "actual_cost_micros", 1_000_000.0, currency))
                        }
                    }
                }
                if (operationLimit < operations.size) WcpButton("Show more operations", { operationLimit += 20 }, secondary = true)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        SectionLabel("Contact inbox")
        Text("Update the internal status of a request. This screen does not send a reply.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        overview?.str("outbound_contact_notifications")?.takeIf { it.startsWith("not_configured") }?.let { Text("Outbound contact notifications are not configured. Requests remain in this inbox.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("all", "new", "in_progress", "resolved", "spam").forEach { status ->
                FilterChip(filter == status, { filter = status; offset = 0 }, label = { Text(status.replace('_', ' ').replaceFirstChar { it.uppercase() }) }, enabled = !contactLoading)
            }
        }
        if (contactLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
        errors["contacts"]?.let { error ->
            ErrorBanner(error) { errors = errors - "contacts" }
            WcpButton("Retry inbox", { contactRefresh++ }, enabled = !contactLoading, secondary = true)
        }
        if (contacts.isEmpty() && !contactLoading && errors["contacts"] == null) EmptyState("No requests in this view", "New contact requests will appear here when received.")
        contacts.forEach { contact ->
            Surface(onClick = { selected = contact }, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text(contact.str("subject").ifBlank { "${contact.str("category").replaceFirstChar { it.uppercase() }} request" }, style = MaterialTheme.typography.titleMedium)
                    Text(contact.str("name"), style = MaterialTheme.typography.bodyMedium)
                    StatusTag(contact.str("status").replace('_', ' '))
                    Text(localTime(contact.str("created_at")), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Open request →", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        if (offset > 0 || moreContacts) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            WcpButton("Previous", { offset = (offset - 50).coerceAtLeast(0) }, Modifier.weight(1f), enabled = offset > 0 && !contactLoading, secondary = true)
            WcpButton("Next", { offset += 50 }, Modifier.weight(1f), enabled = moreContacts && !contactLoading, secondary = true)
        }
        if (offset > 0 || contacts.isNotEmpty()) Text("Showing ${if (contacts.isEmpty()) 0 else offset + 1}–${offset + contacts.size}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(20.dp))
    }
    selected?.let { contact -> AdminContactDialog(state, contact, onDismiss = { selected = null }, onUpdated = { selected = null; refresh++ }) }
}

@Composable
private fun AdminMetric(label: String, value: String, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(value, style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun adminAmount(value: JSONObject, key: String, divisor: Double, currency: String): String {
    if (!value.has(key) || value.isNull(key)) return "Not reported"
    val format = NumberFormat.getNumberInstance().apply { minimumFractionDigits = 2; maximumFractionDigits = 6 }
    return "$currency ${format.format(value.optDouble(key) / divisor)}"
}

@Composable
private fun AdminKeyValue(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun AdminCostValues(value: JSONObject, currency: String) {
    listOf("normalized_micros" to "Normalized usage", "actual_cost_micros" to "Provider-reported cost", "charged_micros" to "Charged", "unfunded_micros" to "Unfunded").forEach { (key, label) ->
        AdminKeyValue(label, adminAmount(value, key, 1_000_000.0, currency))
    }
}

@Composable
private fun AdminContactDialog(state: AppState, contact: JSONObject, onDismiss: () -> Unit, onUpdated: () -> Unit) {
    var status by remember { mutableStateOf(contact.str("status", "new")) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!loading) onDismiss() },
        title = { Text(contact.str("subject").ifBlank { "Contact request" }) },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(contact.str("name"), style = MaterialTheme.typography.titleMedium)
                        Text(contact.str("email"), style = MaterialTheme.typography.bodyMedium)
                        listOf("organization", "role", "team_size", "region").forEach { key -> contact.str(key).takeIf { it.isNotBlank() }?.let { AdminKeyValue(key.replace('_', ' ').replaceFirstChar { it.uppercase() }, it) } }
                        Text(contact.str("message"), style = MaterialTheme.typography.bodyMedium)
                        Text("Reference ${contact.str("id")}\n${localTime(contact.str("created_at"))}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text("Internal request status", style = MaterialTheme.typography.labelMedium)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("new", "in_progress", "resolved", "spam").forEach { option -> FilterChip(status == option, { status = option }, label = { Text(option.replace('_', ' ').replaceFirstChar { it.uppercase() }) }, enabled = !loading) }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = {
            loading = true
            error = null
            scope.launch {
                try {
                    state.api.call("/admin/contacts/${contact.str("id")}", "PATCH", obj("status" to status))
                    onUpdated()
                } catch (exception: Exception) {
                    if (exception is CancellationException) throw exception
                    error = if (exception is ApiException) exception.message else "The status could not be updated. Try again."
                } finally { loading = false }
            }
        }, enabled = !loading && status != contact.str("status")) { Text(if (loading) "Updating…" else "Update status") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !loading) { Text("Close") } },
    )
}
