package com.snapgpt.settings

import com.snapgpt.ai.AiDefaults
import java.util.UUID
import java.net.URI

/**
 * One independently configured API account. Accounts are ordered in AppSettings.apiAccounts;
 * earlier entries have higher failover priority. The endpoint must implement the OpenAI-compatible
 * /chat/completions route and accept Bearer authentication plus image_url data URLs. GET /models is
 * optional and is used only when the provider exposes it for connection/model validation.
 */
data class ApiAccount(
    val id: String,
    val name: String,
    val baseUrl: String,
    val model: String,
    val enabled: Boolean = true,
    val kind: ApiAccountKind = ApiAccountKind.OPENAI_COMPATIBLE,
) {
    fun normalizedBaseUrl(): String = baseUrl.trim().trimEnd('/').removeSuffix("/chat/completions")

    fun isConfigurationValid(): Boolean {
        val url = normalizedBaseUrl()
        val uri = runCatching { URI(url) }.getOrNull()
        return name.isNotBlank() && model.isNotBlank() && uri != null &&
            uri.scheme.equals("https", ignoreCase = true) &&
            !uri.host.isNullOrBlank() && uri.rawUserInfo == null &&
            uri.rawQuery == null && uri.rawFragment == null
    }

    companion object {
        fun groq(name: String = "Groq primary", model: String = AiDefaults.DEFAULT_MODEL) = ApiAccount(
            id = "groq-primary",
            name = name,
            baseUrl = AiDefaults.BASE_URL,
            model = model,
            kind = ApiAccountKind.GROQ,
        )

        fun create(
            name: String,
            baseUrl: String,
            model: String,
            kind: ApiAccountKind = ApiAccountKind.OPENAI_COMPATIBLE,
        ) = ApiAccount(UUID.randomUUID().toString(), name.trim(), baseUrl.trim(), model.trim(), true, kind)
    }
}

enum class ApiAccountKind(val label: String) {
    GROQ("Groq"),
    OPENROUTER("OpenRouter"),
    OPENAI_COMPATIBLE("Custom OpenAI-compatible API"),
}

object ApiAccountPresets {
    fun create(kind: ApiAccountKind): ApiAccount = when (kind) {
        ApiAccountKind.GROQ -> ApiAccount.create(
            name = "Groq API ${System.currentTimeMillis().toString().takeLast(4)}",
            baseUrl = AiDefaults.BASE_URL,
            model = AiDefaults.DEFAULT_MODEL,
            kind = kind,
        )
        ApiAccountKind.OPENROUTER -> ApiAccount.create(
            name = "OpenRouter API ${System.currentTimeMillis().toString().takeLast(4)}",
            baseUrl = "https://openrouter.ai/api/v1",
            model = "",
            kind = kind,
        )
        ApiAccountKind.OPENAI_COMPATIBLE -> ApiAccount.create(
            name = "Custom API ${System.currentTimeMillis().toString().takeLast(4)}",
            baseUrl = "https://",
            model = "",
            kind = kind,
        )
    }
}
