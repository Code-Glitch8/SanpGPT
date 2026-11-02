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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.snapgpt.ai.AiDefaults
import com.snapgpt.capture.ImageQuality
import com.snapgpt.jee.ExamMode
import com.snapgpt.settings.AppSettings
import com.snapgpt.settings.ApiAccount
import com.snapgpt.settings.ApiAccountKind
import com.snapgpt.settings.ApiAccountPresets
import com.snapgpt.settings.DefaultPosition
import com.snapgpt.settings.ThemeMode

private enum class Confirm { HISTORY, SCREENSHOTS, CONVERSATIONS, RESET_USAGE }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val usage by vm.usage.collectAsStateWithLifecycle()
    val accountConnections by vm.accountConnections.collectAsStateWithLifecycle()
    val rate by vm.rateLimit.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(notice) {
        notice?.let { snackbar.showSnackbar(it); vm.consumeNotice() }
    }

    var confirm by remember { mutableStateOf<Confirm?>(null) }
    var showAddApi by remember { mutableStateOf(false) }
    var addKind by remember { mutableStateOf(ApiAccountKind.GROQ) }
    var addName by remember { mutableStateOf("Groq API") }
    var addBaseUrl by remember { mutableStateOf(AiDefaults.BASE_URL) }
    var addModel by remember { mutableStateOf(AiDefaults.DEFAULT_MODEL) }
    var addKey by remember { mutableStateOf("") }
    var showAddKey by remember { mutableStateOf(false) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // ------------------------------------------------ A. Floating assistant
            SectionCard("Floating assistant") {
                SliderRow("Circle size", "{v} dp", s.circleSizeDp, AppSettings.MIN_CIRCLE_DP, AppSettings.MAX_CIRCLE_DP) { v -> vm.update { it.copy(circleSizeDp = v) } }
                SliderRow("Opacity when idle", "{v}%", s.idleOpacityPercent, AppSettings.MIN_OPACITY, AppSettings.MAX_OPACITY) { v -> vm.update { it.copy(idleOpacityPercent = v) } }
                Text("The circle becomes fully opaque while you touch or drag it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SwitchRow("Snap to screen edge", "After dragging, the circle sticks to the nearest side.", s.edgeSnapping) { v -> vm.update { it.copy(edgeSnapping = v) } }
                Text("Default position (used until you move the circle)", style = MaterialTheme.typography.bodyMedium)
                RadioRows(DefaultPosition.entries, s.defaultPosition, { it.label }) { p -> vm.update { it.copy(defaultPosition = p, lastPosX = -1f, lastPosY = -1f) } }
                Text("Start or stop the assistant from the Home screen or the notification.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            // ------------------------------------------------ B. AI accounts and failover
            SectionCard("AI APIs and automatic fallback") {
                Text(
                    "Add as many API accounts as you need. Each account stores its own encrypted key, endpoint and model. The order below is the fallback priority: first account is tried first.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SwitchRow(
                    "Automatic API failover",
                    "When an account reaches a quota/rate limit, has an invalid key, an unavailable model or a temporary server/network failure, try the next enabled account.",
                    s.automaticApiFailover,
                ) { v -> vm.update { it.copy(automaticApiFailover = v) } }
                if (s.apiAccounts.isEmpty()) {
                    Text("No API accounts yet. Add one below to enable screenshot analysis.", style = MaterialTheme.typography.bodyMedium)
                }
                s.apiAccounts.forEachIndexed { index, account ->
                    ApiAccountCard(
                        account = account,
                        index = index,
                        total = s.apiAccounts.size,
                        savedKey = vm.hasApiKey(account.id),
                        maskedKey = vm.maskedApiKey(account.id),
                        connection = accountConnections[account.id] ?: ConnectionUi(null, "Not tested"),
                        onUpdate = vm::updateApiAccount,
                        onSaveKey = { vm.saveApiKey(account.id, it) },
                        onDeleteKey = { vm.deleteApiKey(account.id) },
                        onTest = { vm.testApiAccount(account.id) },
                        onMove = { direction -> vm.moveApiAccount(account.id, direction) },
                        onRemove = { vm.removeApiAccount(account.id) },
                    )
                }
                Button(onClick = { showAddApi = true }, modifier = Modifier.fillMaxWidth()) { Text("+ Add another API account") }
                Text(
                    "Unlimited accounts are supported. This version accepts Groq, OpenRouter and other endpoints that implement the OpenAI-compatible Chat Completions API with image_url inputs. Native Gemini or Anthropic endpoints require a separate protocol adapter and cannot be added as arbitrary URLs here.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "API keys are encrypted with Android Keystore. A key used by a client app can still be read from a rooted/debugged device; use dedicated keys you can revoke. A backend proxy is safer for public distribution.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                UsageBlock(usage, rate, onReset = { confirm = Confirm.RESET_USAGE })
            }

            // ------------------------------------------------ C. JEE
            SectionCard("JEE") {
                Text("Exam mode (used for detection and explanations)", style = MaterialTheme.typography.bodyMedium)
                RadioRows(ExamMode.entries, s.examMode, { it.label }) { m -> vm.update { it.copy(examMode = m) } }
                SwitchRow("JEE syllabus restriction", "Use only JEE Main / Advanced syllabus methods.", s.syllabusRestriction) { v -> vm.update { it.copy(syllabusRestriction = v) } }
                SwitchRow("Show chapter and topic", null, s.showChapterTopic) { v -> vm.update { it.copy(showChapterTopic = v) } }
                SwitchRow("Show alternative methods", "Adds one shorter method to explanations (uses more tokens).", s.showAlternativeMethods) { v -> vm.update { it.copy(showAlternativeMethods = v) } }
            }

            // ------------------------------------------------ D. Scanning
            SectionCard("Scanning") {
                Text("Use the floating circle menu to scan the entire screen or select a question area.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SliderRow("Maximum questions per scan", "{v}", s.maxQuestionsPerScan, AppSettings.MIN_QUESTIONS, AppSettings.MAX_QUESTIONS) { v -> vm.update { it.copy(maxQuestionsPerScan = v) } }
                Text("Image quality", style = MaterialTheme.typography.bodyMedium)
                RadioRows(ImageQuality.entries, s.imageQuality, { it.label }) { q -> vm.update { it.copy(imageQuality = q) } }
                SwitchRow("Automatic answer placement", "Place each answer beside its question. Off = stack answers at the screen edge.", s.autoAnswerPlacement) { v -> vm.update { it.copy(autoAnswerPlacement = v) } }
                SwitchRow("Always send the question image for explanations", "Normally only sent for figures or unclear text (saves tokens).", s.alwaysAttachImageInExplanation) { v -> vm.update { it.copy(alwaysAttachImageInExplanation = v) } }
            }

            // ------------------------------------------------ E. Appearance
            SectionCard("Appearance") {
                RadioRows(ThemeMode.entries, s.theme, { it.label }) { t -> vm.update { it.copy(theme = t) } }
            }

            // ------------------------------------------------ F. Privacy
            SectionCard("Privacy") {
                Text("Screenshots are sent only to the enabled API account selected for that request. With automatic failover enabled, an approved backup account may receive the same screenshot if an earlier account is limited or unavailable. Nothing is uploaded in the background.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SwitchRow("Save scan history", "Questions, answers, explanations and token counts stay on this device.", s.historyEnabled) { v -> vm.update { it.copy(historyEnabled = v) } }
                SwitchRow("Save screenshot thumbnails", "A small preview image with each history entry.", s.saveThumbnails) { v -> vm.update { it.copy(saveThumbnails = v) } }
                OutlinedButton(onClick = { confirm = Confirm.SCREENSHOTS }, modifier = Modifier.fillMaxWidth()) { Text("Delete saved screenshots") }
                OutlinedButton(onClick = { confirm = Confirm.CONVERSATIONS }, modifier = Modifier.fillMaxWidth()) { Text("Clear conversations and explanations") }
                OutlinedButton(onClick = { confirm = Confirm.HISTORY }, modifier = Modifier.fillMaxWidth()) { Text("Clear all history") }
            }
            Text("SnapGPT 1.0.0 · Model answers can be wrong.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 24.dp))
        }
    }

    if (showAddApi) {
        AlertDialog(
            onDismissRequest = { showAddApi = false; addKey = "" },
            title = { Text("Add API account") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Choose a preset, then enter an image-capable model and its API key.", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ApiAccountKind.entries.forEach { kind ->
                            OutlinedButton(onClick = {
                                addKind = kind
                                val preset = ApiAccountPresets.create(kind)
                                addName = preset.name
                                addBaseUrl = preset.baseUrl
                                addModel = preset.model
                            }) { Text(if (addKind == kind) "✓ ${kind.label}" else kind.label) }
                        }
                    }
                    OutlinedTextField(addName, { addName = it }, label = { Text("Account name") }, singleLine = true)
                    OutlinedTextField(addBaseUrl, { addBaseUrl = it }, label = { Text("HTTPS base URL") }, singleLine = true)
                    OutlinedTextField(addModel, { addModel = it }, label = { Text("Vision model ID") }, singleLine = true)
                    OutlinedTextField(
                        addKey, { addKey = it }, label = { Text("API key") }, singleLine = true,
                        visualTransformation = if (showAddKey) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = { TextButton(onClick = { showAddKey = !showAddKey }) { Text(if (showAddKey) "Hide" else "Show") } },
                    )
                    Text("Base URL example: https://api.groq.com/openai/v1. The API must support POST /chat/completions, Bearer keys and image_url data URLs.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val account = ApiAccount.create(addName, addBaseUrl, addModel, addKind)
                    vm.addApiAccount(account, addKey)
                    showAddApi = false
                    addKey = ""
                }, enabled = ApiAccount.create(addName, addBaseUrl, addModel, addKind).isConfigurationValid() && addKey.isNotBlank()) { Text("Add account") }
            },
            dismissButton = { TextButton(onClick = { showAddApi = false; addKey = "" }) { Text("Cancel") } },
        )
    }

    confirm?.let { c ->
        val (title, action) = when (c) {
            Confirm.HISTORY -> "Clear all history?" to { vm.clearHistory() }
            Confirm.SCREENSHOTS -> "Delete saved screenshot thumbnails?" to { vm.deleteScreenshots() }
            Confirm.CONVERSATIONS -> "Clear explanations and follow-up conversations?" to { vm.clearConversations() }
            Confirm.RESET_USAGE -> "Reset the token and request counters?" to { vm.resetUsage() }
        }
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(title) },
            text = { Text("This cannot be undone.") },
            confirmButton = { TextButton(onClick = { action(); confirm = null }) { Text("Confirm") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ApiAccountCard(
    account: ApiAccount,
    index: Int,
    total: Int,
    savedKey: Boolean,
    maskedKey: String,
    connection: ConnectionUi,
    onUpdate: (ApiAccount) -> Unit,
    onSaveKey: (String) -> Unit,
    onDeleteKey: () -> Unit,
    onTest: () -> Unit,
    onMove: (Int) -> Unit,
    onRemove: () -> Unit,
) {
    var name by remember(account.id, account.name) { mutableStateOf(account.name) }
    var baseUrl by remember(account.id, account.baseUrl) { mutableStateOf(account.baseUrl) }
    var model by remember(account.id, account.model) { mutableStateOf(account.model) }
    var keyInput by remember(account.id) { mutableStateOf("") }
    var showKey by remember(account.id) { mutableStateOf(false) }
    SectionCard("${index + 1}. ${account.kind.label}") {
        OutlinedTextField(name, { name = it }, label = { Text("Account name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(baseUrl, { baseUrl = it }, label = { Text("HTTPS base URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(model, { model = it }, label = { Text("Vision model ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Text(if (savedKey) "Saved key: $maskedKey" else "No key saved for this account.", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(
            value = keyInput, onValueChange = { keyInput = it }, label = { Text(if (savedKey) "Replace API key" else "API key") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = { TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "Hide" else "Show") } },
        )
        SwitchRow("Use this account", "Disabled accounts are skipped during fallback.", account.enabled) { onUpdate(account.copy(enabled = it)) }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = { onSaveKey(keyInput); keyInput = "" }, enabled = keyInput.isNotBlank()) { Text("Save key") }
            OutlinedButton(onClick = onDeleteKey, enabled = savedKey) { Text("Delete key") }
            OutlinedButton(onClick = onTest, enabled = savedKey && !connection.testing) { Text(if (connection.testing) "Testing…" else "Test") }
        }
        Text(connection.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = { onMove(-1) }, enabled = index > 0) { Text("Move up") }
            OutlinedButton(onClick = { onMove(1) }, enabled = index < total - 1) { Text("Move down") }
            TextButton(onClick = { onUpdate(account.copy(name = name.trim(), baseUrl = baseUrl.trim(), model = model.trim())) }) { Text("Save settings") }
            TextButton(onClick = onRemove) { Text("Remove") }
        }
    }
}

@Composable
private fun UsageBlock(u: com.snapgpt.ai.UsageStats, rate: com.snapgpt.ai.RateLimitInfo, onReset: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Usage", style = MaterialTheme.typography.titleSmall)
        Text("Requests made: ${u.requests}  ·  successful: ${u.successes}  ·  failed: ${u.failures}  ·  served from cache: ${u.cacheHits}", style = MaterialTheme.typography.bodySmall)
        Text("Actual tokens reported by API providers", style = MaterialTheme.typography.labelLarge)
        Text("Input ${u.actualInputTokens} · Output ${u.actualOutputTokens} · Total ${u.actualTotalTokens}", style = MaterialTheme.typography.bodySmall)
        Text("Estimated locally (about 4 characters per token + 2048 per image; not billing data)", style = MaterialTheme.typography.labelLarge)
        Text("Input ${u.estimatedInputTokens} · Output ${u.estimatedOutputTokens} · Total ${u.estimatedTotalTokens}", style = MaterialTheme.typography.bodySmall)
        val limits = listOfNotNull(
            rate.remainingRequests?.let { "requests left $it" }, rate.limitRequests?.let { "request limit $it" },
            rate.remainingTokens?.let { "tokens left $it" }, rate.limitTokens?.let { "token limit $it" },
        )
        if (limits.isNotEmpty()) Text("Latest limits sent by the last API account: ${limits.joinToString(", ")}", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = onReset) { Text("Reset counters") }
    }
}
