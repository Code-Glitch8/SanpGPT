package com.snapgpt.settings

import com.snapgpt.ai.AiErrorCode
import com.snapgpt.ai.TokenUsage
import com.snapgpt.ai.failover.AccountStore
import com.snapgpt.ai.failover.AccountTransitions
import com.snapgpt.ai.providers.AccountStats
import com.snapgpt.ai.providers.ApiAccount
import com.snapgpt.ai.providers.ConnectionStatus
import com.snapgpt.ai.providers.Endpoints
import com.snapgpt.ai.providers.ImageSupport
import com.snapgpt.ai.providers.ProviderType
import com.snapgpt.ai.providers.RequestKind
import com.snapgpt.database.AccountDao
import com.snapgpt.database.AccountEntity
import com.snapgpt.security.KeyCipher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Room-backed account storage (no limit on the number of accounts). Every API key is encrypted individually with its own
 * Keystore key. All read-modify-write operations are serialised so concurrent requests never lose each other's updates.
 */
class AccountRepository(private val dao: AccountDao, private val cipher: KeyCipher) : AccountStore {
    private val lock = Mutex()

    val flow: Flow<List<ApiAccount>> = dao.observeAll().map { list -> list.map { it.toAccount() } }

    /** True when at least one enabled account has a saved key (needed before scanning). */
    val hasUsableAccount: Flow<Boolean> = dao.observeAll().map { list -> list.any { it.enabled && !it.keyBlob.isNullOrBlank() } }

    // ------------------------------------------------------------------ AccountStore (used by the failover manager)

    override suspend fun accounts(): List<ApiAccount> = withContext(Dispatchers.IO) { dao.all().map { it.toAccount() } }

    override suspend fun apiKey(id: String): String? = withContext(Dispatchers.IO) { cipher.decrypt(id, dao.get(id)?.keyBlob) }

    private suspend fun mutate(id: String, f: (ApiAccount) -> ApiAccount) = withContext(Dispatchers.IO) {
        lock.withLock {
            val e = dao.get(id) ?: return@withLock
            dao.upsert(f(e.toAccount()).toEntity(e.keyBlob))
        }
    }

    override suspend fun recordSuccess(id: String, kind: RequestKind, usage: TokenUsage?, estimatedIn: Long, estimatedOut: Long, rateNote: String?, now: Long) =
        mutate(id) { AccountTransitions.success(it, kind, usage, estimatedIn, estimatedOut, rateNote, now) }

    override suspend fun recordFailure(
        id: String, kind: RequestKind, code: AiErrorCode, message: String, countedRequest: Boolean, usage: TokenUsage?,
        estimatedIn: Long, cooldownUntil: Long?, cooldownReason: String?, now: Long,
    ) = mutate(id) { AccountTransitions.failure(it, kind, code, message, countedRequest, usage, estimatedIn, cooldownUntil, cooldownReason) }

    override suspend fun recordConnection(id: String, status: ConnectionStatus, message: String?, imageSupport: ImageSupport?, now: Long) =
        mutate(id) { AccountTransitions.connection(it, status, message, imageSupport) }

    // ------------------------------------------------------------------ management (used by the UI)

    /** Adds an account at the lowest priority and returns its id. */
    suspend fun add(name: String, provider: ProviderType, endpoint: String, model: String, apiKey: String, imageSupportOverride: Boolean? = null): String =
        withContext(Dispatchers.IO) {
            lock.withLock {
                val id = UUID.randomUUID().toString()
                val next = (dao.all().maxOfOrNull { it.priority } ?: -1) + 1
                val a = ApiAccount(
                    id, name.trim().ifBlank { provider.displayName }, provider, Endpoints.normalize(endpoint), model.trim(),
                    enabled = true, priority = next, imageSupportOverride = imageSupportOverride,
                )
                dao.upsert(a.toEntity(if (apiKey.isBlank()) null else cipher.encrypt(id, apiKey)))
                id
            }
        }

    /**
     * Edits an account. [newApiKey] null = keep the saved key; blank = delete it. Changing the key, model, endpoint or
     * provider clears a cooldown or error that belonged to the old values.
     */
    suspend fun update(
        id: String, name: String, provider: ProviderType, endpoint: String, model: String, imageSupportOverride: Boolean?, newApiKey: String?,
    ) = withContext(Dispatchers.IO) {
        lock.withLock {
            val e = dao.get(id) ?: return@withLock
            val old = e.toAccount()
            var blob = e.keyBlob
            if (newApiKey != null) {
                if (newApiKey.isBlank()) { cipher.delete(id); blob = null } else blob = cipher.encrypt(id, newApiKey)
            }
            var a = old.copy(
                name = name.trim().ifBlank { provider.displayName }, provider = provider, endpoint = Endpoints.normalize(endpoint),
                model = model.trim(), imageSupportOverride = imageSupportOverride,
            )
            if (newApiKey != null || a.model != old.model || a.endpoint != old.endpoint || a.provider != old.provider) a = AccountTransitions.clearedAfterEdit(a)
            dao.upsert(a.toEntity(blob))
        }
    }

