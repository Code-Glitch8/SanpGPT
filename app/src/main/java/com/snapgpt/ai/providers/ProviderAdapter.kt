package com.snapgpt.ai.providers

import com.snapgpt.ai.AiDefaults
import com.snapgpt.ai.AiErrorCode
import com.snapgpt.ai.AiException
import com.snapgpt.ai.ChatMessage
import com.snapgpt.ai.ChatRequest
import com.snapgpt.ai.ChatResult
import com.snapgpt.ai.ConnectionResult
import com.snapgpt.ai.ConnectionState
import com.snapgpt.ai.ErrorMapper
import com.snapgpt.ai.RateLimitInfo
import com.snapgpt.networking.HttpResponse
import com.snapgpt.networking.HttpTransport
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

data class ModelInfo(val id: String, val supportsImages: Boolean? = null)

/** Result of checking an account's model: the connection outcome plus what is known about image support. */
data class ModelCheck(val result: ConnectionResult, val imageSupport: ImageSupport, val models: List<ModelInfo> = emptyList())

/**
 * Common internal interface for every provider. All provider differences (URLs, headers, JSON shapes, error formats,
 * usage reporting) live behind it, so the failover manager and the AI pipeline never know which provider they talk to.
 */
interface ProviderAdapter {
    val type: ProviderType

    /** Send one chat request (text, or text plus images) and return the visible answer with the provider's own usage report. */
    suspend fun chat(account: ApiAccount, apiKey: String, request: ChatRequest): ChatResult

    /** Lists models (when the provider can) and checks the key and the configured model; also reports image support. */
    suspend fun validateModel(account: ApiAccount, apiKey: String): ModelCheck

    suspend fun testConnection(account: ApiAccount, apiKey: String): ConnectionResult = validateModel(account, apiKey).result

    // Convenience entry points for the three kinds of request the app makes. They all go through [chat].
    suspend fun analyzeImage(account: ApiAccount, apiKey: String, system: String, user: String, imageDataUrl: String, maxTokens: Int): ChatResult =
        chat(account, apiKey, ChatRequest(account.model, listOf(ChatMessage("system", system), ChatMessage("user", user, listOf(imageDataUrl))), 0.4, maxTokens, jsonMode = true))

    suspend fun explain(account: ApiAccount, apiKey: String, system: String, user: String, maxTokens: Int): ChatResult =
        chat(account, apiKey, ChatRequest(account.model, listOf(ChatMessage("system", system), ChatMessage("user", user)), 0.6, maxTokens, jsonMode = false))

    suspend fun followUp(account: ApiAccount, apiKey: String, messages: List<ChatMessage>, maxTokens: Int): ChatResult =
        chat(account, apiKey, ChatRequest(account.model, messages, 0.6, maxTokens, jsonMode = false))
}

/** A decoded "data:image/jpeg;base64,..." URL. */
class DataUrl(val mime: String, val base64: String) {
    companion object {
        fun parse(url: String): DataUrl {
            val comma = url.indexOf(',')
            if (!url.startsWith("data:") || comma < 0) throw AiException(AiErrorCode.BAD_REQUEST, "Internal error: image is not a data URL.")
            val meta = url.substring(5, comma)
            return DataUrl(meta.substringBefore(';').ifBlank { "image/jpeg" }, url.substring(comma + 1))
        }
    }
}

/**
 * Shared plumbing: pre-flight checks, HTTP send with exception mapping, HTTP error mapping, retry-after parsing and the
 * connection test. Never logs or returns the API key.
 */
abstract class BaseAdapter(protected val transport: HttpTransport, final override val type: ProviderType) : ProviderAdapter {
    protected val json = Json { ignoreUnknownKeys = true; isLenient = true }
    protected val label: String get() = type.displayName

    protected abstract fun chatUrl(a: ApiAccount): String
    protected abstract fun modelsUrl(a: ApiAccount): String?
    protected abstract fun headers(key: String, hasBody: Boolean): Map<String, String>
    protected abstract fun buildBody(a: ApiAccount, request: ChatRequest): String
    protected abstract fun parseChat(body: String, a: ApiAccount): ChatResult
    protected abstract fun parseModels(body: String): List<ModelInfo>

