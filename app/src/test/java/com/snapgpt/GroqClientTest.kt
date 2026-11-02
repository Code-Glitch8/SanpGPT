package com.snapgpt

import com.snapgpt.ai.AiErrorCode
import com.snapgpt.ai.AiException
import com.snapgpt.ai.ChatMessage
import com.snapgpt.ai.ChatRequest
import com.snapgpt.ai.ConnectionState
import com.snapgpt.ai.GroqClient
import com.snapgpt.ai.GroqRequestBuilder
import com.snapgpt.ai.HttpResponse
import com.snapgpt.ai.HttpTransport
import com.snapgpt.ai.RateLimitGate
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.URI
import java.net.UnknownHostException

/**
 * Tests GroqClient through its injectable HTTP transport.
 * This avoids com.sun.net.httpserver, which is a JDK-only module and is not
 * part of Android's unit-test compile classpath.
 */
class GroqClientTest {
    private class FakeTransport : HttpTransport {
        var status: Int = 200
        var responseBody: String = ""
        var responseHeaders: Map<String, String> = emptyMap()
        var failure: IOException? = null

        var lastMethod: String? = null
        var lastUrl: String? = null
        var lastAuth: String? = null
        var lastBody: String? = null
        var hits: Int = 0
            private set

        override suspend fun execute(
            method: String,
            url: String,
            headers: Map<String, String>,
            body: String?,
            connectTimeoutMs: Int,
            readTimeoutMs: Int,
        ): HttpResponse {
            hits++
            lastMethod = method
            lastUrl = url
            lastAuth = headers["Authorization"]
            lastBody = body.orEmpty()
            failure?.let { throw it }
            return HttpResponse(
                status = status,
                headers = responseHeaders.mapKeys { it.key.lowercase() },
                body = responseBody,
            )
        }
    }

    private lateinit var transport: FakeTransport
    private val base = "https://mock-groq.invalid/openai/v1"

    @Before
    fun setUp() {
        transport = FakeTransport()
    }

    private fun client() = GroqClient(transport = transport, baseUrl = base)

    private fun request(images: List<String> = emptyList(), json: Boolean = true) = ChatRequest(
        model = "qwen/qwen3.8-27b",
        messages = listOf(ChatMessage("system", "sys"), ChatMessage("user", "hello", images)),
        temperature = 0.5,
        maxCompletionTokens = 100,
        jsonMode = json,
    )

    private val okBody = """{"choices":[{"message":{"content":"{\"questions\":[]}"},"finish_reason":"stop"}],
        "usage":{"prompt_tokens":2100,"completion_tokens":40,"total_tokens":2140}}"""

