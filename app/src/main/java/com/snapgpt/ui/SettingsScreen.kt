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
import com.snapgpt.ai.ConnectionState
import com.snapgpt.capture.ImageQuality
import com.snapgpt.jee.ExamMode
import com.snapgpt.settings.AppSettings
import com.snapgpt.settings.DefaultPosition
import com.snapgpt.settings.ThemeMode

private enum class Confirm { HISTORY, SCREENSHOTS, CONVERSATIONS, RESET_USAGE }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val usage by vm.usage.collectAsStateWithLifecycle()
    val hasKey by vm.hasKey.collectAsStateWithLifecycle()
    val masked by vm.maskedKey.collectAsStateWithLifecycle()
    val connection by vm.connection.collectAsStateWithLifecycle()
    val rate by vm.rateLimit.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(notice) {
        notice?.let { snackbar.showSnackbar(it); vm.consumeNotice() }
    }

    var keyInput by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    var modelInput by remember(s.model) { mutableStateOf(s.model) }
    var confirm by remember { mutableStateOf<Confirm?>(null) }

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

            // ------------------------------------------------ B. AI
            SectionCard("AI (Groq)") {
                Text(
                    if (hasKey) "Saved key: $masked" else "No key saved. Create one at console.groq.com/keys (it starts with gsk_).",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = keyInput, onValueChange = { keyInput = it },
                    label = { Text("Groq API key") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = { TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "Hide" else "Show") } },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.saveKey(keyInput); keyInput = "" }, enabled = keyInput.isNotBlank()) { Text("Save key") }
                    OutlinedButton(onClick = { vm.deleteKey() }, enabled = hasKey) { Text("Delete key") }
                }
                Text(
                    "The key is encrypted with the Android Keystore. A key inside any mobile app can still be extracted by someone with control of the device, so use a dedicated key you can revoke. The code is ready for a backend proxy (see README).",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = modelInput, onValueChange = { modelInput = it },
                    label = { Text("Model") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text("Must support image input. Default: ${AiDefaults.DEFAULT_MODEL}. SnapGPT never switches models by itself.") },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.update { it.copy(model = modelInput.trim().ifBlank { AiDefaults.DEFAULT_MODEL }) } }, enabled = modelInput.trim() != s.model) { Text("Apply model") }
                    TextButton(onClick = { modelInput = AiDefaults.DEFAULT_MODEL; vm.update { it.copy(model = AiDefaults.DEFAULT_MODEL) } }) { Text("Reset to default") }
                }
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { vm.testConnection() }, enabled = hasKey && !connection.testing) { Text(if (connection.testing) "Testing…" else "Test connection") }
                    Text(
                        when (connection.state) {
                            ConnectionState.CONNECTED -> "Connected"
                            ConnectionState.INVALID_KEY -> "Invalid key"
                            ConnectionState.MODEL_MISSING -> "Model not listed"
                            ConnectionState.NETWORK_ERROR -> "No connection"
                            ConnectionState.OTHER_ERROR -> "Error"
                            null -> if (connection.testing) "Testing…" else "Not tested"
                        },
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
                Text(connection.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                Text("Screenshots are sent to Groq only when you scan or ask. Nothing is uploaded in the background.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SwitchRow("Save scan history", "Questions, answers, explanations and token counts stay on this device.", s.historyEnabled) { v -> vm.update { it.copy(historyEnabled = v) } }
                SwitchRow("Save screenshot thumbnails", "A small preview image with each history entry.", s.saveThumbnails) { v -> vm.update { it.copy(saveThumbnails = v) } }
                OutlinedButton(onClick = { confirm = Confirm.SCREENSHOTS }, modifier = Modifier.fillMaxWidth()) { Text("Delete saved screenshots") }
                OutlinedButton(onClick = { confirm = Confirm.CONVERSATIONS }, modifier = Modifier.fillMaxWidth()) { Text("Clear conversations and explanations") }
                OutlinedButton(onClick = { confirm = Confirm.HISTORY }, modifier = Modifier.fillMaxWidth()) { Text("Clear all history") }
            }
            Text("SnapGPT 1.0.0 · Model answers can be wrong.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 24.dp))
        }
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
private fun UsageBlock(u: com.snapgpt.ai.UsageStats, rate: com.snapgpt.ai.RateLimitInfo, onReset: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Usage", style = MaterialTheme.typography.titleSmall)
        Text("Requests made: ${u.requests}  ·  successful: ${u.successes}  ·  failed: ${u.failures}  ·  served from cache: ${u.cacheHits}", style = MaterialTheme.typography.bodySmall)
        Text("Actual tokens reported by Groq", style = MaterialTheme.typography.labelLarge)
        Text("Input ${u.actualInputTokens} · Output ${u.actualOutputTokens} · Total ${u.actualTotalTokens}", style = MaterialTheme.typography.bodySmall)
        Text("Estimated locally (about 4 characters per token + 2048 per image; not billing data)", style = MaterialTheme.typography.labelLarge)
        Text("Input ${u.estimatedInputTokens} · Output ${u.estimatedOutputTokens} · Total ${u.estimatedTotalTokens}", style = MaterialTheme.typography.bodySmall)
        val limits = listOfNotNull(
            rate.remainingRequests?.let { "requests left $it" }, rate.limitRequests?.let { "request limit $it" },
            rate.remainingTokens?.let { "tokens left $it" }, rate.limitTokens?.let { "token limit $it" },
        )
        if (limits.isNotEmpty()) Text("Latest limits sent by Groq: ${limits.joinToString(", ")}", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = onReset) { Text("Reset counters") }
    }
}