    suspend fun setEnabled(id: String, enabled: Boolean) = mutate(id) { it.copy(enabled = enabled) }

    /** Moves an account one step up (-1) or down (+1) in the priority list and renumbers all priorities 0..n-1. */
    suspend fun move(id: String, delta: Int) = withContext(Dispatchers.IO) {
        lock.withLock {
            val list = dao.all().toMutableList()
            val i = list.indexOfFirst { it.id == id }
            val j = i + delta
            if (i < 0 || j !in list.indices) return@withLock
            val moved = list.removeAt(i)
            list.add(j, moved)
            dao.upsertAll(list.mapIndexed { idx, a -> a.copy(priority = idx) })
        }
    }

    /** Copies an account. The API key is copied ONLY when [copyKey] is true (the UI asks the user). */
    suspend fun duplicate(id: String, copyKey: Boolean): String? = withContext(Dispatchers.IO) {
        lock.withLock {
            val src = dao.get(id) ?: return@withLock null
            val newId = UUID.randomUUID().toString()
            val plain = if (copyKey) cipher.decrypt(id, src.keyBlob) else null
            val next = (dao.all().maxOfOrNull { it.priority } ?: -1) + 1
            val copy = src.toAccount().copy(
                id = newId, name = src.name + " (copy)", priority = next, stats = AccountStats(), status = ConnectionStatus.UNTESTED,
                lastError = null, lastSuccessAt = 0, cooldownUntil = 0, cooldownReason = null, rateNote = null,
            )
            dao.upsert(copy.toEntity(plain?.let { cipher.encrypt(newId, it) }))
            newId
        }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        lock.withLock {
            cipher.delete(id)
            dao.delete(id)
            dao.all().forEachIndexed { idx, a -> if (a.priority != idx) dao.upsert(a.copy(priority = idx)) }
        }
    }

    /** Removes only the stored credential of one account (the account stays, without a key). */
    suspend fun deleteKey(id: String) = withContext(Dispatchers.IO) {
        lock.withLock {
            val e = dao.get(id) ?: return@withLock
            cipher.delete(id)
            dao.upsert(AccountTransitions.clearedAfterEdit(e.toAccount()).toEntity(null))
        }
    }

    /** Privacy: deletes every stored credential (keys and their Keystore entries) but keeps the accounts. */
    suspend fun deleteAllKeys() = withContext(Dispatchers.IO) {
        lock.withLock {
            dao.all().forEach { cipher.delete(it.id); dao.upsert(it.copy(keyBlob = null)) }
        }
    }

    suspend fun deleteAllAccounts() = withContext(Dispatchers.IO) {
        lock.withLock {
            dao.all().forEach { cipher.delete(it.id) }
            dao.deleteAll()
        }
    }

    suspend fun resetStats(id: String) = mutate(id) { it.copy(stats = AccountStats()) }

    suspend fun clearCooldown(id: String) = mutate(id) { it.copy(cooldownUntil = 0, cooldownReason = null) }

    /** Masked preview ("gsk_••••wxyz") for the edit screen; the full key is never shown again. */
    suspend fun maskedKey(id: String): String = com.snapgpt.security.maskKey(apiKey(id))

    suspend fun hasKey(id: String): Boolean = withContext(Dispatchers.IO) { !dao.get(id)?.keyBlob.isNullOrBlank() }

    // ------------------------------------------------------------------ mapping

    private fun AccountEntity.toAccount() = ApiAccount(
        id = id, name = name, provider = ProviderType.fromKey(provider), endpoint = endpoint, model = model, enabled = enabled, priority = priority,
        imageSupportOverride = when (imageOverride) { 1 -> true; 0 -> false; else -> null },
        status = ConnectionStatus.entries.firstOrNull { it.name == status } ?: ConnectionStatus.UNTESTED,
        lastError = lastError, lastSuccessAt = lastSuccessAt, cooldownUntil = cooldownUntil, cooldownReason = cooldownReason, rateNote = rateNote,
        stats = AccountStats(
            requests, successes, failures, quotaErrors, actualIn, actualOut, estIn, estOut, analysisReq, explanationReq, verificationReq, followUpReq,
        ),
    )

    private fun ApiAccount.toEntity(keyBlob: String?) = AccountEntity(
        id = id, name = name, provider = provider.key, endpoint = endpoint, model = model, enabled = enabled, priority = priority,
        imageOverride = when (imageSupportOverride) { true -> 1; false -> 0; null -> -1 },
        status = status.name, lastError = lastError, lastSuccessAt = lastSuccessAt, cooldownUntil = cooldownUntil, cooldownReason = cooldownReason,
        rateNote = rateNote, keyBlob = keyBlob,
        requests = stats.requests, successes = stats.successes, failures = stats.failures, quotaErrors = stats.quotaErrors,
        actualIn = stats.actualInputTokens, actualOut = stats.actualOutputTokens, estIn = stats.estimatedInputTokens, estOut = stats.estimatedOutputTokens,
        analysisReq = stats.analysisRequests, explanationReq = stats.explanationRequests, verificationReq = stats.verificationRequests, followUpReq = stats.followUpRequests,
    )
}
