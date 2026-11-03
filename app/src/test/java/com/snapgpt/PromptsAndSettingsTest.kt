package com.snapgpt

import com.snapgpt.ai.AiDefaults
import com.snapgpt.ai.DetectedQuestion
import com.snapgpt.ai.ChatMessage
import com.snapgpt.ai.ChoiceOption
import com.snapgpt.ai.Prompts
import com.snapgpt.ai.QuestionTypes
import com.snapgpt.ai.Scrub
import com.snapgpt.ai.TokenEstimator
import com.snapgpt.ai.TokenUsage
import com.snapgpt.ai.providers.AccountStats
import com.snapgpt.ai.providers.RequestKind
import com.snapgpt.ai.DetailIntent
import com.snapgpt.capture.ImageQuality
import com.snapgpt.capture.ImageSizing
import com.snapgpt.jee.ExamMode
import com.snapgpt.jee.JeeConfig
import com.snapgpt.settings.AppSettings
import com.snapgpt.settings.ThemeMode
import com.snapgpt.util.TextSimplifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptsAndSettingsTest {

    private fun question(conf: Float = 0.95f, fig: Boolean = false, clar: Boolean = false, text: String = "What is x?") = DetectedQuestion(
        "q1", text, "Mathematics", "Quadratic Equations", "Roots", "JEE Main", QuestionTypes.MCQ, "B", "option", conf, null, clar,
        listOf(ChoiceOption("A", "1"), ChoiceOption("B", "2")), fig, "unclear sign",
    )

    @Test
    fun defaultsMatchTheProductSpec() {
        val s = AppSettings()
        assertEquals("qwen/qwen3.8-27b", AiDefaults.DEFAULT_MODEL)
        assertTrue(s.autoFailover)
        assertEquals(2, s.maxRetriesPerAccount)
        assertTrue(s.verificationEnabled)
        assertTrue(s.verifyDifferentAccount)
        assertEquals(52, s.circleSizeDp)
        assertTrue(s.idleOpacityPercent in 25..35)
        assertTrue(s.syllabusRestriction)
        assertTrue(s.showChapterTopic)
        assertFalse(s.showAlternativeMethods)
        assertEquals(ExamMode.AUTOMATIC, s.examMode)
        assertEquals(ThemeMode.SYSTEM, s.theme)
        assertTrue(s.historyEnabled)
        assertTrue(s.edgeSnapping)
        assertEquals(JeeConfig(ExamMode.AUTOMATIC, true, true, false), s.jee)
    }

    @Test
    fun keysRoundTripAndUnknownKeysFallBackToDefaults() {
        ExamMode.entries.forEach { assertEquals(it, ExamMode.fromKey(it.key)) }
        ImageQuality.entries.forEach { assertEquals(it, ImageQuality.fromKey(it.key)) }
        assertEquals(ExamMode.AUTOMATIC, ExamMode.fromKey("garbage"))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromKey(null))
    }

    @Test
    fun jeeInstructionsAreUsedInBothStagesAndFollowTheMode() {
        val adv = JeeConfig(ExamMode.JEE_ADVANCED)
        val main = JeeConfig(ExamMode.JEE_MAIN)
        val auto = JeeConfig(ExamMode.AUTOMATIC)
        val s1 = Prompts.stage1System(adv, 4)
        val s2 = Prompts.stage2System(adv)
        assertTrue(s1.contains("EXAM MODE: JEE Advanced") && s2.contains("EXAM MODE: JEE Advanced"))
        assertTrue(Prompts.stage1System(main, 4).contains("EXAM MODE: JEE Main"))
        assertTrue(Prompts.stage1System(auto, 4).contains("Never claim to know the exact exam"))
        assertTrue(s1.contains("official NTA JEE Main syllabus") && s2.contains("official NTA JEE Main syllabus"))
    }

    @Test
    fun syllabusRestrictionToggleChangesThePrompt() {
        val on = Prompts.stage2System(JeeConfig(syllabusRestriction = true))
        val off = Prompts.stage2System(JeeConfig(syllabusRestriction = false))
        assertTrue(on.contains("Do NOT use university-level mathematics"))
        assertFalse(off.contains("Do NOT use university-level mathematics"))
    }

    @Test
    fun stage1IsConciseNormalisedAndLimited() {
        val p = Prompts.stage1System(JeeConfig(), 3)
        assertTrue(p.contains("up to 3 questions"))
        assertTrue(p.contains("Do NOT write explanations"))
        assertTrue(p.contains("NORMALISED coordinates between 0 and 1"))
        assertTrue(p.contains("\"bounding_box\""))
        assertTrue(Prompts.stage1User(1080, 2400, true).contains("selected"))
        assertFalse(Prompts.stage1User(1080, 2400, false).contains("selected"))
    }

    @Test
    fun stage2FormatFollowsSettings() {
        assertTrue(Prompts.stage2System(JeeConfig(showChapterTopic = true)).contains("SUBJECT / CHAPTER / TOPIC"))
        assertFalse(Prompts.stage2System(JeeConfig(showChapterTopic = false)).contains("SUBJECT / CHAPTER / TOPIC"))
        assertTrue(Prompts.stage2System(JeeConfig(showAlternativeMethods = true)).contains("ALTERNATIVE METHOD"))
        assertTrue(Prompts.stage2System(JeeConfig(showAlternativeMethods = false)).contains("Give only one method"))
        val u = Prompts.stage2User(question(), imageAttached = false)
        assertTrue(u.contains("A) 1") && u.contains("Stage 1 quick answer (verify it): B") && u.contains("No image is attached"))
    }

    @Test
    fun imageIsResentOnlyWhenNeeded() {
        assertFalse(Prompts.needsImageForExplanation(question(), alwaysAttach = false))
        assertTrue(Prompts.needsImageForExplanation(question(fig = true), false))
        assertTrue(Prompts.needsImageForExplanation(question(conf = 0.5f), false))
        assertTrue(Prompts.needsImageForExplanation(question(clar = true), false))
        assertTrue(Prompts.needsImageForExplanation(question(text = ""), false))
        assertTrue(Prompts.needsImageForExplanation(question(), alwaysAttach = true))
    }

    @Test
    fun usageSeparatesActualFromEstimatedAndCountsPerKind() {
        var u = AccountStats()
        u = u.recordSuccess(RequestKind.ANALYSIS, TokenUsage(2100, 40, 2140), 2200, 50)
        u = u.recordSuccess(RequestKind.VERIFICATION, null, 300, 20) // no usage object returned: only an estimate
        u = u.recordFailure(RequestKind.EXPLANATION, quota = true, actual = null, estimatedInput = 2048)
        assertEquals(3, u.requests); assertEquals(2, u.successes); assertEquals(1, u.failures); assertEquals(1, u.quotaErrors)
        assertEquals(1, u.analysisRequests); assertEquals(1, u.verificationRequests); assertEquals(1, u.explanationRequests); assertEquals(0, u.followUpRequests)
        assertEquals(2100L, u.actualInputTokens); assertEquals(40L, u.actualOutputTokens); assertEquals(2140L, u.actualTotalTokens)
        assertEquals(2200L + 300L + 2048L, u.estimatedInputTokens)
        assertEquals(70L, u.estimatedOutputTokens)
        assertEquals(u.estimatedInputTokens + u.estimatedOutputTokens, u.estimatedTotalTokens)
        assertEquals(TokenUsage(3, 5, 8), TokenUsage(1, 2, 3) + TokenUsage(2, 3, 5))
        assertEquals(6, (u + u).requests)
    }

    @Test
    fun briefIsTheDefaultAndDetailOnlyOnExplicitRequest() {
        val brief = Prompts.stage2System(JeeConfig(), detailed = false)
        assertTrue(brief.contains("BRIEF") && brief.contains("FINAL ANSWER") && brief.contains("FORMULA") && brief.contains("EXPLANATION"))
        assertFalse(brief.contains("SOLUTION: numbered steps"))
        assertTrue(Prompts.stage2System(JeeConfig(), detailed = true).contains("SOLUTION: numbered steps"))
        assertTrue(Prompts.followUpSystem(JeeConfig()).contains("concisely"))
        assertTrue(Prompts.followUpSystem(JeeConfig(), detailed = true).contains("SOLUTION: numbered steps"))
        listOf("Give a detailed answer", "Explain in detail", "Show all steps", "Explain from basics", "Derive the formula", "explain step by step")
            .forEach { assertTrue(it, DetailIntent.isDetailed(it)) }
        listOf("Why is it B?", "What is the unit of g?", "ok thanks", "Is option C wrong?").forEach { assertFalse(it, DetailIntent.isDetailed(it)) }
    }

    @Test
    fun tokenEstimateCountsImages() {
        val msgs = listOf(ChatMessage("user", "x".repeat(400), listOf("data:image/jpeg;base64,AA")))
        assertEquals(100L + AiDefaults.IMAGE_TOKENS_ESTIMATE, TokenEstimator.request(msgs))
    }

    @Test
    fun imageSizingNeverUpscalesAndCapsLongSide() {
        assertEquals(1080 to 2400, ImageSizing.targetSize(1080, 2400, 2400))
        val (w, h) = ImageSizing.targetSize(1440, 3200, 1600)
        assertEquals(1600, h); assertEquals(720, w)
        assertEquals(1 to 1, ImageSizing.targetSize(0, 10, 100))
        val (pw, ph) = ImageSizing.targetSize(10000, 10000, 100000)
        assertTrue(pw.toLong() * ph <= AiDefaults.MAX_PIXELS)
        assertEquals(8, ImageSizing.base64Length(5))
        assertTrue(ImageSizing.maxImageBytes() < AiDefaults.REQUEST_BUDGET_BYTES)
    }

    @Test
    fun keysAreScrubbedFromText() {
        assertFalse(Scrub.text("bad key gsk_abcDEF123456 here").contains("gsk_abcDEF123456"))
        assertFalse(Scrub.text("Authorization: Bearer abcdef1234567890").contains("abcdef1234567890"))
        assertFalse(Scrub.text("bad sk-ant-api03-ABCDEFGHIJKLMNOP").contains("ABCDEFGHIJKLMNOP"))
        assertFalse(Scrub.text("key AIzaSyA1234567890abcdef rejected").contains("A1234567890abcdef"))
        assertFalse(Scrub.text("sk-or-v1-0123456789abcdef0123").contains("0123456789abcdef"))
        assertFalse(Scrub.text("https://x/y?key=SECRETVALUE123&alt=json").contains("SECRETVALUE123"))
    }

    @Test
    fun latexIsMadeReadable() {
        assertEquals("(a+b)/2", TextSimplifier.simplify("\\frac{a+b}{2}"))
        assertEquals("v = √(2gh)", TextSimplifier.simplify("\$v = \\sqrt{2gh}\$"))
        assertEquals("x² + y₁ ≤ 5 × 3", TextSimplifier.simplify("x^2 + y_1 \\leq 5 \\times 3"))
        assertEquals("θ = 30°", TextSimplifier.simplify("\\theta = 30^\\circ"))
        assertEquals("plain text stays", TextSimplifier.simplify("plain text stays"))
        assertEquals("(x + 1)", TextSimplifier.simplify("\\left(x + 1\\right)"))
    }
}
