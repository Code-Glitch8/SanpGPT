package com.snapgpt

import com.snapgpt.ai.AiErrorCode
import com.snapgpt.ai.AiException
import com.snapgpt.ai.failover.AccountTester
import com.snapgpt.ai.failover.FailoverConfig
import com.snapgpt.ai.providers.ConnectionStatus
import com.snapgpt.ai.providers.ProviderType.ANTHROPIC
import com.snapgpt.ai.providers.ProviderType.CUSTOM_OPENAI
import com.snapgpt.ai.providers.ProviderType.GEMINI
import com.snapgpt.ai.providers.ProviderType.GROQ
import com.snapgpt.ai.providers.ProviderType.OPENROUTER
import com.snapgpt.ai.providers.RequestKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FailoverManagerTest {

    private val dailyQuota = err("""{"error":{"message":"Rate limit reached for model x on tokens per day (TPD): Limit 100000, Used 99000. Please try again in 21m1.2s.","code":"rate_limit_exceeded"}}""", 429, mapOf("retry-after" to "1262"))
    private val tpm = err("""{"error":{"message":"Rate limit reached on tokens per minute (TPM): Limit 6000. Please try again in 7.5s."}}""", 429, mapOf("retry-after" to "8"))
    private fun failing(block: suspend () -> Unit): AiException = try { runBlocking { block() }; throw AssertionError("expected AiException") } catch (e: AiException) { e }

    @Test
    fun highestPriorityIsTriedFirstAndSuccessIsRecorded() = runBlocking {
        val r = Rig()
        r.store.add("low", GROQ, priority = 5); r.store.add("high", GROQ, priority = 1); r.store.add("mid", GEMINI, priority = 3)
        r.scriptOk("high", "from high")
        val log = ArrayList<String>()
        val out = r.manager.route(r.spec(), { log += it }) { it.text }
        assertEquals("from high", out.value)
        assertEquals("high", out.account.id)
        assertEquals(listOf("Connecting to high...", "Answer generated using high."), log)
        val a = r.store["high"]
        assertEquals(1, a.stats.successes); assertEquals(1, a.stats.explanationRequests)
        assertEquals(100L, a.stats.actualInputTokens); assertEquals(20L, a.stats.actualOutputTokens)
        assertEquals(r.now, a.lastSuccessAt); assertEquals(ConnectionStatus.CONNECTED, a.status)
        assertEquals(0, r.http.hitsOn(r.host("low"))); assertEquals(0, r.http.hitsOn(r.host("mid")))
    }

    @Test
    fun quotaExhaustionSwitchesProviderAndCoolsTheAccountDown() = runBlocking {
        val r = Rig()
        r.store.add("groq1", GROQ, 0, name = "Groq Primary"); r.store.add("gem1", GEMINI, 1, name = "Gemini Primary")
        r.script("groq1", dailyQuota); r.scriptOk("gem1", "gemini answer")
        val log = ArrayList<String>()
        val out = r.manager.route(r.spec(needsImage = true), { log += it }) { it.text }
        assertEquals("gemini answer", out.value)
        assertEquals(listOf(
            "Sending screenshot to Groq Primary (Groq)...", "Groq Primary quota reached.",
            "Switching to Gemini Primary... sending the screenshot there.", "Answer generated using Gemini Primary.",
        ), log)
        val g = r.store["groq1"]
        assertEquals(1, g.stats.quotaErrors); assertEquals(1, g.stats.failures)
        assertEquals(r.now + 1262_000L, g.cooldownUntil)
        assertEquals("Quota reached", g.cooldownReason)
        assertTrue(g.lastError!!.contains("quota"))
        assertEquals(1, out.attempts.size); assertEquals(AiErrorCode.QUOTA_EXCEEDED, out.attempts[0].code)
    }

    @Test
    fun cooldownIsRespectedWithoutAnyHttpCallAndExpires() = runBlocking {
        val r = Rig()
        r.store.add("a", GROQ, 0); r.store.add("b", GEMINI, 1)
        r.script("a", tpm, r.ok(r.store["a"], "a again")); r.scriptOk("b", "b")
        r.manager.route(r.spec()) { it.text } // a: 429 -> cooldown 8 s, b answers
        assertEquals(r.now + 8_000, r.store["a"].cooldownUntil)
        val hitsA = r.http.hitsOn(r.host("a"))
        assertEquals("b", r.manager.route(r.spec()) { it.text }.value)
        assertEquals(hitsA, r.http.hitsOn(r.host("a"))) // skipped while cooling down
        r.now += 8_001
        assertEquals("a again", r.manager.route(r.spec()) { it.text }.value)
        assertEquals(0L, r.store["a"].cooldownUntil) // a successful request clears it
    }

    @Test
    fun invalidKeySwitchesAndMarksTheAccount() = runBlocking {
        val r = Rig()
        r.store.add("bad", ANTHROPIC, 0); r.store.add("good", GROQ, 1)
        r.script("bad", err("""{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}""", 401))
        r.scriptOk("good")
        val out = r.manager.route(r.spec()) { it.text }
        assertEquals("good", out.account.id)
        assertEquals(ConnectionStatus.INVALID_KEY, r.store["bad"].status)
        assertEquals(r.now + 6 * 3600_000L, r.store["bad"].cooldownUntil)
    }

    @Test
    fun unavailableModelSwitchesAndNeverSubstitutesAModel() = runBlocking {
        val r = Rig()
        r.store.add("g", GROQ, 0, model = "qwen/qwen3.8-27b"); r.store.add("m", GEMINI, 1, model = "gemini-2.5-flash")
        r.script("g", err("""{"error":{"message":"The model `qwen/qwen3.8-27b` does not exist","code":"model_not_found"}}""", 404))
        r.scriptOk("m")
        val out = r.manager.route(r.spec()) { it.text }
        assertEquals("m", out.account.id)
        // each account was called with exactly its own configured model
        assertTrue(r.http.sent.first { it.host == r.host("g") }.body!!.contains("\"qwen/qwen3.8-27b\""))
        assertTrue(r.http.sent.first { it.host == r.host("m") }.url.contains("models/gemini-2.5-flash:generateContent"))
        assertEquals(ConnectionStatus.MODEL_MISSING, r.store["g"].status)
    }

    @Test
    fun sameProviderAccountsAreTriedBeforeOtherProviders() = runBlocking {
        val r = Rig()
        r.store.add("g1", GROQ, 0); r.store.add("gem", GEMINI, 1); r.store.add("g2", GROQ, 2)
        r.script("g1", dailyQuota); r.scriptOk("g2", "second groq")
        val out = r.manager.route(r.spec()) { it.text }
        assertEquals("g2", out.account.id)
        assertEquals(0, r.http.hitsOn(r.host("gem")))
    }

    @Test
    fun multipleKeysOfOneProviderEachUseTheirOwnKey() = runBlocking {
        val r = Rig()
        r.store.add("k1", GROQ, 0, key = "gsk_FIRSTKEY123456"); r.store.add("k2", GROQ, 1, key = "gsk_SECONDKEY654321"); r.store.add("k3", GROQ, 2, key = "gsk_THIRDKEY000111")
        r.script("k1", dailyQuota); r.script("k2", dailyQuota); r.scriptOk("k3")
        val out = r.manager.route(r.spec()) { it.text }
        assertEquals("k3", out.account.id)
        assertEquals("Bearer gsk_FIRSTKEY123456", r.http.sent[0].headers["Authorization"])
        assertEquals("Bearer gsk_SECONDKEY654321", r.http.sent[1].headers["Authorization"])
        assertEquals("Bearer gsk_THIRDKEY000111", r.http.sent[2].headers["Authorization"])
        assertTrue(r.http.sent.none { it.body!!.contains("gsk_") }) // keys never travel in request bodies
    }

    @Test
    fun serverErrorsRetryWithExponentialBackoffThenSwitch() = runBlocking {
        val r = Rig()
        r.store.add("a", GROQ, 0); r.store.add("b", OPENROUTER, 1, model = "google/gemini-2.5-flash")
        r.script("a", err(status = 503)); r.scriptOk("b")
        val log = ArrayList<String>()
        val out = r.manager.route(r.spec(), { log += it }) { it.text }
        assertEquals("b", out.account.id)
        assertEquals(listOf(1000L, 2000L), r.sleeps)
        assertEquals(3, r.http.hitsOn(r.host("a"))) // 1 try + 2 retries, then no more
        assertEquals(r.now + 60_000, r.store["a"].cooldownUntil)
        assertTrue(log.any { it.contains("Retrying in 1 s") })
    }

    @Test
    fun retryAfterOnTemporaryErrorIsRespected() = runBlocking {
        val r = Rig()
        r.store.add("a", GROQ, 0)
        r.script("a", err(status = 503, headers = mapOf("retry-after" to "5")), r.ok(r.store["a"], "fine"))
        assertEquals("fine", r.manager.route(r.spec()) { it.text }.value)
        assertEquals(listOf(5000L), r.sleeps)
    }

    @Test
    fun networkFailuresAndTimeoutsAreRecoverable() = runBlocking {
        val r = Rig()
        r.store.add("a", GROQ, 0); r.store.add("b", GEMINI, 1)
        r.script("a", FakeTransport.timeout(), java.net.UnknownHostException("x"))
        r.scriptOk("b")
        val out = r.manager.route(r.spec(cfg = FailoverConfig(maxRetriesPerAccount = 1))) { it.text }
        assertEquals("b", out.account.id)
        assertEquals(2, r.http.hitsOn(r.host("a")))
        assertEquals(1, r.sleeps.size)
        assertEquals(ConnectionStatus.NETWORK_ERROR, r.store["a"].status)
    }

    @Test
    fun ordinaryBadRequestsDoNotSwitchAccounts() {
        val r = Rig()
        r.store.add("a", GROQ, 0); r.store.add("b", GEMINI, 1)
        r.script("a", err("""{"error":{"message":"temperature must be <= 2"}}""", 400)); r.scriptOk("b")
        val e = failing { r.manager.route(r.spec()) { it.text } }
        assertEquals(AiErrorCode.BAD_REQUEST, e.code)
        assertEquals(0, r.http.hitsOn(r.host("b")))
        assertEquals(0L, r.store["a"].cooldownUntil)
    }

    @Test
    fun disabledAutomaticFailoverUsesOnlyThePrimaryAccount() {
        val r = Rig()
        r.store.add("a", GROQ, 0); r.store.add("b", GEMINI, 1)
        r.script("a", dailyQuota); r.scriptOk("b")
        val e = failing { r.manager.route(r.spec(cfg = FailoverConfig(enabled = false))) { it.text } }
        assertEquals(AiErrorCode.QUOTA_EXCEEDED, e.code)
        assertEquals(0, r.http.hitsOn(r.host("b")))
        // a disabled primary makes the next enabled account the primary
        r.store.list["a"] = r.store["a"].copy(enabled = false, cooldownUntil = 0)
        assertEquals("b", runBlocking { r.manager.route(r.spec(cfg = FailoverConfig(enabled = false))) { it.text } }.account.id)
    }

    @Test
    fun screenshotsOnlyGoToModelsThatAcceptImages() = runBlocking {
        val r = Rig()
        r.store.add("text", GROQ, 0, model = "llama-3.3-70b-versatile")      // image support unknown -> not used for screenshots
        r.store.add("custom", CUSTOM_OPENAI, 1, model = "my-model")          // unknown, unconfirmed
        r.store.add("vision", GEMINI, 2)
        r.scriptOk("text", "text only"); r.scriptOk("custom"); r.scriptOk("vision", "saw it")
        assertEquals("vision", r.manager.route(r.spec(needsImage = true)) { it.text }.account.id)
        assertEquals(0, r.http.hitsOn(r.host("text")))
        assertEquals("text", r.manager.route(r.spec(needsImage = false)) { it.text }.account.id)
        // once the user confirms the custom model takes images it becomes eligible (priority 1 < 2)
        r.store.list["custom"] = r.store["custom"].copy(imageSupportOverride = true)
        assertEquals("custom", r.manager.route(r.spec(needsImage = true)) { it.text }.account.id)
        // an explicit "no" removes even a model that looks like a vision model
        r.store.list["vision"] = r.store["vision"].copy(imageSupportOverride = false)
        r.store.list["custom"] = r.store["custom"].copy(enabled = false)
        val e = try { r.manager.route(r.spec(needsImage = true)) { it.text }; null } catch (e: AiException) { e }
        assertEquals(AiErrorCode.NO_ACCOUNTS, e!!.code)
        assertTrue(e.message!!.contains("does not accept images") || e.message!!.contains("not confirmed"))
    }

    @Test
    fun providerSideImageRejectionSwitchesButLocalLimitsDoNot() = runBlocking {
        val r = Rig()
        r.store.add("a", GROQ, 0); r.store.add("b", GEMINI, 1)
        r.script("a", err("""{"error":{"message":"This model does not support image input"}}""", 400)); r.scriptOk("b")
        assertEquals("b", r.manager.route(r.spec(needsImage = true)) { it.text }.account.id)
        assertEquals("Model does not accept images", r.store["a"].cooldownReason)
    }

    @Test
    fun unusableAnswerMovesOnWithoutACooldownAndKeepsTheTokenCount() = runBlocking {
        val r = Rig()
        r.store.add("a", GROQ, 0); r.store.add("b", GEMINI, 1)
        r.script("a", FakeTransport.openAiOk("not json at all")); r.scriptOk("b", "{\"ok\":1}")
        val out = r.manager.route(r.spec()) { if (!it.text.startsWith("{")) throw AiException(AiErrorCode.INVALID_RESPONSE, "no json") else it.text }
        assertEquals("b", out.account.id)
        assertEquals(0L, r.store["a"].cooldownUntil)
        assertEquals(1, r.store["a"].stats.failures)
        assertEquals(100L, r.store["a"].stats.actualInputTokens) // the provider did process (and bill) it
    }

    @Test
    fun singleAccountShortRateLimitWaitsOnceAsTheProviderAsked() = runBlocking {
        val r = Rig()
        r.store.add("only", GROQ, 0)
        r.script("only", err("""{"error":{"message":"Rate limit on requests per minute (RPM)"}}""", 429, mapOf("retry-after" to "3")), r.ok(r.store["only"], "after wait"))
        assertEquals("after wait", r.manager.route(r.spec()) { it.text }.value)
        assertEquals(listOf(3000L), r.sleeps)
    }

    @Test
    fun allAccountsFailingGivesAClearSummaryAndBoundedWork() {
        val r = Rig()
        r.store.add("a", GROQ, 0, name = "Groq A"); r.store.add("b", GEMINI, 1, name = "Gemini B"); r.store.add("c", ANTHROPIC, 2, name = "Claude C", key = null)
        r.script("a", dailyQuota); r.script("b", err("""{"error":{"message":"API key not valid. Please pass a valid API key.","status":"INVALID_ARGUMENT"}}""", 400))
        val e = failing { r.manager.route(r.spec()) { it.text } }
        assertEquals(AiErrorCode.ALL_ACCOUNTS_FAILED, e.code)
        assertTrue(e.message!!.contains("Groq A") && e.message!!.contains("Gemini B") && e.message!!.contains("Claude C: no API key saved"))
        assertEquals(1, r.http.hitsOn(r.host("a"))); assertEquals(1, r.http.hitsOn(r.host("b")))
        assertEquals(0, r.http.hitsOn(r.host("c")))
    }

    @Test
    fun noEnabledAccountAndAllCoolingDownAreReportedDistinctly() {
        val r = Rig()
        assertEquals(AiErrorCode.NO_ACCOUNTS, failing { r.manager.route(r.spec()) { it.text } }.code)
        r.store.add("a", GROQ, 0, enabled = false)
        assertEquals(AiErrorCode.NO_ACCOUNTS, failing { r.manager.route(r.spec()) { it.text } }.code)
        r.store.add("b", GEMINI, 1)
        r.store.list["b"] = r.store["b"].copy(cooldownUntil = r.now + 600_000, cooldownReason = "Quota reached")
        val e = failing { r.manager.route(r.spec()) { it.text } }
        assertEquals(AiErrorCode.NO_ACCOUNTS, e.code)
        assertTrue(e.message!!.contains("cooling down for 10 min (Quota reached)"))
        assertEquals(0, r.http.sent.size)
    }

    @Test
    fun identicalInFlightRequestIsNeverSentTwice() = runBlocking {
        val r = Rig()
        r.store.add("a", GROQ, 0)
        val gate = CompletableDeferred<Unit>()
        val slow = object : com.snapgpt.networking.HttpTransport {
            override suspend fun execute(method: String, url: String, headers: Map<String, String>, body: String?, connectTimeoutMs: Int, readTimeoutMs: Int): com.snapgpt.networking.HttpResponse {
                r.http.sent.add(Sent(method, url, headers, body)); gate.await(); return FakeTransport.openAiOk("done")
            }
        }
        val manager = com.snapgpt.ai.failover.FailoverManager(r.store, com.snapgpt.ai.providers.Adapters.standard(slow), { r.now }, { })
        val first = async { manager.route(r.spec(dedupe = "scan:1")) { it.text } }
        kotlinx.coroutines.yield()
        val dup = try { manager.route(r.spec(dedupe = "scan:1")) { it.text }; null } catch (e: AiException) { e }
        assertEquals(AiErrorCode.DUPLICATE, dup!!.code)
        gate.complete(Unit)
        assertEquals("done", first.await().value)
        assertEquals(1, r.http.sent.size)
        // after it finished the same key can be used again
        assertEquals("done", manager.route(r.spec(dedupe = "scan:1")) { it.text }.value)
    }

    @Test
    fun cancellationPropagatesAndDoesNotPunishTheAccount() {
        val r = Rig()
        r.store.add("a", GROQ, 0); r.store.add("b", GEMINI, 1)
        val cancelling = object : com.snapgpt.networking.HttpTransport {
            override suspend fun execute(method: String, url: String, headers: Map<String, String>, body: String?, connectTimeoutMs: Int, readTimeoutMs: Int): com.snapgpt.networking.HttpResponse =
                throw kotlinx.coroutines.CancellationException("user cancelled")
        }
        val manager = com.snapgpt.ai.failover.FailoverManager(r.store, com.snapgpt.ai.providers.Adapters.standard(cancelling), { r.now }, { })
        try { runBlocking { manager.route(r.spec()) { it.text } }; fail() } catch (e: kotlinx.coroutines.CancellationException) { /* expected */ }
        assertEquals(0, r.store["a"].stats.requests); assertEquals(0L, r.store["a"].cooldownUntil)
    }

    @Test
    fun avoidedAccountsAreOnlyUsedWhenNothingElseIsLeft() = runBlocking {
        val r = Rig()
        r.store.add("first", GROQ, 0); r.store.add("other", GEMINI, 1)
        r.scriptOk("first", "same"); r.scriptOk("other", "different")
        assertEquals("other", r.manager.route(r.spec(avoid = setOf("first"))) { it.text }.account.id)
        r.store.list["other"] = r.store["other"].copy(enabled = false)
        assertEquals("first", r.manager.route(r.spec(avoid = setOf("first"))) { it.text }.account.id) // only account: separate request on the same one
    }

    @Test
    fun previewShowsWhereAScreenshotWouldGo() = runBlocking {
        val r = Rig()
        assertNull(r.manager.preview(true, FailoverConfig()))
        r.store.add("a", GROQ, 0, model = "llama-3.3-70b-versatile"); r.store.add("b", GEMINI, 1, name = "Gemini Primary")
        val p = r.manager.preview(true, FailoverConfig())!!
        assertEquals("b", p.id)
        assertEquals("Gemini Primary (Google Gemini, gemini-2.5-flash)", p.destinationLabel())
        assertEquals(0, r.http.sent.size) // previewing never touches the network
    }

    @Test
    fun thousandsOfAccountsAreSupportedAndEachRequestStaysBounded() = runBlocking {
        val r = Rig()
        val order = (0 until 1000).shuffled(java.util.Random(7))
        order.forEach { i -> r.store.add("acc$i", if (i % 2 == 0) GROQ else GEMINI, priority = i) }
        assertEquals(1000, r.store.accounts().size)
        (0 until 20).forEach { r.script("acc$it", dailyQuota) }
        r.scriptOk("acc20"); r.scriptOk("acc21", "21")
        // 8 accounts tried per request at most: acc0..acc7 fail, request fails (they are now cooling)
        val e = try { r.manager.route(r.spec()) { it.text }; null } catch (e: AiException) { e }
        assertEquals(AiErrorCode.ALL_ACCOUNTS_FAILED, e!!.code)
        assertEquals(8, r.http.sent.size)
        // each following request skips the accounts that are cooling down and works further down the list
        var requests = 1
        var out: com.snapgpt.ai.failover.RoutedResult<String>? = null
        while (out == null && requests < 6) {
            val before = r.http.sent.size
            requests++
            try { out = r.manager.route(r.spec()) { it.text } } catch (_: AiException) { }
            assertTrue(r.http.sent.size - before <= 8)
        }
        assertEquals("acc20", out!!.account.id)
        assertEquals(3, requests)            // acc0-7, acc8-15, then acc16, acc18 (same provider first) fail and acc20 answers
        assertEquals(19, r.http.sent.size)   // no account was ever called twice while cooling down
    }

    @Test
    fun accountTesterReportsAndClearsCooldownOnSuccess() = runBlocking {
        val r = Rig()
        r.store.add("a", GROQ, 0)
        r.store.list["a"] = r.store["a"].copy(cooldownUntil = r.now + 9999, cooldownReason = "Rate limit", status = ConnectionStatus.ERROR, lastError = "old")
        val tester = AccountTester(r.store, r.adapters, { r.now })
        r.script("a", FakeTransport().on("x", 200, """{"data":[{"id":"qwen/qwen3.8-27b"}]}"""))
        val res = tester.test(r.store["a"])
        assertEquals(com.snapgpt.ai.ConnectionState.CONNECTED, res.state)
        assertEquals(ConnectionStatus.CONNECTED, r.store["a"].status)
        assertEquals(0L, r.store["a"].cooldownUntil); assertNull(r.store["a"].lastError)
        // missing key
        r.store.add("nokey", GEMINI, 1, key = null)
        assertEquals(com.snapgpt.ai.ConnectionState.INVALID_KEY, tester.test(r.store["nokey"]).state)
        assertEquals(ConnectionStatus.INVALID_KEY, r.store["nokey"].status)
        // bad key
        r.store.add("bad", ANTHROPIC, 2)
        r.script("bad", err("""{"error":{"type":"authentication_error","message":"invalid x-api-key"}}""", 401))
        assertEquals(com.snapgpt.ai.ConnectionState.INVALID_KEY, tester.test(r.store["bad"]).state)
        assertNotNull(r.store["bad"].lastError)
        assertFalse(r.store["bad"].lastError!!.contains("key-bad"))
    }
}
