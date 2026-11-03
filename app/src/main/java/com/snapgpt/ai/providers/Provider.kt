package com.snapgpt.ai.providers

import com.snapgpt.ai.TokenUsage

/** Supported providers. Each has its own wire format (see the matching adapter). */
enum class ProviderType(
    val key: String,
    val displayName: String,
    val defaultEndpoint: String,
    val defaultModel: String,
    /** The user must type the endpoint (custom OpenAI-compatible servers). For others it is an optional override. */
    val endpointRequired: Boolean,
    val modelHint: String,
) {
    GROQ("groq", "Groq", "https://api.groq.com/openai/v1", "qwen/qwen3.8-27b", false, "e.g. qwen/qwen3.8-27b"),
    GEMINI("gemini", "Google Gemini", "https://generativelanguage.googleapis.com/v1beta", "gemini-2.5-flash", false, "e.g. gemini-2.5-flash"),
    OPENROUTER("openrouter", "OpenRouter", "https://openrouter.ai/api/v1", "", false, "e.g. google/gemini-2.5-flash"),
    ANTHROPIC("anthropic", "Anthropic", "https://api.anthropic.com/v1", "claude-sonnet-5-5", false, "e.g. claude-sonnet-5-5"),
    CUSTOM_OPENAI("custom", "Custom (OpenAI-compatible)", "", "", true, "model id exactly as your server names it");

    companion object {
        fun fromKey(key: String?) = entries.firstOrNull { it.key == key } ?: CUSTOM_OPENAI
    }
}

enum class ConnectionStatus(val label: String) {
    UNTESTED("Not tested"), CONNECTED("Connected"), INVALID_KEY("Invalid key"), MODEL_MISSING("Model not found"),
    NETWORK_ERROR("Network error"), ERROR("Error"),
}

/** Why an account is (or is not) being used; used for the "last request" kinds in usage statistics. */
enum class RequestKind { ANALYSIS, EXPLANATION, VERIFICATION, FOLLOW_UP, TEST }

enum class ImageSupport { YES, NO, UNKNOWN }

/**
 * Per-account request and token accounting.
 * ACTUAL token values come from the provider's own usage report; ESTIMATED values are a local guess shown separately.
 * Remaining quota is never stored here because it is never invented.
 */
data class AccountStats(
    val requests: Int = 0,
    val successes: Int = 0,
    val failures: Int = 0,
    val quotaErrors: Int = 0,
    val actualInputTokens: Long = 0,
    val actualOutputTokens: Long = 0,
    val estimatedInputTokens: Long = 0,
    val estimatedOutputTokens: Long = 0,
    val analysisRequests: Int = 0,
    val explanationRequests: Int = 0,
    val verificationRequests: Int = 0,
    val followUpRequests: Int = 0,
) {
    val actualTotalTokens: Long get() = actualInputTokens + actualOutputTokens
    val estimatedTotalTokens: Long get() = estimatedInputTokens + estimatedOutputTokens

    private fun byKind(kind: RequestKind): AccountStats = when (kind) {
        RequestKind.ANALYSIS -> copy(analysisRequests = analysisRequests + 1)
        RequestKind.EXPLANATION -> copy(explanationRequests = explanationRequests + 1)
        RequestKind.VERIFICATION -> copy(verificationRequests = verificationRequests + 1)
        RequestKind.FOLLOW_UP -> copy(followUpRequests = followUpRequests + 1)
        RequestKind.TEST -> this
    }

    fun recordSuccess(kind: RequestKind, actual: TokenUsage?, estimatedInput: Long, estimatedOutput: Long): AccountStats = byKind(kind).copy(
        requests = requests + 1,
        successes = successes + 1,
        actualInputTokens = actualInputTokens + (actual?.promptTokens ?: 0),
        actualOutputTokens = actualOutputTokens + (actual?.completionTokens ?: 0),
        estimatedInputTokens = estimatedInputTokens + estimatedInput,
        estimatedOutputTokens = estimatedOutputTokens + estimatedOutput,
    )

    /** A request that reached the provider and failed. Only what was sent is estimated; actual tokens only if the provider reported them. */
    fun recordFailure(kind: RequestKind, quota: Boolean, actual: TokenUsage?, estimatedInput: Long): AccountStats = byKind(kind).copy(
        requests = requests + 1,
        failures = failures + 1,
        quotaErrors = quotaErrors + if (quota) 1 else 0,
        actualInputTokens = actualInputTokens + (actual?.promptTokens ?: 0),
        actualOutputTokens = actualOutputTokens + (actual?.completionTokens ?: 0),
        estimatedInputTokens = estimatedInputTokens + estimatedInput,
    )

    operator fun plus(o: AccountStats) = AccountStats(
        requests + o.requests, successes + o.successes, failures + o.failures, quotaErrors + o.quotaErrors,
        actualInputTokens + o.actualInputTokens, actualOutputTokens + o.actualOutputTokens,
        estimatedInputTokens + o.estimatedInputTokens, estimatedOutputTokens + o.estimatedOutputTokens,
        analysisRequests + o.analysisRequests, explanationRequests + o.explanationRequests,
        verificationRequests + o.verificationRequests, followUpRequests + o.followUpRequests,
    )
}

