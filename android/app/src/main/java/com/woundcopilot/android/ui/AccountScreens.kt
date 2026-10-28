package com.woundcopilot.android.ui

import android.util.Patterns
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.woundcopilot.android.AppState
import com.woundcopilot.android.obj
import com.woundcopilot.android.str
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import java.text.NumberFormat

private fun validEmail(value: String) = Patterns.EMAIL_ADDRESS.matcher(value.trim()).matches()

@Composable
fun AuthScreen(state: AppState, signup: Boolean) {
    var email by rememberSaveable(signup) { mutableStateOf("") }
    // Credentials are kept in memory only, outside saved instance state.
    var password by remember(signup) { mutableStateOf("") }
    var visiblePassword by remember { mutableStateOf(false) }
    var name by rememberSaveable(signup) { mutableStateOf("") }
    var workspace by rememberSaveable(signup) { mutableStateOf("") }
    var accepted by rememberSaveable(signup) { mutableStateOf(false) }
    var attempted by remember { mutableStateOf(false) }
    var legal by remember { mutableStateOf<String?>(null) }
    var support by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val emailValid = validEmail(email)
    val passwordValid = if (signup) password.length in 12..128 else password.isNotBlank() && password.length <= 128
    val legalAvailable = state.config.str("legal_version").isNotBlank()
    val registrationOpen = state.config.optBoolean("allow_signup", false)
    val canSubmit = emailValid && passwordValid && (!signup || (name.isNotBlank() && workspace.isNotBlank() && accepted && registrationOpen && legalAvailable))
    val submit: () -> Unit = {
        attempted = true
        if (canSubmit && !state.loading) {
            focus.clearFocus()
            if (signup) state.signup(email, password, name.trim(), workspace.trim()) else state.login(email, password)
        }
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 24.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        InstrumentTitle(if (signup) "Create account" else "Sign in")
        Spacer(Modifier.height(4.dp))
        if (signup && !registrationOpen && !state.loading) {
            AccountCallout("Registration is currently unavailable", "You can sign in to an existing account or explore the sample encounter.")
        }
        if (signup) {
            OutlinedTextField(
                value = name, onValueChange = { if (it.length <= 160) name = it },
                modifier = Modifier.fillMaxWidth(), label = { Text("Your name") },
                singleLine = true, enabled = !state.loading,
                isError = attempted && name.isBlank(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                shape = MaterialTheme.shapes.medium,
            )
            OutlinedTextField(
                value = workspace, onValueChange = { if (it.length <= 160) workspace = it },
                modifier = Modifier.fillMaxWidth(), label = { Text("Workspace name") },
                singleLine = true, enabled = !state.loading,
                isError = attempted && workspace.isBlank(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                shape = MaterialTheme.shapes.medium,
            )
        }
        OutlinedTextField(
            value = email, onValueChange = { if (it.length <= 254) email = it },
            modifier = Modifier.fillMaxWidth(), label = { Text("Email") }, singleLine = true,
            enabled = !state.loading, isError = attempted && !emailValid,
            supportingText = if (attempted && !emailValid) ({ Text("Enter a valid email address.") }) else null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
            shape = MaterialTheme.shapes.medium,
        )
        OutlinedTextField(
            value = password, onValueChange = { if (it.length <= 128) password = it },
            modifier = Modifier.fillMaxWidth(), label = { Text("Password") }, singleLine = true,
            enabled = !state.loading, isError = attempted && !passwordValid,
            visualTransformation = if (visiblePassword) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                TextButton(onClick = { visiblePassword = !visiblePassword }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(if (visiblePassword) "Hide" else "Show", style = MaterialTheme.typography.labelSmall)
                }
            },
            supportingText = if (signup) ({ Text("At least 12 characters.") }) else null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }), shape = MaterialTheme.shapes.medium,
        )
        if (signup) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = accepted, enabled = !state.loading, role = Role.Checkbox, onValueChange = { accepted = it }),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(accepted, onCheckedChange = null, enabled = !state.loading)
                Spacer(Modifier.width(12.dp))
                Text("I accept the Terms of Use and Privacy Policy.", style = MaterialTheme.typography.bodyMedium)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { legal = "terms" }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Read terms") }
                TextButton(onClick = { legal = "privacy" }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Read privacy policy") }
            }
        }
        WcpButton(
            if (state.loading) "Please wait…" else if (signup) "Create workspace" else "Sign in",
            submit, Modifier.fillMaxWidth(), enabled = !state.loading && (!signup || (accepted && registrationOpen && legalAvailable)),
        )
        TextButton(onClick = { state.screen = if (signup) "login" else "signup" }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = !state.loading) {
            Text(if (signup) "Already have an account? Sign in" else "Create a workspace")
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .65f))
        TextButton(onClick = { support = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Need help signing in?") }
        Spacer(Modifier.height(24.dp))
    }
    legal?.let { LegalDocumentDialog(state, it) { legal = null } }
    if (support) SupportDialog(state, onDismiss = { support = false })
}

