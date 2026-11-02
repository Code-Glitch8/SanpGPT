package com.snapgpt.ai

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
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
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** One chat message. [imageDataUrls] are base64 data URLs (data:image/jpeg;base64,...) attached to a user message. */
data class ChatMessage(val role: String, val text: String, val imageDataUrls: List<String> = emptyList())

data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double,
    val maxCompletionTokens: Int,
    val jsonMode: Boolean,
    val reasoningFormat: String? = AiDefaults.REASONING_FORMAT,
)

/** Rate-limit values exactly as Groq sent them in response headers. Absent values stay null (nothing is invented). */
data class RateLimitInfo(
    val limitRequests: String? = null,
    val remainingRequests: String? = null,
    val limitTokens: String? = null,
    val remainingTokens: String? = null,
    val resetRequests: String? = null,
    val resetTokens: String? = null,
    val retryAfter: String? = null,
)

data class ChatResult(val text: String, val usage: TokenUsage?, val finishReason: String?, val rateLimit: RateLimitInfo)

enum class ConnectionState { CONNECTED, INVALID_KEY, MODEL_MISSING, NETWORK_ERROR, OTHER_ERROR }

data class ConnectionResult(val state: ConnectionState, val message: String)

object GroqRequestBuilder {
    fun build(request: ChatRequest): String {
        val body = buildJsonObject {
            put("model", request.model)
            put("temperature", request.temperature)
            put("max_completion_tokens", request.maxCompletionTokens)
            put("stream", false)
            if (request.reasoningFormat != null) put("reasoning_format", request.reasoningFormat)
            if (request.jsonMode) putJsonObject("response_format") { put("type", "json_object") }
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
        }
        return body.toString()
    }
}

/**
 * Groq chat-completions client (OpenAI-compatible API). Never logs the API key; errors are scrubbed.
 * Rate limits (HTTP 429) are respected through [gate] and never retried automatically.
 */