/**
 * One user-configured API account. The API key itself is NOT part of this class: it is stored encrypted and fetched only
 * at request time (see security/ and settings/AccountRepository).
 */
data class ApiAccount(
    val id: String,
    val name: String,
    val provider: ProviderType,
    /** Blank = provider default. */
    val endpoint: String = "",
    val model: String = provider.defaultModel,
    val enabled: Boolean = true,
    /** Lower number = tried first. */
    val priority: Int = 0,
    /** User/validation override for image support: true/false, or null to use the built-in knowledge for this provider. */
    val imageSupportOverride: Boolean? = null,
    val status: ConnectionStatus = ConnectionStatus.UNTESTED,
    val lastError: String? = null,
    val lastSuccessAt: Long = 0,
    val cooldownUntil: Long = 0,
    val cooldownReason: String? = null,
    /** Note built only from rate-limit headers the provider actually sent. */
    val rateNote: String? = null,
    val stats: AccountStats = AccountStats(),
) {
    val effectiveEndpoint: String get() = Endpoints.normalize(endpoint.ifBlank { provider.defaultEndpoint })

    fun inCooldown(now: Long): Boolean = cooldownUntil > now

    fun cooldownRemainingSeconds(now: Long): Int = if (cooldownUntil <= now) 0 else (((cooldownUntil - now) + 999) / 1000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    fun imageSupport(): ImageSupport = ModelCapabilities.imageSupport(this)

    /** True when screenshots may be sent to this account's model. UNKNOWN models are excluded until validated or confirmed. */
    fun supportsImages(): Boolean = imageSupport() == ImageSupport.YES

    /** Where the user's screenshot would go; shown on the confirmation card. */
    fun destinationLabel(): String = "$name (${provider.displayName}, ${model.ifBlank { "no model set" }})"
}

object Endpoints {
    /** Trim, drop a trailing slash and a pasted "/chat/completions" so users can paste either form. */
    fun normalize(raw: String): String {
        var s = raw.trim().trimEnd('/')
        for (suffix in listOf("/chat/completions", "/messages")) if (s.endsWith(suffix)) s = s.removeSuffix(suffix).trimEnd('/')
        return s
    }

    private fun host(url: String): String? = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://(?:[^@/]*@)?(\\[[^]]+]|[^:/?#]+)").find(url)?.groupValues?.get(1)?.lowercase()

    /** HTTPS is required; plain http is allowed only to this device (loopback), e.g. for a local test server. */
    fun isAllowed(url: String): Boolean {
        val u = url.trim().lowercase()
        if (u.startsWith("https://")) return host(u) != null
        if (u.startsWith("http://")) return host(u) in setOf("localhost", "127.0.0.1", "[::1]")
        return false
    }

    /** User-facing validation message for an endpoint field, or null if it is fine. */
    fun validate(type: ProviderType, raw: String): String? {
        val s = raw.trim()
        if (s.isEmpty()) return if (type.endpointRequired) "An endpoint URL is required for this provider." else null
        if (!isAllowed(normalize(s))) return "The endpoint must start with https:// (plain http is not allowed because your API key would be sent unencrypted)."
        return null
    }
}

/** What SnapGPT knows (or refuses to assume) about whether a model accepts images. */
object ModelCapabilities {
    fun imageSupport(a: ApiAccount): ImageSupport {
        a.imageSupportOverride?.let { return if (it) ImageSupport.YES else ImageSupport.NO }
        return byModelName(a.provider, a.model)
    }

    fun byModelName(provider: ProviderType, model: String): ImageSupport {
        val m = model.lowercase().removePrefix("models/")
        if (m.isBlank()) return ImageSupport.UNKNOWN
        val never = listOf("whisper", "guard", "tts", "orpheus", "embed", "rerank", "moderation", "audio")
        if (never.any { m.contains(it) }) return ImageSupport.NO
        return when (provider) {
            ProviderType.GROQ ->
                if (listOf("qwen3.8", "llama-4-scout", "llama-4-maverick", "vision", "-vl").any { m.contains(it) }) ImageSupport.YES else ImageSupport.UNKNOWN
            ProviderType.GEMINI -> when {
                m.startsWith("gemini") && !m.contains("imagen") && !m.contains("aqa") -> ImageSupport.YES
                else -> ImageSupport.UNKNOWN
            }
            ProviderType.ANTHROPIC -> when {
                m.startsWith("claude-2") || m.contains("instant") -> ImageSupport.NO
                m.startsWith("claude") -> ImageSupport.YES
                else -> ImageSupport.UNKNOWN
            }
            ProviderType.OPENROUTER ->
                if (listOf("vision", "-vl", "gpt-4o", "gpt-4.1", "gpt-5", "gemini", "claude", "llama-4", "pixtral", "qwen3.8").any { m.contains(it) }) ImageSupport.YES else ImageSupport.UNKNOWN
            ProviderType.CUSTOM_OPENAI -> ImageSupport.UNKNOWN
        }
    }
}
