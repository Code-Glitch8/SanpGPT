package com.snapgpt.ai.verification

import com.snapgpt.ai.AiDefaults
import com.snapgpt.ai.AiErrorCode
import com.snapgpt.ai.AiException
import com.snapgpt.ai.ChatMessage
import com.snapgpt.ai.ChatRequest
import com.snapgpt.ai.DetectedQuestion
import com.snapgpt.ai.JsonExtract
import com.snapgpt.ai.Prompts
import com.snapgpt.ai.TokenUsage
import com.snapgpt.ai.VerificationStatus
import com.snapgpt.ai.failover.FailoverConfig
import com.snapgpt.ai.failover.FailoverManager
import com.snapgpt.ai.failover.RouteSpec
import com.snapgpt.ai.providers.RequestKind
import com.snapgpt.jee.JeeConfig
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonObject

/** [failed] = the verification request itself could not run (as opposed to questions that simply cannot be checked). */
data class VerifyReport(val questions: List<DetectedQuestion>, val usage: TokenUsage?, val requests: Int, val failed: Boolean = false)

/**
 * Independent answer verification.
 *
 *  1. Every question that can be checked from text is solved again from scratch (the first answer is NOT shown to the solver),
 *     preferably through a different account than the one that gave the first answer.
 *  2. The two answers are compared by [AnswerComparer].
 *  3. Disagreements go to a closer re-check that also checks formulas, signs and units; its verdict can correct the answer.
 *
 * "Verified" means: an independent solve agreed AND it flagged no doubtful formula, sign or unit. It is not a proof, and two
 * answers merely looking alike is not enough when the solver itself reported doubts. Failures never throw answers away:
 * the question just stays "Unable to verify".
 */
