package com.snapgpt.ai

import android.graphics.Bitmap
import com.snapgpt.capture.BitmapCodec
import com.snapgpt.settings.ApiAccount
import com.snapgpt.settings.SecureKeyStore
import com.snapgpt.settings.SettingsRepository
import com.snapgpt.settings.UsageRepository
import com.snapgpt.util.TextSimplifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

data class Stage1Outcome(
    val questions: List<DetectedQuestion>,
    val warnings: List<String>,
    val usage: TokenUsage?,
    val fromCache: Boolean,
    val providerUsed: String = "",
)

data class TextOutcome(val text: String, val usage: TokenUsage?, val providerUsed: String = "")

private data class RoutedChatResult(val result: ChatResult, val providerName: String, val failedProviders: List<String>)

/**
 * Multi-account AI pipeline. Each configured account has its own encrypted API key, endpoint and model.
 * Accounts are attempted in the order shown in Settings. When automatic failover is enabled, quota,
 * rate-limit, invalid-key, unavailable-model and transient server/network errors move to the next
 * enabled account. All entries use the OpenAI-compatible Chat Completions protocol with image_url inputs.
 */
class AiService(
    private val settings: SettingsRepository,
    private val keys: SecureKeyStore,
    private val usage: UsageRepository,
) {
    private val clients = ConcurrentHashMap<String, GroqClient>()
    private val stage1Cache = object : LinkedHashMap<String, Stage1Outcome>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Stage1Outcome>?) = size > 6
    }

    @Volatile var lastRateLimit: RateLimitInfo = RateLimitInfo()
        private set

    @Volatile var lastProviderUsed: String = ""
        private set

    fun clearCache() = synchronized(stage1Cache) { stage1Cache.clear() }

    /** A newly saved key should not inherit a cooldown caused by the previous key. */
    fun resetAccountCooldown(accountId: String) {
        clients.entries.filter { it.key.startsWith("$accountId|") }.forEach { it.value.resetCooldown() }
    }

    /** Drop stale HTTP clients when an account endpoint/name/kind changes or the account is removed. */
    fun invalidateAccount(accountId: String) {
        clients.keys.filter { it.startsWith("$accountId|") }.forEach { clients.remove(it) }
        synchronized(stage1Cache) { stage1Cache.clear() }
    }

    private fun clientFor(account: ApiAccount): GroqClient = clients.getOrPut("${account.id}|${account.normalizedBaseUrl()}|${account.name}|${account.kind.name}") {
        GroqClient(
            baseUrl = account.normalizedBaseUrl(),
            gate = RateLimitGate(account.name),
            providerName = account.name,
            useGroqExtensions = account.kind == com.snapgpt.settings.ApiAccountKind.GROQ,
        )
    }

    suspend fun stage1(bitmap: Bitmap, scanType: ScanType): Stage1Outcome {
        val s = settings.current()
        val encoded = withContext(Dispatchers.Default) { BitmapCodec.encode(bitmap, s.imageQuality) }
        val accountFingerprint = s.apiAccounts.joinToString(";") { "${it.id}:${it.baseUrl}:${it.model}:${it.enabled}:${it.kind.name}" }
        val cacheKey = listOf(encoded.sha256(), accountFingerprint, s.automaticApiFailover, s.examMode.key, s.syllabusRestriction, s.showChapterTopic, s.maxQuestionsPerScan, scanType).joinToString("|")
        // Read the cache while holding the monitor, but never call a suspending function
        // from inside synchronized: Kotlin correctly rejects suspension points in
        // critical sections. Record the cache hit only after leaving the monitor.
        val cachedOutcome = synchronized(stage1Cache) { stage1Cache[cacheKey] }
        if (cachedOutcome != null) {
            usage.recordCacheHit()
            return cachedOutcome.copy(fromCache = true, usage = null)
        }
        val messages = listOf(
            ChatMessage("system", Prompts.stage1System(s.jee, s.maxQuestionsPerScan)),
            ChatMessage("user", Prompts.stage1User(encoded.width, encoded.height, scanType == ScanType.AREA), listOf(encoded.dataUrl())),
        )
        val routed = call(ChatRequest(s.model, messages, 0.4, AiDefaults.STAGE1_MAX_COMPLETION_TOKENS, jsonMode = true), messages)
        val parsed = Stage1Parser.parse(routed.result.text, s.maxQuestionsPerScan, encoded.width, encoded.height)
        val providerStatus = if (routed.failedProviders.isEmpty()) {
            "Using API account: ${routed.providerName}."
        } else {
            "Fallback used after ${routed.failedProviders.joinToString(", ")}; answer served by ${routed.providerName}."
        }
        val providerWarnings = listOf(providerStatus)
        val outcome = Stage1Outcome(parsed.questions, providerWarnings + parsed.warnings, routed.result.usage, fromCache = false, providerUsed = routed.providerName)
        synchronized(stage1Cache) { stage1Cache[cacheKey] = outcome }
        return outcome
    }

    /** Stage 2: explain ONE question. The image crop is attached only when needed. */
    suspend fun explain(q: DetectedQuestion, crop: Bitmap?): TextOutcome {
        val s = settings.current()
        val attach = crop != null && Prompts.needsImageForExplanation(q, s.alwaysAttachImageInExplanation)
        val images = if (attach) listOf(withContext(Dispatchers.Default) { BitmapCodec.encode(crop!!, s.imageQuality).dataUrl() }) else emptyList()
        val messages = listOf(
            ChatMessage("system", Prompts.stage2System(s.jee)),
            ChatMessage("user", Prompts.stage2User(q, attach), images),
        )
        val routed = call(ChatRequest(s.model, messages, 0.6, AiDefaults.STAGE2_MAX_COMPLETION_TOKENS, jsonMode = false), messages)
        return TextOutcome(TextSimplifier.simplify(routed.result.text), routed.result.usage, routed.providerName)
    }

    /** Follow-up is text-only unless the question depends on a figure. */
    suspend fun followUp(q: DetectedQuestion, explanation: String, history: List<ChatMessage>, ask: String, crop: Bitmap?): TextOutcome {
        val s = settings.current()
        val attach = crop != null && q.hasFigure
        val images = if (attach) listOf(withContext(Dispatchers.Default) { BitmapCodec.encode(crop!!, s.imageQuality).dataUrl() }) else emptyList()
        val messages = buildList {
            add(ChatMessage("system", Prompts.followUpSystem(s.jee)))
            add(ChatMessage("user", Prompts.stage2User(q, attach), images))
            add(ChatMessage("assistant", explanation))
            addAll(history.takeLast(8))
            add(ChatMessage("user", ask))
        }
        val routed = call(ChatRequest(s.model, messages, 0.6, AiDefaults.FOLLOWUP_MAX_COMPLETION_TOKENS, jsonMode = false), messages)
        return TextOutcome(TextSimplifier.simplify(routed.result.text), routed.result.usage, routed.providerName)
    }

    suspend fun testConnection(accountId: String): ConnectionResult {
        val s = settings.current()
        val account = s.apiAccounts.firstOrNull { it.id == accountId }
            ?: return ConnectionResult(ConnectionState.OTHER_ERROR, "API account no longer exists.")
        val key = keys.load(account.id)
            ?: return ConnectionResult(ConnectionState.INVALID_KEY, "No key saved for ${account.name}.")
        if (!account.isConfigurationValid()) return ConnectionResult(ConnectionState.OTHER_ERROR, "Check the API name, HTTPS base URL and vision model for ${account.name}.")
        val result = clientFor(account).testConnection(key, account.model)
        lastRateLimit = clientFor(account).lastRateLimit
        when (result.state) {
            ConnectionState.CONNECTED, ConnectionState.MODEL_MISSING -> usage.recordSuccess(null, 0, 0)
            else -> usage.recordFailure(0)
        }
        return result
    }

    suspend fun testConnection(): ConnectionResult {
        val first = settings.current().apiAccounts.firstOrNull { it.enabled && keys.hasKey(it.id) }
            ?: return ConnectionResult(ConnectionState.INVALID_KEY, "Add and enable at least one API key in Settings > AI APIs.")
        return testConnection(first.id)
    }

    private suspend fun call(request: ChatRequest, messages: List<ChatMessage>): RoutedChatResult {
        val s = settings.current()
        val configured = s.apiAccounts.filter { it.enabled && keys.hasKey(it.id) }
        if (configured.isEmpty()) {
            throw AiException(AiErrorCode.NO_API_KEY, "No enabled API key is saved. Add one or more API accounts in SnapGPT > Settings > AI APIs.")
        }
        val attempts = if (s.automaticApiFailover) configured else configured.take(1)
        var lastError: AiException? = null
        val failed = mutableListOf<String>()
        for (account in attempts) {
            if (!account.isConfigurationValid()) {
                val e = AiException(AiErrorCode.BAD_REQUEST, "${account.name} has an invalid configuration. Check its HTTPS base URL and model in Settings.")
                lastError = e
                failed += "${account.name} (configuration)"
                if (!s.automaticApiFailover) throw e
                continue
            }
            val key = keys.load(account.id)
            if (key.isNullOrBlank()) {
                val e = AiException(AiErrorCode.NO_API_KEY, "No key is saved for ${account.name}.")
                lastError = e
                failed += "${account.name} (missing key)"
                if (!s.automaticApiFailover) throw e
                continue
            }

            val client = clientFor(account)
            val providerRequest = request.copy(
                model = account.model,
                // reasoning_format is a Groq-specific optional extension; omit it for generic compatible endpoints.
                reasoningFormat = if (account.kind == com.snapgpt.settings.ApiAccountKind.GROQ) request.reasoningFormat else null,
            )
            val estimatedIn = TokenEstimator.request(messages)
            try {
                val result = client.chat(key, providerRequest)
                usage.recordSuccess(result.usage, estimatedIn, TokenEstimator.text(result.text.length))
                lastRateLimit = result.rateLimit
                lastProviderUsed = account.name
                return RoutedChatResult(result, account.name, failed.toList())
            } catch (e: CancellationException) {
                throw e
            } catch (e: AiException) {
                if (e.httpStatus != null || e.code == AiErrorCode.NETWORK || e.code == AiErrorCode.TIMEOUT) usage.recordFailure(estimatedIn)
                lastError = e
                // Remember exhausted credits for a while so the next scan does not repeatedly hit
                // the same account before falling back again. A provider-supplied retry-after wins.
                if (e.code == AiErrorCode.QUOTA || (e.code == AiErrorCode.TOKEN_LIMIT && e.retryAfterSeconds == null)) {
                    client.gate.block(e.retryAfterSeconds ?: 3600)
                }
                if (!s.automaticApiFailover || !shouldFailOver(e)) throw e
                failed += "${account.name} (${failureLabel(e.code)})"
            }
        }
        val error = lastError ?: AiException(AiErrorCode.NO_API_KEY, "No usable API account is configured.")
        val tried = if (failed.isEmpty()) "No API account could be used." else "Tried: ${failed.joinToString("; ")}."
        throw AiException(error.code, "${error.message ?: "API request failed."} $tried Add another API account or check Settings > AI APIs.", error.retryAfterSeconds, error.httpStatus)
    }

    private fun shouldFailOver(e: AiException): Boolean = ApiFailoverPolicy.shouldTryNext(e.code)

    private fun failureLabel(code: AiErrorCode): String = when (code) {
        AiErrorCode.QUOTA -> "quota exhausted"
        AiErrorCode.RATE_LIMIT, AiErrorCode.TOKEN_LIMIT, AiErrorCode.COOLDOWN -> "rate limited"
        AiErrorCode.INVALID_KEY, AiErrorCode.FORBIDDEN -> "authentication or access error"
        AiErrorCode.MODEL_UNAVAILABLE -> "model unavailable"
        AiErrorCode.NETWORK, AiErrorCode.TIMEOUT -> "network error"
        AiErrorCode.SERVER -> "server error"
        AiErrorCode.INVALID_RESPONSE -> "invalid response"
        else -> code.name.lowercase()
    }
}
