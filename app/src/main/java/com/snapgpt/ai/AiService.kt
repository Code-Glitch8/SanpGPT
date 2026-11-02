package com.snapgpt.ai

import android.graphics.Bitmap
import com.snapgpt.capture.BitmapCodec
import com.snapgpt.settings.AppSettings
import com.snapgpt.settings.SecureKeyStore
import com.snapgpt.settings.SettingsRepository
import com.snapgpt.settings.UsageRepository
import com.snapgpt.util.TextSimplifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class Stage1Outcome(
    val questions: List<DetectedQuestion>,
    val warnings: List<String>,
    val usage: TokenUsage?,
    val fromCache: Boolean,
)

data class TextOutcome(val text: String, val usage: TokenUsage?)

/**
 * Two-stage pipeline.
 *  Stage 1: one image request -> concise JSON answers (never explanations).
 *  Stage 2: only when the user taps an answer -> detailed explanation of that single question.
 * Token savers: identical scans are answered from an in-memory cache, Stage 2 re-sends only a small crop (and only when
 * needed), Stage 1 output is capped, and every request is counted.
 */
class AiService(
    private val client: GroqClient,
    private val settings: SettingsRepository,
    private val keys: SecureKeyStore,
    private val usage: UsageRepository,
) {
    private val stage1Cache = object : LinkedHashMap<String, Stage1Outcome>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Stage1Outcome>?) = size > 6
    }

    fun clearCache() = stage1Cache.clear()

    private fun requireKey(): String =
        keys.load()?.takeIf { it.isNotBlank() } ?: throw AiException(AiErrorCode.NO_API_KEY, "No Groq API key is saved. Open SnapGPT > Settings > AI and add one.")

    suspend fun stage1(bitmap: Bitmap, scanType: ScanType): Stage1Outcome {
        val s = settings.current()
        val key = requireKey()
        val encoded = withContext(Dispatchers.Default) { BitmapCodec.encode(bitmap, s.imageQuality) }
        val cacheKey = listOf(encoded.sha256(), s.model, s.examMode.key, s.syllabusRestriction, s.showChapterTopic, s.maxQuestionsPerScan, scanType).joinToString("|")
        stage1Cache[cacheKey]?.let {
            usage.recordCacheHit()
            return it.copy(fromCache = true, usage = null)
        }
        val messages = listOf(
            ChatMessage("system", Prompts.stage1System(s.jee, s.maxQuestionsPerScan)),
            ChatMessage("user", Prompts.stage1User(encoded.width, encoded.height, scanType == ScanType.AREA), listOf(encoded.dataUrl())),
        )
        val result = call(key, ChatRequest(s.model, messages, 0.4, AiDefaults.STAGE1_MAX_COMPLETION_TOKENS, jsonMode = true), messages)
        val parsed = Stage1Parser.parse(result.text, s.maxQuestionsPerScan, encoded.width, encoded.height)
        val outcome = Stage1Outcome(parsed.questions, parsed.warnings, result.usage, fromCache = false)
        stage1Cache[cacheKey] = outcome
        return outcome
    }

    /** Stage 2: explain ONE question. [crop] is attached only when [Prompts.needsImageForExplanation] says it is worth the tokens. */
    suspend fun explain(q: DetectedQuestion, crop: Bitmap?): TextOutcome {
        val s = settings.current()
        val key = requireKey()
        val attach = crop != null && Prompts.needsImageForExplanation(q, s.alwaysAttachImageInExplanation)
        val images = if (attach) listOf(withContext(Dispatchers.Default) { BitmapCodec.encode(crop!!, s.imageQuality).dataUrl() }) else emptyList()
        val messages = listOf(
            ChatMessage("system", Prompts.stage2System(s.jee)),
            ChatMessage("user", Prompts.stage2User(q, attach), images),
        )
        val r = call(key, ChatRequest(s.model, messages, 0.6, AiDefaults.STAGE2_MAX_COMPLETION_TOKENS, jsonMode = false), messages)
        return TextOutcome(TextSimplifier.simplify(r.text), r.usage)
    }

    /** Follow-up question about an explained question. Text-only unless the question depends on a figure. */
    suspend fun followUp(q: DetectedQuestion, explanation: String, history: List<ChatMessage>, ask: String, crop: Bitmap?): TextOutcome {
        val s = settings.current()
        val key = requireKey()
        val attach = crop != null && q.hasFigure
        val images = if (attach) listOf(withContext(Dispatchers.Default) { BitmapCodec.encode(crop!!, s.imageQuality).dataUrl() }) else emptyList()
        val messages = buildList {
            add(ChatMessage("system", Prompts.followUpSystem(s.jee)))
            add(ChatMessage("user", Prompts.stage2User(q, attach), images))
            add(ChatMessage("assistant", explanation))
            addAll(history.takeLast(8))
            add(ChatMessage("user", ask))
        }
        val r = call(key, ChatRequest(s.model, messages, 0.6, AiDefaults.FOLLOWUP_MAX_COMPLETION_TOKENS, jsonMode = false), messages)
        return TextOutcome(TextSimplifier.simplify(r.text), r.usage)
    }

    suspend fun testConnection(): ConnectionResult {
        val s: AppSettings = settings.current()
        val key = keys.load() ?: return ConnectionResult(ConnectionState.INVALID_KEY, "No API key saved.")
        val res = client.testConnection(key, s.model)
        when (res.state) {
            ConnectionState.CONNECTED, ConnectionState.MODEL_MISSING -> usage.recordSuccess(null, 0, 0)
            else -> usage.recordFailure(0)
        }
        return res
    }

    private suspend fun call(key: String, request: ChatRequest, messages: List<ChatMessage>): ChatResult {
        val estimatedIn = TokenEstimator.request(messages)
        try {
            val r = client.chat(key, request)
            usage.recordSuccess(r.usage, estimatedIn, TokenEstimator.text(r.text.length))
            return r
        } catch (e: CancellationException) {
            throw e
        } catch (e: AiException) {
            // Only count requests that actually reached the network (pre-flight rejections cost nothing).
            if (e.httpStatus != null || e.code == AiErrorCode.NETWORK || e.code == AiErrorCode.TIMEOUT) usage.recordFailure(estimatedIn)
            throw e
        }
    }
}