    @Test
    fun sendsAuthorizedImageRequestAndParsesUsage() = runBlocking {
        transport.responseBody = okBody
        transport.responseHeaders = mapOf(
            "x-ratelimit-remaining-tokens" to "5000",
            "x-ratelimit-limit-requests" to "30",
        )
        val client = client()
        val result = client.chat("gsk_testkey1234", request(listOf("data:image/jpeg;base64,AAAA")))

        assertEquals("Bearer gsk_testkey1234", transport.lastAuth)
        assertEquals("POST", transport.lastMethod)
        assertEquals("/openai/v1/chat/completions", URI(transport.lastUrl!!).path)
        assertEquals("{\"questions\":[]}", result.text)
        assertEquals(2100, result.usage!!.promptTokens)
        assertEquals(40, result.usage!!.completionTokens)
        assertEquals(2140, result.usage!!.totalTokens)
        assertEquals("5000", client.lastRateLimit.remainingTokens)
        assertEquals("30", client.lastRateLimit.limitRequests)
        assertNull(client.lastRateLimit.retryAfter) // not sent => not invented

        val sent = Json.parseToJsonElement(transport.lastBody!!).jsonObject
        assertEquals("qwen/qwen3.8-27b", sent["model"]!!.jsonPrimitive.content)
        assertEquals("json_object", sent["response_format"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("hidden", sent["reasoning_format"]!!.jsonPrimitive.content)
        assertEquals(100, sent["max_completion_tokens"]!!.jsonPrimitive.content.toInt())
        val userContent = sent["messages"]!!.jsonArray[1].jsonObject["content"]!!.jsonArray
        assertEquals("text", userContent[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("image_url", userContent[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(
            "data:image/jpeg;base64,AAAA",
            userContent[1].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun textOnlyMessageUsesPlainStringContentAndNoJsonModeWhenDisabled() {
        val body = Json.parseToJsonElement(GroqRequestBuilder.build(request(json = false))).jsonObject
        assertEquals("hello", body["messages"]!!.jsonArray[1].jsonObject["content"]!!.jsonPrimitive.content)
        assertFalse(body.containsKey("response_format"))
    }

    @Test
    fun invalidKeyIsMappedAndNeverLeaksKey() = runBlocking {
        transport.status = 401
        transport.responseBody = """{"error":{"message":"Invalid API Key gsk_testkey1234","type":"invalid_request_error"}}"""
        try {
            client().chat("gsk_testkey1234", request())
            fail("Expected invalid-key error")
        } catch (e: AiException) {
            assertEquals(AiErrorCode.INVALID_KEY, e.code)
            assertFalse(e.message!!.contains("gsk_testkey1234"))
        }
    }

    @Test
    fun rateLimitHonoursRetryAfterAndBlocksFollowUpRequestsWithoutSendingAnotherRequest() = runBlocking {
        transport.status = 429
        transport.responseHeaders = mapOf("retry-after" to "7")
        transport.responseBody = """{"error":{"message":"Rate limit reached for model on tokens per minute (TPM)","code":"rate_limit_exceeded"}}"""
        val client = client()
        try {
            client.chat("gsk_x1234567", request())
            fail("Expected token-limit error")
        } catch (e: AiException) {
            assertEquals(AiErrorCode.TOKEN_LIMIT, e.code)
            assertEquals(7, e.retryAfterSeconds)
        }
        val before = transport.hits
        try {
            client.chat("gsk_x1234567", request())
            fail("Expected cooldown error")
        } catch (e: AiException) {
            assertEquals(AiErrorCode.COOLDOWN, e.code)
        }
        assertEquals(before, transport.hits) // no second HTTP call during cool-down
    }

    @Test
    fun modelUnavailableAndServerErrors() = runBlocking {
        val client = client()
        transport.status = 404
        transport.responseBody = """{"error":{"message":"The model `qwen/qwen3.8-27b` does not exist","code":"model_not_found"}}"""
        try {
            client.chat("gsk_k1234567", request())
            fail("Expected model-unavailable error")
        } catch (e: AiException) {
            assertEquals(AiErrorCode.MODEL_UNAVAILABLE, e.code)
            assertTrue(e.message!!.contains("does not switch models automatically"))
        }
        transport.status = 503
        transport.responseBody = "oops"
        try {
            client.chat("gsk_k1234567", request())
            fail("Expected server error")
        } catch (e: AiException) {
            assertEquals(AiErrorCode.SERVER, e.code)
        }
        transport.status = 413
        try {
            client.chat("gsk_k1234567", request())
            fail("Expected image-too-large error")
        } catch (e: AiException) {
            assertEquals(AiErrorCode.IMAGE_TOO_LARGE, e.code)
        }
    }

    @Test
    fun emptyOrTruncatedReplyIsAnError() = runBlocking {
        val client = client()
        transport.responseBody = """{"choices":[{"message":{"content":"<think>long reasoning</think>"},"finish_reason":"length"}]}"""
        try {
            client.chat("gsk_k1234567", request())
            fail("Expected truncated-response error")
        } catch (e: AiException) {
            assertEquals(AiErrorCode.TRUNCATED, e.code)
        }
    }

    @Test
    fun missingKeyAndTooManyImagesAreRejectedBeforeAnyNetworkCall() = runBlocking {
        val client = client()
        try {
            client.chat("  ", request())
            fail("Expected missing-key error")
        } catch (e: AiException) {
            assertEquals(AiErrorCode.NO_API_KEY, e.code)
        }
        val images = List(4) { "data:image/jpeg;base64,AA" }
        try {
            client.chat("gsk_k1234567", request(images))
            fail("Expected too-many-images error")
        } catch (e: AiException) {
            assertEquals(AiErrorCode.IMAGE_UNSUPPORTED, e.code)
        }
        assertEquals(0, transport.hits)
    }

    @Test
    fun connectionTestReportsConnectedMissingModelAndBadKey() = runBlocking {
        val client = client()
        transport.responseBody = """{"data":[{"id":"qwen/qwen3.8-27b"},{"id":"other"}]}"""
        assertEquals(ConnectionState.CONNECTED, client.testConnection("gsk_k1234567", "qwen/qwen3.8-27b").state)
        assertEquals("GET", transport.lastMethod)
        assertEquals("/openai/v1/models", URI(transport.lastUrl!!).path)
        assertEquals(ConnectionState.MODEL_MISSING, client.testConnection("gsk_k1234567", "nope/model").state)
        transport.status = 401
        transport.responseBody = """{"error":{"message":"bad"}}"""
        assertEquals(ConnectionState.INVALID_KEY, client.testConnection("gsk_k1234567", "qwen/qwen3.8-27b").state)
    }

    @Test
    fun unreachableTransportGivesNetworkError() = runBlocking {
        transport.failure = UnknownHostException("mock host is unavailable")
        val client = client()
        try {
            client.chat("gsk_k1234567", request())
            fail("Expected network error")
        } catch (e: AiException) {
            assertTrue(e.code == AiErrorCode.NETWORK || e.code == AiErrorCode.TIMEOUT)
        }
        assertEquals(ConnectionState.NETWORK_ERROR, client.testConnection("gsk_k1234567", "m").state)
        assertEquals(2, transport.hits)
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
