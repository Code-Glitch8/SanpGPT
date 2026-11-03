package com.snapgpt.ai

import com.snapgpt.ai.failover.FailoverConfig
import com.snapgpt.ai.failover.FailoverManager
import com.snapgpt.ai.failover.RouteSpec
import com.snapgpt.ai.providers.RequestKind
import com.snapgpt.ai.verification.AnswerVerifier
import com.snapgpt.jee.JeeConfig
import com.snapgpt.settings.AppSettings
import com.snapgpt.util.TextSimplifier

/** A screenshot (or crop) already compressed to JPEG and wrapped as a data URL. [hash] identifies identical images. */
class EncodedImage(val dataUrl: String, val width: Int, val height: Int, val hash: String)

/** The settings the pipeline needs, decoupled from Android storage. */
data class PipelineSettings(
    val jee: JeeConfig = JeeConfig(),
    val maxQuestions: Int = 5,
    val alwaysAttachImageInExplanation: Boolean = false,
    val failover: FailoverConfig = FailoverConfig(),
    val verificationEnabled: Boolean = true,
    val verifyDifferentAccount: Boolean = true,
)

fun AppSettings.toPipelineSettings() = PipelineSettings(
    jee = jee,
    maxQuestions = maxQuestionsPerScan,
    alwaysAttachImageInExplanation = alwaysAttachImageInExplanation,
    failover = FailoverConfig(enabled = autoFailover, maxRetriesPerAccount = maxRetriesPerAccount),
    verificationEnabled = verificationEnabled,
    verifyDifferentAccount = verifyDifferentAccount,
)

data class AnalysisOutcome(
    val questions: List<DetectedQuestion>,
    val warnings: List<String>,
    /** Actual tokens reported by providers for analysis + verification (null when none were reported). */
    val usage: TokenUsage?,
    val fromCache: Boolean,
    val accountId: String,
    val accountName: String,
    val providerName: String,
    val verificationRan: Boolean,
)

data class TextOutcome(val text: String, val usage: TokenUsage?, val accountName: String)

/**
 * Two-stage pipeline on top of the failover manager.
 *  Stage 1: one image request -> concise JSON answers (never explanations), then independent verification.
 *  Stage 2: only when the user taps an answer -> a BRIEF explanation (or the full step-by-step one when asked).
 * Everything is routed through [FailoverManager], so every stage can use a different account when needed.
 */
class AiPipeline(private val failover: FailoverManager, private val verifier: AnswerVerifier = AnswerVerifier(failover)) {

    private val cache = object : LinkedHashMap<String, AnalysisOutcome>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, AnalysisOutcome>?) = size > 6
    }

    fun clearCache() = cache.clear()

    suspend fun analyze(image: EncodedImage, scanType: ScanType, s: PipelineSettings, onStatus: (String) -> Unit = {}): AnalysisOutcome {
        val j = s.jee
        val cacheKey = listOf(image.hash, j.mode.key, j.syllabusRestriction, j.showChapterTopic, s.maxQuestions, scanType, s.verificationEnabled, s.verifyDifferentAccount).joinToString("|")
        cache[cacheKey]?.let { return it.copy(fromCache = true, usage = null) }

        val system = Prompts.stage1System(j, s.maxQuestions)
        val user = Prompts.stage1User(image.width, image.height, scanType == ScanType.AREA)
        val routed = failover.route(
            RouteSpec(RequestKind.ANALYSIS, needsImage = true, config = s.failover, dedupeKey = "analysis:$cacheKey") { a ->
                ChatRequest(
                    a.model,
                    listOf(ChatMessage("system", system), ChatMessage("user", user, listOf(image.dataUrl))),
                    0.4, AiDefaults.STAGE1_MAX_COMPLETION_TOKENS, jsonMode = true,
                )
            },
            onStatus,
        ) { r -> Stage1Parser.parse(r.text, s.maxQuestions, image.width, image.height) }

        var questions = routed.value.questions
        var usage = routed.result.usage
        var verified = false
        var verifyFailed = false
        if (s.verificationEnabled && questions.isNotEmpty()) {
            val report = verifier.verify(questions, j, s.failover, setOf(routed.account.id), s.verifyDifferentAccount, onStatus)
            questions = report.questions
            report.usage?.let { usage = (usage ?: TokenUsage.ZERO) + it }
            verified = true
            verifyFailed = report.failed
        }
        val outcome = AnalysisOutcome(
            questions, routed.value.warnings, usage, false,
            routed.account.id, routed.account.name, routed.account.provider.displayName, verified,
        )
        // A failed verification should be retried on the next scan, so only complete results are reused.
        if (!verifyFailed) cache[cacheKey] = outcome
        return outcome
    }

    /** Explain ONE question. [crop] supplies the question image lazily and is used only when it is worth the tokens and the account takes images. */
    suspend fun explain(
        q: DetectedQuestion, crop: (suspend () -> EncodedImage?)?, detailed: Boolean, s: PipelineSettings, onStatus: (String) -> Unit = {},
    ): TextOutcome {
        val wantsImage = crop != null && Prompts.needsImageForExplanation(q, s.alwaysAttachImageInExplanation)
        val image = if (wantsImage) crop!!() else null
        val system = Prompts.stage2System(s.jee, detailed)
        val routed = failover.route(
            RouteSpec(RequestKind.EXPLANATION, needsImage = false, config = s.failover) { a ->
                val attach = image != null && a.supportsImages()
                ChatRequest(
                    a.model,
                    listOf(ChatMessage("system", system), ChatMessage("user", Prompts.stage2User(q, attach), if (attach) listOf(image!!.dataUrl) else emptyList())),
                    0.6, if (detailed) AiDefaults.STAGE2_MAX_COMPLETION_TOKENS else AiDefaults.BRIEF_MAX_COMPLETION_TOKENS, jsonMode = false,
                )
            },
            onStatus,
        ) { it }
        return TextOutcome(TextSimplifier.simplify(routed.result.text), routed.result.usage, routed.account.name)
    }

    /** Follow-up about an explained question. Concise unless the user explicitly asks for detail; text-only unless a figure is involved. */
    suspend fun followUp(
        q: DetectedQuestion, explanation: String, history: List<ChatMessage>, ask: String,
        crop: (suspend () -> EncodedImage?)?, s: PipelineSettings, onStatus: (String) -> Unit = {},
    ): TextOutcome {
        val detailed = DetailIntent.isDetailed(ask)
        val image = if (crop != null && q.hasFigure) crop() else null
        val system = Prompts.followUpSystem(s.jee, detailed)
        val routed = failover.route(
            RouteSpec(RequestKind.FOLLOW_UP, needsImage = false, config = s.failover) { a ->
                val attach = image != null && a.supportsImages()
                val messages = buildList {
                    add(ChatMessage("system", system))
                    add(ChatMessage("user", Prompts.stage2User(q, attach), if (attach) listOf(image!!.dataUrl) else emptyList()))
                    add(ChatMessage("assistant", explanation))
                    addAll(history.takeLast(8))
                    add(ChatMessage("user", ask))
                }
                ChatRequest(a.model, messages, 0.6, if (detailed) AiDefaults.STAGE2_MAX_COMPLETION_TOKENS else AiDefaults.FOLLOWUP_MAX_COMPLETION_TOKENS, jsonMode = false)
            },
            onStatus,
        ) { it }
        return TextOutcome(TextSimplifier.simplify(routed.result.text), routed.result.usage, routed.account.name)
    }
}