    /** Maximum base64 length of one image, if the provider documents one. */
    protected open val maxImageBase64Length: Int? = null

    /** When the models endpoint is missing (404/405/501), fall back to a 1-token chat request to test the key. */
    protected open val chatFallbackForTest: Boolean = false

    protected open fun rateLimitOf(h: Map<String, String>): RateLimitInfo = RateLimitInfo(
        limitRequests = h["x-ratelimit-limit-requests"],
        remainingRequests = h["x-ratelimit-remaining-requests"],
        limitTokens = h["x-ratelimit-limit-tokens"],
        remainingTokens = h["x-ratelimit-remaining-tokens"],
        resetRequests = h["x-ratelimit-reset-requests"],
        resetTokens = h["x-ratelimit-reset-tokens"],
        retryAfter = h["retry-after"],
    )

    protected fun normalizeModelId(id: String): String = id.trim().lowercase().removePrefix("models/")

    override suspend fun chat(account: ApiAccount, apiKey: String, request: ChatRequest): ChatResult {
        if (apiKey.isBlank()) throw AiException(AiErrorCode.NO_API_KEY, "No API key is saved for \"${account.name}\".")
        if (request.model.isBlank()) throw AiException(AiErrorCode.MODEL_UNAVAILABLE, "No model is set for \"${account.name}\". Choose a model in the account settings.")
        requireAllowedEndpoint(account)
        val images = request.messages.flatMap { it.imageDataUrls }
        if (images.size > AiDefaults.MAX_IMAGES_PER_REQUEST) {
            throw AiException(AiErrorCode.IMAGE_UNSUPPORTED, "At most ${AiDefaults.MAX_IMAGES_PER_REQUEST} images can be sent in one request.")
        }
        maxImageBase64Length?.let { cap ->
            if (images.any { DataUrl.parse(it).base64.length > cap }) {
                throw AiException(AiErrorCode.IMAGE_TOO_LARGE, "The screenshot is larger than $label accepts (about ${cap / 1_000_000} MB per image). Lower the image quality or select a smaller area.")
            }
        }
        val body = buildBody(account, request)
        if (body.toByteArray(Charsets.UTF_8).size > AiDefaults.REQUEST_BUDGET_BYTES) {
            throw AiException(AiErrorCode.IMAGE_TOO_LARGE, "The request is larger than 18 MB. Lower the image quality or select a smaller area.")
        }
        val response = send("POST", chatUrl(account), headers(apiKey, true), body)
        if (response.status !in 200..299) throw mapError(response, request.model)
        val parsed = parseChat(response.body, account)
        return parsed.copy(rateLimit = rateLimitOf(response.headers))
    }

    override suspend fun validateModel(account: ApiAccount, apiKey: String): ModelCheck {
        if (apiKey.isBlank()) return failed(ConnectionState.INVALID_KEY, "No API key saved for this account.")
        if (!Endpoints.isAllowed(account.effectiveEndpoint)) return failed(ConnectionState.OTHER_ERROR, "The endpoint must be an https:// URL.")
        val url = modelsUrl(account) ?: return chatTest(account, apiKey)
        return try {
            val response = send("GET", url, headers(apiKey, false), null)
            when {
                response.status in 200..299 -> {
                    val models = try { parseModels(response.body) } catch (_: Exception) { emptyList() }
                    val wanted = normalizeModelId(account.model)
                    val found = models.firstOrNull { normalizeModelId(it.id) == wanted }
                    val image = found?.supportsImages?.let { if (it) ImageSupport.YES else ImageSupport.NO } ?: ModelCapabilities.byModelName(type, account.model)
                    when {
                        account.model.isBlank() -> ModelCheck(ConnectionResult(ConnectionState.MODEL_MISSING, "Key accepted by $label, but no model is set for this account."), ImageSupport.UNKNOWN, models)
                        models.isEmpty() -> ModelCheck(ConnectionResult(ConnectionState.CONNECTED, "Connected. $label did not list its models, so \"${account.model}\" could not be checked."), image, models)
                        found != null -> ModelCheck(ConnectionResult(ConnectionState.CONNECTED, "Connected. Model \"${account.model}\" is available."), image, models)
                        else -> ModelCheck(ConnectionResult(ConnectionState.MODEL_MISSING, "Key is valid, but \"${account.model}\" was not listed for it. SnapGPT will not switch models for you."), image, models)
                    }
                }
                response.status in listOf(404, 405, 501) && chatFallbackForTest -> chatTest(account, apiKey)
                else -> {
                    val e = mapError(response, account.model)
                    val state = when (e.code) {
                        AiErrorCode.INVALID_KEY -> ConnectionState.INVALID_KEY
                        AiErrorCode.NETWORK, AiErrorCode.TIMEOUT -> ConnectionState.NETWORK_ERROR
                        else -> ConnectionState.OTHER_ERROR
                    }
                    failed(state, e.message ?: "HTTP ${response.status}")
                }
            }
        } catch (e: AiException) {
            failed(if (e.code == AiErrorCode.NETWORK || e.code == AiErrorCode.TIMEOUT) ConnectionState.NETWORK_ERROR else ConnectionState.OTHER_ERROR, e.message ?: "Error")
        }
    }

