package com.snapgpt.ai

/**
 * Token and request accounting.
 *
 * ACTUAL values come from the usage object returned by a successful provider request.
 * ESTIMATED values are computed locally and are shown separately so they are never
 * mistaken for billing data.
 */
data class UsageStats(
    val requests: Int = 0,
    val successes: Int = 0,
    val failures: Int = 0,
    val cacheHits: Int = 0,
    val actualInputTokens: Long = 0,
    val actualOutputTokens: Long = 0,
    val actualTotalTokens: Long = 0,
    val estimatedInputTokens: Long = 0,
    val estimatedOutputTokens: Long = 0,
) {
    val estimatedTotalTokens: Long
        get() = estimatedInputTokens + estimatedOutputTokens

    fun recordSuccess(
        actual: TokenUsage?,
        estimatedInput: Long,
        estimatedOutput: Long,
    ): UsageStats = copy(
        requests = requests + 1,
        successes = successes + 1,
        actualInputTokens = actualInputTokens + (actual?.promptTokens ?: 0),
        actualOutputTokens = actualOutputTokens + (actual?.completionTokens ?: 0),
        actualTotalTokens = actualTotalTokens + (actual?.totalTokens ?: 0),
        estimatedInputTokens = estimatedInputTokens + estimatedInput,
        estimatedOutputTokens = estimatedOutputTokens + estimatedOutput,
    )

    /** A failed request records only the local estimate of what was sent. */
    fun recordFailure(estimatedInput: Long): UsageStats = copy(
        requests = requests + 1,
        failures = failures + 1,
        estimatedInputTokens = estimatedInputTokens + estimatedInput,
    )

    fun recordCacheHit(): UsageStats = copy(cacheHits = cacheHits + 1)
}
