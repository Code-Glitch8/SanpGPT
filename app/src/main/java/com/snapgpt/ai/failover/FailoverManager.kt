package com.snapgpt.ai.failover

import com.snapgpt.ai.AiErrorCode
import com.snapgpt.ai.AiException
import com.snapgpt.ai.ChatRequest
import com.snapgpt.ai.ChatResult
import com.snapgpt.ai.ConnectionResult
import com.snapgpt.ai.ConnectionState
import com.snapgpt.ai.ErrorMapper
import com.snapgpt.ai.TokenEstimator
import com.snapgpt.ai.providers.Adapters
import com.snapgpt.ai.providers.ApiAccount
import com.snapgpt.ai.providers.ConnectionStatus
import com.snapgpt.ai.providers.Endpoints
import com.snapgpt.ai.providers.ImageSupport
import com.snapgpt.ai.providers.ProviderType
import com.snapgpt.ai.providers.RequestKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap

data class FailoverConfig(
    /** When false only the highest-priority enabled account is ever used. */
    val enabled: Boolean = true,
    /** Extra tries on the SAME account for temporary problems (5xx, network, timeout) before moving on. */
    val maxRetriesPerAccount: Int = 2,
    val baseBackoffMs: Long = 1_000,
    val maxBackoffMs: Long = 8_000,
    /** Hard cap on accounts tried for one request, so a long list never means a long wait. */
    val maxAccountsTried: Int = 8,
)

/** One request to route. [build] creates the provider-neutral request for the chosen account (its own model id). */
class RouteSpec(
    val kind: RequestKind,
    val needsImage: Boolean,
    val config: FailoverConfig,
    /** Accounts to try only after all others (e.g. verification prefers a different account than the one that answered). */
    val avoidAccountIds: Set<String> = emptySet(),
    /** While a request with this key is running, an identical one is rejected instead of being sent twice. */
    val dedupeKey: String? = null,
    val build: (ApiAccount) -> ChatRequest,
)

data class Attempt(val accountName: String, val code: AiErrorCode, val message: String)

class RoutedResult<T>(val value: T, val result: ChatResult, val account: ApiAccount, val attempts: List<Attempt>)

/**
 * Routes each request over the user's accounts in priority order and moves on when an account cannot serve it.
 *
 *  - Eligible = enabled, has a key, a model and an https endpoint, not cooling down, and (for screenshots) a model that takes images.
 *  - Order: priority first; after a failure, remaining accounts of the SAME provider come before other providers.
 *  - Recoverable failures (quota, 429, bad/revoked key, model gone, temporary 5xx/network after limited retries) cool the account
 *    down (respecting Retry-After) and the next account is tried. Ordinary malformed requests are NOT retried elsewhere.
 *  - Nothing is retried without bound and an identical in-flight request is never sent twice.
 *  - Models are never swapped: every account uses exactly the model the user configured for it.
 */