    /** Minimal real request (1 short answer) used only when a provider has no models endpoint. It consumes a few tokens. */
    private suspend fun chatTest(account: ApiAccount, apiKey: String): ModelCheck = try {
        chat(account, apiKey, ChatRequest(account.model, listOf(ChatMessage("user", "Reply with the single word OK.")), 0.0, 16, jsonMode = false))
        ModelCheck(ConnectionResult(ConnectionState.CONNECTED, "Connected. The model answered a small test request."), ModelCapabilities.byModelName(type, account.model))
    } catch (e: AiException) {
        val state = when (e.code) {
            AiErrorCode.INVALID_KEY -> ConnectionState.INVALID_KEY
            AiErrorCode.MODEL_UNAVAILABLE -> ConnectionState.MODEL_MISSING
            AiErrorCode.NETWORK, AiErrorCode.TIMEOUT -> ConnectionState.NETWORK_ERROR
            // A rate/quota answer still proves the key and model exist.
            AiErrorCode.RATE_LIMIT, AiErrorCode.TOKEN_LIMIT -> ConnectionState.CONNECTED
            else -> ConnectionState.OTHER_ERROR
        }
        ModelCheck(ConnectionResult(state, e.message ?: "Error"), ImageSupport.UNKNOWN)
    }

    private fun failed(state: ConnectionState, message: String) = ModelCheck(ConnectionResult(state, message), ImageSupport.UNKNOWN)

    private fun requireAllowedEndpoint(a: ApiAccount) {
        if (!Endpoints.isAllowed(a.effectiveEndpoint)) {
            throw AiException(AiErrorCode.BAD_REQUEST, "The endpoint of \"${a.name}\" must be an https:// URL, so your API key is never sent unencrypted.")
        }
    }

    protected fun mapError(response: HttpResponse, model: String): AiException {
        val h = response.headers
        val retry = h["retry-after"]?.trim()?.toDoubleOrNull()?.let { Math.ceil(it).toInt() }
            ?: h["retry-after-ms"]?.trim()?.toDoubleOrNull()?.let { Math.ceil(it / 1000.0).toInt() }
        return ErrorMapper.map(label, response.status, response.body, retry, model)
    }

    protected suspend fun send(method: String, url: String, headers: Map<String, String>, body: String?): HttpResponse {
        try {
            return transport.execute(method, url, headers, body, AiDefaults.CONNECT_TIMEOUT_MS, AiDefaults.READ_TIMEOUT_MS)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SocketTimeoutException) {
            throw AiException(AiErrorCode.TIMEOUT, "$label took too long to answer. Check your connection and try again.")
        } catch (e: UnknownHostException) {
            throw AiException(AiErrorCode.NETWORK, "Cannot reach $label. Check your internet connection.")
        } catch (e: SSLException) {
            throw AiException(AiErrorCode.NETWORK, "Secure connection to $label failed. Check the date/time and network.")
        } catch (e: IOException) {
            throw AiException(AiErrorCode.NETWORK, "Network error while talking to $label. Check your internet connection.")
        }
    }
}
