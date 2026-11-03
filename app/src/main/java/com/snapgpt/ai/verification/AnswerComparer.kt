package com.snapgpt.ai.verification

import com.snapgpt.ai.DetectedQuestion
import com.snapgpt.ai.QuestionTypes

enum class Agreement { SAME, DIFFERENT, UNITS_DIFFER }

/**
 * Compares two final answers without calling an AI. Multiple-choice answers are compared as sets of option labels,
 * numeric answers by value (about 1 % tolerance, exact for integer-type questions) with a separate units check, and
 * everything else as normalised text. Anything it cannot call equal is DIFFERENT and goes to a closer re-check.
 */
object AnswerComparer {

    fun compare(a: String, b: String, q: DetectedQuestion): Agreement {
        if (q.isMultipleChoice || q.options.isNotEmpty()) {
            val la = labels(a, q)
            val lb = labels(b, q)
            if (la != null && lb != null) return if (la == lb) Agreement.SAME else Agreement.DIFFERENT
        }
        val na = number(a)
        val nb = number(b)
        if (na != null && nb != null) {
            val exact = q.questionType == QuestionTypes.INTEGER
            if (!close(na.value, nb.value, exact)) return Agreement.DIFFERENT
            return if (na.unit.isNotEmpty() && nb.unit.isNotEmpty() && na.unit != nb.unit) Agreement.UNITS_DIFFER else Agreement.SAME
        }
        return if (text(a) == text(b)) Agreement.SAME else Agreement.DIFFERENT
    }

    // ---------------------------------------------------------------- choices

    private val LABEL_CHARS = Regex("^[A-Ha-h1-8]{1,6}$")

    /** The set of option labels named by [answer], or null when it is not a list of labels. */
    fun labels(answer: String, q: DetectedQuestion): Set<String>? {
        val stripped = answer.trim()
            .replace(Regex("(?i)\\boptions?\\b"), " ")
            .replace(Regex("(?i)\\band\\b"), " ")
            .replace("&", " ")
            .replace(Regex("[\\s,;/().:]+"), "")
        if (stripped.isNotEmpty() && LABEL_CHARS.matches(stripped)) {
            val set = stripped.uppercase().map { it.toString() }.toSet()
            // With known options, only real labels count ("2" is option text, not a label, when the options are A/B/C/D).
            val known = q.options.map { it.label.uppercase().filter { c -> c.isLetterOrDigit() } }.toSet()
            if (known.isEmpty() || set.all { it in known }) return set
        }
        // The solver wrote the option's text instead of its label.
        val wanted = text(answer)
        if (wanted.isNotEmpty()) {
            val hit = q.options.firstOrNull { text(it.text) == wanted }
            if (hit != null) return setOf(hit.label.uppercase().filter { it.isLetterOrDigit() })
        }
        return null
    }

    // ---------------------------------------------------------------- numbers

    data class Num(val value: Double, val unit: String)

    private val SCI = Regex("^\\s*([-+]?\\d+(?:\\.\\d+)?|[-+]?\\.\\d+)\\s*(?:x|\\*)\\s*10\\s*\\^?\\s*[({]?\\s*([-+]?\\d+)\\s*[)}]?")
    private val ENOT = Regex("^\\s*([-+]?\\d*\\.?\\d+)[eE]([-+]?\\d+)")
    private val FRAC = Regex("^\\s*([-+]?\\d+)\\s*/\\s*(\\d+)(?![\\d.])")
    private val DEC = Regex("^\\s*([-+]?\\d+(?:\\.\\d+)?|[-+]?\\.\\d+)")

    private val SUPERSCRIPT = Regex("([⁻⁺]?[⁰¹²³⁴⁵⁶⁷⁸⁹]+)")
    private const val SUP_FROM = "⁰¹²³⁴⁵⁶⁷⁸⁹⁻⁺"
    private const val SUP_TO = "0123456789-+"

    private fun clean(raw: String): String {
        var s = raw.trim()
            .replace('−', '-').replace('–', '-').replace('—', '-')
            .replace('×', 'x').replace('·', '*').replace('⋅', '*')
            .replace(Regex("(?i)^\\s*(?:final answer|answer|ans)\\s*[:=]?"), "")
            .replace(Regex("^\\s*[=≈~]+\\s*"), "")
            .replace("$", "")
            .replace("\\,", "")
        // Superscripts become "^n": 10⁻³ -> 10^-3, m/s² -> m/s^2.
        s = SUPERSCRIPT.replace(s) { m -> "^" + m.value.map { c -> SUP_TO[SUP_FROM.indexOf(c)] }.joinToString("") }
        s = s.replace(Regex("(?<=\\d),(?=\\d{3}(\\D|$))"), "")
        return s
    }

    private fun unitOf(rest: String): String {
        var u = rest.trim().trimEnd('.', ' ', ';').lowercase()
        u = u.replace(" ", "").replace("*", "").replace("\\", "").replace("{", "").replace("}", "")
        u = u.replace("°c", "°c").replace("ohms", "ω").replace("ohm", "ω").replace("µ", "u").replace("μ", "u")
        return u
    }

    fun number(raw: String): Num? {
        val s = clean(raw)
        val (value, end) = when {
            SCI.find(s) != null -> SCI.find(s)!!.let { it.groupValues[1].toDouble() * Math.pow(10.0, it.groupValues[2].toDouble()) to it.range.last + 1 }
            ENOT.find(s) != null -> ENOT.find(s)!!.let { it.groupValues[1].toDouble() * Math.pow(10.0, it.groupValues[2].toDouble()) to it.range.last + 1 }
            FRAC.find(s) != null -> FRAC.find(s)!!.let { (it.groupValues[1].toDouble() / it.groupValues[2].toDouble()) to it.range.last + 1 }
            DEC.find(s) != null -> DEC.find(s)!!.let { it.groupValues[1].toDouble() to it.range.last + 1 }
            else -> return null
        }
        if (value.isNaN() || value.isInfinite()) return null
        val rest = s.substring(end)
        // Anything that continues like an algebraic expression is not a plain number with a unit.
        if (rest.length > 16 || rest.contains('+') || rest.contains('=') || rest.trimStart().firstOrNull()?.isDigit() == true) return null
        return Num(value, unitOf(rest))
    }

    private fun close(a: Double, b: Double, exact: Boolean): Boolean {
        val diff = Math.abs(a - b)
        if (diff < 1e-9) return true
        val scale = Math.max(Math.abs(a), Math.abs(b))
        return diff <= scale * (if (exact) 1e-9 else 0.01)
    }

    // ---------------------------------------------------------------- text

    private fun text(s: String): String = s.lowercase()
        .replace('−', '-').replace('×', '*').replace('·', '*').replace('⋅', '*')
        .replace("²", "^2").replace("³", "^3").replace("√", "sqrt")
        .replace(Regex("[\\s$\\\\{}]"), "")
}
