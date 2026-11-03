package com.snapgpt.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

enum class AiErrorCode {
    NO_API_KEY, INVALID_KEY, FORBIDDEN, RATE_LIMIT, TOKEN_LIMIT, QUOTA_EXCEEDED, MODEL_UNAVAILABLE, BAD_REQUEST,
    IMAGE_UNSUPPORTED, IMAGE_TOO_LARGE, NETWORK, TIMEOUT, SERVER, INVALID_RESPONSE, TRUNCATED, CAPTURE, NOT_READY,
    NO_ACCOUNTS, ALL_ACCOUNTS_FAILED, DUPLICATE,
}

/** Every failure shown to the user. [message] is user-facing and never contains an API key. */
class AiException(
    val code: AiErrorCode,
    message: String,
    val retryAfterSeconds: Int? = null,
    val httpStatus: Int? = null,
) : Exception(Scrub.text(message))

object Scrub {
    private val PREFIXED_KEY = Regex("\\b(?:gsk_|sk-or-v1-|sk-ant-|sk-|AIza)[A-Za-z0-9_-]{6,}")
    private val BEARER = Regex("Bearer\\s+[A-Za-z0-9._~+/=-]{8,}", RegexOption.IGNORE_CASE)
    private val KEY_PARAM = Regex("([?&]key=)[^&\\s\"']+", RegexOption.IGNORE_CASE)

    /** Remove anything that looks like an API key before text is stored, shown or logged. */
    fun text(s: String?): String = (s ?: "")
        .replace(PREFIXED_KEY, "[redacted]")
        .replace(BEARER, "Bearer [redacted]")
        .replace(KEY_PARAM, "$1[redacted]")
}

/** Reads "try again in 21m1.2s" style hints that some providers put in error text instead of a Retry-After header. */
object RetryHint {
    private val PHRASE = Regex("(?:try again|retry)\\s+in\\s+((?:\\d+(?:\\.\\d+)?\\s*(?:ms|h|m|s)\\s*)+)", RegexOption.IGNORE_CASE)
    private val PART = Regex("(\\d+(?:\\.\\d+)?)\\s*(ms|h|m|s)", RegexOption.IGNORE_CASE)

    fun fromText(text: String): Int? {
        val m = PHRASE.find(text) ?: return null
        var seconds = 0.0
        PART.findAll(m.groupValues[1]).forEach {
            val v = it.groupValues[1].toDouble()
            seconds += when (it.groupValues[2].lowercase()) { "h" -> v * 3600; "m" -> v * 60; "ms" -> v / 1000; else -> v }
        }
        return if (seconds > 0) Math.ceil(seconds).toInt() else null
    }

    /** Gemini: error.details[].retryDelay = "34s". */
    fun fromDelayString(s: String): Int? = PART.findAll(s).sumOf {
        val v = it.groupValues[1].toDouble()
        when (it.groupValues[2].lowercase()) { "h" -> v * 3600; "m" -> v * 60; "ms" -> v / 1000; else -> v }
    }.let { if (it > 0) Math.ceil(it).toInt() else null }
}

/** Maps HTTP failures from any provider to understandable [AiException]s that the failover manager can classify. */
object ErrorMapper {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun root(body: String): JsonObject? = try { json.parseToJsonElement(body).jsonObject } catch (_: Exception) { null }

    fun apiMessage(body: String): String? {
        val err = root(body)?.get("error")
        return when (err) {
            is JsonObject -> (err["message"] as? JsonPrimitive)?.contentOrNull
            is JsonPrimitive -> err.contentOrNull
            else -> null
        }
    }

    fun apiCode(body: String): String? {
        val err = root(body)?.get("error") as? JsonObject ?: return null
        val c = (err["code"] as? JsonPrimitive)?.contentOrNull ?: (err["type"] as? JsonPrimitive)?.contentOrNull
        return c
    }

    /** Gemini puts a machine-readable retry delay and quota ids in error.details. */
    private fun detailsText(body: String): Pair<Int?, String> {
        val details = ((root(body)?.get("error") as? JsonObject)?.get("details") as? JsonArray) ?: return null to ""
        var retry: Int? = null
        val sb = StringBuilder()
        details.forEach { d ->
            val o = d as? JsonObject ?: return@forEach
            (o["retryDelay"] as? JsonPrimitive)?.contentOrNull?.let { retry = RetryHint.fromDelayString(it) }
            sb.append(o.toString().take(1500)).append(' ')
        }
        return retry to sb.toString()
    }