@Composable
fun AccountScreen(state: AppState) {
    var editProfile by remember { mutableStateOf(false) }
    var support by remember { mutableStateOf(false) }
    var legal by remember { mutableStateOf<String?>(null) }
    var signOut by remember { mutableStateOf(false) }
    val user = state.user ?: JSONObject()
    val billing = state.billing
    val name = user.str("display_name", "Your account")
    val unlimited = billing.optBoolean("is_unlimited")
    val profile = user.optJSONObject("profile") ?: JSONObject()
    val number = remember { NumberFormat.getNumberInstance().apply { maximumFractionDigits = 2 } }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        InstrumentTitle("Account")
        Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.size(58.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                Text(name.trim().split(" ").filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1).uppercase() }, color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.headlineSmall)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(name, style = MaterialTheme.typography.titleLarge)
                if (!state.demo) Text(user.str("email"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (state.demo) StatusTag("Synthetic preview") else if (user.optBoolean("is_platform_admin")) StatusTag("Administrator", "accent")
            }
        }
        if (!state.demo) {
            WcpButton("Edit profile", { editProfile = true }, Modifier.fillMaxWidth(), secondary = true, enabled = !state.loading)
            if (profile.str("profession").isNotBlank()) AccountValue("Profession", profile.str("profession"))
            if (profile.str("care_setting").isNotBlank()) AccountValue("Care setting", profile.str("care_setting"))
            DetailDisclosure("Workspace ID") {
                SelectionContainer { Text(user.str("workspace_id"), style = MaterialTheme.typography.bodySmall) }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        SectionLabel("Appearance")
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(value = state.dark, role = Role.Switch, onValueChange = { state.toggleTheme() }),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Dark appearance", style = MaterialTheme.typography.titleSmall)
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = state.dark, onCheckedChange = null)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        SectionLabel("Workspace credits", action = if (state.demo) null else "Refresh", onAction = { state.runTask { state.refreshHome() } })
        EvidenceRail {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    when { state.demo -> "Sample workspace"; unlimited -> "Unlimited"; billing.has("balance_credits") -> number.format(billing.optDouble("balance_credits")); else -> "Not available" },
                    style = MaterialTheme.typography.displaySmall,
                )
                Text(
                    when {
                        state.demo -> "Preview actions stay separate from your account. No provider inference is requested."
                        unlimited -> "Your account has unlimited WoundCoPilot credits."
                        else -> "WoundCoPilot credits available in this workspace."
                    },
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!state.demo) {
                    StatusTag(billing.str("status", "Status unavailable").replace('_', ' '), if (billing.optBoolean("active") || unlimited) "success" else "neutral")
                    if (billing.optDouble("reserved_credits") > 0) Text("${number.format(billing.optDouble("reserved_credits"))} credits reserved for operations in progress.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Provider funding is separate. An enabled service may still return a provider credit limit.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        SectionLabel("Service configuration")
        if (state.demo) {
            AccountCallout("Local sample", "Recording, findings and document actions in this preview do not call a reasoning model.")
        } else {
            val capabilities = state.config.optJSONObject("capabilities") ?: JSONObject()
            CapabilityRow("AI assistance", capabilities.optJSONObject("ai"))
            CapabilityRow("Transcription", capabilities.optJSONObject("audio"))
            AccountCallout(
                if (state.config.optBoolean("clinical_data_enabled")) "Clinical data enabled" else "Synthetic data only",
                if (state.config.optBoolean("clinical_data_enabled")) "Use your workspace's approved data handling policies." else "This deployment is not enabled for identifiable clinical data. Use synthetic examples.",
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        SectionLabel("Help & policies")
        var automaticBiometrics by remember{mutableStateOf(state.vault.read("biometric-auto")!="false")}
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
            Text("Offer biometrics on return",Modifier.weight(1f))
            Switch(automaticBiometrics,{automaticBiometrics=it;state.vault.write("biometric-auto",it.toString())})
        }
        if (!state.demo && user.optBoolean("is_platform_admin")) {
            WcpButton("Open administration", { state.screen = "admin" }, Modifier.fillMaxWidth(), secondary = true)
        }
        WcpButton("Contact support", { support = true }, Modifier.fillMaxWidth(), secondary = true)
        if(!state.demo && user.optBoolean("is_platform_admin"))com.woundcopilot.android.SharePerformanceDiagnostics(state)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { legal = "terms" }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Terms of Use") }
            TextButton(onClick = { legal = "privacy" }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Privacy Policy") }
        }
        TextButton(onClick = { signOut = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), enabled = !state.loading) {
            Text(if (state.demo) "Leave the sample" else "Sign out", color = MaterialTheme.colorScheme.error)
        }
        Text("WoundCoPilot for Android", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(20.dp))
    }
    if (editProfile) ProfileDialog(state) { editProfile = false }
    if (support) SupportDialog(state) { support = false }
    legal?.let { LegalDocumentDialog(state, it) { legal = null } }
    if (signOut) {
        AlertDialog(
            onDismissRequest = { if (!state.loading) signOut = false },
            title = { Text(if (state.demo) "Leave the sample?" else "Sign out of your workspace?") },
            text = { Text(if (state.demo) "Sample changes do not become patient records." else "Your local document draft is retained on this device for this account. Sign back in with the same account to continue. Signing out does not upload pending work.") },
            confirmButton = { TextButton(onClick = { state.logout(); signOut = false }, enabled = !state.loading) { Text(if (state.demo) "Leave sample" else "Sign out") } },
            dismissButton = { TextButton(onClick = { signOut = false }) { Text("Stay here") } },
        )
    }
}

@Composable
private fun AccountValue(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun AccountCallout(title: String, text: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun CapabilityRow(title: String, capability: JSONObject?) {
    val enabled = capability?.optBoolean("enabled") == true
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            StatusTag(if (capability == null) "Unknown" else if (enabled) "Configured" else "Unavailable", if (enabled) "success" else "neutral")
        }
        capability?.str("reason")?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun ProfileDialog(state: AppState, onDismiss: () -> Unit) {
    val original = state.user ?: JSONObject()
    var name by remember { mutableStateOf(original.str("display_name")) }
    var profession by remember { mutableStateOf(original.optJSONObject("profile")?.str("profession") ?: "") }
    var careSetting by remember { mutableStateOf(original.optJSONObject("profile")?.str("care_setting") ?: "") }
    AlertDialog(
        onDismissRequest = { if (!state.loading) onDismiss() },
        title = { Text("Your profile") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                OutlinedTextField(name, { if (it.length <= 160) name = it }, label = { Text("Display name") }, singleLine = true, enabled = !state.loading, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next))
                OutlinedTextField(profession, { if (it.length <= 160) profession = it }, label = { Text("Profession") }, singleLine = true, enabled = !state.loading, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next))
                OutlinedTextField(careSetting, { if (it.length <= 160) careSetting = it }, label = { Text("Care setting") }, singleLine = true, enabled = !state.loading, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done))
                Text("Your email and workspace identity stay the same.", style = MaterialTheme.typography.bodySmall)
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                state.runTask {
                    state.api.call("/auth/profile", "PATCH", obj("display_name" to name.trim(), "profile" to obj("profession" to profession.trim(), "care_setting" to careSetting.trim())))
                    state.refreshSession()
                    state.notice = "Profile saved."
                    onDismiss()
                }
            }, enabled = name.isNotBlank() && !state.loading) { Text(if (state.loading) "Saving…" else "Save profile") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !state.loading) { Text("Cancel") } },
    )
}

