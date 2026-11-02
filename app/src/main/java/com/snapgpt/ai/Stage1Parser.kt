package com.snapgpt.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Pulls the JSON payload out of a model reply (reasoning blocks, Markdown fences, chatter around it). */
object JsonExtract {
    private val THINK_BLOCK = Regex("<think>[\\s\\S]*?</think>", RegexOption.IGNORE_CASE)
    private val FENCE = Regex("```(?:json|JSON)?\\s*([\\s\\S]*?)```")

    fun stripThinking(raw: String): String {
        var t = THINK_BLOCK.replace(raw, "")
        val open = t.indexOf("<think>", ignoreCase = true)
        if (open >= 0) t = t.substring(0, open) // unterminated reasoning block: drop the rest
        return t.trim()
    }

    /** Returns the first balanced JSON object or array in [raw], or null. */
    fun extract(raw: String): String? {
        var t = stripThinking(raw)
        FENCE.find(t)?.let { t = it.groupValues[1].trim() }
        val start = t.indexOfFirst { it == '{' || it == '[' }
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until t.length) {
            val c = t[i]
            if (inString) {
                if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') inString = false
                continue
            }
            when (c) {
                '"' -> inString = true
                '{', '[' -> depth++
                '}', ']' -> {
                    depth--
                    if (depth == 0) return t.substring(start, i + 1)
                }
            }
        }
        return null
    }
}

data class Stage1Result(
    val questions: List<DetectedQuestion>,
    val dropped: Int,
    val warnings: List<String>,
)

/** Converts the model's box notation into 0..1 coordinates relative to the image. */
object BoxNormalizer {
    /**
     * [a],[b],[c],[d] are x,y,width,height (or x1,y1,x2,y2 when [corners] is true). The prompt asks for 0..1, but vision
     * models often answer in percent, in a 0-1000 grid (Qwen style) or in pixels, so the scale is detected.
     */
    fun normalize(a: Float, b: Float, c: Float, d: Float, corners: Boolean, imageWidth: Int, imageHeight: Int): BoundingBox? {
        if (listOf(a, b, c, d).any { it.isNaN() || it.isInfinite() || it < 0f }) return null
        var x = a
        var y = b
        var w = if (corners) c - a else c
        var h = if (corners) d - b else d
        if (w <= 0f || h <= 0f) return null
        val maxEdge = maxOf(x + w, y + h)
        // Up to 1.5 is treated as "normalised but slightly overflowing" (clamped below), not as a percentage.
        // A 0-1000 grid and pixel values that all stay under 1000 are indistinguishable; the 0-1000 grid is assumed.
        val (sx, sy) = when {
            maxEdge <= 1.5f -> 1f to 1f
            maxEdge <= 100.5f -> 100f to 100f
            maxEdge <= 1000.5f -> 1000f to 1000f
            else -> imageWidth.coerceAtLeast(1).toFloat() to imageHeight.coerceAtLeast(1).toFloat()
        }
        x /= sx; w /= sx; y /= sy; h /= sy
        x = x.coerceIn(0f, 1f)
        y = y.coerceIn(0f, 1f)
        w = w.coerceAtMost(1f - x)
        h = h.coerceAtMost(1f - y)
        if (w < 0.005f || h < 0.005f) return null
        return BoundingBox(x, y, w, h)
    }
}

/** Validates the Stage 1 JSON. Never invents questions or answers. */
object Stage1Parser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(raw: String, maxQuestions: Int, imageWidth: Int, imageHeight: Int): Stage1Result {
        val payload = JsonExtract.extract(raw)
            ?: throw AiException(AiErrorCode.INVALID_RESPONSE, "The API did not return the expected JSON. Please scan again.")
        val root: JsonElement = try {
            json.parseToJsonElement(payload)
        } catch (e: Exception) {
            throw AiException(AiErrorCode.INVALID_RESPONSE, "The API returned malformed JSON. Please scan again.")
        }
        val array: JsonArray = when {
            root is JsonArray -> root
            root is JsonObject && root["questions"] is JsonArray -> root["questions"] as JsonArray
            root is JsonObject && root["questions"] == null && root["question_text"] != null -> JsonArray(listOf(root))
            else -> throw AiException(AiErrorCode.INVALID_RESPONSE, "The API reply had no \"questions\" list. Please scan again.")
        }

        val warnings = mutableListOf<String>()
        val out = mutableListOf<DetectedQuestion>()
        val usedIds = HashSet<String>()
        var dropped = 0

        for ((index, el) in array.withIndex()) {
            val obj = el as? JsonObject
            if (obj == null) { dropped++; continue }
            val text = str(obj, "question_text").ifBlank { str(obj, "question") }.trim()
            val answerRaw = str(obj, "answer").trim()
            if (text.isBlank() && answerRaw.isBlank()) { dropped++; continue }

            var needs = bool(obj, "needs_clarification")
            if (answerRaw.isBlank() && !needs) {
                needs = true
                warnings += "Question ${index + 1} came back without an answer and was marked as needing clarification."
            }

            var id = str(obj, "id").trim().ifBlank { "q${index + 1}" }
            if (!usedIds.add(id)) {
                id = "q${index + 1}"
                var n = index + 1
                while (!usedIds.add(id)) { n++; id = "q$n" }
            }

            val box = parseBox(obj["bounding_box"] ?: obj["bbox"], imageWidth, imageHeight)
            if (box == null) warnings += "Question $id has no usable position; its answer is placed at the screen edge."

            out += DetectedQuestion(
                id = id,
                questionText = text,
                subject = normalizeSubject(str(obj, "subject")),
                chapter = str(obj, "chapter").trim(),
                topic = str(obj, "topic").trim(),
                examLevel = normalizeLevel(str(obj, "exam_level")),
                questionType = normalizeType(str(obj, "question_type"), obj["options"] != null),
                answer = answerRaw,
                answerType = str(obj, "answer_type").trim().lowercase(),
                confidence = (num(obj, "confidence") ?: 0.5f).coerceIn(0f, 1f),
                boundingBox = box,
                needsClarification = needs,
                options = parseOptions(obj["options"]),
                hasFigure = bool(obj, "has_figure"),
                note = str(obj, "note").trim(),
            )
        }

