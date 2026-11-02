package com.snapgpt.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

enum class AiErrorCode {
    NO_API_KEY, INVALID_KEY, FORBIDDEN, QUOTA, RATE_LIMIT, TOKEN_LIMIT, COOLDOWN, MODEL_UNAVAILABLE, BAD_REQUEST,
    IMAGE_UNSUPPORTED, IMAGE_TOO_LARGE, NETWORK, TIMEOUT, SERVER, INVALID_RESPONSE, TRUNCATED, CAPTURE, NOT_READY,
}

/** Every failure shown to the user. [message] is user-facing and never contains the API key. */
class AiException(
    val code: AiErrorCode,
    message: String,
    val retryAfterSeconds: Int? = null,
    val httpStatus: Int? = null,
) : Exception(Scrub.text(message))

object Scrub {
    private val GROQ_KEY = Regex("gsk_[A-Za-z0-9_-]{6,}")
    private val GENERIC_KEY = Regex("\\bsk-[A-Za-z0-9_-]{12,}")
    private val BEARER = Regex("Bearer\\s+[A-Za-z0-9._~+/=-]{8,}", RegexOption.IGNORE_CASE)

    /** Remove anything that looks like an API key before text is stored, shown or logged. */
    fun text(s: String?): String = (s ?: "")
        .replace(GROQ_KEY, "[redacted]")
        .replace(GENERIC_KEY, "[redacted]")
        .replace(BEARER, "Bearer [redacted]")
}

/** Maps HTTP failures from an OpenAI-compatible API endpoint to understandable [AiException]s. */
object GroqErrorMapper {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun apiMessage(body: String): String? = try {
        val root = json.parseToJsonElement(body).jsonObject
        val err = root["error"]
        when (err) {
            is JsonObject -> (err["message"] as? JsonPrimitive)?.contentOrNull
            is JsonPrimitive -> err.contentOrNull
            else -> null
        }
    } catch (_: Exception) {
        null
    }

    fun apiCode(body: String): String? = try {
        ((json.parseToJsonElement(body).jsonObject["error"] as? JsonObject)?.get("code") as? JsonPrimitive)?.contentOrNull
    } catch (_: Exception) {
        null
    }

