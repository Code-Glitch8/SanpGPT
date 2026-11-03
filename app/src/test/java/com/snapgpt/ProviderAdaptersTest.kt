package com.snapgpt

import com.snapgpt.ai.AiErrorCode
import com.snapgpt.ai.AiException
import com.snapgpt.ai.ChatMessage
import com.snapgpt.ai.ChatRequest
import com.snapgpt.ai.ConnectionState
import com.snapgpt.ai.RetryHint
import com.snapgpt.ai.providers.*
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress

class ProviderAdaptersTest {
    private val img = "data:image/jpeg;base64,AAAA"
    private fun req(model: String, images: List<String> = emptyList(), json: Boolean = true) = ChatRequest(
        model, listOf(ChatMessage("system", "sys"), ChatMessage("user", "hello", images)), 0.5, 100, json,
    )
    private fun acct(p: ProviderType, model: String = p.defaultModel.ifBlank { "m" }, endpoint: String = "https://${p.key}.test/v1") =
        ApiAccount("id-${p.key}", p.displayName, p, endpoint, model)
    private fun body(t: FakeTransport) = Json.parseToJsonElement(t.sent.last().body!!).jsonObject
    private fun fails(block: suspend () -> Unit): AiException = try { runBlocking { block() }; throw AssertionError("expected failure") } catch (e: AiException) { e }