        val limited = if (maxQuestions > 0 && out.size > maxQuestions) {
            warnings += "Showing the first $maxQuestions of ${out.size} detected questions (Settings > Scanning)."
            out.take(maxQuestions)
        } else out
        return Stage1Result(limited, dropped, warnings)
    }

    // ---- helpers

    private fun prim(el: JsonElement?): JsonPrimitive? = (el as? JsonPrimitive)?.takeIf { it !is JsonNull }

    private fun str(o: JsonObject, key: String): String = prim(o[key])?.contentOrNull ?: ""

    private fun num(o: JsonObject, key: String): Float? = prim(o[key])?.contentOrNull?.trim()?.toFloatOrNull()

    private fun bool(o: JsonObject, key: String): Boolean {
        val p = prim(o[key]) ?: return false
        return p.contentOrNull?.trim()?.lowercase() == "true"
    }

    private fun floatOf(el: JsonElement?): Float? = prim(el)?.contentOrNull?.trim()?.toFloatOrNull()

    private fun parseBox(el: JsonElement?, w: Int, h: Int): BoundingBox? {
        when (el) {
            is JsonObject -> {
                val x = floatOf(el["x"] ?: el["left"])
                val y = floatOf(el["y"] ?: el["top"])
                val bw = floatOf(el["width"] ?: el["w"])
                val bh = floatOf(el["height"] ?: el["h"])
                if (x != null && y != null && bw != null && bh != null) return BoxNormalizer.normalize(x, y, bw, bh, false, w, h)
                val x1 = floatOf(el["x1"] ?: el["left"])
                val y1 = floatOf(el["y1"] ?: el["top"])
                val x2 = floatOf(el["x2"] ?: el["right"])
                val y2 = floatOf(el["y2"] ?: el["bottom"])
                if (x1 != null && y1 != null && x2 != null && y2 != null) return BoxNormalizer.normalize(x1, y1, x2, y2, true, w, h)
            }
            is JsonArray -> {
                // [x1, y1, x2, y2] corners (common for vision models)
                val v = el.map { floatOf(it) }
                if (v.size == 4 && v.all { it != null }) return BoxNormalizer.normalize(v[0]!!, v[1]!!, v[2]!!, v[3]!!, true, w, h)
            }
            else -> {}
        }
        return null
    }

    private fun parseOptions(el: JsonElement?): List<ChoiceOption> {
        val arr = el as? JsonArray ?: return emptyList()
        val out = mutableListOf<ChoiceOption>()
        for ((i, item) in arr.withIndex()) {
            when (item) {
                is JsonObject -> {
                    val label = str(item, "label").trim().ifBlank { ('A' + i).toString() }
                    val text = str(item, "text").trim().ifBlank { str(item, "value").trim() }
                    out += ChoiceOption(label, text)
                }
                is JsonPrimitive -> {
                    val s = item.contentOrNull?.trim().orEmpty()
                    val m = Regex("^\\(?([A-Za-z1-9])[).:]\\s*(.*)$").find(s)
                    out += if (m != null) ChoiceOption(m.groupValues[1].uppercase(), m.groupValues[2]) else ChoiceOption(('A' + i).toString(), s)
                }
                else -> {}
            }
            if (out.size >= 10) break
        }
        return out
    }

    internal fun normalizeSubject(s: String): String {
        val l = s.lowercase()
        return when {
            l.contains("math") -> "Mathematics"
            l.contains("phys") -> "Physics"
            l.contains("chem") -> "Chemistry"
            s.isBlank() -> "Unknown"
            else -> s.trim()
        }
    }

    internal fun normalizeLevel(s: String): String {
        val l = s.lowercase()
        return when {
            l.contains("adv") -> "JEE Advanced"
            l.contains("main") -> "JEE Main"
            else -> "Unknown"
        }
    }

    internal fun normalizeType(s: String, hasOptions: Boolean): String {
        val l = s.lowercase().replace(' ', '_').replace('-', '_')
        return when {
            l.contains("multiple") && (l.contains("correct") || l.contains("mcq")) || l == "mcq_multiple" || l == "multi_correct" -> QuestionTypes.MCQ_MULTIPLE
            l.contains("mcq") || l.contains("choice") || l.contains("single_correct") || l == "option" -> QuestionTypes.MCQ
            l.contains("integer") -> QuestionTypes.INTEGER
            l.contains("numer") -> QuestionTypes.NUMERICAL
            l.contains("true") -> QuestionTypes.TRUE_FALSE
            l.contains("subjective") -> QuestionTypes.SUBJECTIVE
            l.isBlank() && hasOptions -> QuestionTypes.MCQ
            l.isBlank() -> QuestionTypes.OTHER
            else -> QuestionTypes.OTHER
        }
    }
}
