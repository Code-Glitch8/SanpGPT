package com.snapgpt.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.snapgpt.container
import com.snapgpt.history.StoredQuestion
import com.snapgpt.overlay.AnswerFormatter

/** A saved scan: answers, on-demand explanations (text only) and follow-up conversations. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionScreen(sessionId: Long, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val controller = remember(sessionId) { SessionController(ctx.container, sessionId) }
    DisposableEffect(controller) { onDispose { controller.close() } }

    val session by controller.session.collectAsStateWithLifecycle()
    val loaded by controller.loaded.collectAsStateWithLifecycle()
    val explanations by controller.explanations.collectAsStateWithLifecycle()
    val chats by controller.chats.collectAsStateWithLifecycle()
    val busy by controller.busy.collectAsStateWithLifecycle()
    val errors by controller.errors.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Saved scan") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { pad ->
        val s = session
        if (s == null) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) { Text(if (loaded) "This scan no longer exists." else "Loading…") }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Text(
                        "${s.session.model} · ${s.session.examMode} · ${s.session.totalTokens} tokens (provider-reported)",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(s.questions, key = { it.rowId }) { q ->
                    QuestionCard(
                        q = q, explanation = explanations[q.rowId], chat = chats[q.rowId].orEmpty(),
                        busy = q.rowId in busy, error = errors[q.rowId],
                        onExplain = { controller.explain(q) }, onAsk = { controller.ask(q, it) },
                    )
                }
                item {
                    Text(
                        "Explanations here use the saved question text only (the full screenshot is not stored), so figure-based questions may be less accurate than when explained live.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun QuestionCard(
    q: StoredQuestion, explanation: String?, chat: List<com.snapgpt.ai.ChatMessage>, busy: Boolean, error: String?,
    onExplain: () -> Unit, onAsk: (String) -> Unit,
) {
    var ask by remember { mutableStateOf("") }
    val d = q.question
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                listOf(d.subject, d.chapter, d.topic).filter { it.isNotBlank() && it != "Unknown" }.joinToString(" · ").ifBlank { "Question" },
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
            )
            if (d.questionText.isNotBlank()) Text(d.questionText, style = MaterialTheme.typography.bodyMedium)
            d.options.forEach { Text("${it.label}) ${it.text}", style = MaterialTheme.typography.bodySmall) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Answer: ", style = MaterialTheme.typography.bodyMedium)
                Text(AnswerFormatter.bubbleText(d), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (AnswerFormatter.isUncertain(d)) Text("   (low confidence — double-check)", style = MaterialTheme.typography.bodySmall)
            }
            if (d.verification != com.snapgpt.ai.VerificationStatus.NOT_RUN) {
                Text(
                    d.verification.label + if (d.verificationNote.isNotBlank()) ": ${d.verificationNote}" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (d.verification.isUncertain) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (explanation == null) {
                Button(onClick = onExplain, enabled = !busy) { Text(if (busy) "Explaining…" else "Explain this question") }
            } else {
                Text(explanation, style = MaterialTheme.typography.bodyMedium)
                chat.chunked(2).forEach { pair ->
                    Text("You: ${pair[0].text}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    if (pair.size > 1) Text(pair[1].text, style = MaterialTheme.typography.bodyMedium)
                }
                OutlinedTextField(
                    value = ask, onValueChange = { ask = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("Ask a follow-up") },
                )
                Button(onClick = { onAsk(ask); ask = "" }, enabled = ask.isNotBlank() && !busy) { Text(if (busy) "Thinking…" else "Ask") }
            }
            if (error != null) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}
