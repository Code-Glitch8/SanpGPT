package com.snapgpt.ai.providers

import com.snapgpt.ai.AiErrorCode
import com.snapgpt.ai.AiException
import com.snapgpt.ai.ChatMessage
import com.snapgpt.ai.ChatRequest
import com.snapgpt.ai.ChatResult
import com.snapgpt.ai.JsonExtract
import com.snapgpt.ai.RateLimitInfo
import com.snapgpt.ai.TokenUsage
import com.snapgpt.networking.HttpTransport
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Anthropic Messages API: `/v1/messages`, `x-api-key`, top-level `system`, base64 image blocks, `usage.input_tokens/output_tokens`. */
class AnthropicAdapter(transport: HttpTransport) : BaseAdapter(transport, ProviderType.ANTHROPIC) {
    // Anthropic documents a 5 MB limit per image.
    override val maxImageBase64Length: Int = 5 * 1024 * 1024

    override fun chatUrl(a: ApiAccount) = a.effectiveEndpoint + "/messages"
    override fun modelsUrl(a: ApiAccount): String? = a.effectiveEndpoint + "/models?limit=1000"

    override fun headers(key: String, hasBody: Boolean): Map<String, String> = buildMap {
        put("x-api-key", key)
        put("anthropic-version", "2023-06-01")
        put("Accept", "application/json")
        if (hasBody) put("Content-Type", "application/json")
    }

    override fun rateLimitOf(h: Map<String, String>) = RateLimitInfo(
        limitRequests = h["anthropic-ratelimit-requests-limit"],
        remainingRequests = h["anthropic-ratelimit-requests-remaining"],
        limitTokens = h["anthropic-ratelimit-tokens-limit"],
        remainingTokens = h["anthropic-ratelimit-tokens-remaining"],
        resetRequests = h["anthropic-ratelimit-requests-reset"],
        resetTokens = h["anthropic-ratelimit-tokens-reset"],
        retryAfter = h["retry-after"],
    )

    /** Anthropic requires alternating user/assistant turns starting with a user turn, so neighbours with the same role are merged. */
    private fun mergeTurns(messages: List<ChatMessage>): List<ChatMessage> {
        val out = ArrayList<ChatMessage>()
        for (m in messages.filter { it.role != "system" }) {
            val role = if (m.role == "assistant") "assistant" else "user"
            val last = out.lastOrNull()
            if (last != null && last.role == role) out[out.size - 1] = ChatMessage(role, last.text + "\n\n" + m.text, last.imageDataUrls + m.imageDataUrls)
            else out.add(ChatMessage(role, m.text, m.imageDataUrls))
        }
        if (out.firstOrNull()?.role == "assistant") out.add(0, ChatMessage("user", "(continue)"))
        return out
    }

    override fun buildBody(a: ApiAccount, request: ChatRequest): String = buildJsonObject {
        put("model", request.model)
        put("max_tokens", request.maxCompletionTokens)
        put("temperature", request.temperature)
        val system = request.messages.filter { it.role == "system" }.joinToString("\n\n") { it.text }
        if (system.isNotBlank()) put("system", system)
        putJsonArray("messages") {
            mergeTurns(request.messages).forEach { m ->
                add(
                    buildJsonObject {
                        put("role", m.role)
                        putJsonArray("content") {
                            // Images first: Anthropic recommends image-then-text ordering.
                            m.imageDataUrls.forEach { url ->
                                val d = DataUrl.parse(url)
                                add(buildJsonObject {
                                    put("type", "image")
                                    putJsonObject("source") { put("type", "base64"); put("media_type", d.mime); put("data", d.base64) }
                                })
                            }
                            add(buildJsonObject { put("type", "text"); put("text", m.text.ifBlank { "(empty)" }) })
                        }
                    },
                )
            }
        }
    }.toString()

    override fun parseChat(body: String, a: ApiAccount): ChatResult {
        val root = try { json.parseToJsonElement(body).jsonObject } catch (e: Exception) {
            throw AiException(AiErrorCode.INVALID_RESPONSE, "$label returned an unreadable response. Please try again.")
        }
        val text = ((root["content"] as? JsonArray).orEmpty()).mapNotNull { b ->
            val o = b as? JsonObject ?: return@mapNotNull null
            if ((o["type"] as? JsonPrimitive)?.contentOrNull == "text") (o["text"] as? JsonPrimitive)?.contentOrNull else null
        }.joinToString("")
        val stop = (root["stop_reason"] as? JsonPrimitive)?.contentOrNull
        val usage = (root["usage"] as? JsonObject)?.let {
            val i = (it["input_tokens"] as? JsonPrimitive)?.intOrNull
            val o = (it["output_tokens"] as? JsonPrimitive)?.intOrNull
            if (i == null && o == null) null else TokenUsage(i ?: 0, o ?: 0, (i ?: 0) + (o ?: 0))
        }
        if (text.isBlank()) {
            if (stop == "max_tokens") throw AiException(AiErrorCode.TRUNCATED, "$label used the whole response budget and returned no answer. Try a smaller area or fewer questions.")
            if (stop == "refusal") throw AiException(AiErrorCode.INVALID_RESPONSE, "$label declined to answer this request.")
            throw AiException(AiErrorCode.INVALID_RESPONSE, "$label returned an empty answer. Please try again.")
        }
        return ChatResult(JsonExtract.stripThinking(text), usage, stop)
    }

    override fun parseModels(body: String): List<ModelInfo> {
        val data = json.parseToJsonElement(body).jsonObject["data"]?.jsonArray ?: return emptyList()
        return data.mapNotNull { el ->
            val id = ((el as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            ModelInfo(id, ModelCapabilities.byModelName(ProviderType.ANTHROPIC, id).let { if (it == ImageSupport.YES) true else null })
        }
    }
}