    fun map(provider: String, status: Int, body: String, retryAfterSeconds: Int?, model: String): AiException {
        val detail = Scrub.text(apiMessage(body)?.take(300) ?: "")
        val code = apiCode(body).orEmpty().lowercase()
        val lower = detail.lowercase()
        val (detailRetry, detailBlob) = detailsText(body)
        val blob = (lower + " " + detailBlob.lowercase())
        val retry = retryAfterSeconds ?: detailRetry ?: RetryHint.fromText(detail)

        val keyProblem = blob.contains("api key not valid") || blob.contains("api_key_invalid") || blob.contains("invalid api key") ||
            blob.contains("incorrect api key") || blob.contains("invalid x-api-key") || blob.contains("invalid_api_key") ||
            code.contains("authentication") || code == "invalid_api_key"
        val billing = blob.contains("credit balance") || blob.contains("insufficient credit") || blob.contains("insufficient_quota") ||
            blob.contains("insufficient funds") || blob.contains("billing") && !blob.contains("api key") || blob.contains("exceeded your current quota") ||
            blob.contains("payment required")
        val perDay = blob.contains("per day") || blob.contains("perday") || blob.contains("(tpd)") || blob.contains("(rpd)") || blob.contains("daily") ||
            blob.contains("per month") || blob.contains("permonth")
        val perMinute = blob.contains("per minute") || blob.contains("perminute") || blob.contains("(tpm)") || blob.contains("(rpm)")
        val tokenLimit = blob.contains("token") || blob.contains("tpm") || blob.contains("tpd")
        val imageProblem = blob.contains("image") || blob.contains("vision") || blob.contains("multimodal")
        val imageNotSupported = imageProblem && (blob.contains("not support") || blob.contains("does not support") || blob.contains("cannot process") ||
            blob.contains("only text") || blob.contains("text-only") || blob.contains("support image") || blob.contains("no endpoints found") || blob.contains("unsupported"))
        val p = provider

        return when {
            status == 401 || keyProblem && status in 400..403 ->
                AiException(AiErrorCode.INVALID_KEY, "$p rejected the API key (HTTP $status). Check or re-enter the key for this account.", httpStatus = status)
            status == 402 || billing && status in 400..429 && !(status == 429 && perMinute && !perDay) ->
                AiException(AiErrorCode.QUOTA_EXCEEDED, "$p reports that the quota, credits or billing limit for this account is used up (HTTP $status)." + suffix(detail), retry, status)
            status == 403 ->
                AiException(AiErrorCode.FORBIDDEN, "$p refused the request (HTTP 403)." + suffix(detail) + " The key, organization or region may not be allowed to use this model.", httpStatus = status)
            status == 404 || code == "model_not_found" || lower.contains("does not exist") && lower.contains("model") || lower.contains("no endpoints found") && !imageNotSupported ->
                AiException(AiErrorCode.MODEL_UNAVAILABLE, "The model \"$model\" is not available for this $p account." + suffix(detail) + " SnapGPT does not switch models automatically; pick another model for this account.", httpStatus = status)
            status == 413 -> AiException(AiErrorCode.IMAGE_TOO_LARGE, "The screenshot is too large for $p. Lower the image quality in Settings > Screenshot or select a smaller area.", httpStatus = status)
            status == 429 -> when {
                perDay -> AiException(AiErrorCode.QUOTA_EXCEEDED, "$p daily/monthly quota reached." + (retry?.let { " It may reset in about ${formatWait(it)}." } ?: ""), retry, status)
                tokenLimit -> AiException(AiErrorCode.TOKEN_LIMIT, "$p token rate limit reached." + waitText(retry), retry, status)
                else -> AiException(AiErrorCode.RATE_LIMIT, "$p request rate limit reached." + waitText(retry), retry, status)
            }
            status == 400 && imageNotSupported -> AiException(AiErrorCode.IMAGE_UNSUPPORTED, "The model \"$model\" on $p does not accept images:$detail", httpStatus = status)
            status == 400 && (lower.contains("decommission") || lower.contains("no longer supported") || lower.contains("model") && !imageProblem && (lower.contains("not found") || lower.contains("invalid") || lower.contains("unavailable"))) ->
                AiException(AiErrorCode.MODEL_UNAVAILABLE, "$p says the model \"$model\" cannot be used:$detail SnapGPT does not switch models automatically.", httpStatus = status)
            status == 408 || status == 504 -> AiException(AiErrorCode.TIMEOUT, "$p timed out (HTTP $status). Please try again in a moment.", httpStatus = status)
            status == 529 || status >= 500 -> AiException(AiErrorCode.SERVER, "$p had a temporary server problem (HTTP $status).", retry, status)
            status in 400..499 -> AiException(AiErrorCode.BAD_REQUEST, "$p rejected the request (HTTP $status)." + suffix(detail), httpStatus = status)
            else -> AiException(AiErrorCode.BAD_REQUEST, "Unexpected response from $p (HTTP $status).", httpStatus = status)
        }
    }

    private fun suffix(detail: String) = if (detail.isBlank()) "" else " $detail"
    private fun waitText(retry: Int?) = retry?.let { " Try again in about ${formatWait(it)}." } ?: " Try again shortly."

    fun formatWait(seconds: Int): String = when {
        seconds < 90 -> "$seconds s"
        seconds < 5400 -> "${(seconds + 30) / 60} min"
        else -> "${(seconds + 1800) / 3600} h"
    }
}
