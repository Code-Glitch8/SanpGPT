package com.snapgpt.ai.providers

import com.snapgpt.ai.AiDefaults
import com.snapgpt.ai.AiErrorCode
import com.snapgpt.ai.AiException
import com.snapgpt.ai.ChatRequest
import com.snapgpt.ai.ChatResult
import com.snapgpt.ai.JsonExtract
import com.snapgpt.ai.TokenUsage
import com.snapgpt.networking.HttpTransport
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
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

/**
 * Any server that speaks the OpenAI chat-completions format (/chat/completions with Bearer auth).
 * Used directly for custom endpoints; [GroqAdapter] and [OpenRouterAdapter] refine it.
 * Native JSON mode is NOT requested here because many compatible servers reject it; the prompts ask for JSON instead.
 */
open class OpenAICompatibleAdapter(transport: HttpTransport, type: ProviderType = ProviderType.CUSTOM_OPENAI) : BaseAdapter(transport, type) {
    override val chatFallbackForTest: Boolean get() = type == ProviderType.CUSTOM_OPENAI

    protected open val maxTokensField: String = "max_tokens"

    override fun chatUrl(a: ApiAccount) = a.effectiveEndpoint + "/chat/completions"
    override fun modelsUrl(a: ApiAccount): String? = a.effectiveEndpoint + "/models"

    protected open fun extraHeaders(): Map<String, String> = emptyMap()

    override fun headers(key: String, hasBody: Boolean): Map<String, String> = buildMap {
        put("Authorization", "Bearer $key")
        put("Accept", "application/json")
        if (hasBody) put("Content-Type", "application/json")
        putAll(extraHeaders())
    }

    /** Hook for provider-specific body fields. */
    protected open fun configureBody(b: JsonObjectBuilder, a: ApiAccount, request: ChatRequest) {}

    override fun buildBody(a: ApiAccount, request: ChatRequest): String = buildJsonObject {
        put("model", request.model)
        put("temperature", request.temperature)
        put(maxTokensField, request.maxCompletionTokens)
        put("stream", false)
        configureBody(this, a, request)
        putJsonArray("messages") {
            request.messages.forEach { m ->
                add(
                    buildJsonObject {
                        put("role", m.role)
                        if (m.imageDataUrls.isEmpty()) {
                            put("content", m.text)
                        } else {
                            putJsonArray("content") {
                                add(buildJsonObject { put("type", "text"); put("text", m.text) })
                                m.imageDataUrls.forEach { url ->
                                    add(buildJsonObject {
                                        put("type", "image_url")
                                        putJsonObject("image_url") { put("url", url) }
                                    })
                                }
                            }
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
        val choice = (root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
        val message = choice?.get("message") as? JsonObject
        val content = when (val c = message?.get("content")) {
            is JsonPrimitive -> c.contentOrNull.orEmpty()
            is JsonArray -> c.joinToString("") { ((it as? JsonObject)?.get("text") as? JsonPrimitive)?.contentOrNull.orEmpty() }
            else -> ""
        }
        val finish = (choice?.get("finish_reason") as? JsonPrimitive)?.contentOrNull
        val usage = (root["usage"] as? JsonObject)?.let {
            val p = (it["prompt_tokens"] as? JsonPrimitive)?.intOrNull
            val c = (it["completion_tokens"] as? JsonPrimitive)?.intOrNull
            val t = (it["total_tokens"] as? JsonPrimitive)?.intOrNull
            if (p == null && c == null && t == null) null else TokenUsage(p ?: 0, c ?: 0, t ?: ((p ?: 0) + (c ?: 0)))
        }
        val visible = JsonExtract.stripThinking(content)
        if (visible.isBlank()) {
            if (finish == "length") throw AiException(AiErrorCode.TRUNCATED, "$label used the whole response budget while reasoning and returned no answer. Try a smaller area or fewer questions.")
            throw AiException(AiErrorCode.INVALID_RESPONSE, "$label returned an empty answer. Please try again.")
        }
        return ChatResult(visible, usage, finish)
    }

    override fun parseModels(body: String): List<ModelInfo> {
        val data = json.parseToJsonElement(body).jsonObject["data"]?.jsonArray ?: return emptyList()
        return data.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = (o["id"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            ModelInfo(id, imageFlag(o))
        }
    }

    /** null = this server does not say. */
    protected open fun imageFlag(model: JsonObject): Boolean? = null
}

/** Groq: OpenAI-compatible plus native JSON mode and hidden reasoning for models that think. */
class GroqAdapter(transport: HttpTransport) : OpenAICompatibleAdapter(transport, ProviderType.GROQ) {
    override val maxTokensField = "max_completion_tokens"

    override fun configureBody(b: JsonObjectBuilder, a: ApiAccount, request: ChatRequest) {
        val m = request.model.lowercase()
        if (m.contains("qwen") || m.contains("deepseek-r1") || m.contains("qwq")) b.put("reasoning_format", AiDefaults.REASONING_FORMAT)
        if (request.jsonMode) b.putJsonObject("response_format") { put("type", "json_object") }
    }
}

/** OpenRouter: OpenAI-compatible; its model list says which models take image input. */
class OpenRouterAdapter(transport: HttpTransport) : OpenAICompatibleAdapter(transport, ProviderType.OPENROUTER) {
    override fun extraHeaders(): Map<String, String> = mapOf("HTTP-Referer" to "https://github.com/snapgpt", "X-Title" to "SnapGPT")

    override fun imageFlag(model: JsonObject): Boolean? {
        val modalities = ((model["architecture"] as? JsonObject)?.get("input_modalities") as? JsonArray) ?: return null
        return modalities.any { (it as? JsonPrimitive)?.contentOrNull == "image" }
    }
}
