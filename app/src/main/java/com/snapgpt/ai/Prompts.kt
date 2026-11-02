package com.snapgpt.ai

import com.snapgpt.jee.JeeConfig
import com.snapgpt.jee.JeeInstructions

/** Prompt builders. Stage 1 = quick detection + concise answers. Stage 2 = detailed explanation on demand. */
object Prompts {

    // ---------------------------------------------------------------- Stage 1

    fun stage1System(config: JeeConfig, maxQuestions: Int): String = buildString {
        append(JeeInstructions.core(config))
        append("\n\n")
        append(
            """
            TASK (Stage 1 - quick detection and concise answers):
            Look at the screenshot and find every question or problem that is fully or mostly visible, up to $maxQuestions questions (top to bottom; skip partly cut-off ones first).
            For each question: read it exactly, identify its boundaries on screen, classify it, solve it, and return ONLY the concise final answer.

            STRICT RULES:
            - Do NOT write explanations, steps or reasoning in the output. Stage 2 explains later, only if the user asks.
            - Only report questions that really appear in the image. Never invent a question, option or number.
            - If text is blurry, cut off or ambiguous, set "needs_clarification": true and say what is unclear in "note". Do not guess silently.
            - If the screenshot contains no question, return {"questions": []}.
            - Multiple-choice: "answer" is ONLY the option label(s), e.g. "B" or "A, C" (use the labels printed in the image, such as A-D or 1-4). Put the option texts in "options".
            - Numerical / integer: "answer" is the final value with its unit if one is required, e.g. "9.8 m/s²". Keep it under 30 characters.
            - Subjective / other: "answer" is a short final result (an expression, a name, a formula).
            - Use plain text and Unicode (², √, π, ×) in answers. No LaTeX, no Markdown.
            - Set "has_figure": true when the question depends on a diagram, graph or circuit that is not fully described in text.

            BOUNDING BOX (very important):
            - "bounding_box" covers the whole question block including its options.
            - Use NORMALISED coordinates between 0 and 1 relative to THE IMAGE YOU RECEIVED: x and y are the top-left corner as a fraction of image width and height; width and height are fractions too.
            - Origin is the top-left of the image. Never use pixels, percentages or a 0-1000 scale.

            OUTPUT: one JSON object only, no Markdown fences, no text before or after:
            {
              "questions": [
                {
                  "id": "q1",
                  "question_text": "...",
                  "subject": "Physics | Chemistry | Mathematics",
                  "chapter": "...",
                  "topic": "...",
                  "exam_level": "JEE Main | JEE Advanced | Unknown",
                  "question_type": "numerical | integer | mcq | mcq_multiple | true_false | subjective | other",
                  "options": [{"label": "A", "text": "..."}],
                  "answer": "...",
                  "answer_type": "numerical | option | expression | text",
                  "confidence": 0.0,
                  "bounding_box": {"x": 0.1, "y": 0.2, "width": 0.8, "height": 0.2},
                  "has_figure": false,
                  "needs_clarification": false,
                  "note": ""
                }
              ]
            }
            """.trimIndent(),
        )
    }

    fun stage1User(imageWidth: Int, imageHeight: Int, areaScan: Boolean): String = buildString {
        append("Screenshot size: ${imageWidth}x$imageHeight pixels. ")
        if (areaScan) append("This image is a region the user selected, so coordinates are relative to this cropped image. ")
        append("Detect the questions and return the Stage 1 JSON only.")
    }

    // ---------------------------------------------------------------- Stage 2

    fun stage2System(config: JeeConfig): String = buildString {
        append(JeeInstructions.core(config))
        append("\n\n")
        append("TASK (Stage 2 - detailed explanation of ONE question the user tapped).\n")
        append("Solve it independently and carefully. A quick answer from Stage 1 is provided only as a claim to verify: if you find it wrong, say so clearly and give the correct answer.\n\n")
        append("FORMAT (plain text, short headings in capitals, Unicode maths such as ², √, π, ×, →; NO LaTeX, no tables, no code fences):\n")
        if (config.showChapterTopic) append("SUBJECT / CHAPTER / TOPIC: one line.\n")
        append("GIVEN: the data in the question.\n")
        append("TO FIND: what is required.\n")
        append("CONCEPT: the JEE-level idea and why it applies.\n")
        append("FORMULA: the relevant formula(s).\n")
        append("SOLUTION: numbered steps with substitutions and units; keep each step short.\n")
        append("FINAL ANSWER: the result, highlighted on its own line (for multiple-choice give the option label and its value).\n")
        append("Add \"CHECK:\" with a quick verification when it is cheap.\n")
        append("Multiple-choice: also say briefly why the correct option is right, and why the others are wrong when that is useful.\n")
        append("Numerical: show substitutions and keep units consistent.\n")
        if (config.showAlternativeMethods) append("After the final answer add \"ALTERNATIVE METHOD:\" with one shorter or different JEE-level approach if one exists.\n")
        else append("Give only one method.\n")
        append("Be exact and concise. Do not repeat the question.")
    }

    fun stage2User(q: DetectedQuestion, imageAttached: Boolean): String = buildString {
        append("Explain this question.\n")
        append("Subject: ").append(q.subject).append(" | Chapter: ").append(q.chapter).append(" | Topic: ").append(q.topic).append('\n')
        append("Type: ").append(q.questionType).append(" | Level guess: ").append(q.examLevel).append('\n')
        append("Question (as read by Stage 1):\n").append(q.questionText.ifBlank { "(unreadable - use the image)" }).append('\n')
        if (q.options.isNotEmpty()) {
            append("Options:\n")
            q.options.forEach { append(it.label).append(") ").append(it.text).append('\n') }
        }
        append("Stage 1 quick answer (verify it): ").append(q.answer.ifBlank { "(none)" }).append('\n')
        if (q.needsClarification && q.note.isNotBlank()) append("Stage 1 uncertainty: ").append(q.note).append('\n')
        append(if (imageAttached) "The cropped image of this question is attached; trust it over the text when they differ." else "No image is attached; rely on the text above.")
    }

    /** Decide whether Stage 2 should re-send the cropped image (costs about 2048 input tokens). */
    fun needsImageForExplanation(q: DetectedQuestion, alwaysAttach: Boolean): Boolean =
        alwaysAttach || q.hasFigure || q.needsClarification || q.confidence < 0.75f || q.questionText.isBlank()

    // ---------------------------------------------------------------- Follow-ups

    fun followUpSystem(config: JeeConfig): String =
        stage2System(config) + "\n\nFOLLOW-UP: the user now asks a follow-up about the same question. Answer only what is asked, briefly, with the same JEE-level rules. You do not need to repeat the full solution format."
}
