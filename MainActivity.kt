package com.snapgpt

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.snapgpt.ai.GroqClient
import com.snapgpt.data.HistoryDatabase
import com.snapgpt.data.SecureKeyStore
import com.snapgpt.data.SettingsStore
import com.snapgpt.data.UserSettings
import com.snapgpt.overlay.FloatingAssistantService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SnapGptApp(this) }
    }

    fun enableAssistant() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 901)
        }
        ContextCompat.startForegroundService(this, Intent(this, FloatingAssistantService::class.java).setAction(FloatingAssistantService.ACTION_START))
    }

    fun disableAssistant() {
        startService(Intent(this, FloatingAssistantService::class.java).setAction(FloatingAssistantService.ACTION_STOP))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SnapGptApp(activity: MainActivity) {
    var tab by remember { mutableStateOf(0) }
    MaterialTheme {
        Scaffold(
            topBar = {
                TopAppBar(title = {
                    Column {
                        Text("SnapGPT", fontWeight = FontWeight.Bold)
                        Text("Your floating JEE study assistant", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                })
            },
            bottomBar = {
                NavigationBar {
                    listOf("Home", "Settings", "History").forEachIndexed { index, label ->
                        NavigationBarItem(selected = tab == index, onClick = { tab = index },
                            icon = { Text(listOf("⌂", "⚙", "◷")[index]) }, label = { Text(label) })
                    }
                }
            }
        ) { padding ->
            Surface(Modifier.fillMaxSize().padding(padding)) {
                when (tab) {
                    0 -> HomeScreen(activity)
                    1 -> SettingsScreen(activity)
                    else -> HistoryScreen(activity)
                }
            }
        }
    }
}

@Composable
private fun HomeScreen(activity: MainActivity) {
    val context = activity
    val scope = rememberCoroutineScope()
    var keyExists by remember { mutableStateOf(SecureKeyStore.read(context) != null) }
    var assistantOn by remember { mutableStateOf(false) }
    val settings by remember { SettingsStore(context).settings }.collectAsState(initial = UserSettings())
    val usage = context.getSharedPreferences("api_usage", 0)
    val input = usage.getInt("input_tokens", 0)
    val output = usage.getInt("output_tokens", 0)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF14263A))) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("AI screen assistant", color = Color(0xFF75E0C1), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("Capture a question you are studying, see a concise answer beside it, then tap the answer for a full JEE-level explanation.", color = Color.White)
                Text("Study and practice use: follow your school or exam rules.", color = Color(0xFFB8C8D8), style = MaterialTheme.typography.labelSmall)
            }
        }
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Assistant", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(if (Settings.canDrawOverlays(context)) "Display over other apps: allowed" else "Display over other apps: permission needed")
                Text(if (keyExists) "Groq API key: saved on this device" else "Groq API key: not configured")
                Text("Model: ${settings.model}")
                Button(onClick = {
                    if (!keyExists) { activity.enableAssistant() }
                    else { activity.enableAssistant(); assistantOn = true }
                }, modifier = Modifier.fillMaxWidth()) { Text("Enable floating assistant") }
                OutlinedButton(onClick = { activity.disableAssistant(); assistantOn = false }, modifier = Modifier.fillMaxWidth()) { Text("Stop assistant") }
                Text("To scan: tap the floating S button → Scan entire screen or Select question area. Android will ask you to confirm each screen-capture session.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Usage recorded by this app", fontWeight = FontWeight.SemiBold)
                Text("Requests: ${usage.getInt("requests", 0)}")
                Text("Input tokens: $input  ·  Output tokens: $output")
                Text("These are local totals from successful responses, not your live Groq account quota.", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun SettingsScreen(activity: MainActivity) {
    val context = activity
    val store = remember { SettingsStore(context) }
    val settings by store.settings.collectAsState(initial = UserSettings())
    val scope = rememberCoroutineScope()
    var keyInput by remember { mutableStateOf("") }
    var keyVisible by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf(if (SecureKeyStore.read(context) != null) "API key is saved securely on this device." else "No API key saved.") }
    var testing by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Groq connection", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("Get a key from console.groq.com/keys. The key is encrypted at rest with Android Keystore; a key used by a client app can still be exposed on a compromised or instrumented device.", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(
            value = keyInput, onValueChange = { keyInput = it }, label = { Text("Groq API key") },
            modifier = Modifier.fillMaxWidth(), singleLine = true,
            visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { keyVisible = !keyVisible }) { Text(if (keyVisible) "Hide" else "Show") }
            Button(onClick = {
                try { SecureKeyStore.save(context, keyInput.trim()); keyInput = ""; status = "API key saved." }
                catch (e: Exception) { status = "Could not save key: ${e.message}" }
            }, enabled = keyInput.isNotBlank()) { Text("Save key") }
            OutlinedButton(onClick = { SecureKeyStore.delete(context); keyInput = ""; status = "API key deleted." }) { Text("Delete") }
        }
        Button(onClick = {
            testing = true; status = "Testing connection…"
            scope.launch {
                try { status = GroqClient(context).testConnection() }
                catch (e: Exception) { status = e.message ?: "Connection failed." }
                testing = false
            }
        }, enabled = !testing, modifier = Modifier.fillMaxWidth()) { Text(if (testing) "Testing…" else "Test Groq connection") }
        Text(status, color = if (status.contains("Connected")) Color(0xFF16805D) else MaterialTheme.colorScheme.onSurfaceVariant)
        Divider()
        Text("AI model", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text("Default vision model: qwen/qwen3.8-27b", style = MaterialTheme.typography.bodyMedium)
        Text("The app checks the selected model by calling Groq. Model availability and rate limits are controlled by Groq and your account.", style = MaterialTheme.typography.bodySmall)
        Divider()
        Text("JEE solving mode", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        listOf("Automatic", "JEE Main", "JEE Advanced").forEach { mode ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.RadioButton(selected = settings.examMode == mode, onClick = { scope.launch { store.update { it.copy(examMode = mode) } } })
                Text(mode)
            }
        }
        SettingSwitch("Restrict methods to JEE syllabus", settings.syllabusRestriction) { v -> scope.launch { store.update { it.copy(syllabusRestriction = v) } } }
        SettingSwitch("Show subject, chapter and topic", settings.showChapter) { v -> scope.launch { store.update { it.copy(showChapter = v) } } }
        SettingSwitch("Save scan history on this device", settings.saveHistory) { v -> scope.launch { store.update { it.copy(saveHistory = v) } } }
        Text("Maximum questions per scan: ${settings.maxQuestions}")
        Slider(value = settings.maxQuestions.toFloat(), onValueChange = { value -> scope.launch { store.update { it.copy(maxQuestions = value.toInt().coerceIn(1, 15)) } } }, valueRange = 1f..15f, steps = 13)
        Text("Floating button idle opacity: ${(settings.bubbleOpacity * 100).toInt()}%")
        Slider(value = settings.bubbleOpacity, onValueChange = { value -> scope.launch { store.update { it.copy(bubbleOpacity = value) } } }, valueRange = 0.15f..1f)
        Text("Privacy: screenshots are sent to Groq only after you start a scan. Android secure-window restrictions may prevent some apps from being captured. Avoid scanning passwords, payment screens or sensitive personal information.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun HistoryScreen(activity: MainActivity) {
    val dao = remember { HistoryDatabase.get(activity).historyDao() }
    val history by dao.observeAll().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Recent scans", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            OutlinedButton(onClick = { scope.launch { dao.clear() } }) { Text("Clear") }
        }
        if (history.isEmpty()) Text("No saved scans yet. Scan a practice question to see it here.")
        history.forEach { item ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${item.subject} · ${item.chapter}", color = Color(0xFF16805D), fontWeight = FontWeight.SemiBold)
                    Text(item.question)
                    Text("Answer: ${item.answer}", fontWeight = FontWeight.Bold)
                    Text(java.text.DateFormat.getDateTimeInstance().format(java.util.Date(item.createdAt)), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
