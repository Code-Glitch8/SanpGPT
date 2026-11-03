package com.snapgpt.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.snapgpt.ai.providers.AccountStats
import com.snapgpt.ai.providers.ApiAccount
import java.text.DateFormat
import java.util.Date

/**
 * Per-account usage. "Actual" tokens are what the provider reported; "estimated" is a local guess (about 4 characters per
 * token + 2048 per image) and is labelled as such. Remaining quota is shown only when a provider reported it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsageScreen(vm: MainViewModel, onBack: () -> Unit) {
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val now = System.currentTimeMillis()
    val total = accounts.fold(AccountStats()) { acc, a -> acc + a.stats }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Usage dashboard") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard("All accounts") { StatsBlock(total) }
            if (accounts.isEmpty()) Text("No accounts yet.", style = MaterialTheme.typography.bodyMedium)
            accounts.forEach { a -> AccountUsage(a, now, onReset = { vm.resetStats(a.id) }) }
            Text(
                "Quota left is never guessed: it is shown only if the provider reports it in its responses. Providers that do not report it simply show nothing.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 24.dp),
            )
        }
    }
}

@Composable
private fun AccountUsage(a: ApiAccount, now: Long, onReset: () -> Unit) {
    SectionCard(a.name) {
        Text("${a.provider.displayName} · ${a.model.ifBlank { "no model" }}", style = MaterialTheme.typography.bodySmall)
        StatsBlock(a.stats)
        Text(
            "Last success: " + if (a.lastSuccessAt > 0) DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(a.lastSuccessAt)) else "never",
            style = MaterialTheme.typography.bodySmall,
        )
        Text("Last error: ${a.lastError ?: "none"}", style = MaterialTheme.typography.bodySmall)
        Text("Cooldown: " + if (a.inCooldown(now)) "${com.snapgpt.ai.ErrorMapper.formatWait(a.cooldownRemainingSeconds(now))} left" + (a.cooldownReason?.let { " ($it)" } ?: "") else "none", style = MaterialTheme.typography.bodySmall)
        Text(a.rateNote ?: "Provider has not reported remaining quota.", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = onReset) { Text("Reset this account's counters") }
    }
}

@Composable
private fun StatsBlock(s: AccountStats) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("Requests ${s.requests} · successful ${s.successes} · failed ${s.failures} · quota errors ${s.quotaErrors}", style = MaterialTheme.typography.bodySmall)
        Text("Analysis ${s.analysisRequests} · explanations ${s.explanationRequests} · follow-ups ${s.followUpRequests} · verification ${s.verificationRequests}", style = MaterialTheme.typography.bodySmall)
        Text("Actual tokens (reported by the provider)", style = MaterialTheme.typography.labelLarge)
        Text("Input ${s.actualInputTokens} · Output ${s.actualOutputTokens} · Total ${s.actualTotalTokens}", style = MaterialTheme.typography.bodySmall)
        Text("Estimated locally (not billing data)", style = MaterialTheme.typography.labelLarge)
        Text("Input ${s.estimatedInputTokens} · Output ${s.estimatedOutputTokens} · Total ${s.estimatedTotalTokens}", style = MaterialTheme.typography.bodySmall)
    }
}
