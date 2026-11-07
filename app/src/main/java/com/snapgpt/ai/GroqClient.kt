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

object GroqRequestBuilder {
    fun build(request: ChatRequest, useGroqExtensions: Boolean = true): String {
        val body = buildJsonObject {
            put("model", request.model)
            put("temperature", request.temperature)
            if (useGroqExtensions) {
                put("max_completion_tokens", request.maxCompletionTokens)
            } else {
                put("max_tokens", request.maxCompletionTokens)
            }
            put("stream", false)
            if (request.reasoningFormat != null) {
                put("reasoning_format", request.reasoningFormat)
            }
            if (request.jsonMode) {
                putJsonObject("response_format") { put("type", "json_object") }
            }
            putJsonArray("messages") {
                request.messages.forEach { message ->
                    add(
                        buildJsonObject {
                            put("role", message.role)
                            if (message.imageDataUrls.isEmpty()) {
                                put("content", message.text)
                            } else {
                                putJsonArray("content") {
                                    add(
                                        buildJsonObject {
                                            put("type", "text")
                                            put("text", message.text)
                                        },
                                    )
                                    message.imageDataUrls.forEach { url ->
                                        add(
                                            buildJsonObject {
                                                put("type", "image_url")
                                                putJsonObject("image_url") { put("url", url) }
                                            },
                                        )
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
 * Groq chat-completions client (OpenAI-compatible API).
 * API keys are never logged; errors are converted through the shared ErrorMapper.
 */
class GroqClient(
    private val transport: HttpTransport = UrlConnectionTransport(),
    private val baseUrl: String = AiDefaults.BASE_URL,
    private val providerName: String = "Groq",
    val gate: RateLimitGate = RateLimitGate(providerName),
    private val useGroqExtensions: Boolean = providerName == "Groq",
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Volatile
    var lastRateLimit: RateLimitInfo = RateLimitInfo()
        private set

    fun resetCooldown() = gate.reset()

    suspend fun chat(apiKey: String, request: ChatRequest): ChatResult {
        if (apiKey.isBlank()) {
            throw AiException(
                AiErrorCode.NO_API_KEY,
                "No API key is saved for $providerName. Add it in SnapGPT > Settings > AI APIs.",
            )
        }

        val imageCount = request.messages.sumOf { it.imageDataUrls.size }
        if (imageCount > AiDefaults.MAX_IMAGES_PER_REQUEST) {
            throw AiException(
                AiErrorCode.IMAGE_UNSUPPORTED,
                "This model accepts at most ${AiDefaults.MAX_IMAGES_PER_REQUEST} images per request.",
            )
        }

        gate.check()

        val body = GroqRequestBuilder.build(request, useGroqExtensions)
        if (body.toByteArray(Charsets.UTF_8).size > AiDefaults.REQUEST_BUDGET_BYTES) {
            throw AiException(
                AiErrorCode.IMAGE_TOO_LARGE,
                "The request is larger than SnapGPT's 18 MB safety budget. Lower the image quality or select a smaller area.",
            )
        }

        val response = send("POST", "$baseUrl/chat/completions", apiKey, body)
        lastRateLimit = rateLimitOf(response.headers)

        if (response.status !in 200..299) {
            val retry = response.headers["retry-after"]
                ?.trim()
                ?.toDoubleOrNull()
                ?.let { Math.ceil(it).toInt() }

            val error = ErrorMapper.map(
                providerName,
                response.status,
                response.body,
                retry,
                request.model,
            )

            if (response.status == 429) {
                gate.block(error.retryAfterSeconds ?: retry ?: 30)
            }
            throw error
        }

        return parseChat(response.body)
    }

    /** GET /models checks the key and whether the configured model is offered to it. */
    suspend fun testConnection(apiKey: String, model: String): ConnectionResult {
        if (apiKey.isBlank()) {
            return ConnectionResult(ConnectionState.INVALID_KEY, "No API key saved.")
        }

        return try {
            val response = send("GET", "$baseUrl/models", apiKey, null)
            lastRateLimit = rateLimitOf(response.headers)

            when {
                response.status == 401 -> {
                    ConnectionResult(
                        ConnectionState.INVALID_KEY,
                        "$providerName rejected the key (HTTP 401).",
                    )
                }

                response.status == 404 || response.status == 405 -> {
                    ConnectionResult(
                        ConnectionState.OTHER_ERROR,
                        "The endpoint is reachable but does not expose GET /models. " +
                            "This check cannot validate the key or model; run a scan to test Chat Completions.",
                    )
                }

                response.status !in 200..299 -> {
                    val error = ErrorMapper.map(
                        providerName,
                        response.status,
                        response.body,
                        null,
                        model,
                    )
                    ConnectionResult(
                        ConnectionState.OTHER_ERROR,
                        error.message ?: "HTTP ${response.status}",
                    )
                }

                else -> {
                    val ids = try {
                        json.parseToJsonElement(response.body)
                            .jsonObject["data"]
                            ?.jsonArray
                            ?.mapNotNull { item ->
                                (item as? JsonObject)
                                    ?.get("id")
                                    ?.let { id -> (id as? JsonPrimitive)?.contentOrNull }
                            }
                            ?: emptyList()
                    } catch (_: Exception) {
                        emptyList()
                    }

                    if (ids.isEmpty() || model in ids) {
                        ConnectionResult(
                            ConnectionState.CONNECTED,
                            "Connected. Model \"$model\" is available.",
                        )
                    } else {
                        ConnectionResult(
                            ConnectionState.MODEL_MISSING,
                            "Key is valid, but \"$model\" was not listed for this account.",
                        )
                    }
                }
            }
        } catch (error: AiException) {
            val state = if (
                error.code == AiErrorCode.NETWORK ||
                error.code == AiErrorCode.TIMEOUT
            ) {
                ConnectionState.NETWORK_ERROR
            } else {
                ConnectionState.OTHER_ERROR
            }
            ConnectionResult(state, error.message ?: "Error")
        }
    }

    private suspend fun send(
        method: String,
        url: String,
        apiKey: String,
        body: String?,
    ): HttpResponse {
        val headers = buildMap {
            put("Authorization", "Bearer $apiKey")
            put("Accept", "application/json")
            if (body != null) put("Content-Type", "application/json")
        }

        try {
            return transport.execute(
                method,
                url,
                headers,
                body,
                AiDefaults.CONNECT_TIMEOUT_MS,
                AiDefaults.READ_TIMEOUT_MS,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: SocketTimeoutException) {
            throw AiException(
                AiErrorCode.TIMEOUT,
                "$providerName took too long to answer. Check your connection and try again.",
            )
        } catch (error: UnknownHostException) {
            throw AiException(
                AiErrorCode.NETWORK,
                "Cannot reach $providerName. Check your internet connection.",
            )
        } catch (error: SSLException) {
            throw AiException(
                AiErrorCode.NETWORK,
                "Secure connection to $providerName failed. Check the date/time and network.",
            )
        } catch (error: IOException) {
            throw AiException(
                AiErrorCode.NETWORK,
                "Network error while talking to $providerName. Check your internet connection.",
            )
        }
    }

    private fun rateLimitOf(headers: Map<String, String>) = RateLimitInfo(
        limitRequests = headers["x-ratelimit-limit-requests"],
        remainingRequests = headers["x-ratelimit-remaining-requests"],
        limitTokens = headers["x-ratelimit-limit-tokens"],
        remainingTokens = headers["x-ratelimit-remaining-tokens"],
        resetRequests = headers["x-ratelimit-reset-requests"],
        resetTokens = headers["x-ratelimit-reset-tokens"],
        retryAfter = headers["retry-after"],
    )

    internal fun parseChat(body: String): ChatResult {
        val root = try {
            json.parseToJsonElement(body).jsonObject
        } catch (_: Exception) {
            throw AiException(
                AiErrorCode.INVALID_RESPONSE,
                "$providerName returned an unreadable response. Please try again.",
            )
        }

        val choice = (root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
        val message = choice?.get("message") as? JsonObject
        val content = (message?.get("content") as? JsonPrimitive)?.contentOrNull.orEmpty()
        val finish = (choice?.get("finish_reason") as? JsonPrimitive)?.contentOrNull

        val usageObject = root["usage"] as? JsonObject
        val usage = usageObject?.let {
            val promptTokens = (it["prompt_tokens"] as? JsonPrimitive)?.intOrNull
            val completionTokens = (it["completion_tokens"] as? JsonPrimitive)?.intOrNull
            val totalTokens = (it["total_tokens"] as? JsonPrimitive)?.intOrNull

            if (promptTokens == null && completionTokens == null && totalTokens == null) {
                null
            } else {
                TokenUsage(
                    promptTokens ?: 0,
                    completionTokens ?: 0,
                    totalTokens ?: ((promptTokens ?: 0) + (completionTokens ?: 0)),
                )
            }
        }

        val visible = JsonExtract.stripThinking(content)
        if (visible.isBlank()) {
            if (finish == "length") {
                throw AiException(
                    AiErrorCode.TRUNCATED,
                    "$providerName used the whole response budget and returned no answer. " +
                        "Try a smaller area or fewer questions.",
                )
            }
            throw AiException(
                AiErrorCode.INVALID_RESPONSE,
                "$providerName returned an empty answer. Please try again.",
            )
        }

        return ChatResult(visible, usage, finish, lastRateLimit)
    }
}
