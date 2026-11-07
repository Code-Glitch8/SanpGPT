package com.snapgpt.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.snapgpt.capture.ConsentActivity
import com.snapgpt.services.OverlayService
import java.text.DateFormat
import java.util.Date

private enum class StartDialog {
    OVERLAY,
    KEY,
    NOTIFICATIONS,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    vm: MainViewModel,
    onSettings: () -> Unit,
    onAccounts: () -> Unit,
    onUsage: () -> Unit,
    onHistory: () -> Unit,
    onOpenSession: (Long) -> Unit,
) {
    val ctx = LocalContext.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val running by vm.running.collectAsStateWithLifecycle()
    val projection by vm.projectionReady.collectAsStateWithLifecycle()
    val perms by vm.perms.collectAsStateWithLifecycle()
    val hasAccount by vm.hasUsableAccount.collectAsStateWithLifecycle()
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.refreshPermissions()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Shared by both the Status and API accounts cards. It must be in HomeScreen scope.
    val now = System.currentTimeMillis()

    var dialog by remember { mutableStateOf<StartDialog?>(null) }

    fun launchConsent() {
        ctx.startActivity(Intent(ctx, ConsentActivity::class.java))
    }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        vm.refreshPermissions()
        launchConsent()
    }

    fun start() {
        when {
            !perms.overlay -> dialog = StartDialog.OVERLAY
            !hasAccount -> dialog = StartDialog.KEY
            Build.VERSION.SDK_INT >= 33 && !perms.notifications -> dialog = StartDialog.NOTIFICATIONS
            else -> launchConsent()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SnapGPT", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { paddingValues ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                SnapLogo(84.dp)
                Gap(8.dp)
                Text(
                    "AI floating screen assistant for JEE",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SectionCard("Floating assistant") {
                Text(
                    if (running) {
                        "The floating circle is active. Tap it over any app to scan the screen or select a question area."
                    } else {
                        "Start the assistant to show the floating circle over other apps. Android will ask you to allow screen capture; SnapGPT only captures when you tap Scan."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )

                if (running) {
                    Button(
                        onClick = { OverlayService.stop(ctx) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Stop assistant")
                    }
                } else {
                    Button(
                        onClick = { start() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Start assistant")
                    }
                }
            }

            SectionCard("Status") {
                StatusRow(
                    "Draw over other apps",
                    perms.overlay,
                    if (perms.overlay) "Allowed" else "Needed to show the floating circle",
                )
                StatusRow(
                    "Notifications",
                    perms.notifications || Build.VERSION.SDK_INT < 33,
                    if (perms.notifications) "Allowed (shows the Stop button)"
                    else "Optional; the assistant still works",
                )
                StatusRow(
                    "Screen capture",
                    projection,
                    if (projection) "Authorised for this session"
                    else "Asked each time the assistant starts",
                )

                // The account a screenshot would go to first.
                val active = accounts.firstOrNull {
                    it.enabled && it.model.isNotBlank() && !it.inCooldown(now)
                }
                StatusRow(
                    "Active provider",
                    active != null,
                    active?.let { "${it.name} (${it.provider.displayName})" } ?: "No usable account",
                )
                StatusRow(
                    "Active model",
                    active != null,
                    active?.model ?: "Add an account in Settings > API accounts",
                )
            }

            SectionCard("API accounts") {
                if (accounts.isEmpty()) {
                    Text(
                        "No accounts yet. Add a Groq, Gemini, OpenRouter, Anthropic or custom OpenAI-compatible account.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    val cooling = accounts.count { it.enabled && it.inCooldown(now) }
                    Text(
                        "${accounts.size} account(s) · ${accounts.count { it.enabled }} enabled" +
                            if (cooling > 0) " · $cooling cooling down" else "",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    accounts.take(4).forEachIndexed { index, account ->
                        Text(
                            "${index + 1}. ${account.name} · ${account.provider.displayName} · ${statusLine(account, now)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (accounts.size > 4) {
                        Text(
                            "…and ${accounts.size - 4} more",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onAccounts) {
                        Text(if (accounts.isEmpty()) "Add account" else "Manage accounts")
                    }
                    OutlinedButton(onClick = onUsage) { Text("Usage") }
                }
            }

            SectionCard("Recent scans") {
                if (sessions.isEmpty()) {
                    Text(
                        if (settings.historyEnabled) "No scans yet."
                        else "History is turned off (Settings > Privacy).",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    sessions.take(3).forEach { session ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onOpenSession(session.id) }
                                .padding(vertical = 4.dp),
                        ) {
                            Text(
                                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                                    .format(Date(session.createdAt)),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                listOf(
                                    if (session.scanType == "AREA") "Area" else "Full screen",
                                    session.summary.ifBlank { "No subject" },
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    TextButton(onClick = onHistory) { Text("See all history") }
                }
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = onHistory,
                    modifier = Modifier.weight(1f),
                ) { Text("History") }
                OutlinedButton(
                    onClick = onSettings,
                    modifier = Modifier.weight(1f),
                ) { Text("Settings") }
            }

            Text(
                "Answers are generated by an AI model and can be wrong. Always double-check important results.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
            )
        }
    }

    when (dialog) {
        StartDialog.OVERLAY -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Allow drawing over other apps") },
            text = {
                Text(
                    "SnapGPT shows its floating circle and answer bubbles on top of other apps. " +
                        "Android needs you to switch this on for SnapGPT in system settings. " +
                        "Come back here afterwards and press Start again.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        dialog = null
                        ctx.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${ctx.packageName}"),
                            ),
                        )
                    },
                ) { Text("Open settings") }
            },
            dismissButton = {
                TextButton(onClick = { dialog = null }) { Text("Not now") }
            },
        )

        StartDialog.KEY -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Add an API account first") },
            text = {
                Text(
                    "SnapGPT needs at least one enabled API account (Groq, Gemini, OpenRouter, " +
                        "Anthropic or a custom OpenAI-compatible server) to analyse screenshots. " +
                        "Add as many as you like; they act as backups for each other.",
                )
            },
            confirmButton = {
                TextButton(onClick = { dialog = null; onAccounts() }) { Text("Add account") }
            },
            dismissButton = {
                TextButton(onClick = { dialog = null }) { Text("Later") }
            },
        )

        StartDialog.NOTIFICATIONS -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Show the assistant notification") },
            text = {
                Text(
                    "While the assistant runs, Android requires a notification. It also gives you a Stop button. " +
                        "You can skip this; the assistant still works.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        dialog = null
                        notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    },
                ) { Text("Allow") }
            },
            dismissButton = {
                TextButton(onClick = { dialog = null; launchConsent() }) { Text("Skip") }
            },
        )

        null -> Unit
    }
}
