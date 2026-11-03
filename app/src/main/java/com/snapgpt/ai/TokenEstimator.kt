package com.snapgpt.ai

/**
 * Local token estimate (about 4 characters per token plus ~2048 per image). Shown separately from the ACTUAL values that
 * providers report in their `usage` objects, so an estimate is never mistaken for billing data.
 */
object TokenEstimator {
    fun text(chars: Int): Long = (chars + 3L) / 4L

    fun request(messages: List<ChatMessage>): Long {
        val chars = messages.sumOf { it.text.length }
        val images = messages.sumOf { it.imageDataUrls.size }
        return text(chars) + images.toLong() * AiDefaults.IMAGE_TOKENS_ESTIMATE
    }
}
