package com.snapgpt.ai.failover

import com.snapgpt.ai.AiErrorCode
import com.snapgpt.ai.TokenUsage
import com.snapgpt.ai.providers.ApiAccount
import com.snapgpt.ai.providers.ConnectionStatus
import com.snapgpt.ai.providers.ImageSupport
import com.snapgpt.ai.providers.RequestKind

/**
 * What the failover manager needs from account storage. The Android implementation is Room + Keystore backed
 * (settings/AccountRepository); tests use an in-memory one. Keys are only ever handed out one at a time, at request time.
 */
interface AccountStore {
    suspend fun accounts(): List<ApiAccount>

    /** Decrypted API key for one account, or null when none is saved. */
    suspend fun apiKey(id: String): String?

    suspend fun recordSuccess(id: String, kind: RequestKind, usage: TokenUsage?, estimatedIn: Long, estimatedOut: Long, rateNote: String?, now: Long)

    /**
     * [countedRequest] = the request reached the provider (so it counts in the totals). [cooldownUntil] null = leave the
     * cooldown as it is. [usage] is whatever the provider reported for a request that failed after being processed.
     */
    suspend fun recordFailure(
        id: String, kind: RequestKind, code: AiErrorCode, message: String, countedRequest: Boolean,
        usage: TokenUsage?, estimatedIn: Long, cooldownUntil: Long?, cooldownReason: String?, now: Long,
    )

    /** Result of a manual connection test. A CONNECTED result clears the error and any cooldown. */
    suspend fun recordConnection(id: String, status: ConnectionStatus, message: String?, imageSupport: ImageSupport?, now: Long)
}

/**
 * The state changes applied to an account after a request or a test. Kept as pure functions so the Room-backed repository
 * and the in-memory test store behave identically.
 */
object AccountTransitions {
    fun success(a: ApiAccount, kind: RequestKind, usage: TokenUsage?, estIn: Long, estOut: Long, rateNote: String?, now: Long): ApiAccount = a.copy(
        stats = a.stats.recordSuccess(kind, usage, estIn, estOut),
        status = ConnectionStatus.CONNECTED,
        lastSuccessAt = now,
        lastError = null,
        // A request that worked proves the account is usable again.
        cooldownUntil = 0,
        cooldownReason = null,
        rateNote = rateNote ?: a.rateNote,
    )

    fun failure(
        a: ApiAccount, kind: RequestKind, code: AiErrorCode, message: String, counted: Boolean, usage: TokenUsage?,
        estIn: Long, cooldownUntil: Long?, cooldownReason: String?,
    ): ApiAccount {
        val status = when (code) {
            AiErrorCode.INVALID_KEY, AiErrorCode.FORBIDDEN -> ConnectionStatus.INVALID_KEY
            AiErrorCode.MODEL_UNAVAILABLE -> ConnectionStatus.MODEL_MISSING
            AiErrorCode.NETWORK, AiErrorCode.TIMEOUT -> ConnectionStatus.NETWORK_ERROR
            else -> if (a.status == ConnectionStatus.UNTESTED) ConnectionStatus.ERROR else a.status
        }
        return a.copy(
            stats = if (counted) a.stats.recordFailure(kind, code == AiErrorCode.QUOTA_EXCEEDED, usage, estIn) else a.stats,
            status = status,
            lastError = com.snapgpt.ai.Scrub.text(message).take(400),
            cooldownUntil = cooldownUntil ?: a.cooldownUntil,
            cooldownReason = if (cooldownUntil != null) cooldownReason else a.cooldownReason,
        )
    }

    fun connection(a: ApiAccount, status: ConnectionStatus, message: String?, imageSupport: ImageSupport?): ApiAccount = a.copy(
        status = status,
        lastError = if (status == ConnectionStatus.CONNECTED) null else com.snapgpt.ai.Scrub.text(message).take(400),
        cooldownUntil = if (status == ConnectionStatus.CONNECTED) 0 else a.cooldownUntil,
        cooldownReason = if (status == ConnectionStatus.CONNECTED) null else a.cooldownReason,
        imageSupportOverride = when (imageSupport) {
            ImageSupport.YES -> true
            ImageSupport.NO -> false
            else -> a.imageSupportOverride
        },
    )

    /** Editing the key, model or endpoint invalidates a cooldown that was caused by the old values. */
    fun clearedAfterEdit(a: ApiAccount): ApiAccount = a.copy(cooldownUntil = 0, cooldownReason = null, status = ConnectionStatus.UNTESTED, lastError = null)
}
