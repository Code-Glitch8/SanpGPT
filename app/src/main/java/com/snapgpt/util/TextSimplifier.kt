package com.snapgpt.util

/**
 * The prompts ask for plain Unicode maths, but models sometimes still emit LaTeX. This converts the common LaTeX pieces
 * into readable text so the floating panel never shows raw "\frac{a}{b}". It is a readability helper, not a LaTeX renderer.
 */
object TextSimplifier {
    private val SYMBOLS = linkedMapOf(
        "\\times" to "×", "\\cdot" to "·", "\\div" to "÷", "\\pm" to "±", "\\mp" to "∓",
        "\\leq" to "≤", "\\le" to "≤", "\\geq" to "≥", "\\ge" to "≥", "\\neq" to "≠", "\\ne" to "≠", "\\approx" to "≈",
        "\\infty" to "∞", "\\rightarrow" to "→", "\\Rightarrow" to "⇒", "\\to" to "→", "\\leftrightarrow" to "↔",
        "\\degree" to "°", "\\circ" to "°", "\\propto" to "∝", "\\partial" to "∂", "\\nabla" to "∇",
        "\\int" to "∫", "\\sum" to "Σ", "\\prod" to "∏", "\\angle" to "∠", "\\perp" to "⊥", "\\parallel" to "∥",
        "\\alpha" to "α", "\\beta" to "β", "\\gamma" to "γ", "\\delta" to "δ", "\\Delta" to "Δ", "\\epsilon" to "ε",
        "\\varepsilon" to "ε", "\\theta" to "θ", "\\lambda" to "λ", "\\mu" to "μ", "\\nu" to "ν", "\\pi" to "π",
        "\\rho" to "ρ", "\\sigma" to "σ", "\\Sigma" to "Σ", "\\tau" to "τ", "\\phi" to "φ", "\\varphi" to "φ",
        "\\Phi" to "Φ", "\\omega" to "ω", "\\Omega" to "Ω", "\\eta" to "η", "\\kappa" to "κ", "\\hbar" to "ħ",
        "\\ldots" to "…", "\\dots" to "…", "\\quad" to " ", "\\qquad" to "  ", "\\," to " ", "\\;" to " ", "\\!" to "",
        "\\left" to "", "\\right" to "", "\\displaystyle" to "",
    )
    private val ORDERED = SYMBOLS.entries.sortedByDescending { it.key.length }.map { it.key to it.value }
    private val SUP = mapOf('0' to '⁰', '1' to '¹', '2' to '²', '3' to '³', '4' to '⁴', '5' to '⁵', '6' to '⁶', '7' to '⁷', '8' to '⁸', '9' to '⁹', '+' to '⁺', '-' to '⁻', 'n' to 'ⁿ')
    private val SUB = mapOf('0' to '₀', '1' to '₁', '2' to '₂', '3' to '₃', '4' to '₄', '5' to '₅', '6' to '₆', '7' to '₇', '8' to '₈', '9' to '₉', '+' to '₊', '-' to '₋')

    private val FRAC = Regex("\\\\[dt]?frac\\{([^{}]*)\\}\\{([^{}]*)\\}")
    private val SQRT = Regex("\\\\sqrt\\{([^{}]*)\\}")
    private val TEXT = Regex("\\\\(?:text|mathrm|mathbf|textbf|operatorname)\\{([^{}]*)\\}")
    private val SUP_BRACED = Regex("\\^\\{([0-9+\\-n]+)\\}")
    private val SUP_PLAIN = Regex("\\^([0-9n])")
    private val SUB_BRACED = Regex("_\\{([0-9+\\-]+)\\}")
    private val SUB_PLAIN = Regex("_([0-9])")

    fun simplify(input: String): String {
        if (!input.contains('\\') && !input.contains('$') && !input.contains('^') && !input.contains('_')) return input
        var s = input
        s = s.replace("\\[", "\n").replace("\\]", "\n").replace("\\(", "").replace("\\)", "").replace("$$", "").replace("$", "")
        repeat(3) { // nested fractions / roots
            s = FRAC.replace(s) { m -> wrap(m.groupValues[1]) + "/" + wrap(m.groupValues[2]) }
            s = SQRT.replace(s) { m -> "√(" + m.groupValues[1] + ")" }
            s = TEXT.replace(s) { m -> m.groupValues[1] }
        }
        for ((k, v) in ORDERED) s = s.replace(k, v) // longest first so "\left" is not eaten by "\le"
        s = s.replace("^°", "°").replace("^{°}", "°")
        s = SUP_BRACED.replace(s) { m -> m.groupValues[1].map { SUP[it] ?: it }.joinToString("") }
        s = SUP_PLAIN.replace(s) { m -> (SUP[m.groupValues[1][0]] ?: m.groupValues[1][0]).toString() }
        s = SUB_BRACED.replace(s) { m -> m.groupValues[1].map { SUB[it] ?: it }.joinToString("") }
        s = SUB_PLAIN.replace(s) { m -> (SUB[m.groupValues[1][0]] ?: m.groupValues[1][0]).toString() }
        return s.replace(Regex("[ \\t]{2,}"), " ")
    }

    private fun wrap(part: String): String {
        val t = part.trim()
        return if (t.length <= 1 || t.all { it.isLetterOrDigit() || it == '.' }) t else "($t)"
    }
}
