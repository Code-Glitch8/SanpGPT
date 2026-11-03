package com.snapgpt.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.snapgpt.ai.providers.ApiAccount
import com.snapgpt.ai.providers.ImageSupport
import com.snapgpt.ai.providers.ProviderType
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** Short, honest status line for an account card. */
fun statusLine(a: ApiAccount, now: Long): String = buildString {
    append(if (!a.enabled) "Disabled" else a.status.label)
    if (a.enabled && a.inCooldown(now)) append(" · cooling down ${com.snapgpt.ai.ErrorMapper.formatWait(a.cooldownRemainingSeconds(now))}" + (a.cooldownReason?.let { " ($it)" } ?: ""))
}

fun imageLine(a: ApiAccount): String = when (a.imageSupport()) {
    ImageSupport.YES -> "Accepts images"
    ImageSupport.NO -> "No images (screenshots are never sent here)"
    ImageSupport.UNKNOWN -> "Image support not confirmed (tick the box in Edit to use it for screenshots)"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountsScreen(vm: MainViewModel, onBack: () -> Unit, onUsage: () -> Unit) {
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val testing by vm.testing.collectAsStateWithLifecycle()
    val messages by vm.testMessages.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(notice) { notice?.let { snackbar.showSnackbar(it); vm.consumeNotice() } }

    var editing by remember { mutableStateOf<ApiAccount?>(null) }
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<ApiAccount?>(null) }
    var duplicating by remember { mutableStateOf<ApiAccount?>(null) }
    val now = System.currentTimeMillis()

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("API accounts") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Add as many accounts as you like, including several keys for the same provider. SnapGPT tries them from the top of this list; " +
                    "when one hits a quota or rate limit, has a bad key, or is down, it moves on to the next eligible account and tells you.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { adding = true }) { Text("Add account") }
                OutlinedButton(onClick = onUsage) { Text("Usage dashboard") }
            }
            if (accounts.isEmpty()) {
                Text("No accounts yet. Add one to start scanning.", style = MaterialTheme.typography.bodyMedium)
            }
            accounts.forEachIndexed { index, a ->
                SectionCard("${index + 1}. ${a.name}") {
                    Text("${a.provider.displayName} · ${a.model.ifBlank { "no model set" }}", style = MaterialTheme.typography.bodyMedium)
                    Text(statusLine(a, now), style = MaterialTheme.typography.titleSmall)
                    Text(imageLine(a), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (a.effectiveEndpoint.isNotBlank() && (a.provider.endpointRequired || a.endpoint.isNotBlank())) {
                        Text("Endpoint: ${a.effectiveEndpoint}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (a.lastSuccessAt > 0) Text("Last success: ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(a.lastSuccessAt))}", style = MaterialTheme.typography.bodySmall)
                    a.lastError?.let { Text("Last error: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    messages[a.id]?.let { Text("Test: $it", style = MaterialTheme.typography.bodySmall) }
                    a.rateNote?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = a.enabled, onCheckedChange = { vm.setEnabled(a.id, it) })
                        Text(if (a.enabled) "  Enabled" else "  Disabled", style = MaterialTheme.typography.bodyMedium)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { vm.move(a.id, -1) }, enabled = index > 0) { Text("Move up") }
                        TextButton(onClick = { vm.move(a.id, 1) }, enabled = index < accounts.lastIndex) { Text("Move down") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.testAccount(a) }, enabled = a.id !in testing) { Text(if (a.id in testing) "Testing…" else "Test") }
                        OutlinedButton(onClick = { editing = a }) { Text("Edit") }
                        OutlinedButton(onClick = { duplicating = a }) { Text("Duplicate") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (a.inCooldown(now)) TextButton(onClick = { vm.clearCooldown(a.id) }) { Text("End cooldown now") }
                        TextButton(onClick = { deleting = a }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
            Text(
                "Keys are encrypted one by one with the Android Keystore and are never shown again after saving. A key inside any mobile app can still be " +
                    "extracted by someone who controls the device, so use dedicated keys you can revoke. See the README for the backend-proxy option.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 24.dp),
            )
        }
    }

    if (adding) AccountEditor(vm, null, onDismiss = { adding = false })
    editing?.let { AccountEditor(vm, it, onDismiss = { editing = null }) }

    deleting?.let { a ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete \"${a.name}\"?") },
            text = { Text("The account and its encrypted API key are removed from this device. This cannot be undone.") },
            confirmButton = { TextButton(onClick = { vm.deleteAccount(a.id); deleting = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
    duplicating?.let { a ->
        AlertDialog(
            onDismissRequest = { duplicating = null },
            title = { Text("Duplicate \"${a.name}\"") },
            text = { Text("The copy keeps the provider, endpoint and model. Do you also want to copy the API key? Without it you can paste a different key for the copy.") },
            confirmButton = { TextButton(onClick = { vm.duplicateAccount(a.id, true); duplicating = null }) { Text("Copy with key") } },
            dismissButton = { TextButton(onClick = { vm.duplicateAccount(a.id, false); duplicating = null }) { Text("Copy without key") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccountEditor(vm: MainViewModel, account: ApiAccount?, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var provider by remember { mutableStateOf(account?.provider ?: ProviderType.GROQ) }
    var name by remember { mutableStateOf(account?.name ?: "") }
    var endpoint by remember { mutableStateOf(account?.endpoint ?: "") }
    var model by remember { mutableStateOf(account?.model ?: ProviderType.GROQ.defaultModel) }
    var key by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    var imagesOk by remember { mutableStateOf(account?.supportsImages() ?: (com.snapgpt.ai.providers.ModelCapabilities.byModelName(ProviderType.GROQ, ProviderType.GROQ.defaultModel) == ImageSupport.YES)) }
    var error by remember { mutableStateOf<String?>(null) }
    var masked by remember { mutableStateOf("") }
    LaunchedEffect(account?.id) { if (account != null) masked = vm.maskedKey(account.id) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (account == null) "Add API account" else "Edit account") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Provider", style = MaterialTheme.typography.labelLarge)
                Column {
                    ProviderType.entries.chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            row.forEach { p ->
                                FilterChip(
                                    selected = provider == p,
                                    onClick = {
                                        // Switching provider swaps the suggested default model only if the user has not typed their own.
                                        if (model.isBlank() || model == provider.defaultModel) model = p.defaultModel
                                        provider = p
                                    },
                                    label = { Text(p.displayName) },
                                )
                            }
                        }
                    }
                }
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Display name") }, singleLine = true,
                    placeholder = { Text("e.g. ${provider.displayName} Primary") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    value = endpoint, onValueChange = { endpoint = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text(if (provider.endpointRequired) "Endpoint (https://…/v1)" else "Endpoint (optional override)") },
                    supportingText = { Text(if (provider.endpointRequired) "Base URL of an OpenAI-compatible server. Must be https." else "Leave empty to use ${provider.defaultEndpoint}. Set a proxy URL here if you run one.") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                OutlinedTextField(
                    value = model, onValueChange = { model = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text("Model") }, supportingText = { Text("${provider.modelHint}. SnapGPT never switches models by itself.") },
                )
                OutlinedTextField(
                    value = key, onValueChange = { key = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text(if (account == null) "API key" else "New API key (leave empty to keep)") },
                    supportingText = { if (account != null && masked.isNotEmpty()) Text("Saved key: $masked") else Text("Stored encrypted. Never shown again.") },
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = { TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "Hide" else "Show") } },
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = imagesOk, onCheckedChange = { imagesOk = it })
                    Text("This model accepts images (needed for screenshots)", style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    "Known vision models are recognised automatically. For other models tick this only if you are sure; unconfirmed models are used for text-only steps but never receive screenshots.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (account != null && masked.isNotEmpty()) {
                    TextButton(onClick = { vm.deleteKey(account.id); masked = "" }) { Text("Delete stored key", color = MaterialTheme.colorScheme.error) }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    // Store an override only when the box disagrees with what SnapGPT already knows about this model.
                    val builtIn = com.snapgpt.ai.providers.ModelCapabilities.byModelName(provider, model) == ImageSupport.YES
                    val override: Boolean? = if (imagesOk == builtIn) null else imagesOk
                    val err = vm.saveAccount(account?.id, name, provider, endpoint, model, override, key)
                    if (err == null) onDismiss() else error = err
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