@Composable
private fun SupportDialog(state: AppState, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(if (state.demo) "" else state.user?.str("display_name") ?: "") }
    var email by remember { mutableStateOf(if (state.demo) "" else state.user?.str("email") ?: "") }
    var subject by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("support") }
    var acknowledged by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!state.loading) onDismiss() },
        title = { Text("Contact support") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Describe the issue without patient names, photos, record details or other patient information.", style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(name, { if (it.length <= 120) name = it }, label = { Text("Your name") }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !state.loading, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next))
                OutlinedTextField(email, { if (it.length <= 254) email = it }, label = { Text("Reply email") }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !state.loading, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next))
                Text("Topic", style = MaterialTheme.typography.labelMedium)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("support", "account", "billing", "privacy", "feedback", "other").forEach { option ->
                        FilterChip(selected = option == category, onClick = { category = option }, label = { Text(option.replaceFirstChar { it.uppercase() }) }, enabled = !state.loading)
                    }
                }
                OutlinedTextField(subject, { if (it.length <= 180) subject = it }, label = { Text("Subject (optional)") }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !state.loading, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next))
                OutlinedTextField(message, { if (it.length <= 4000) message = it }, label = { Text("What happened?") }, modifier = Modifier.fillMaxWidth(), minLines = 4, maxLines = 8, enabled = !state.loading, supportingText = { Text("${message.length}/4000") }, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = acknowledged, role = Role.Checkbox, enabled = !state.loading, onValueChange = { acknowledged = it }), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(acknowledged, null, enabled = !state.loading)
                    Spacer(Modifier.width(10.dp))
                    Text("This request contains no patient information.", style = MaterialTheme.typography.bodySmall)
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                state.runTask {
                    val result = state.api.call("/contact", "POST", obj("name" to name.trim(), "email" to email.trim(), "subject" to subject.trim().ifBlank { null }, "message" to message.trim(), "type" to "support", "category" to category, "no_patient_information" to acknowledged)) as JSONObject
                    state.notice = "Support request received. Reference: ${result.str("id")}"
                    onDismiss()
                }
            }, enabled = !state.loading && name.trim().length >= 2 && validEmail(email) && message.trim().length >= 5 && acknowledged) {
                Text(if (state.loading) "Sending…" else "Send request")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !state.loading) { Text("Cancel") } },
    )
}

