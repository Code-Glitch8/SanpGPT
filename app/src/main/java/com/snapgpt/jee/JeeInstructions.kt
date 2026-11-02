package com.snapgpt.jee

/** Exam mode chosen by the user. Used in BOTH Stage 1 and Stage 2 prompts. */
enum class ExamMode(val key: String, val label: String) {
    AUTOMATIC("auto", "Automatic"),
    JEE_MAIN("main", "JEE Main"),
    JEE_ADVANCED("advanced", "JEE Advanced");

    companion object {
        fun fromKey(key: String?): ExamMode = entries.firstOrNull { it.key == key } ?: AUTOMATIC
    }
}

/** JEE-related user settings that shape the AI instructions. */
data class JeeConfig(
    val mode: ExamMode = ExamMode.AUTOMATIC,
    val syllabusRestriction: Boolean = true,
    val showChapterTopic: Boolean = true,
    val showAlternativeMethods: Boolean = false,
)

/**
 * Builds the JEE-specific part of the system prompts.
 *
 * The app does NOT ship or claim to ship the official syllabus text. The AI is told to treat the official NTA JEE Main
 * and JEE Advanced syllabi as the authority; [SUBJECT_HINTS] is only a navigation aid for naming chapters consistently.
 */
object JeeInstructions {

    /** Compact chapter hints so chapter names stay consistent. Not exhaustive and not authoritative. */
    val SUBJECT_HINTS: Map<String, List<String>> = linkedMapOf(
        "Mathematics" to listOf(
            "Sets, Relations and Functions", "Quadratic Equations", "Complex Numbers", "Sequences and Series",
            "Permutations and Combinations", "Binomial Theorem", "Matrices and Determinants", "Trigonometry",
            "Straight Lines", "Circles", "Conic Sections", "Limits", "Continuity and Differentiability",
            "Differentiation", "Applications of Derivatives", "Integration", "Differential Equations", "Vectors",
            "Three-Dimensional Geometry", "Probability", "Statistics", "Mathematical Reasoning",
        ),
        "Physics" to listOf(
            "Units and Dimensions", "Kinematics", "Laws of Motion", "Work, Energy and Power", "Rotational Motion",
            "Gravitation", "Properties of Matter", "Thermodynamics", "Kinetic Theory of Gases", "Oscillations", "Waves",
            "Electrostatics", "Current Electricity", "Magnetism", "Electromagnetic Induction", "Alternating Current",
            "Electromagnetic Waves", "Optics", "Modern Physics", "Semiconductors", "Experimental Skills",
        ),
        "Chemistry" to listOf(
            "Mole Concept", "Atomic Structure", "Chemical Bonding", "Thermodynamics", "Chemical Equilibrium",
            "Ionic Equilibrium", "Redox Reactions", "Electrochemistry", "Chemical Kinetics", "Solutions",
            "Periodic Properties", "p-Block Elements", "d- and f-Block Elements", "Coordination Compounds",
            "General Organic Chemistry", "Hydrocarbons", "Haloalkanes and Haloarenes", "Alcohols, Phenols and Ethers",
            "Aldehydes and Ketones", "Carboxylic Acids", "Amines", "Biomolecules", "Environmental/Practical Chemistry",
        ),
    )

    fun syllabusBlock(restriction: Boolean): String =
        if (restriction) {
            """
            SYLLABUS RULES (strict):
            - Solve using only concepts, formulas and methods that belong to the official NTA JEE Main syllabus and the official JEE Advanced syllabus (Class 11-12 level).
            - Do NOT use university-level mathematics, graduate-level physics, advanced analysis, or out-of-syllabus chemical theories.
            - Always prefer the simplest valid JEE-level method. Do not use a formula merely because it gives the right number; the method must be suitable for JEE preparation.
            - A difficult JEE Advanced problem is NOT out of syllabus. First look for a valid JEE-level solution.
            - Only if no JEE-level method exists, say so plainly and give the closest syllabus-appropriate approach.
            - Treat the official syllabi as authoritative; the chapter hints below are only a naming aid and are not exhaustive.
            """.trimIndent()
        } else {
            """
            SYLLABUS RULES (relaxed): Prefer JEE-level methods, but you may use other correct standard methods when no simple JEE-level method exists. Say when you do.
            """.trimIndent()
        }

    fun modeBlock(mode: ExamMode): String = when (mode) {
        ExamMode.JEE_MAIN -> """
            EXAM MODE: JEE Main.
            - Prefer efficient calculations, standard formulas and direct conceptual applications.
            - For multiple-choice questions use option elimination when it is quicker and safe.
            - For numerical-value questions use clean numerical techniques and appropriate shortcuts.
        """.trimIndent()

        ExamMode.JEE_ADVANCED -> """
            EXAM MODE: JEE Advanced.
            - Expect deeper conceptual understanding, multi-step reasoning and several concepts combined in one problem.
            - Use more rigorous derivations, but only advanced methods that are still inside the JEE Advanced syllabus.
            - Watch for multiple-correct options, integer-type answers and subtle conditions in the statement.
        """.trimIndent()

        ExamMode.AUTOMATIC -> """
            EXAM MODE: Automatic.
            - Judge the likely level (JEE Main or JEE Advanced) from the difficulty and style of each problem and report it as exam_level.
            - Never claim to know the exact exam, year or source of a question unless the screenshot itself shows it; use "Unknown" if unsure.
        """.trimIndent()
    }

    private fun hintsBlock(): String = buildString {
        append("CHAPTER NAME HINTS (not exhaustive):\n")
        SUBJECT_HINTS.forEach { (subject, chapters) -> append("- ").append(subject).append(": ").append(chapters.joinToString("; ")).append('\n') }
    }.trimEnd()

    /** Shared by Stage 1 and Stage 2. */
    fun core(config: JeeConfig): String = listOf(
        "You are a JEE (Main and Advanced) expert for Physics, Chemistry and Mathematics.",
        modeBlock(config.mode),
        syllabusBlock(config.syllabusRestriction),
        hintsBlock(),
    ).joinToString("\n\n")
}