    @Test fun groqRequestShapeAndUsage() = runBlocking {
        val t = FakeTransport(); t.reply("groq.test", FakeTransport.openAiOk("{\"questions\":[]}", 2100, 40))
        val res = GroqAdapter(t).chat(acct(ProviderType.GROQ), "gsk_testkey1234", req("qwen/qwen3.8-27b", listOf(img)))
        assertEquals("{\"questions\":[]}", res.text); assertEquals(2140, res.usage!!.totalTokens)
        assertEquals("https://groq.test/v1/chat/completions", t.sent[0].url)
        assertEquals("Bearer gsk_testkey1234", t.sent[0].headers["Authorization"])
        val b = body(t)
        assertEquals("json_object", b["response_format"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("hidden", b["reasoning_format"]!!.jsonPrimitive.content)
        assertEquals(100, b["max_completion_tokens"]!!.jsonPrimitive.content.toInt())
        val c = b["messages"]!!.jsonArray[1].jsonObject["content"]!!.jsonArray
        assertEquals(img, c[1].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content)
        // non-reasoning model: no reasoning_format
        t.reply("groq.test", FakeTransport.openAiOk("x"))
        GroqAdapter(t).chat(acct(ProviderType.GROQ, "llama-4-scout"), "k", req("llama-4-scout"))
        assertFalse(body(t).containsKey("reasoning_format"))
    }

    @Test fun customOpenAiCompatibleUsesPromptJsonAndMaxTokens() = runBlocking {
        val t = FakeTransport(); t.reply("custom.test", FakeTransport.openAiOk("hi"))
        OpenAICompatibleAdapter(t).chat(acct(ProviderType.CUSTOM_OPENAI, "my-model", "https://custom.test/v1/chat/completions/"), "k", req("my-model"))
        assertEquals("https://custom.test/v1/chat/completions", t.sent[0].url) // pasted full URL is normalised
        val b = body(t)
        assertFalse(b.containsKey("response_format")); assertEquals(100, b["max_tokens"]!!.jsonPrimitive.content.toInt())
        assertEquals("hello", b["messages"]!!.jsonArray[1].jsonObject["content"]!!.jsonPrimitive.content)
    }

    @Test fun openRouterHeadersAndImageCapabilityFromModelList() = runBlocking {
        val t = FakeTransport()
        t.reply("openrouter.test", t.on("x", 200, """{"data":[{"id":"vendor/vis","architecture":{"input_modalities":["text","image"]}},{"id":"vendor/txt","architecture":{"input_modalities":["text"]}}]}"""))
        val ad = OpenRouterAdapter(t)
        val ok = ad.validateModel(acct(ProviderType.OPENROUTER, "vendor/vis"), "k")
        assertEquals(ConnectionState.CONNECTED, ok.result.state); assertEquals(ImageSupport.YES, ok.imageSupport)
        assertEquals("SnapGPT", t.sent[0].headers["X-Title"]); assertNotNull(t.sent[0].headers["HTTP-Referer"])
        assertEquals(ImageSupport.NO, ad.validateModel(acct(ProviderType.OPENROUTER, "vendor/txt"), "k").imageSupport)
        assertEquals(ConnectionState.MODEL_MISSING, ad.validateModel(acct(ProviderType.OPENROUTER, "vendor/none"), "k").result.state)
    }

    @Test fun geminiRequestShapeKeyInHeaderOnlyAndThoughtsIgnored() = runBlocking {
        val t = FakeTransport()
        t.reply("gemini.test", t.on("x", 200, """{"candidates":[{"content":{"parts":[{"thought":true,"text":"thinking"},{"text":"answer"}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":90,"candidatesTokenCount":10,"thoughtsTokenCount":30,"totalTokenCount":130}}"""))
        val msgs = listOf(ChatMessage("system", "sys"), ChatMessage("user", "q", listOf(img)), ChatMessage("assistant", "a"), ChatMessage("user", "more"))
        val res = GeminiAdapter(t).chat(acct(ProviderType.GEMINI, "models/gemini-2.5-flash"), "AIzaSECRET123456", ChatRequest("models/gemini-2.5-flash", msgs, 0.3, 200, true))
        assertEquals("answer", res.text); assertEquals(40, res.usage!!.completionTokens); assertEquals(130, res.usage!!.totalTokens)
        assertEquals("https://gemini.test/v1/models/gemini-2.5-flash:generateContent", t.sent[0].url)
        assertFalse(t.sent[0].url.contains("SECRET")); assertEquals("AIzaSECRET123456", t.sent[0].headers["x-goog-api-key"])
        val b = body(t)
        assertEquals("sys", b["systemInstruction"]!!.jsonObject["parts"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
        val contents = b["contents"]!!.jsonArray
        assertEquals(listOf("user", "model", "user"), contents.map { it.jsonObject["role"]!!.jsonPrimitive.content })
        val inline = contents[0].jsonObject["parts"]!!.jsonArray[1].jsonObject["inline_data"]!!.jsonObject
        assertEquals("image/jpeg", inline["mime_type"]!!.jsonPrimitive.content); assertEquals("AAAA", inline["data"]!!.jsonPrimitive.content)
        assertEquals("application/json", b["generationConfig"]!!.jsonObject["responseMimeType"]!!.jsonPrimitive.content)
        assertEquals(200, b["generationConfig"]!!.jsonObject["maxOutputTokens"]!!.jsonPrimitive.content.toInt())
    }

    @Test fun geminiBlockedAndTruncatedAnswersAreErrors() {
        val t = FakeTransport(); val a = acct(ProviderType.GEMINI)
        t.reply("gemini.test", t.on("x", 200, """{"promptFeedback":{"blockReason":"SAFETY"}}"""))
        assertEquals(AiErrorCode.INVALID_RESPONSE, fails { GeminiAdapter(t).chat(a, "k", req(a.model)) }.code)
        val t2 = FakeTransport(); t2.reply("gemini.test", t2.on("x", 200, """{"candidates":[{"finishReason":"MAX_TOKENS"}]}"""))
        assertEquals(AiErrorCode.TRUNCATED, fails { GeminiAdapter(t2).chat(a, "k", req(a.model)) }.code)
    }

    @Test fun anthropicRequestShapeAndTurnMerging() = runBlocking {
        val t = FakeTransport(); t.reply("anthropic.test", FakeTransport.anthropicOk("claude says"))
        val msgs = listOf(ChatMessage("system", "sys"), ChatMessage("user", "one", listOf(img)), ChatMessage("user", "two"), ChatMessage("assistant", "ans"), ChatMessage("user", "three"))
        val res = AnthropicAdapter(t).chat(acct(ProviderType.ANTHROPIC), "sk-ant-KEYKEYKEY1", ChatRequest("claude-sonnet-5-5", msgs, 0.2, 300, true))
        assertEquals("claude says", res.text); assertEquals(80, res.usage!!.promptTokens); assertEquals(92, res.usage!!.totalTokens)
        assertEquals("https://anthropic.test/v1/messages", t.sent[0].url)
        assertEquals("sk-ant-KEYKEYKEY1", t.sent[0].headers["x-api-key"]); assertEquals("2023-06-01", t.sent[0].headers["anthropic-version"])
        val b = body(t)
        assertEquals("sys", b["system"]!!.jsonPrimitive.content)
        val m = b["messages"]!!.jsonArray
        assertEquals(listOf("user", "assistant", "user"), m.map { it.jsonObject["role"]!!.jsonPrimitive.content })
        val first = m[0].jsonObject["content"]!!.jsonArray
        assertEquals("image", first[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("image/jpeg", first[0].jsonObject["source"]!!.jsonObject["media_type"]!!.jsonPrimitive.content)
        assertEquals(300, b["max_tokens"]!!.jsonPrimitive.content.toInt())
    }

    @Test fun anthropicRejectsOversizedImagesBeforeTheNetwork() {
        val t = FakeTransport(); val big = "data:image/jpeg;base64," + "A".repeat(5 * 1024 * 1024 + 10)
        val e = fails { AnthropicAdapter(t).chat(acct(ProviderType.ANTHROPIC), "k", req("claude-sonnet-5-5", listOf(big))) }
        assertEquals(AiErrorCode.IMAGE_TOO_LARGE, e.code); assertEquals(0, t.sent.size)
    }

    private fun mapped(p: ProviderType, status: Int, body: String, headers: Map<String, String> = emptyMap()): AiException {
        val t = FakeTransport(); t.reply("${p.key}.test", err(body, status, headers))
        val ad = Adapters.standard(t).forType(p)
        val a = acct(p, "some-model")
        return fails { ad.chat(a, "gsk_SECRETSECRET1", req("some-model")) }
    }

    @Test fun errorMappingAcrossProviders() {
        assertEquals(AiErrorCode.INVALID_KEY, mapped(ProviderType.GROQ, 401, """{"error":{"message":"Invalid API Key gsk_SECRETSECRET1"}}""").code)
        assertFalse(mapped(ProviderType.GROQ, 401, """{"error":{"message":"Invalid API Key gsk_SECRETSECRET1"}}""").message!!.contains("SECRETSECRET"))
        mapped(ProviderType.GROQ, 429, """{"error":{"message":"on tokens per minute (TPM): Limit 6000. Please try again in 7.5s."}}""", mapOf("retry-after" to "8")).let {
            assertEquals(AiErrorCode.TOKEN_LIMIT, it.code); assertEquals(8, it.retryAfterSeconds) }
        assertEquals(AiErrorCode.QUOTA_EXCEEDED, mapped(ProviderType.GROQ, 429, """{"error":{"message":"on requests per day (RPD): Limit 1000"}}""").code)
        val gq = """{"error":{"code":429,"message":"You exceeded your current quota, please check your plan and billing details.","status":"RESOURCE_EXHAUSTED","details":[{"violations":[{"quotaId":"GenerateRequestsPerDayPerProjectPerModel-FreeTier"}]},{"retryDelay":"34s"}]}}"""
        mapped(ProviderType.GEMINI, 429, gq).let { assertEquals(AiErrorCode.QUOTA_EXCEEDED, it.code); assertEquals(34, it.retryAfterSeconds) }
        val gm = gq.replace("PerDay", "PerMinute")
        mapped(ProviderType.GEMINI, 429, gm).let { assertEquals(AiErrorCode.RATE_LIMIT, it.code); assertEquals(34, it.retryAfterSeconds) }
        assertEquals(AiErrorCode.INVALID_KEY, mapped(ProviderType.GEMINI, 400, """{"error":{"code":400,"message":"API key not valid. Please pass a valid API key.","status":"INVALID_ARGUMENT"}}""").code)
        assertEquals(AiErrorCode.QUOTA_EXCEEDED, mapped(ProviderType.ANTHROPIC, 400, """{"error":{"type":"invalid_request_error","message":"Your credit balance is too low to access the Anthropic API."}}""").code)
        assertEquals(AiErrorCode.SERVER, mapped(ProviderType.ANTHROPIC, 529, """{"error":{"type":"overloaded_error","message":"Overloaded"}}""").code)
        assertEquals(AiErrorCode.QUOTA_EXCEEDED, mapped(ProviderType.OPENROUTER, 402, """{"error":{"code":402,"message":"Insufficient credits"}}""").code)
        mapped(ProviderType.GROQ, 404, """{"error":{"message":"The model does not exist","code":"model_not_found"}}""").let {
            assertEquals(AiErrorCode.MODEL_UNAVAILABLE, it.code); assertTrue(it.message!!.contains("does not switch models automatically")) }
        assertEquals(AiErrorCode.IMAGE_TOO_LARGE, mapped(ProviderType.GROQ, 413, "{}").code)
        assertEquals(AiErrorCode.IMAGE_UNSUPPORTED, mapped(ProviderType.OPENROUTER, 400, """{"error":{"message":"No endpoints found that support image input"}}""").code)
        assertEquals(AiErrorCode.BAD_REQUEST, mapped(ProviderType.CUSTOM_OPENAI, 400, """{"error":{"message":"bad temperature"}}""").code)
        assertEquals(AiErrorCode.FORBIDDEN, mapped(ProviderType.GROQ, 403, "{}").code)
        assertEquals(AiErrorCode.SERVER, mapped(ProviderType.GROQ, 503, "oops").code)
    }

    @Test fun retryHintParsing() {
        assertEquals(1262, RetryHint.fromText("Please try again in 21m1.2s."))
        assertEquals(8, RetryHint.fromText("try again in 7.5s"))
        assertEquals(3600, RetryHint.fromText("retry in 1h"))
        assertNull(RetryHint.fromText("no hint"))
        assertEquals(34, RetryHint.fromDelayString("34s"))
    }

    @Test fun preflightRejectsMissingKeyModelAndInsecureEndpoint() {
        val t = FakeTransport(); val ad = GroqAdapter(t)
        assertEquals(AiErrorCode.NO_API_KEY, fails { ad.chat(acct(ProviderType.GROQ), " ", req("m")) }.code)
        assertEquals(AiErrorCode.MODEL_UNAVAILABLE, fails { ad.chat(acct(ProviderType.GROQ), "k", req("")) }.code)
        assertEquals(AiErrorCode.BAD_REQUEST, fails { ad.chat(acct(ProviderType.GROQ, endpoint = "http://evil.example.com/v1"), "k", req("m")) }.code)
        assertEquals(AiErrorCode.IMAGE_UNSUPPORTED, fails { ad.chat(acct(ProviderType.GROQ), "k", req("m", List(4) { img })) }.code)
        assertEquals(0, t.sent.size)
    }

    @Test fun emptyAnswerAndThinkingOnlyAnswerAreErrors() {
        val t = FakeTransport(); val ad = GroqAdapter(t)
        t.reply("groq.test", t.on("x", 200, """{"choices":[{"message":{"content":"<think>long</think>"},"finish_reason":"length"}]}"""))
        assertEquals(AiErrorCode.TRUNCATED, fails { ad.chat(acct(ProviderType.GROQ), "k", req("m")) }.code)
        val t2 = FakeTransport(); t2.reply("groq.test", t2.on("x", 200, "not json"))
        assertEquals(AiErrorCode.INVALID_RESPONSE, fails { GroqAdapter(t2).chat(acct(ProviderType.GROQ), "k", req("m")) }.code)
    }

    @Test fun connectionTestsPerProvider() = runBlocking {
        val t = FakeTransport()
        t.reply("gemini.test", t.on("x", 200, """{"models":[{"name":"models/gemini-2.5-flash","supportedGenerationMethods":["generateContent"]}]}"""))
        val g = GeminiAdapter(t).validateModel(acct(ProviderType.GEMINI), "k")
        assertEquals(ConnectionState.CONNECTED, g.result.state); assertEquals(ImageSupport.YES, g.imageSupport)
        assertEquals("https://gemini.test/v1/models?pageSize=1000", t.sent[0].url)
        t.reply("anthropic.test", err("""{"error":{"type":"authentication_error","message":"invalid x-api-key"}}""", 401))
        assertEquals(ConnectionState.INVALID_KEY, AnthropicAdapter(t).validateModel(acct(ProviderType.ANTHROPIC), "k").result.state)
        // custom server without /models: falls back to a tiny real chat request
        t.reply("custom.test", err("{}", 404), FakeTransport.openAiOk("OK"))
        val c = OpenAICompatibleAdapter(t).validateModel(acct(ProviderType.CUSTOM_OPENAI, "m"), "k")
        assertEquals(ConnectionState.CONNECTED, c.result.state); assertEquals("POST", t.sent.last().method)
        // network failure
        t.reply("groq.test", java.net.UnknownHostException("x"))
        assertEquals(ConnectionState.NETWORK_ERROR, GroqAdapter(t).validateModel(acct(ProviderType.GROQ), "k").result.state)
        assertEquals(ConnectionState.INVALID_KEY, GroqAdapter(t).validateModel(acct(ProviderType.GROQ), "").result.state)
    }

    @Test fun realHttpTransportAgainstLocalServer() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var auth: String? = null
        server.createContext("/") { ex ->
            auth = ex.requestHeaders.getFirst("Authorization"); ex.requestBody.readBytes()
            val b = """{"choices":[{"message":{"content":"pong"}}],"usage":{"prompt_tokens":3,"completion_tokens":1}}""".toByteArray()
            ex.responseHeaders.add("x-ratelimit-remaining-tokens", "4999"); ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) }
        }
        server.start()
        try {
            val a = acct(ProviderType.GROQ, endpoint = "http://127.0.0.1:${server.address.port}/openai/v1")
            val r = GroqAdapter(com.snapgpt.networking.UrlConnectionTransport()).chat(a, "gsk_realkey1234", req("m"))
            assertEquals("pong", r.text); assertEquals("Bearer gsk_realkey1234", auth)
            assertEquals("4999", r.rateLimit.remainingTokens); assertNull(r.rateLimit.limitTokens)
            assertEquals("Provider reports: 4999 tokens left (current window)", r.rateLimit.describe())
        } finally { server.stop(0) }
    }

    @Test fun endpointsAndCapabilities() {
        assertEquals("https://x.io/v1", Endpoints.normalize(" https://x.io/v1/chat/completions/ "))
        assertTrue(Endpoints.isAllowed("https://x.io/v1")); assertTrue(Endpoints.isAllowed("http://127.0.0.1:8080/v1")); assertTrue(Endpoints.isAllowed("http://localhost/v1"))
        assertFalse(Endpoints.isAllowed("http://example.com/v1")); assertFalse(Endpoints.isAllowed("ftp://x")); assertFalse(Endpoints.isAllowed("example.com"))
        assertNotNull(Endpoints.validate(ProviderType.CUSTOM_OPENAI, "")); assertNull(Endpoints.validate(ProviderType.GROQ, ""))
        assertNotNull(Endpoints.validate(ProviderType.CUSTOM_OPENAI, "http://example.com"))
        assertEquals(ImageSupport.YES, ModelCapabilities.byModelName(ProviderType.GROQ, "qwen/qwen3.8-27b"))
        assertEquals(ImageSupport.UNKNOWN, ModelCapabilities.byModelName(ProviderType.GROQ, "llama-3.3-70b-versatile"))
        assertEquals(ImageSupport.NO, ModelCapabilities.byModelName(ProviderType.GROQ, "whisper-large-v3"))
        assertEquals(ImageSupport.YES, ModelCapabilities.byModelName(ProviderType.GEMINI, "gemini-2.5-flash"))
        assertEquals(ImageSupport.NO, ModelCapabilities.byModelName(ProviderType.ANTHROPIC, "claude-2.1"))
        assertEquals(ImageSupport.UNKNOWN, ModelCapabilities.byModelName(ProviderType.CUSTOM_OPENAI, "gpt-4o"))
        assertEquals(ImageSupport.UNKNOWN, ModelCapabilities.byModelName(ProviderType.OPENROUTER, ""))
    }
}
