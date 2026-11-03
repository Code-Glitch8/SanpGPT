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
import com.snapgpt.capture.ImageQuality
import com.snapgpt.jee.ExamMode
import com.snapgpt.settings.AppSettings
import com.snapgpt.settings.DefaultPosition
import com.snapgpt.settings.ThemeMode

private enum class Confirm { HISTORY, SCREENSHOTS, CONVERSATIONS, DELETE_KEYS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: MainViewModel, onBack: () -> Unit, onAccounts: () -> Unit, onUsage: () -> Unit) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(notice) {
        notice?.let { snackbar.showSnackbar(it); vm.consumeNotice() }
    }

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

            // ------------------------------------------------ B. API account management
            SectionCard("API accounts") {
                val enabled = accounts.count { it.enabled }
                Text(
                    if (accounts.isEmpty()) "No accounts yet. Add a Groq, Gemini, OpenRouter, Anthropic or custom OpenAI-compatible account."
                    else "${accounts.size} account(s), $enabled enabled. There is no limit on how many you add, including several keys for one provider.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = onAccounts, modifier = Modifier.fillMaxWidth()) { Text("Manage API accounts") }
                OutlinedButton(onClick = { confirm = Confirm.DELETE_KEYS }, enabled = accounts.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text("Delete all stored API keys") }
            }

            // ------------------------------------------------ C. Automatic API failover
            SectionCard("Automatic API failover") {
                SwitchRow(
                    "Switch accounts automatically",
                    "On: if an account hits a quota or rate limit, has an invalid key, an unavailable model or a temporary outage, the next eligible account is used (same provider first). Off: only your top account is used.",
                    s.autoFailover,
                ) { v -> vm.update { it.copy(autoFailover = v) } }
                SliderRow("Retries per account for temporary errors", "{v}", s.maxRetriesPerAccount, AppSettings.MIN_RETRIES, AppSettings.MAX_RETRIES) { v -> vm.update { it.copy(maxRetriesPerAccount = v) } }
                Text(
                    "Applies to network problems, timeouts and server errors, with growing waits between tries. Rate-limit waits asked for by a provider are always respected. Malformed requests are never retried elsewhere, and SnapGPT never swaps your configured models.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ------------------------------------------------ D. Answer verification
            SectionCard("Answer verification") {
                SwitchRow(
                    "Verify answers",
                    "After each scan the question text is solved again independently and compared. Results are labelled Verified, Corrected after verification, Unable to verify or Low confidence. Uses extra requests and tokens.",
                    s.verificationEnabled,
                ) { v -> vm.update { it.copy(verificationEnabled = v) } }
                SwitchRow(
                    "Prefer a different account",
                    "Use another account than the one that gave the first answer when possible; otherwise a separate request is made on the same account.",
                    s.verifyDifferentAccount,
                ) { v -> vm.update { it.copy(verifyDifferentAccount = v) } }
                Text(
                    "\"Verified\" means an independent solve agreed and flagged no doubtful formula, sign or unit. It is not a proof: always double-check important results.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ------------------------------------------------ C. JEE
            SectionCard("JEE") {
                Text("Exam mode (used for detection and explanations)", style = MaterialTheme.typography.bodyMedium)
                RadioRows(ExamMode.entries, s.examMode, { it.label }) { m -> vm.update { it.copy(examMode = m) } }
                SwitchRow("JEE syllabus restriction", "Use only JEE Main / Advanced syllabus methods.", s.syllabusRestriction) { v -> vm.update { it.copy(syllabusRestriction = v) } }
                SwitchRow("Show chapter and topic", null, s.showChapterTopic) { v -> vm.update { it.copy(showChapterTopic = v) } }
                SwitchRow("Show alternative methods", "Adds one shorter method to explanations (uses more tokens).", s.showAlternativeMethods) { v -> vm.update { it.copy(showAlternativeMethods = v) } }
            }

            // ------------------------------------------------ F. Screenshot
            SectionCard("Screenshot") {
                Text("Use the floating circle menu to scan the entire screen or select a question area. A screenshot is only sent after you tap Confirm, and the card shows which provider receives it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SliderRow("Maximum questions per scan", "{v}", s.maxQuestionsPerScan, AppSettings.MIN_QUESTIONS, AppSettings.MAX_QUESTIONS) { v -> vm.update { it.copy(maxQuestionsPerScan = v) } }
                Text("Image quality", style = MaterialTheme.typography.bodyMedium)
                RadioRows(ImageQuality.entries, s.imageQuality, { it.label }) { q -> vm.update { it.copy(imageQuality = q) } }
                SwitchRow("Automatic answer placement", "Place each answer beside its question. Off = stack answers at the screen edge.", s.autoAnswerPlacement) { v -> vm.update { it.copy(autoAnswerPlacement = v) } }
                SwitchRow("Always send the question image for explanations", "Normally only sent for figures or unclear text (saves tokens).", s.alwaysAttachImageInExplanation) { v -> vm.update { it.copy(alwaysAttachImageInExplanation = v) } }
            }

            // ------------------------------------------------ G. Token usage
            SectionCard("Token usage") {
                Text("Requests and tokens are counted per account. Actual tokens come from the provider; estimates are labelled as such. Remaining quota is never invented.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = onUsage, modifier = Modifier.fillMaxWidth()) { Text("Open usage dashboard") }
            }

            // ------------------------------------------------ H. Appearance
            SectionCard("Appearance") {
                RadioRows(ThemeMode.entries, s.theme, { it.label }) { t -> vm.update { it.copy(theme = t) } }
            }

            // ------------------------------------------------ I. Privacy
            SectionCard("Privacy") {
                Text("A screenshot goes only to the provider named on the confirmation card, and only after you confirm. Nothing is uploaded in the background and nothing is sent to providers you have not configured. API keys are encrypted individually and can be deleted at any time.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = { confirm = Confirm.DELETE_KEYS }, enabled = accounts.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text("Delete all stored API keys") }
            }

            // ------------------------------------------------ J. History
            SectionCard("History") {
                SwitchRow("Save scan history", "Questions, answers, verification results, explanations and token counts stay on this device.", s.historyEnabled) { v -> vm.update { it.copy(historyEnabled = v) } }
                SwitchRow("Save screenshot thumbnails", "A small preview image with each history entry.", s.saveThumbnails) { v -> vm.update { it.copy(saveThumbnails = v) } }
                OutlinedButton(onClick = { confirm = Confirm.SCREENSHOTS }, modifier = Modifier.fillMaxWidth()) { Text("Delete saved screenshots") }
                OutlinedButton(onClick = { confirm = Confirm.CONVERSATIONS }, modifier = Modifier.fillMaxWidth()) { Text("Clear conversations and explanations") }
                OutlinedButton(onClick = { confirm = Confirm.HISTORY }, modifier = Modifier.fillMaxWidth()) { Text("Clear all history") }
            }
            Text("SnapGPT 2.0.0 · Model answers can be wrong.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 24.dp))
        }
    }

    confirm?.let { c ->
        val (title, action) = when (c) {
            Confirm.HISTORY -> "Clear all history?" to { vm.clearHistory() }
            Confirm.SCREENSHOTS -> "Delete saved screenshot thumbnails?" to { vm.deleteScreenshots() }
            Confirm.CONVERSATIONS -> "Clear explanations and follow-up conversations?" to { vm.clearConversations() }
            Confirm.DELETE_KEYS -> "Delete every stored API key? Accounts stay, but cannot be used until you add keys again." to { vm.deleteAllKeys() }
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