@Composable
fun LegalGate(state: AppState) {
    var accepted by rememberSaveable { mutableStateOf(false) }
    var document by remember { mutableStateOf<String?>(null) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Eyebrow("WORKSPACE / POLICIES")
        PageHeading("Review the\ncurrent policies", "Read the Terms of Use and Privacy Policy before continuing with your workspace.")
        Text("Version ${state.config.str("legal_version")}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
        WcpButton("Read Terms of Use", { document = "terms" }, Modifier.fillMaxWidth(), secondary = true)
        WcpButton("Read Privacy Policy", { document = "privacy" }, Modifier.fillMaxWidth(), secondary = true)
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(value = accepted, enabled = !state.loading, role = Role.Checkbox, onValueChange = { accepted = it }),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(accepted, null, enabled = !state.loading)
            Spacer(Modifier.width(12.dp))
            Text("I accept these Terms of Use and the Privacy Policy.", style = MaterialTheme.typography.bodyMedium)
        }
        WcpButton(if (state.loading) "Saving acceptance…" else "Accept and continue", { state.acceptLegal() }, Modifier.fillMaxWidth(), enabled = accepted && !state.loading && state.config.str("legal_version").isNotBlank())
        TextButton(onClick = { state.logout() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = !state.loading) { Text("Sign out instead") }
        Spacer(Modifier.height(20.dp))
    }
    document?.let { LegalDocumentDialog(state, it) { document = null } }
}

@Composable
fun LegalDocumentDialog(state: AppState, document: String, onDismiss: () -> Unit) {
    var data by remember(document) { mutableStateOf<JSONObject?>(null) }
    var error by remember(document) { mutableStateOf<String?>(null) }
    var loading by remember(document) { mutableStateOf(true) }
    var attempt by remember(document) { mutableIntStateOf(0) }
    LaunchedEffect(document, attempt) {
        loading = true
        error = null
        try { data = state.api.call("/legal/$document") as JSONObject }
        catch (e: Exception) {
            if (e is CancellationException) throw e
            error = "This policy could not be loaded. Check your connection and try again."
        }
        finally { loading = false }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(data?.str("title") ?: if (document == "privacy") "Privacy Policy" else "Terms of Use") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (loading) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("Loading the current policy…")
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                data?.let { policy ->
                    Text("Version ${policy.str("version")}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SelectionContainer {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            policy.str("content").split(Regex("\\n\\s*\\n")).filter { it.isNotBlank() }.forEach { paragraph ->
                                val heading = paragraph.startsWith("#")
                                Text(
                                    text = if (heading) paragraph.trimStart('#', ' ') else paragraph,
                                    style = if (heading) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = if (error != null) ({ TextButton(onClick = { attempt++ }) { Text("Retry") } }) else null,
    )
}