class FailoverManager(
    private val store: AccountStore,
    private val adapters: Adapters,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
) {
    private val inFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private enum class Decision { TEMPORARY, SWITCH, NEXT, STOP }

    private fun classify(e: AiException): Decision = when (e.code) {
        AiErrorCode.NETWORK, AiErrorCode.TIMEOUT, AiErrorCode.SERVER -> Decision.TEMPORARY
        AiErrorCode.RATE_LIMIT, AiErrorCode.TOKEN_LIMIT, AiErrorCode.QUOTA_EXCEEDED, AiErrorCode.INVALID_KEY,
        AiErrorCode.FORBIDDEN, AiErrorCode.MODEL_UNAVAILABLE -> Decision.SWITCH
        // A provider-side "this model takes no images" is account-specific; our own pre-flight limit (no HTTP status) is not.
        AiErrorCode.IMAGE_UNSUPPORTED -> if (e.httpStatus != null) Decision.SWITCH else Decision.STOP
        AiErrorCode.INVALID_RESPONSE, AiErrorCode.NO_API_KEY -> Decision.NEXT
        else -> Decision.STOP
    }

    private fun cooldownSeconds(e: AiException): Pair<Int, String> {
        val retry = e.retryAfterSeconds
        val (s, why) = when (e.code) {
            // Never come back sooner than 5 minutes after a quota error, even if a provider reports a very short delay.
            AiErrorCode.QUOTA_EXCEEDED -> maxOf(retry ?: 1_800, 300) to "Quota reached"
            AiErrorCode.RATE_LIMIT -> (retry ?: 60) to "Rate limit"
            AiErrorCode.TOKEN_LIMIT -> (retry ?: 60) to "Token rate limit"
            AiErrorCode.INVALID_KEY -> 6 * 3600 to "API key rejected"
            AiErrorCode.FORBIDDEN -> 6 * 3600 to "Access refused"
            AiErrorCode.MODEL_UNAVAILABLE -> 1_800 to "Model unavailable"
            AiErrorCode.IMAGE_UNSUPPORTED -> 1_800 to "Model does not accept images"
            else -> 60 to "Temporary provider problem"
        }
        return s.coerceIn(1, 24 * 3600) to why
    }

    private fun statusFor(name: String, e: AiException): String = when (e.code) {
        AiErrorCode.QUOTA_EXCEEDED -> "$name quota reached."
        AiErrorCode.RATE_LIMIT, AiErrorCode.TOKEN_LIMIT -> "$name rate limit reached."
        AiErrorCode.INVALID_KEY -> "$name rejected the API key."
        AiErrorCode.FORBIDDEN -> "$name refused access."
        AiErrorCode.MODEL_UNAVAILABLE -> "$name: the configured model is unavailable."
        AiErrorCode.IMAGE_UNSUPPORTED -> "$name: the model does not accept images."
        AiErrorCode.NETWORK, AiErrorCode.TIMEOUT -> "$name could not be reached."
        AiErrorCode.SERVER -> "$name had a temporary problem."
        else -> "$name returned an unusable answer."
    }

    private fun sortedEnabled(all: List<ApiAccount>) = all.filter { it.enabled }.sortedWith(compareBy({ it.priority }, { it.name.lowercase() }, { it.id }))

    private suspend fun skipReason(a: ApiAccount, needsImage: Boolean, now: Long): String? = when {
        store.apiKey(a.id).isNullOrBlank() -> "no API key saved"
        a.model.isBlank() -> "no model selected"
        a.provider.endpointRequired && a.endpoint.isBlank() -> "no endpoint set"
        !Endpoints.isAllowed(a.effectiveEndpoint) -> "endpoint is not https"
        a.inCooldown(now) -> "cooling down for ${ErrorMapper.formatWait(a.cooldownRemainingSeconds(now))}" + (a.cooldownReason?.let { " ($it)" } ?: "")
        needsImage && !a.supportsImages() ->
            if (a.imageSupport() == ImageSupport.NO) "model does not accept images" else "image support for this model is not confirmed"
        else -> null
    }

    /** First eligible account in order, or null. Used to show the user WHERE a screenshot would go before they confirm. */
    suspend fun preview(needsImage: Boolean, config: FailoverConfig, avoid: Set<String> = emptySet()): ApiAccount? {
        val pool = pool(config, store.accounts())
        val now = clock()
        val eligible = pool.filter { skipReason(it, needsImage, now) == null }
        return eligible.firstOrNull { it.id !in avoid } ?: eligible.firstOrNull()
    }

    private fun pool(config: FailoverConfig, all: List<ApiAccount>): List<ApiAccount> {
        val enabled = sortedEnabled(all)
        return if (config.enabled) enabled else enabled.take(1)
    }

    suspend fun <T : Any> route(spec: RouteSpec, onStatus: (String) -> Unit = {}, parse: (ChatResult) -> T): RoutedResult<T> {
        val key = spec.dedupeKey
        if (key != null && !inFlight.add(key)) throw AiException(AiErrorCode.DUPLICATE, "This request is already running.")
        try {
            return doRoute(spec, onStatus, parse)
        } finally {
            if (key != null) inFlight.remove(key)
        }
    }

    private suspend fun <T : Any> doRoute(spec: RouteSpec, onStatus: (String) -> Unit, parse: (ChatResult) -> T): RoutedResult<T> {
        val cfg = spec.config
        val all = store.accounts()
        if (all.none { it.enabled }) {
            throw AiException(AiErrorCode.NO_ACCOUNTS, "No API account is enabled. Open SnapGPT > Settings > API Accounts and add or enable one.")
        }
        val remaining = pool(cfg, all).toMutableList()
        val attempts = mutableListOf<Attempt>()
        val skipped = linkedMapOf<String, String>()
        var tried = 0
        var lastProvider: ProviderType? = null
        var lastError: AiException? = null

        while (tried < cfg.maxAccountsTried) {
            val now = clock()
            // Drop accounts that cannot serve this request, remembering why.
            val eligible = ArrayList<ApiAccount>()
            val iter = remaining.iterator()
            while (iter.hasNext()) {
                val a = iter.next()
                val why = skipReason(a, spec.needsImage, now)
                if (why != null) { skipped.putIfAbsent(a.name, why); iter.remove() } else eligible.add(a)
            }
            if (eligible.isEmpty()) break
            val preferred = eligible.filter { it.id !in spec.avoidAccountIds }.ifEmpty { eligible }
            val account = preferred.firstOrNull { it.provider == lastProvider } ?: preferred.first()
            remaining.remove(account)
            tried++

            val provider = account.provider.displayName
            onStatus(
                when {
                    attempts.isNotEmpty() -> "Switching to ${account.name}" + if (spec.needsImage) "... sending the screenshot there." else "..."
                    spec.needsImage -> "Sending screenshot to ${account.name} ($provider)..."
                    else -> "Connecting to ${account.name}..."
                },
            )

            val outcome = tryAccount(account, spec, onStatus, parse, hasOthers = { remaining.isNotEmpty() })
            val value = outcome.value
            if (value != null) {
                onStatus("Answer generated using ${account.name}.")
                return RoutedResult(value, outcome.result!!, account, attempts)
            }
            val e = outcome.error!!
            lastError = e
            attempts.add(Attempt(account.name, e.code, e.message ?: ""))
            lastProvider = account.provider
            if (outcome.stop) throw e
            if (!cfg.enabled) break
            onStatus(statusFor(account.name, e))
        }

        if (attempts.isEmpty()) {
            val why = skipped.entries.joinToString("\n") { "• ${it.key}: ${it.value}" }
            throw AiException(AiErrorCode.NO_ACCOUNTS, "No account can take this request right now." + if (why.isNotEmpty()) "\n$why" else "")
        }
        if (attempts.size == 1 && skipped.isEmpty()) throw lastError!!
        val lines = attempts.joinToString("\n") { "• ${it.accountName}: ${it.message}" }
        val skipLines = if (skipped.isEmpty()) "" else "\nSkipped:\n" + skipped.entries.joinToString("\n") { "• ${it.key}: ${it.value}" }
        throw AiException(AiErrorCode.ALL_ACCOUNTS_FAILED, "All eligible accounts failed:\n$lines$skipLines", lastError?.retryAfterSeconds)
    }

    private class Outcome<T>(val value: T?, val result: ChatResult?, val error: AiException?, val stop: Boolean)

    /** Runs one account, including its limited same-account retries. */
    private suspend fun <T : Any> tryAccount(
        account: ApiAccount, spec: RouteSpec, onStatus: (String) -> Unit, parse: (ChatResult) -> T, hasOthers: () -> Boolean,
    ): Outcome<T> {
        val cfg = spec.config
        val adapter = adapters.forType(account.provider)
        val apiKey = store.apiKey(account.id).orEmpty()
        var retries = 0
        while (true) {
            val request = spec.build(account)
            val estIn = TokenEstimator.request(request.messages)
            var result: ChatResult? = null
            var failure: AiException? = null
            try {
                result = adapter.chat(account, apiKey, request)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AiException) {
                failure = e
            }
            var parsed: T? = null
            if (failure == null) {
                try {
                    parsed = parse(result!!)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: AiException) {
                    failure = e
                }
            }
            val now = clock()
            if (failure == null) {
                store.recordSuccess(account.id, spec.kind, result!!.usage, estIn, TokenEstimator.text(result.text.length), result.rateLimit.describe(), now)
                return Outcome(parsed, result, null, false)
            }

            val e: AiException = failure
            val counted = e.httpStatus != null || e.code == AiErrorCode.NETWORK || e.code == AiErrorCode.TIMEOUT || result != null
            val quota = e.code == AiErrorCode.QUOTA_EXCEEDED
            when (classify(e)) {
                Decision.TEMPORARY -> {
                    val retryAfter = e.retryAfterSeconds
                    val canWait = retries < cfg.maxRetriesPerAccount && (retryAfter == null || retryAfter <= 15)
                    if (canWait) {
                        val backoff = minOf(cfg.baseBackoffMs shl retries, cfg.maxBackoffMs)
                        val wait = maxOf(backoff, (retryAfter ?: 0) * 1000L)
                        store.recordFailure(account.id, spec.kind, e.code, e.message ?: "", counted, result?.usage, estIn, null, null, now)
                        onStatus("${account.name} had a temporary problem. Retrying in ${(wait + 999) / 1000} s...")
                        retries++
                        sleeper(wait)
                        continue
                    }
                    val (s, why) = cooldownSeconds(e)
                    val secs = if (retryAfter != null && retryAfter > 15) s.coerceAtLeast(retryAfter) else s
                    store.recordFailure(account.id, spec.kind, e.code, e.message ?: "", counted, result?.usage, estIn, now + secs * 1000L, why, now)
                    return Outcome(null, null, e, false)
                }
                Decision.SWITCH -> {
                    val retryAfter = e.retryAfterSeconds
                    val shortRateLimit = (e.code == AiErrorCode.RATE_LIMIT || e.code == AiErrorCode.TOKEN_LIMIT) && retryAfter != null && retryAfter in 1..10
                    // Only account left and the provider asked for a short wait: honour Retry-After and try once more, within the retry cap.
                    if (shortRateLimit && !hasOthers() && retries < cfg.maxRetriesPerAccount) {
                        store.recordFailure(account.id, spec.kind, e.code, e.message ?: "", counted, result?.usage, estIn, null, null, now)
                        onStatus("${account.name} rate limit reached. Waiting $retryAfter s as the provider asked...")
                        retries++
                        sleeper(retryAfter!! * 1000L)
                        continue
                    }
                    val (s, why) = cooldownSeconds(e)
                    store.recordFailure(account.id, spec.kind, e.code, e.message ?: "", counted, result?.usage, estIn, now + s * 1000L, why, now)
                    return Outcome(null, null, e, false)
                }
                Decision.NEXT -> {
                    store.recordFailure(account.id, spec.kind, e.code, e.message ?: "", counted, result?.usage, estIn, null, null, now)
                    return Outcome(null, null, e, false)
                }
                Decision.STOP -> {
                    store.recordFailure(account.id, spec.kind, e.code, e.message ?: "", counted, result?.usage, estIn, null, null, now)
                    return Outcome(null, null, e, true)
                }
            }
        }
    }
}

