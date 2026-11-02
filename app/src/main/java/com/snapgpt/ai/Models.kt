package com.snapgpt.ai

/** Default Groq endpoint, vision model and conservative request limits (see https://console.groq.com/docs/vision). */
object AiDefaults {
    const val BASE_URL = "https://api.groq.com/openai/v1"
    const val CHAT_URL = "$BASE_URL/chat/completions"
    const val MODELS_URL = "$BASE_URL/models"

    /** Default vision model. SnapGPT never switches models silently. */
    const val DEFAULT_MODEL = "qwen/qwen3.8-27b"

    /** Groq documents 3 images per request and ~2048 input tokens per image for this model. */
    const val MAX_IMAGES_PER_REQUEST = 3
    const val IMAGE_TOKENS_ESTIMATE = 2048

    /** Groq documents 20 MB as the maximum request size with an image; we refuse to send above 18 MB. */
    const val MAX_REQUEST_BYTES = 20_000_000
    const val REQUEST_BUDGET_BYTES = 18_000_000
    const val MAX_PIXELS = 33_177_600L

    // Reasoning tokens count towards the completion budget, so leave headroom for the actual answer.
    const val STAGE1_MAX_COMPLETION_TOKENS = 8192
    const val STAGE2_MAX_COMPLETION_TOKENS = 6144
    const val FOLLOWUP_MAX_COMPLETION_TOKENS = 4096

    /** Return only the final answer, not the model's reasoning. */
    const val REASONING_FORMAT = "hidden"

    const val CONNECT_TIMEOUT_MS = 15_000
    const val READ_TIMEOUT_MS = 120_000
}

/** Normalised (0..1) rectangle relative to the image that was sent to the AI. Origin is the top-left corner. */
data class BoundingBox(val x: Float, val y: Float, val width: Float, val height: Float) {
    val right: Float get() = x + width
    val bottom: Float get() = y + height
}

data class ChoiceOption(val label: String, val text: String)

/** One question detected by Stage 1 together with its concise answer. */
data class DetectedQuestion(
    val id: String,
    val questionText: String,
    val subject: String,
    val chapter: String,
    val topic: String,
    val examLevel: String,
    val questionType: String,
    val answer: String,
    val answerType: String,
    val confidence: Float,
    val boundingBox: BoundingBox?,
    val needsClarification: Boolean,
    val options: List<ChoiceOption> = emptyList(),
    val hasFigure: Boolean = false,
    val note: String = "",
) {
    val isMultipleChoice: Boolean get() = questionType == QuestionTypes.MCQ || questionType == QuestionTypes.MCQ_MULTIPLE
}

object QuestionTypes {
    const val NUMERICAL = "numerical"
    const val INTEGER = "integer"
    const val MCQ = "mcq"
    const val MCQ_MULTIPLE = "mcq_multiple"
    const val TRUE_FALSE = "true_false"
    const val SUBJECTIVE = "subjective"
    const val OTHER = "other"
}

/** Token usage as reported by the API ("actual") for one request. */
data class TokenUsage(val promptTokens: Int, val completionTokens: Int, val totalTokens: Int) {
    operator fun plus(o: TokenUsage) = TokenUsage(promptTokens + o.promptTokens, completionTokens + o.completionTokens, totalTokens + o.totalTokens)

    companion object {
        val ZERO = TokenUsage(0, 0, 0)
    }
}

enum class ScanType { FULL_SCREEN, AREA }
