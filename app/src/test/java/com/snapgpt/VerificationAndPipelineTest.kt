package com.snapgpt

import com.snapgpt.ai.*
import com.snapgpt.ai.failover.FailoverConfig
import com.snapgpt.ai.providers.ProviderType.ANTHROPIC
import com.snapgpt.ai.providers.ProviderType.GEMINI
import com.snapgpt.ai.providers.ProviderType.GROQ
import com.snapgpt.ai.verification.AnswerComparer
import com.snapgpt.ai.verification.Agreement
import com.snapgpt.ai.verification.AnswerVerifier
import com.snapgpt.jee.JeeConfig
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class VerificationAndPipelineTest {
    private fun q(id: String = "q1", answer: String = "B", type: String = QuestionTypes.MCQ, fig: Boolean = false, clar: Boolean = false, conf: Float = 0.9f) =
        DetectedQuestion(id, "Which is right?", "Physics", "Kinematics", "t", "JEE Main", type, answer, "option", conf, null, clar,
            if (type == QuestionTypes.MCQ) listOf(ChoiceOption("A", "1"), ChoiceOption("B", "2")) else emptyList(), fig)

    private fun solveJson(id: String, ans: String, conf: Double = 0.9, ok: Boolean = true, solvable: Boolean = true) =
        """{"results":[{"id":"$id","solvable":$solvable,"answer":"$ans","confidence":$conf,"formula":"v=u+at","formula_ok":$ok,"units_ok":true,"signs_ok":true,"note":""}]}"""
    private fun judgeJson(id: String, verdict: String, final: String, conf: Double = 0.9) =
        """{"results":[{"id":"$id","verdict":"$verdict","final_answer":"$final","confidence":$conf,"reason":"checked signs"}]}"""

    private fun twoAccounts(): Rig = Rig().also { it.store.add("a", GROQ, 0, name = "Groq A"); it.store.add("b", GEMINI, 1, name = "Gemini B") }
    private fun verify(r: Rig, qs: List<DetectedQuestion>, preferDifferent: Boolean = true) = runBlocking {
        AnswerVerifier(r.manager).verify(qs, JeeConfig(), FailoverConfig(), setOf("a"), preferDifferent)
    }

    @Test fun agreementOnADifferentAccountIsVerified() {
        val r = twoAccounts(); r.script("b", r.ok(r.store["b"], solveJson("q1", "B")))
        val rep = verify(r, listOf(q()))
        assertEquals(VerificationStatus.VERIFIED, rep.questions[0].verification)
        assertTrue(rep.questions[0].verificationNote.contains("Gemini B"))
        assertEquals(0, r.http.hitsOn(r.host("a"))); assertEquals(1, r.store["b"].stats.verificationRequests)
        // the solver never sees the first answer
        val sent = r.http.sent[0].body!!
        assertFalse(sent.contains("Stage 1")); assertFalse(Regex("(?i)candidate|first answer").containsMatchIn(sent))
    }

    @Test fun disagreementResolvedInFavourOfTheCheckerCorrectsTheAnswer() {
        val r = twoAccounts(); r.store.add("c", ANTHROPIC, 2, name = "Claude C")
        r.script("b", r.ok(r.store["b"], solveJson("q1", "A")))
        r.script("c", r.ok(r.store["c"], judgeJson("q1", "second", "A")))
        val rep = verify(r, listOf(q()))
        val x = rep.questions[0]
        assertEquals(VerificationStatus.CORRECTED, x.verification); assertEquals("A", x.answer); assertEquals("B", x.originalAnswer)
        assertEquals(2, rep.requests); assertEquals(1, r.http.hitsOn(r.host("c"))) // the judge is a third account
        assertTrue(rep.usage!!.totalTokens > 0)
    }

    @Test fun judgeSupportingTheOriginalGivesLowConfidenceNotVerified() {
        val r = twoAccounts()
        r.script("b", r.ok(r.store["b"], solveJson("q1", "A")))
        r.script("a", r.ok(r.store["a"], judgeJson("q1", "first", "B"))) // judge avoids both used accounts only if a third exists
        val x = verify(r, listOf(q())).questions[0]
        assertEquals(VerificationStatus.LOW_CONFIDENCE, x.verification); assertEquals("B", x.answer)
    }

    @Test fun equivalentFormsAreVerifiedAndSolverDoubtsBlockVerification() {
        val r = twoAccounts()
        r.script("b", r.ok(r.store["b"], solveJson("q1", "2 m", 0.9, ok = false)))
        val doubt = verify(r, listOf(q("q1", "2 m", QuestionTypes.NUMERICAL))).questions[0]
        assertEquals(VerificationStatus.LOW_CONFIDENCE, doubt.verification) // answers agree, but the checker doubted the formula
        val r2 = twoAccounts()
        r2.script("b", r2.ok(r2.store["b"], solveJson("q1", "sqrt(2)")))
        r2.script("a", r2.ok(r2.store["a"], judgeJson("q1", "equivalent", "√2")))
        assertEquals(VerificationStatus.VERIFIED, verify(r2, listOf(q("q1", "√2", QuestionTypes.SUBJECTIVE))).questions[0].verification)
    }

    @Test fun figureDependentUnclearAndUnsolvableAreUnableToVerify() {
        val r = twoAccounts(); r.script("b", r.ok(r.store["b"], solveJson("q3", "B", solvable = false)))
        val rep = verify(r, listOf(q("q1", fig = true), q("q2", clar = true), q("q3")))
        assertTrue(rep.questions.all { it.verification == VerificationStatus.UNABLE })
        assertTrue(rep.questions.all { it.verification.isUncertain })
        assertEquals("B", rep.questions[2].answer) // answers are never thrown away
    }

    @Test fun verifierFailureKeepsAnswersAndFlagsUnableWithoutThrowing() {
        val r = twoAccounts(); r.script("a", err(status = 400)); r.script("b", err(status = 400))
        val rep = verify(r, listOf(q()))
        assertTrue(rep.failed); assertEquals(VerificationStatus.UNABLE, rep.questions[0].verification); assertEquals("B", rep.questions[0].answer)
    }

    @Test fun singleAccountFallsBackToASeparateRequestOnTheSameAccount() {
        val r = Rig(); r.store.add("a", GROQ, 0); r.script("a", r.ok(r.store["a"], solveJson("q1", "B")))
        assertEquals(VerificationStatus.VERIFIED, verify(r, listOf(q())).questions[0].verification)
        assertEquals(1, r.http.hitsOn(r.host("a")))
    }

    @Test fun comparerHandlesChoicesNumbersUnitsAndText() {
        val mc = q(); val num = q(type = QuestionTypes.NUMERICAL); val int = q(type = QuestionTypes.INTEGER)
        assertEquals(Agreement.SAME, AnswerComparer.compare("B", "Option (b)", mc))
        assertEquals(Agreement.SAME, AnswerComparer.compare("A, B", "B and A", q(type = QuestionTypes.MCQ_MULTIPLE)))
        assertEquals(Agreement.SAME, AnswerComparer.compare("B", "2", mc)) // option text instead of label
        assertEquals(Agreement.DIFFERENT, AnswerComparer.compare("A", "B", mc))
        assertEquals(Agreement.SAME, AnswerComparer.compare("9.8 m/s²", "9.81 m/s^2", num))
        assertEquals(Agreement.DIFFERENT, AnswerComparer.compare("9.8", "10.5", num))
        assertEquals(Agreement.SAME, AnswerComparer.compare("2×10⁻³ C", "0.002 C", num))
        assertEquals(Agreement.SAME, AnswerComparer.compare("1,000", "1000", num))
        assertEquals(Agreement.DIFFERENT, AnswerComparer.compare("100", "101", int)) // integers must match exactly
        assertEquals(Agreement.SAME, AnswerComparer.compare("3/4", "0.75", num))
        assertEquals(Agreement.UNITS_DIFFER, AnswerComparer.compare("5 m", "5 km", num))
        assertEquals(Agreement.SAME, AnswerComparer.compare("2x + 3", "2x+3", q(type = QuestionTypes.SUBJECTIVE)))
        assertEquals(Agreement.DIFFERENT, AnswerComparer.compare("2x + 3", "2x + 5", q(type = QuestionTypes.SUBJECTIVE)))
    }

    // ------------------------------------------------------------ pipeline

    private val stage1 = """{"questions":[{"id":"q1","question_text":"Which is right?","subject":"Physics","chapter":"Kinematics","topic":"t","exam_level":"JEE Main","question_type":"mcq","options":[{"label":"A","text":"1"},{"label":"B","text":"2"}],"answer":"B","answer_type":"option","confidence":0.9,"bounding_box":{"x":0.1,"y":0.2,"width":0.8,"height":0.2},"has_figure":false,"needs_clarification":false,"note":""}]}"""
    private val image = EncodedImage("data:image/jpeg;base64,AAAA", 1000, 2000, "hash1")

    @Test fun twoStageFlowWithVerificationUsesBothAccountsAndCaches() = runBlocking {
        val r = twoAccounts(); val p = AiPipeline(r.manager)
        r.script("a", r.ok(r.store["a"], stage1)); r.script("b", r.ok(r.store["b"], solveJson("q1", "B")))
        val log = ArrayList<String>()
        val out = p.analyze(image, ScanType.FULL_SCREEN, PipelineSettings()) { log += it }
        assertEquals("Groq A", out.accountName); assertTrue(out.verificationRan)
        assertEquals(VerificationStatus.VERIFIED, out.questions[0].verification)
        assertTrue(log.first().startsWith("Sending screenshot to Groq A")); assertTrue(log.contains("Answer generated using Groq A."))
        // stage 1 carried the image, verification did not
        assertTrue(r.http.sent[0].body!!.contains("image_url")); assertFalse(r.http.sent[1].body!!.contains("inline_data"))
        assertEquals(1, r.store["a"].stats.analysisRequests); assertEquals(1, r.store["b"].stats.verificationRequests)
        val n = r.http.sent.size
        val again = p.analyze(image, ScanType.FULL_SCREEN, PipelineSettings())
        assertTrue(again.fromCache); assertEquals(n, r.http.sent.size)
    }

    @Test fun stageOneFailsOverAcrossProvidersAndVerificationCanBeDisabled() = runBlocking {
        val r = twoAccounts(); val p = AiPipeline(r.manager)
        r.script("a", err("""{"error":{"message":"on requests per day (RPD)"}}""", 429)); r.script("b", r.ok(r.store["b"], stage1))
        val out = p.analyze(image, ScanType.AREA, PipelineSettings(verificationEnabled = false))
        assertEquals("Gemini B", out.accountName); assertFalse(out.verificationRan); assertEquals(VerificationStatus.NOT_RUN, out.questions[0].verification)
        assertEquals(2, r.http.sent.size)
    }

    @Test fun unparsableStageOneMovesToTheNextAccount() = runBlocking {
        val r = twoAccounts(); r.script("a", FakeTransport.openAiOk("sorry, no json")); r.script("b", r.ok(r.store["b"], stage1))
        assertEquals("Gemini B", AiPipeline(r.manager).analyze(image, ScanType.FULL_SCREEN, PipelineSettings(verificationEnabled = false)).accountName)
    }

    @Test fun explanationIsBriefByDefaultDetailedOnRequestAndFollowUpsStayConcise() = runBlocking {
        val r = Rig(); r.store.add("a", GROQ, 0); r.scriptOk("a", "FINAL ANSWER: B")
        val p = AiPipeline(r.manager); val s = PipelineSettings()
        p.explain(q(), null, detailed = false, s)
        var b = Json.parseToJsonElement(r.http.sent.last().body!!).jsonObject
        assertTrue(b["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonPrimitive.content.contains("BRIEF"))
        assertEquals(AiDefaults.BRIEF_MAX_COMPLETION_TOKENS, b["max_completion_tokens"]!!.jsonPrimitive.content.toInt())
        p.explain(q(), null, detailed = true, s)
        b = Json.parseToJsonElement(r.http.sent.last().body!!).jsonObject
        assertTrue(b["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonPrimitive.content.contains("SOLUTION: numbered steps"))
        p.followUp(q(), "FINAL ANSWER: B", emptyList(), "why not A?", null, s)
        b = Json.parseToJsonElement(r.http.sent.last().body!!).jsonObject
        assertTrue(b["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonPrimitive.content.contains("concisely"))
        assertEquals(AiDefaults.FOLLOWUP_MAX_COMPLETION_TOKENS, b["max_completion_tokens"]!!.jsonPrimitive.content.toInt())
        p.followUp(q(), "x", emptyList(), "Show all steps", null, s)
        b = Json.parseToJsonElement(r.http.sent.last().body!!).jsonObject
        assertTrue(b["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonPrimitive.content.contains("SOLUTION: numbered steps"))
        assertEquals(4, r.store["a"].stats.successes)
        assertEquals(2, r.store["a"].stats.explanationRequests); assertEquals(2, r.store["a"].stats.followUpRequests)
    }

    @Test fun cropImageIsAttachedOnlyWhenNeededAndSupported() = runBlocking {
        val r = Rig(); r.store.add("v", GROQ, 0); r.store.add("t", GROQ, 1, model = "llama-3.3-70b-versatile")
        r.scriptOk("v"); r.scriptOk("t")
        var cropCalls = 0
        val crop: suspend () -> EncodedImage? = { cropCalls++; EncodedImage("data:image/jpeg;base64,BBBB", 10, 10, "c") }
        val p = AiPipeline(r.manager); val s = PipelineSettings()
        p.explain(q(), crop, false, s) // confident, no figure: no image needed
        assertEquals(0, cropCalls); assertFalse(r.http.sent.last().body!!.contains("image_url"))
        p.explain(q(fig = true), crop, false, s)
        assertEquals(1, cropCalls); assertTrue(r.http.sent.last().body!!.contains("BBBB"))
        // the first account is down: the text-only model must NOT receive the image
        r.store.list["v"] = r.store["v"].copy(enabled = false)
        p.explain(q(fig = true), crop, false, s)
        assertFalse(r.http.sent.last().body!!.contains("BBBB"))
        assertTrue(r.http.sent.last().body!!.contains("No image is attached"))
    }
}