class AnswerVerifier(private val failover: FailoverManager) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private class Solve(val solvable: Boolean, val answer: String, val confidence: Float?, val flagged: Boolean, val note: String)
    private class Judge(val verdict: String, val finalAnswer: String, val confidence: Float?, val reason: String)

    suspend fun verify(
        questions: List<DetectedQuestion>,
        jee: JeeConfig,
        failoverConfig: FailoverConfig,
        avoidAccountIds: Set<String>,
        preferDifferentAccount: Boolean,
        onStatus: (String) -> Unit = {},
    ): VerifyReport {
        if (questions.isEmpty()) return VerifyReport(questions, null, 0)
        val result = questions.toMutableList()
        var usage: TokenUsage? = null
        var requests = 0
        fun addUsage(u: TokenUsage?) { if (u != null) usage = (usage ?: TokenUsage.ZERO) + u }
        fun set(id: String, f: (DetectedQuestion) -> DetectedQuestion) {
            val i = result.indexOfFirst { it.id == id }
            if (i >= 0) result[i] = f(result[i])
        }
        fun mark(id: String, status: VerificationStatus, note: String) = set(id) { it.copy(verification = status, verificationNote = note) }

        val targets = ArrayList<DetectedQuestion>()
        for (q in questions) {
            when {
                q.needsClarification -> mark(q.id, VerificationStatus.UNABLE, "The question text was unclear, so it could not be checked.")
                q.hasFigure -> mark(q.id, VerificationStatus.UNABLE, "It depends on a figure, which a text-only independent check cannot see.")
                q.answer.isBlank() || q.questionText.isBlank() -> mark(q.id, VerificationStatus.UNABLE, "There was no readable question or answer to check.")
                else -> targets.add(q)
            }
        }
        if (targets.isEmpty()) return VerifyReport(result, usage, requests)

        val avoid = if (preferDifferentAccount) avoidAccountIds else emptySet()

        // ---- 1. independent solve
        onStatus("Verifying the answer independently...")
        val solved = try {
            requests++
            failover.route(
                RouteSpec(RequestKind.VERIFICATION, needsImage = false, config = failoverConfig, avoidAccountIds = avoid) { a ->
                    ChatRequest(
                        a.model,
                        listOf(ChatMessage("system", Prompts.verifySolveSystem(jee)), ChatMessage("user", Prompts.verifySolveUser(targets))),
                        0.2, AiDefaults.VERIFY_MAX_COMPLETION_TOKENS, jsonMode = true,
                    )
                },
                onStatus,
            ) { r -> parseSolve(r.text) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: AiException) {
            val why = "Verification could not run: " + (e.message ?: "unknown error").lineSequence().first().take(160)
            targets.forEach { mark(it.id, VerificationStatus.UNABLE, why) }
            return VerifyReport(result, usage, requests, failed = true)
        }
        addUsage(solved.result.usage)
        val solver = solved.account
        val solves = solved.value

        val disputes = ArrayList<Prompts.Dispute>()
        for (q in targets) {
            val s = solves[q.id]
            if (s == null || !s.solvable || s.answer.isBlank()) {
                mark(q.id, VerificationStatus.UNABLE, "The independent solver could not solve it from the text.")
                continue
            }
            when (AnswerComparer.compare(q.answer, s.answer, q)) {
                Agreement.SAME -> {
                    val lowSolver = s.confidence != null && s.confidence < 0.6f
                    val lowFirst = q.confidence < 0.6f
                    when {
                        s.flagged -> mark(q.id, VerificationStatus.LOW_CONFIDENCE, "Both solves gave ${q.answer}, but the checker doubted a formula, sign or unit" + noteSuffix(s.note) + ".")
                        lowSolver || lowFirst -> mark(q.id, VerificationStatus.LOW_CONFIDENCE, "Both solves gave ${q.answer}, but at least one was not confident.")
                        else -> mark(q.id, VerificationStatus.VERIFIED, "An independent solve on ${solver.name} reached the same answer; formulas, signs and units were not flagged.")
                    }
                }
                Agreement.UNITS_DIFFER -> mark(q.id, VerificationStatus.LOW_CONFIDENCE, "The values agree (${q.answer} vs ${s.answer}) but the units are written differently. Check the units.")
                Agreement.DIFFERENT -> disputes.add(Prompts.Dispute(q, q.answer, s.answer))
            }
        }
        if (disputes.isEmpty()) return VerifyReport(result, usage, requests)

        // ---- 3. closer re-check of disagreements (prefer a third account)
        onStatus("The checks disagreed. Re-checking...")
        val judged = try {
            requests++
            failover.route(
                RouteSpec(RequestKind.VERIFICATION, needsImage = false, config = failoverConfig, avoidAccountIds = avoid + solver.id) { a ->
                    ChatRequest(
                        a.model,
                        listOf(ChatMessage("system", Prompts.verifyJudgeSystem(jee)), ChatMessage("user", Prompts.verifyJudgeUser(disputes))),
                        0.2, AiDefaults.VERIFY_MAX_COMPLETION_TOKENS, jsonMode = true,
                    )
                },
                onStatus,
            ) { r -> parseJudge(r.text) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: AiException) {
            disputes.forEach { d ->
                mark(d.question.id, VerificationStatus.LOW_CONFIDENCE, "An independent check gave ${d.second} instead of ${d.first}, and the re-check could not run. Treat the answer with caution.")
            }
            return VerifyReport(result, usage, requests, failed = true)
        }
        addUsage(judged.result.usage)

        for (d in disputes) {
            val q = d.question
            val j = judged.value[q.id]
            val conf = j?.confidence
            when {
                j == null -> mark(q.id, VerificationStatus.LOW_CONFIDENCE, "An independent check gave ${d.second} instead of ${d.first}, and the re-check did not settle it.")
                j.verdict == "equivalent" -> mark(q.id, VerificationStatus.VERIFIED, "The two independent answers (${d.first} and ${d.second}) are the same value in different notation.")
                j.verdict == "first" -> mark(q.id, VerificationStatus.LOW_CONFIDENCE, "An independent check gave ${d.second}; a closer re-check supported the original ${d.first}" + noteSuffix(j.reason) + ". Treat with caution.")
                j.verdict == "second" && (conf == null || conf >= 0.5f) -> {
                    val corrected = j.finalAnswer.ifBlank { d.second }
                    set(q.id) { it.copy(answer = corrected, verification = VerificationStatus.CORRECTED, originalAnswer = d.first, verificationNote = "Changed from ${d.first} to $corrected after an independent re-check" + noteSuffix(j.reason) + ".") }
                }
                j.verdict == "neither" && j.finalAnswer.isNotBlank() && (conf ?: 0f) >= 0.7f ->
                    set(q.id) { it.copy(answer = j.finalAnswer, verification = VerificationStatus.CORRECTED, originalAnswer = d.first, verificationNote = "Neither ${d.first} nor ${d.second} held up; the re-check gives ${j.finalAnswer}" + noteSuffix(j.reason) + ".") }
                else -> mark(q.id, VerificationStatus.LOW_CONFIDENCE, "Independent solves disagreed (${d.first} vs ${d.second}) and the re-check was not conclusive. Treat the answer with caution.")
            }
        }
        return VerifyReport(result, usage, requests)
    }

    private fun noteSuffix(s: String) = if (s.isBlank()) "" else " (" + s.trim().trimEnd('.').take(120) + ")"

    // ---------------------------------------------------------------- parsing

    private fun resultsOf(raw: String): List<JsonObject> {
        val payload = JsonExtract.extract(raw) ?: throw AiException(AiErrorCode.INVALID_RESPONSE, "The verifier did not return JSON.")
        val root: JsonElement = try { json.parseToJsonElement(payload) } catch (e: Exception) {
            throw AiException(AiErrorCode.INVALID_RESPONSE, "The verifier returned malformed JSON.")
        }
        val arr = when {
            root is JsonArray -> root
            root is JsonObject && root["results"] is JsonArray -> root["results"] as JsonArray
            else -> throw AiException(AiErrorCode.INVALID_RESPONSE, "The verifier's reply had no \"results\" list.")
        }
        return arr.mapNotNull { it as? JsonObject }
    }

    private fun str(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
    private fun flt(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.floatOrNull
    private fun bool(o: JsonObject, k: String, default: Boolean) = (o[k] as? JsonPrimitive)?.let { it.booleanOrNull ?: it.contentOrNull?.lowercase()?.let { s -> s == "true" } } ?: default

    private fun parseSolve(raw: String): Map<String, Solve> {
        val out = LinkedHashMap<String, Solve>()
        for (o in resultsOf(raw)) {
            val id = str(o, "id")
            if (id.isBlank()) continue
            val flagged = !bool(o, "formula_ok", true) || !bool(o, "units_ok", true) || !bool(o, "signs_ok", true)
            out[id] = Solve(bool(o, "solvable", true), str(o, "answer"), flt(o, "confidence"), flagged, str(o, "note"))
        }
        if (out.isEmpty()) throw AiException(AiErrorCode.INVALID_RESPONSE, "The verifier returned no results.")
        return out
    }

    private fun parseJudge(raw: String): Map<String, Judge> {
        val out = LinkedHashMap<String, Judge>()
        for (o in resultsOf(raw)) {
            val id = str(o, "id")
            if (id.isBlank()) continue
            out[id] = Judge(str(o, "verdict").lowercase(), str(o, "final_answer"), flt(o, "confidence"), str(o, "reason"))
        }
        if (out.isEmpty()) throw AiException(AiErrorCode.INVALID_RESPONSE, "The re-check returned no results.")
        return out
    }
}
