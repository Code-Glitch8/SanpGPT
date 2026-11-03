package com.snapgpt

import com.snapgpt.ai.AiErrorCode
import com.snapgpt.ai.TokenUsage
import com.snapgpt.ai.failover.AccountStore
import com.snapgpt.ai.failover.AccountTransitions
import com.snapgpt.ai.providers.ApiAccount
import com.snapgpt.ai.providers.ConnectionStatus
import com.snapgpt.ai.providers.ImageSupport
import com.snapgpt.ai.providers.ProviderType
import com.snapgpt.ai.providers.RequestKind
import com.snapgpt.networking.HttpResponse
import com.snapgpt.networking.HttpTransport
import java.io.IOException
import java.net.SocketTimeoutException

/** In-memory AccountStore with the same state transitions as the Room repository. */
class MemoryStore : AccountStore {
    val list = LinkedHashMap<String, ApiAccount>()
    val keys = HashMap<String, String>()

    fun add(
        id: String, provider: ProviderType, priority: Int, model: String = provider.defaultModel.ifBlank { "m-$id" },
        key: String? = "key-$id", endpoint: String = "https://${provider.key}-$id.test/v1", enabled: Boolean = true, name: String = id,
        imageOverride: Boolean? = null,
    ): ApiAccount {
        val a = ApiAccount(id, name, provider, endpoint, model, enabled, priority, imageOverride)
        list[id] = a
        if (key != null) keys[id] = key
        return a
    }

    operator fun get(id: String) = list.getValue(id)

    override suspend fun accounts() = list.values.toList()
    override suspend fun apiKey(id: String) = keys[id]
    override suspend fun recordSuccess(id: String, kind: RequestKind, usage: TokenUsage?, estimatedIn: Long, estimatedOut: Long, rateNote: String?, now: Long) {
        list[id] = AccountTransitions.success(list.getValue(id), kind, usage, estimatedIn, estimatedOut, rateNote, now)
    }
    override suspend fun recordFailure(
        id: String, kind: RequestKind, code: AiErrorCode, message: String, countedRequest: Boolean, usage: TokenUsage?,
        estimatedIn: Long, cooldownUntil: Long?, cooldownReason: String?, now: Long,
    ) {
        list[id] = AccountTransitions.failure(list.getValue(id), kind, code, message, countedRequest, usage, estimatedIn, cooldownUntil, cooldownReason)
    }
    override suspend fun recordConnection(id: String, status: ConnectionStatus, message: String?, imageSupport: ImageSupport?, now: Long) {
        list[id] = AccountTransitions.connection(list.getValue(id), status, message, imageSupport)
    }
}

class Sent(val method: String, val url: String, val headers: Map<String, String>, val body: String?) {
    val host: String get() = url.substringAfter("://").substringBefore("/")
}

/**
 * Scripted HTTP transport. Replies are chosen by the account host (e.g. "groq-a.test"); each host has a queue of replies
 * and the last one repeats. A reply can be a response or an exception.
 */
class FakeTransport : HttpTransport {
    val sent = ArrayList<Sent>()
    private val script = HashMap<String, ArrayDeque<Any>>()

    fun reply(host: String, vararg r: Any) { script.getOrPut(host) { ArrayDeque() }.addAll(r) }
    fun on(host: String, status: Int, body: String, headers: Map<String, String> = emptyMap()) = HttpResponse(status, headers, body)

    fun hitsOn(host: String) = sent.count { it.host == host }

    override suspend fun execute(method: String, url: String, headers: Map<String, String>, body: String?, connectTimeoutMs: Int, readTimeoutMs: Int): HttpResponse {
        val s = Sent(method, url, headers, body)
        sent.add(s)
        val q = script[s.host] ?: error("No scripted reply for ${s.host}")
        val next: Any = if (q.size > 1) q.removeFirst() else q.first()
        when (next) {
            is HttpResponse -> return next
            is IOException -> throw next
            else -> error("bad script entry")
        }
    }

    companion object {
        fun timeout() = SocketTimeoutException("timeout")
        fun openAiOk(text: String, p: Int = 100, c: Int = 20) =
            HttpResponse(200, emptyMap(), """{"choices":[{"message":{"content":${com.snapgpt.jsonString(text)}},"finish_reason":"stop"}],"usage":{"prompt_tokens":$p,"completion_tokens":$c,"total_tokens":${p + c}}}""")
        fun geminiOk(text: String) =
            HttpResponse(200, emptyMap(), """{"candidates":[{"content":{"parts":[{"text":${com.snapgpt.jsonString(text)}}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":90,"candidatesTokenCount":10,"totalTokenCount":100}}""")
        fun anthropicOk(text: String) =
            HttpResponse(200, emptyMap(), """{"content":[{"type":"text","text":${com.snapgpt.jsonString(text)}}],"stop_reason":"end_turn","usage":{"input_tokens":80,"output_tokens":12}}""")
    }
}

fun jsonString(s: String): String = kotlinx.serialization.json.JsonPrimitive(s).toString()

class Rig {
    val store = MemoryStore()
    val http = FakeTransport()
    var now = 10_000_000L
    val sleeps = ArrayList<Long>()
    val adapters = com.snapgpt.ai.providers.Adapters.standard(http)
    val manager = com.snapgpt.ai.failover.FailoverManager(store, adapters, { now }, { sleeps.add(it) })

    /** Scripts a successful reply for the account's provider. */
    fun ok(a: ApiAccount, text: String = "OK") = when (a.provider) {
        ProviderType.GEMINI -> FakeTransport.geminiOk(text)
        ProviderType.ANTHROPIC -> FakeTransport.anthropicOk(text)
        else -> FakeTransport.openAiOk(text)
    }
    fun host(id: String) = store[id].effectiveEndpoint.substringAfter("://").substringBefore("/")
    fun script(id: String, vararg r: Any) = http.reply(host(id), *r)
    fun scriptOk(id: String, text: String = "OK") = script(id, ok(store[id], text))

    fun spec(
        kind: RequestKind = RequestKind.EXPLANATION, needsImage: Boolean = false, cfg: com.snapgpt.ai.failover.FailoverConfig = com.snapgpt.ai.failover.FailoverConfig(),
        avoid: Set<String> = emptySet(), dedupe: String? = null,
    ) = com.snapgpt.ai.failover.RouteSpec(kind, needsImage, cfg, avoid, dedupe) { a ->
        com.snapgpt.ai.ChatRequest(a.model, listOf(com.snapgpt.ai.ChatMessage("user", "hi", if (needsImage) listOf("data:image/jpeg;base64,AAAA") else emptyList())), 0.2, 100, false)
    }
}

fun err(body: String = """{"error":{"message":"x"}}""", status: Int, headers: Map<String, String> = emptyMap()) = HttpResponse(status, headers, body)