    fun map(status: Int, body: String, retryAfterSeconds: Int?, model: String, providerName: String = "API provider"): AiException {
        val detail = Scrub.text(apiMessage(body)?.take(300) ?: "")
        val code = apiCode(body).orEmpty()
        val lower = detail.lowercase()
        return when {
            status == 401 -> AiException(AiErrorCode.INVALID_KEY, "$providerName rejected the API key (HTTP 401). Check or re-enter this account key in SnapGPT > Settings > AI APIs.", httpStatus = status)
            status == 402 || (status in 400..499 && (lower.contains("insufficient_quota") || lower.contains("quota exceeded") || lower.contains("credits exhausted") || lower.contains("billing limit"))) ->
                AiException(AiErrorCode.QUOTA, "$providerName quota or credits are exhausted." + suffix(detail), retryAfterSeconds, status)
            status == 403 -> AiException(AiErrorCode.FORBIDDEN, "$providerName refused the request (HTTP 403)." + suffix(detail) + " Your account, organization or region may not be allowed to use this model.", httpStatus = status)
            status == 404 || code == "model_not_found" || lower.contains("does not exist") && lower.contains("model") ->
                AiException(AiErrorCode.MODEL_UNAVAILABLE, "The model \"$model\" is not available for this API account." + suffix(detail) + " SnapGPT does not switch models automatically; pick another model in Settings > AI.", httpStatus = status)
            status == 413 -> AiException(AiErrorCode.IMAGE_TOO_LARGE, "The screenshot is too large for this API (SnapGPT request budget is 18 MB). Lower the image quality in Settings > Scanning or select a smaller area.", httpStatus = status)
            status == 429 -> {
                val errorHints = "$lower ${code.lowercase()}"
                val quotaExhausted = errorHints.contains("quota") || errorHints.contains("billing") || errorHints.contains("credits exhausted") || errorHints.contains("insufficient_credit")
                if (quotaExhausted) return AiException(AiErrorCode.QUOTA, "$providerName quota or credits are exhausted.", retryAfterSeconds, status)
                val tokenLimit = errorHints.contains("token") || errorHints.contains("tpm") || errorHints.contains("tpd")
                val wait = retryAfterSeconds?.let { " Try again in about $it s." } ?: " Try again shortly."
                if (tokenLimit) AiException(AiErrorCode.TOKEN_LIMIT, "$providerName token quota or rate limit reached.$wait", retryAfterSeconds, status)
                else AiException(AiErrorCode.RATE_LIMIT, "$providerName request rate limit reached.$wait", retryAfterSeconds, status)
            }
            status == 400 && (lower.contains("decommission") || lower.contains("no longer supported") || lower.contains("model")) && !lower.contains("image") ->
                AiException(AiErrorCode.MODEL_UNAVAILABLE, "$providerName says the model \"$model\" cannot be used:$detail", httpStatus = status)
            status == 400 && lower.contains("image") ->
                AiException(AiErrorCode.IMAGE_UNSUPPORTED, "$providerName could not accept the image:$detail", httpStatus = status)
            status in 400..499 -> AiException(AiErrorCode.BAD_REQUEST, "$providerName rejected the request (HTTP $status)." + suffix(detail), httpStatus = status)
            status >= 500 -> AiException(AiErrorCode.SERVER, "$providerName had a server problem (HTTP $status). Please try again in a moment.", httpStatus = status)
            else -> AiException(AiErrorCode.BAD_REQUEST, "Unexpected response from $providerName (HTTP $status).", httpStatus = status)
        }
    }

    private fun suffix(detail: String) = if (detail.isBlank()) "" else " $detail"
}

/** Refuses requests to a particular account until its reported rate-limit wait has passed. */
class RateLimitGate(private val providerName: String = "API provider", private val clock: () -> Long = { System.currentTimeMillis() }) {
    @Volatile
    private var blockedUntil = 0L

    fun block(seconds: Int) {
        blockedUntil = clock() + seconds.coerceIn(1, 3600) * 1000L
    }

    fun reset() { blockedUntil = 0L }

    fun remainingSeconds(): Int {
        val ms = blockedUntil - clock()
        return if (ms <= 0) 0 else ((ms + 999) / 1000).toInt()
    }

    fun check() {
        val s = remainingSeconds()
        if (s > 0) throw AiException(AiErrorCode.COOLDOWN, "$providerName asked SnapGPT to wait. Try again in $s s.", s)
    }
}

/** Central policy so every AI stage (scan, explain and follow-up) uses the same bounded fallback rules. */
object ApiFailoverPolicy {
    fun shouldTryNext(code: AiErrorCode): Boolean = when (code) {
        AiErrorCode.QUOTA,
        AiErrorCode.RATE_LIMIT,
        AiErrorCode.TOKEN_LIMIT,
        AiErrorCode.COOLDOWN,
        AiErrorCode.INVALID_KEY,
        AiErrorCode.FORBIDDEN,
        AiErrorCode.MODEL_UNAVAILABLE,
        AiErrorCode.NETWORK,
        AiErrorCode.TIMEOUT,
        AiErrorCode.SERVER,
        AiErrorCode.INVALID_RESPONSE -> true
        // These are usually request/user/configuration problems. Repeating them against every account
        // would waste quota and cannot make the same screenshot/request valid.
        AiErrorCode.NO_API_KEY,
        AiErrorCode.BAD_REQUEST,
        AiErrorCode.IMAGE_UNSUPPORTED,
        AiErrorCode.IMAGE_TOO_LARGE,
        AiErrorCode.TRUNCATED,
        AiErrorCode.CAPTURE,
        AiErrorCode.NOT_READY -> false
    }
}
