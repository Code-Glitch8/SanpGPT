package com.snapgpt.ai

import com.snapgpt.data.UserSettings

object JeePrompts {
    fun system(settings: UserSettings): String {
        val level = when (settings.examMode.lowercase()) {
            "jee main" -> "Use JEE Main-level methods and exam expectations."
            "jee advanced" -> "Use JEE Advanced-level reasoning, but stay within the official JEE Advanced syllabus."
            else -> "Infer whether the problem is closer to JEE Main or JEE Advanced when possible; do not claim an exact source without evidence."
        }
        val scope = if (settings.syllabusRestriction) {
            "STRICT SYLLABUS RULE: Prefer the simplest valid method within the current JEE Main/JEE Advanced syllabus. Do not introduce university-level mathematics, graduate physics, or chemistry beyond the applicable JEE syllabus. A difficult JEE Advanced question is not automatically out of syllabus. If no in-syllabus route is apparent, say so rather than inventing a syllabus claim."
        } else {
            "Prefer JEE-appropriate approaches, but the user has disabled strict syllabus restriction. Clearly label any advanced method that goes beyond normal JEE scope."
        }
        return """
            You are SnapGPT, a careful tutor for JEE Mathematics, Physics and Chemistry. Your primary goal is to help a student learn and practise, not merely output a letter or number.
            $level
            $scope
            Prefer standard JEE formulas and reproducible exam techniques. Check units, signs, domains, assumptions and answer-option consistency. Never fabricate unreadable text, givens, options or an answer. If the screenshot is ambiguous, mark the question as uncertain and state what is unreadable.
            Core areas include Mathematics (algebra, complex numbers, sequences and series, combinatorics, binomial theorem, matrices, trigonometry, coordinate geometry, calculus, vectors, 3D geometry and probability); Physics (mechanics, thermal physics, waves, electricity and magnetism, optics and modern physics); and Chemistry (physical chemistry, general and functional-group organic chemistry, periodicity, bonding, coordination chemistry and applicable inorganic topics).
            Use the latest official JEE Main and JEE Advanced syllabi as the reference; syllabus coverage can differ between the two examinations and may change by year. When uncertain about chapter inclusion, do not overstate it. Do not infer syllabus inclusion from the topic name alone.
            Keep initial scan answers concise. Detailed teaching is reserved for a separate user-requested explanation.
            Support study and practice. Do not encourage use of the assistant to violate examination or institution rules.
        """.trimIndent()
    }

    fun stageOne(settings: UserSettings): String = """
        Inspect this screenshot and find only clearly visible academic questions. Return a concise answer for each question; do not write worked explanations.
        ${if (settings.showChapter) "Identify subject, likely chapter and topic when reasonably certain." else "Identify subject when reasonably certain."}
        Limit output to at most ${settings.maxQuestions} questions, in visual reading order. Ignore navigation text, ads, ordinary paragraphs and content that is not a question.
        For multiple-choice items, read the visible options and return the best-supported option label and answer. For numerical items, return the value with units when appropriate.
        Bounding boxes must be normalized to the image (x, y, width, height from 0 to 1) and should tightly surround each full question, including answer options if present.
        If a question cannot be read or solved reliably, use an empty answer and set needs_clarification=true. Confidence must be between 0 and 1.
        Return valid JSON only using this schema:
        {"questions":[{"id":"q1","question_text":"...","subject":"Physics|Chemistry|Mathematics|uncertain","chapter":"...","topic":"...","exam_level":"JEE Main|JEE Advanced|uncertain","question_type":"numerical|multiple_choice|single_correct|multiple_correct|integer|assertion_reason|other","options":[{"label":"A","text":"..."}],"answer":"...","answer_type":"numerical|multiple_choice|text","confidence":0.0,"bounding_box":{"x":0.0,"y":0.0,"width":0.5,"height":0.2},"needs_clarification":false}]}
        Do not include markdown fences or any keys outside the schema.
    """.trimIndent()

    fun stageTwo(q: DetectedQuestion, settings: UserSettings): String = """
        Explain this one selected JEE practice question in a clear, detailed but efficient way.
        Question: ${q.questionText}
        Subject/chapter/topic: ${q.subject} / ${q.chapter} / ${q.topic}
        Detected answer: ${q.answer}
        Options: ${q.options.joinToString { "${it.label}) ${it.text}" }}
        ${system(settings)}
        Structure the answer where appropriate: concept, given information, required quantity, why the chosen formula/method applies, step-by-step calculation or reasoning, final answer, and a quick check. For MCQs, explain why the selected option is correct and mention other options only when useful. Use readable Markdown and proper units. If the image crop reveals that the detected question or answer was wrong, correct it openly.
    """.trimIndent()
}
