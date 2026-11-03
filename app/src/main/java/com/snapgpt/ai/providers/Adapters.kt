package com.snapgpt.ai.providers

import com.snapgpt.networking.HttpTransport
import com.snapgpt.networking.UrlConnectionTransport

/** Registry that hands out the adapter for a provider type. */
class Adapters(private val byType: Map<ProviderType, ProviderAdapter>) {
    fun forType(type: ProviderType): ProviderAdapter = byType[type] ?: error("No adapter for $type")

    companion object {
        fun standard(transport: HttpTransport = UrlConnectionTransport()) = Adapters(
            mapOf(
                ProviderType.GROQ to GroqAdapter(transport),
                ProviderType.GEMINI to GeminiAdapter(transport),
                ProviderType.OPENROUTER to OpenRouterAdapter(transport),
                ProviderType.ANTHROPIC to AnthropicAdapter(transport),
                ProviderType.CUSTOM_OPENAI to OpenAICompatibleAdapter(transport),
            ),
        )
    }
}