class GroqClient(
    private val transport: HttpTransport = UrlConnectionTransport(),
    private val baseUrl: String = AiDefaults.BASE_URL,
    val gate: RateLimitGate = RateLimitGate(),
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Volatile
    var lastRateLimit: RateLimitInfo = RateLimitInfo()
        private set

    suspend fun chat(apiKey: String, request: ChatRequest): ChatResult {
        if (apiKey.isBlank()) throw AiException(AiErrorCode.NO_API_KEY, "No Groq API key is saved. Add one in SnapGPT > Settings > AI.")
        val imageCount = request.messages.sumOf { it.imageDataUrls.size }
        if (imageCount > AiDefaults.MAX_IMAGES_PER_REQUEST) {
            throw AiException(AiErrorCode.IMAGE_UNSUPPORTED, "This model accepts at most ${AiDefaults.MAX_IMAGES_PER_REQUEST} images per request.")
        }
        gate.check()
        val body = GroqRequestBuilder.build(request)
        if (body.toByteArray(Charsets.UTF_8).size > AiDefaults.REQUEST_BUDGET_BYTES) {
            throw AiException(AiErrorCode.IMAGE_TOO_LARGE, "The request is larger than 18 MB (Groq's limit is 20 MB). Lower the image quality or select a smaller area.")
        }
        val response = send("POST", "$baseUrl/chat/completions", apiKey, body)
        lastRateLimit = rateLimitOf(response.headers)
        if (response.status !in 200..299) {
            val retry = response.headers["retry-after"]?.trim()?.toDoubleOrNull()?.let { Math.ceil(it).toInt() }
            val err = GroqErrorMapper.map(response.status, response.body, retry, request.model)
            if (response.status == 429) gate.block(retry ?: 30)
            throw err
        }
        return parseChat(response.body)
    }

    /** GET /models: checks the key and whether the configured model is offered to it. Counts as a request in the usage log. */
    suspend fun testConnection(apiKey: String, model: String): ConnectionResult {
        if (apiKey.isBlank()) return ConnectionResult(ConnectionState.INVALID_KEY, "No API key saved.")
        return try {
            val response = send("GET", "$baseUrl/models", apiKey, null)
            lastRateLimit = rateLimitOf(response.headers)
            when {
                response.status == 401 -> ConnectionResult(ConnectionState.INVALID_KEY, "Groq rejected the key (HTTP 401).")
                response.status !in 200..299 -> {
                    val e = GroqErrorMapper.map(response.status, response.body, null, model)
                    ConnectionResult(ConnectionState.OTHER_ERROR, e.message ?: "HTTP ${response.status}")
                }
                else -> {
                    val ids = try {
                        json.parseToJsonElement(response.body).jsonObject["data"]?.jsonArray
                            ?.mapNotNull { (it as? JsonObject)?.get("id")?.let { id -> (id as? JsonPrimitive)?.contentOrNull } } ?: emptyList()
                    } catch (_: Exception) {
                        emptyList()
                    }
                    if (ids.isEmpty() || model in ids) ConnectionResult(ConnectionState.CONNECTED, "Connected. Model \"$model\" is available.")
                    else ConnectionResult(ConnectionState.MODEL_MISSING, "Key is valid, but \"$model\" was not listed for it. SnapGPT will not switch models for you.")
                }
            }
        } catch (e: AiException) {
            ConnectionResult(if (e.code == AiErrorCode.NETWORK || e.code == AiErrorCode.TIMEOUT) ConnectionState.NETWORK_ERROR else ConnectionState.OTHER_ERROR, e.message ?: "Error")
        }
    }

    private suspend fun send(method: String, url: String, apiKey: String, body: String?): HttpResponse {
        val headers = buildMap {
            put("Authorization", "Bearer $apiKey")
            put("Accept", "application/json")
            if (body != null) put("Content-Type", "application/json")
        }
        try {
            return transport.execute(method, url, headers, body, AiDefaults.CONNECT_TIMEOUT_MS, AiDefaults.READ_TIMEOUT_MS)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SocketTimeoutException) {
            throw AiException(AiErrorCode.TIMEOUT, "Groq took too long to answer. Check your connection and try again.")
        } catch (e: UnknownHostException) {
            throw AiException(AiErrorCode.NETWORK, "Cannot reach Groq. Check your internet connection.")
        } catch (e: SSLException) {
            throw AiException(AiErrorCode.NETWORK, "Secure connection to Groq failed. Check the date/time and network.")
        } catch (e: IOException) {
            throw AiException(AiErrorCode.NETWORK, "Network error while talking to Groq. Check your internet connection.")
        }
    }

    private fun rateLimitOf(h: Map<String, String>) = RateLimitInfo(
        limitRequests = h["x-ratelimit-limit-requests"],
        remainingRequests = h["x-ratelimit-remaining-requests"],
        limitTokens = h["x-ratelimit-limit-tokens"],
        remainingTokens = h["x-ratelimit-remaining-tokens"],
        resetRequests = h["x-ratelimit-reset-requests"],
        resetTokens = h["x-ratelimit-reset-tokens"],
        retryAfter = h["retry-after"],
    )

    internal fun parseChat(body: String): ChatResult {
        val root = try {
            json.parseToJsonElement(body).jsonObject
        } catch (e: Exception) {
            throw AiException(AiErrorCode.INVALID_RESPONSE, "Groq returned an unreadable response. Please try again.")
        }
        val choice = (root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
        val message = choice?.get("message") as? JsonObject
        val content = (message?.get("content") as? JsonPrimitive)?.contentOrNull.orEmpty()
        val finish = (choice?.get("finish_reason") as? JsonPrimitive)?.contentOrNull
        val usageObj = root["usage"] as? JsonObject
        val usage = usageObj?.let {
            val p = (it["prompt_tokens"] as? JsonPrimitive)?.intOrNull
            val c = (it["completion_tokens"] as? JsonPrimitive)?.intOrNull
            val t = (it["total_tokens"] as? JsonPrimitive)?.intOrNull
            if (p == null && c == null && t == null) null
            else TokenUsage(p ?: 0, c ?: 0, t ?: ((p ?: 0) + (c ?: 0)))
        }
        val visible = JsonExtract.stripThinking(content)
        if (visible.isBlank()) {
            if (finish == "length") throw AiException(AiErrorCode.TRUNCATED, "Groq used the whole response budget while reasoning and returned no answer. Try a smaller area or fewer questions.")
            throw AiException(AiErrorCode.INVALID_RESPONSE, "Groq returned an empty answer. Please try again.")
        }
        return ChatResult(visible, usage, finish, lastRateLimit)
    }
}