/** Manual "Test connection" for one account. A successful test clears the account's error and cooldown. */
class AccountTester(
    private val store: AccountStore,
    private val adapters: Adapters,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    suspend fun test(account: ApiAccount): ConnectionResult {
        val key = store.apiKey(account.id)
        if (key.isNullOrBlank()) {
            store.recordConnection(account.id, ConnectionStatus.INVALID_KEY, "No API key saved.", null, clock())
            return ConnectionResult(ConnectionState.INVALID_KEY, "No API key saved for this account.")
        }
        val check = try {
            adapters.forType(account.provider).validateModel(account, key)
        } catch (e: CancellationException) {
            throw e
        } catch (e: AiException) {
            com.snapgpt.ai.providers.ModelCheck(ConnectionResult(ConnectionState.OTHER_ERROR, e.message ?: "Error"), ImageSupport.UNKNOWN)
        }
        val status = when (check.result.state) {
            ConnectionState.CONNECTED -> ConnectionStatus.CONNECTED
            ConnectionState.INVALID_KEY -> ConnectionStatus.INVALID_KEY
            ConnectionState.MODEL_MISSING -> ConnectionStatus.MODEL_MISSING
            ConnectionState.NETWORK_ERROR -> ConnectionStatus.NETWORK_ERROR
            ConnectionState.OTHER_ERROR -> ConnectionStatus.ERROR
        }
        // Only learn image support from the provider when the user has not decided it themselves.
        val learned = if (account.imageSupportOverride == null && check.imageSupport != ImageSupport.UNKNOWN) check.imageSupport else null
        store.recordConnection(account.id, status, if (status == ConnectionStatus.CONNECTED) null else check.result.message, learned, clock())
        return check.result
    }
}
