package com.snapgpt.overlay

import com.snapgpt.ai.DetectedQuestion

/** Turns a Stage 1 answer into the short text shown on the floating answer bubble. */
object AnswerFormatter {
    private const val MAX_CHARS = 26
    private val OPTION_LETTER = Regex("(?<![A-Za-z0-9])\\(?([A-H1-4])\\)?(?![A-Za-z0-9])")
    private val LETTER_RUN = Regex("^[A-H]{2,4}$")

    fun bubbleText(q: DetectedQuestion): String {
        val raw = q.answer.trim()
        if (raw.isEmpty()) return "?"
        if (q.isMultipleChoice) {
            val compact = raw.replace(" ", "").replace(",", "").replace("&", "").uppercase()
            if (LETTER_RUN.matches(compact)) return compact.toList().joinToString(", ")
            val letters = OPTION_LETTER.findAll(raw).map { it.groupValues[1] }.distinct().toList()
            if (letters.isNotEmpty()) return letters.take(4).joinToString(", ")
        }
        val oneLine = raw.replace(Regex("\\s+"), " ")
        return if (oneLine.length <= MAX_CHARS) oneLine else oneLine.take(MAX_CHARS - 1).trimEnd() + "…"
    }

    /** Short answers (a letter or two digits) get a round bubble; everything else a pill. */
    fun isRound(text: String): Boolean = text.length <= 2

    /** Low confidence or unverifiable answers are drawn with an amber outline so the student double-checks them. */
    fun isUncertain(q: DetectedQuestion): Boolean = q.needsClarification || q.confidence < 0.6f || q.verification.isUncertain
}
