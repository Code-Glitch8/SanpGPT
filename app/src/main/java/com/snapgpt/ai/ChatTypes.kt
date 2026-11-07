package com.snapgpt.ai

/** One chat message. [imageDataUrls] are base64 data URLs attached to a user message. */
data class ChatMessage(
    val role: String,
    val text: String,
    val imageDataUrls: List<String> = emptyList(),
)

/** Provider-neutral request. Each adapter translates it into its own wire format. */
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double,
    val maxCompletionTokens: Int,
    /** Ask for a JSON object where the provider supports native JSON mode. */
    val jsonMode: Boolean,
    /** Groq-specific reasoning output format; other adapters may ignore this field. */
    val reasoningFormat: String? = AiDefaults.REASONING_FORMAT,
)

/** Rate-limit values exactly as the provider sent them. Missing values stay null. */
data class RateLimitInfo(
    val limitRequests: String? = null,
    val remainingRequests: String? = null,
    val limitTokens: String? = null,
    val remainingTokens: String? = null,
    val resetRequests: String? = null,
    val resetTokens: String? = null,
    val retryAfter: String? = null,
) {
    val isEmpty: Boolean
        get() = listOf(
            limitRequests,
            remainingRequests,
            limitTokens,
            remainingTokens,
            resetRequests,
            resetTokens,
            retryAfter,
        ).all { it == null }

    /** Human-readable note built only from values the provider reported. */
    fun describe(): String? {
        val parts = buildList {
            if (remainingRequests != null) {
                add("$remainingRequests requests left" + (limitRequests?.let { " of $it" } ?: ""))
            }
            if (remainingTokens != null) {
                add("$remainingTokens tokens left" + (limitTokens?.let { " of $it" } ?: ""))
            }
        }
        return if (parts.isEmpty()) null
        else "Provider reports: " + parts.joinToString(", ") + " (current window)"
    }
}

data class ChatResult(
    val text: String,
    val usage: TokenUsage?,
    val finishReason: String?,
    val rateLimit: RateLimitInfo = RateLimitInfo(),
)

enum class ConnectionState {
    CONNECTED,
    INVALID_KEY,
    MODEL_MISSING,
    NETWORK_ERROR,
    OTHER_ERROR,
}

data class ConnectionResult(
    val state: ConnectionState,
    val message: String,
)
