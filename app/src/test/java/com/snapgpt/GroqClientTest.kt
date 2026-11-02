package com.snapgpt

import com.snapgpt.ai.AiErrorCode
import com.snapgpt.ai.AiException
import com.snapgpt.ai.ChatMessage
import com.snapgpt.ai.ChatRequest
import com.snapgpt.ai.ConnectionState
import com.snapgpt.ai.GroqClient
import com.snapgpt.ai.GroqRequestBuilder
import com.snapgpt.ai.RateLimitGate
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress

/** Runs the real HTTP client against a local mock of Groq's OpenAI-compatible API (no network, no API key). */
class GroqClientTest {
    private lateinit var server: HttpServer
    private lateinit var base: String
    private var status = 200
    private var responseBody = ""
    private var responseHeaders = mapOf<String, String>()
    private var lastAuth: String? = null
    private var lastBody: String = ""
    private var lastPath = ""
    private var hits = 0

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            hits++
            lastAuth = ex.requestHeaders.getFirst("Authorization")
            lastPath = ex.requestURI.path
            lastBody = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            val bytes = responseBody.toByteArray()
            responseHeaders.forEach { (k, v) -> ex.responseHeaders.add(k, v) }
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        base = "http://127.0.0.1:${server.address.port}/openai/v1"
    }

    @After
    fun stop() = server.stop(0)

    private fun request(images: List<String> = emptyList(), json: Boolean = true) = ChatRequest(
        model = "qwen/qwen3.8-27b",
        messages = listOf(ChatMessage("system", "sys"), ChatMessage("user", "hello", images)),
        temperature = 0.5, maxCompletionTokens = 100, jsonMode = json,
    )

    private val okBody = """{"choices":[{"message":{"content":"{\"questions\":[]}"},"finish_reason":"stop"}],
        "usage":{"prompt_tokens":2100,"completion_tokens":40,"total_tokens":2140}}"""

    @Test
    fun sendsAuthorizedImageRequestAndParsesUsage() = runBlocking {
        responseBody = okBody
        responseHeaders = mapOf("x-ratelimit-remaining-tokens" to "5000", "x-ratelimit-limit-requests" to "30")
        val client = GroqClient(baseUrl = base)
        val res = client.chat("gsk_testkey1234", request(listOf("data:image/jpeg;base64,AAAA")))

        assertEquals("Bearer gsk_testkey1234", lastAuth)
        assertEquals("/openai/v1/chat/completions", lastPath)
        assertEquals("{\"questions\":[]}", res.text)
        assertEquals(2100, res.usage!!.promptTokens)
        assertEquals(40, res.usage!!.completionTokens)
        assertEquals(2140, res.usage!!.totalTokens)
        assertEquals("5000", client.lastRateLimit.remainingTokens)
        assertEquals("30", client.lastRateLimit.limitRequests)
        assertNull(client.lastRateLimit.retryAfter) // not sent => not invented

        val sent = Json.parseToJsonElement(lastBody).jsonObject
        assertEquals("qwen/qwen3.8-27b", sent["model"]!!.jsonPrimitive.content)
        assertEquals("json_object", sent["response_format"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("hidden", sent["reasoning_format"]!!.jsonPrimitive.content)
        assertEquals(100, sent["max_completion_tokens"]!!.jsonPrimitive.content.toInt())
        val userContent = sent["messages"]!!.jsonArray[1].jsonObject["content"]!!.jsonArray
        assertEquals("text", userContent[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("image_url", userContent[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("data:image/jpeg;base64,AAAA", userContent[1].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content)
    }

    @Test
    fun textOnlyMessageUsesPlainStringContentAndNoJsonModeWhenDisabled() {
        val body = Json.parseToJsonElement(GroqRequestBuilder.build(request(json = false))).jsonObject
        assertEquals("hello", body["messages"]!!.jsonArray[1].jsonObject["content"]!!.jsonPrimitive.content)
        assertFalse(body.containsKey("response_format"))
    }

    @Test
    fun invalidKeyIsMappedAndNeverLeaksKey() = runBlocking {
        status = 401
        responseBody = """{"error":{"message":"Invalid API Key gsk_testkey1234","type":"invalid_request_error"}}"""
        try {
            GroqClient(baseUrl = base).chat("gsk_testkey1234", request())
            fail()
        } catch (e: AiException) {
            assertEquals(AiErrorCode.INVALID_KEY, e.code)
            assertFalse(e.message!!.contains("gsk_testkey1234"))
        }
    }

    @Test
    fun rateLimitHonoursRetryAfterAndBlocksFollowUpRequestsWithoutHittingTheServer() = runBlocking {
        status = 429
        responseHeaders = mapOf("retry-after" to "7")
        responseBody = """{"error":{"message":"Rate limit reached for model on tokens per minute (TPM)","code":"rate_limit_exceeded"}}"""
        val client = GroqClient(baseUrl = base)
        try { client.chat("gsk_x1234567", request()); fail() } catch (e: AiException) {
            assertEquals(AiErrorCode.TOKEN_LIMIT, e.code)
            assertEquals(7, e.retryAfterSeconds)
        }
        val before = hits
        try { client.chat("gsk_x1234567", request()); fail() } catch (e: AiException) {
            assertEquals(AiErrorCode.COOLDOWN, e.code)
        }
        assertEquals(before, hits) // no second HTTP call during the cool-down
    }

    @Test
    fun modelUnavailableAndServerErrors() = runBlocking {
        val client = GroqClient(baseUrl = base)
        status = 404
        responseBody = """{"error":{"message":"The model `qwen/qwen3.8-27b` does not exist","code":"model_not_found"}}"""
        try { client.chat("gsk_k1234567", request()); fail() } catch (e: AiException) {
            assertEquals(AiErrorCode.MODEL_UNAVAILABLE, e.code)
            assertTrue(e.message!!.contains("does not switch models automatically"))
        }
        status = 503
        responseBody = "oops"
        try { client.chat("gsk_k1234567", request()); fail() } catch (e: AiException) {
            assertEquals(AiErrorCode.SERVER, e.code)
        }
        status = 413
        try { client.chat("gsk_k1234567", request()); fail() } catch (e: AiException) {
            assertEquals(AiErrorCode.IMAGE_TOO_LARGE, e.code)
        }
    }

    @Test
    fun emptyOrTruncatedReplyIsAnError() = runBlocking {
        val client = GroqClient(baseUrl = base)
        responseBody = """{"choices":[{"message":{"content":"<think>long reasoning</think>"},"finish_reason":"length"}]}"""
        try { client.chat("gsk_k1234567", request()); fail() } catch (e: AiException) {
            assertEquals(AiErrorCode.TRUNCATED, e.code)
        }
    }

    @Test
    fun missingKeyAndTooManyImagesAreRejectedBeforeAnyNetworkCall() = runBlocking {
        val client = GroqClient(baseUrl = base)
        try { client.chat("  ", request()); fail() } catch (e: AiException) { assertEquals(AiErrorCode.NO_API_KEY, e.code) }
        val imgs = List(4) { "data:image/jpeg;base64,AA" }
        try { client.chat("gsk_k1234567", request(imgs)); fail() } catch (e: AiException) { assertEquals(AiErrorCode.IMAGE_UNSUPPORTED, e.code) }
        assertEquals(0, hits)
    }

    @Test
    fun connectionTestReportsConnectedMissingModelAndBadKey() = runBlocking {
        val client = GroqClient(baseUrl = base)
        responseBody = """{"data":[{"id":"qwen/qwen3.8-27b"},{"id":"other"}]}"""
        assertEquals(ConnectionState.CONNECTED, client.testConnection("gsk_k1234567", "qwen/qwen3.8-27b").state)
        assertEquals("/openai/v1/models", lastPath)
        assertEquals(ConnectionState.MODEL_MISSING, client.testConnection("gsk_k1234567", "nope/model").state)
        status = 401
        responseBody = """{"error":{"message":"bad"}}"""
        assertEquals(ConnectionState.INVALID_KEY, client.testConnection("gsk_k1234567", "qwen/qwen3.8-27b").state)
    }

    @Test
    fun unreachableServerGivesNetworkError() = runBlocking {
        server.stop(0)
        val client = GroqClient(baseUrl = base)
        try { client.chat("gsk_k1234567", request()); fail() } catch (e: AiException) {
            assertTrue(e.code == AiErrorCode.NETWORK || e.code == AiErrorCode.TIMEOUT)
        }
        assertEquals(ConnectionState.NETWORK_ERROR, client.testConnection("gsk_k1234567", "m").state)
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0) // so @After has something to stop
    }

    @Test
    fun rateLimitGateExpires() {
        var now = 1_000L
        val gate = RateLimitGate { now }
        gate.block(5)
        assertEquals(5, gate.remainingSeconds())
        now += 5_001
        assertEquals(0, gate.remainingSeconds())
        gate.check() // does not throw once expired
    }
}
