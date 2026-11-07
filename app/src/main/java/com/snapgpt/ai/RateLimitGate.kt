package com.snapgpt.ai

/**
 * In-memory cooldown gate for a provider client.
 *
 * A 429 response blocks later requests through this client until the cooldown expires.
 * The gate uses wall-clock time so it also works in local JVM unit tests.
 */
class RateLimitGate(
    private val providerName: String,
) {
    private val lock = Any()

    @Volatile
    private var blockedUntilMillis: Long = 0L

    /** Start or extend the cooldown. A non-positive duration is treated as one second. */
    fun block(seconds: Int) {
        val durationSeconds = seconds.coerceAtLeast(1).toLong()
        val newDeadline = System.currentTimeMillis() + durationSeconds * 1_000L

        synchronized(lock) {
            if (newDeadline > blockedUntilMillis) {
                blockedUntilMillis = newDeadline
            }
        }
    }

    /** Clear the cooldown, for example after the account is edited or tested successfully. */
    fun reset() {
        synchronized(lock) {
            blockedUntilMillis = 0L
        }
    }

    /** Throws a typed, retryable rate-limit error while the cooldown is active. */
    fun check() {
        val remainingMillis = synchronized(lock) {
            (blockedUntilMillis - System.currentTimeMillis()).coerceAtLeast(0L)
        }

        if (remainingMillis <= 0L) return

        val remainingSeconds = ((remainingMillis + 999L) / 1_000L)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()

        throw AiException(
            code = AiErrorCode.RATE_LIMIT,
            message = "$providerName is cooling down after a rate limit. " +
                "Try again in about $remainingSeconds seconds.",
            retryAfterSeconds = remainingSeconds,
        )
    }
}
