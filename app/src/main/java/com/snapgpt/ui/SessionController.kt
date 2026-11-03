package com.snapgpt.ui

import com.snapgpt.AppContainer
import com.snapgpt.ai.AiException
import com.snapgpt.ai.ChatMessage
import com.snapgpt.history.StoredQuestion
import com.snapgpt.history.StoredSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Re-opens a saved scan: shows its questions and answers, explains a question on demand (text only, because the full
 * screenshot is not stored) and continues the conversation with follow-up questions.
 */
class SessionController(private val c: AppContainer, private val sessionId: Long) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _session = MutableStateFlow<StoredSession?>(null)
    val session: StateFlow<StoredSession?> = _session.asStateFlow()
    private val _explanations = MutableStateFlow<Map<Long, String>>(emptyMap())
    val explanations: StateFlow<Map<Long, String>> = _explanations.asStateFlow()
    private val _chats = MutableStateFlow<Map<Long, List<ChatMessage>>>(emptyMap())
    val chats: StateFlow<Map<Long, List<ChatMessage>>> = _chats.asStateFlow()
    private val _busy = MutableStateFlow<Set<Long>>(emptySet())
    val busy: StateFlow<Set<Long>> = _busy.asStateFlow()
    private val _errors = MutableStateFlow<Map<Long, String>>(emptyMap())
    val errors: StateFlow<Map<Long, String>> = _errors.asStateFlow()
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    init {
        scope.launch {
            val s = c.history.load(sessionId)
            _session.value = s
            s?.questions?.forEach { q ->
                q.explanation?.let { e -> _explanations.update { it + (q.rowId to e) } }
                val msgs = c.history.messages(q.rowId)
                if (msgs.isNotEmpty()) _chats.update { it + (q.rowId to msgs) }
            }
            _loaded.value = true
        }
    }

    fun explain(q: StoredQuestion, detailed: Boolean = false) {
        if (q.rowId in _busy.value) return
        scope.launch {
            _busy.update { it + q.rowId }
            _errors.update { it - q.rowId }
            try {
                val out = c.ai.explain(q.question, null, detailed) { }
                _explanations.update { it + (q.rowId to out.text) }
                c.history.saveExplanation(q.rowId, out.text)
                c.history.addTokens(sessionId, out.usage)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AiException) {
                _errors.update { it + (q.rowId to (e.message ?: "Failed")) }
            } catch (e: Exception) {
                _errors.update { it + (q.rowId to "Failed (${e.javaClass.simpleName})") }
            } finally {
                _busy.update { it - q.rowId }
            }
        }
    }

    fun ask(q: StoredQuestion, text: String) {
        val explanation = _explanations.value[q.rowId] ?: return
        if (q.rowId in _busy.value || text.isBlank()) return
        scope.launch {
            _busy.update { it + q.rowId }
            _errors.update { it - q.rowId }
            try {
                val history = _chats.value[q.rowId].orEmpty()
                val out = c.ai.followUp(q.question, explanation, history, text.trim(), null) { }
                val updated = history + ChatMessage("user", text.trim()) + ChatMessage("assistant", out.text)
                _chats.update { it + (q.rowId to updated) }
                c.history.addMessage(q.rowId, "user", text.trim())
                c.history.addMessage(q.rowId, "assistant", out.text)
                c.history.addTokens(sessionId, out.usage)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AiException) {
                _errors.update { it + (q.rowId to (e.message ?: "Failed")) }
            } catch (e: Exception) {
                _errors.update { it + (q.rowId to "Failed (${e.javaClass.simpleName})") }
            } finally {
                _busy.update { it - q.rowId }
            }
        }
    }

    fun close() = scope.cancel()
}
