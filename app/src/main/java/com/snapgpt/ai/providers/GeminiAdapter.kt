package com.snapgpt.ai.providers

import com.snapgpt.ai.AiErrorCode
import com.snapgpt.ai.AiException
import com.snapgpt.ai.ChatRequest
import com.snapgpt.ai.ChatResult
import com.snapgpt.ai.RateLimitInfo
import com.snapgpt.ai.TokenUsage
import com.snapgpt.networking.HttpTransport
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Google Gemini (generativelanguage.googleapis.com): `:generateContent`, `contents[].parts[]`, `inline_data` images, `usageMetadata`. */
class GeminiAdapter(transport: HttpTransport) : BaseAdapter(transport, ProviderType.GEMINI) {

    private fun modelPath(model: String) = model.trim().removePrefix("models/")

    override fun chatUrl(a: ApiAccount) = a.effectiveEndpoint + "/models/" + modelPath(a.model) + ":generateContent"
    override fun modelsUrl(a: ApiAccount): String? = a.effectiveEndpoint + "/models?pageSize=1000"

    // The key travels in a header, never in the URL, so it cannot end up in logs or error messages.
    override fun headers(key: String, hasBody: Boolean): Map<String, String> = buildMap {
        put("x-goog-api-key", key)
        put("Accept", "application/json")
        if (hasBody) put("Content-Type", "application/json")
    }

    override fun rateLimitOf(h: Map<String, String>) = RateLimitInfo(retryAfter = h["retry-after"])

    override fun buildBody(a: ApiAccount, request: ChatRequest): String = buildJsonObject {
        val system = request.messages.filter { it.role == "system" }.joinToString("\n\n") { it.text }
        if (system.isNotBlank()) putJsonObject("systemInstruction") { putJsonArray("parts") { add(buildJsonObject { put("text", system) }) } }
        putJsonArray("contents") {
            request.messages.filter { it.role != "system" }.forEach { m ->
                add(
                    buildJsonObject {
                        put("role", if (m.role == "assistant") "model" else "user")
                        putJsonArray("parts") {
                            add(buildJsonObject { put("text", m.text) })
                            m.imageDataUrls.forEach { url ->
                                val d = DataUrl.parse(url)
                                add(buildJsonObject { putJsonObject("inline_data") { put("mime_type", d.mime); put("data", d.base64) } })
                            }
                        }
                    },
                )
            }
        }
        putJsonObject("generationConfig") {
            put("temperature", request.temperature)
            put("maxOutputTokens", request.maxCompletionTokens)
            if (request.jsonMode) put("responseMimeType", "application/json")
        }
    }.toString()

    override fun parseChat(body: String, a: ApiAccount): ChatResult {
        val root = try { json.parseToJsonElement(body).jsonObject } catch (e: Exception) {
            throw AiException(AiErrorCode.INVALID_RESPONSE, "$label returned an unreadable response. Please try again.")
        }
        val block = ((root["promptFeedback"] as? JsonObject)?.get("blockReason") as? JsonPrimitive)?.contentOrNull
        val cand = (root["candidates"] as? JsonArray)?.firstOrNull() as? JsonObject
        val parts = ((cand?.get("content") as? JsonObject)?.get("parts") as? JsonArray).orEmpty()
        // Parts flagged as "thought" are reasoning summaries, not the answer.
        val text = parts.mapNotNull { p ->
            val o = p as? JsonObject ?: return@mapNotNull null
            if ((o["thought"] as? JsonPrimitive)?.booleanOrNull == true) null else (o["text"] as? JsonPrimitive)?.contentOrNull
        }.joinToString("")
        val finish = (cand?.get("finishReason") as? JsonPrimitive)?.contentOrNull
        val u = root["usageMetadata"] as? JsonObject
        val usage = u?.let {
            val p = (it["promptTokenCount"] as? JsonPrimitive)?.intOrNull
            val c = (it["candidatesTokenCount"] as? JsonPrimitive)?.intOrNull
            val th = (it["thoughtsTokenCount"] as? JsonPrimitive)?.intOrNull ?: 0
            val t = (it["totalTokenCount"] as? JsonPrimitive)?.intOrNull
            if (p == null && c == null && t == null) null else TokenUsage(p ?: 0, (c ?: 0) + th, t ?: ((p ?: 0) + (c ?: 0) + th))
        }
        if (text.isBlank()) {
            if (block != null) throw AiException(AiErrorCode.INVALID_RESPONSE, "$label blocked this request ($block).")
            if (finish == "MAX_TOKENS") throw AiException(AiErrorCode.TRUNCATED, "$label used the whole response budget while thinking and returned no answer. Try a smaller area or fewer questions.")
            if (finish == "SAFETY" || finish == "RECITATION" || finish == "PROHIBITED_CONTENT") throw AiException(AiErrorCode.INVALID_RESPONSE, "$label declined to answer ($finish).")
            throw AiException(AiErrorCode.INVALID_RESPONSE, "$label returned an empty answer. Please try again.")
        }
        return ChatResult(com.snapgpt.ai.JsonExtract.stripThinking(text), usage, finish)
    }

    override fun parseModels(body: String): List<ModelInfo> {
        val models = json.parseToJsonElement(body).jsonObject["models"]?.jsonArray ?: return emptyList()
        return models.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val name = (o["name"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val methods = (o["supportedGenerationMethods"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            if (methods != null && "generateContent" !in methods) return@mapNotNull null
            val id = name.removePrefix("models/")
            ModelInfo(id, ModelCapabilities.byModelName(ProviderType.GEMINI, id).let { if (it == ImageSupport.YES) true else null })
        }
    }
}
