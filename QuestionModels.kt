package com.snapgpt.ai

data class BoundingBox(
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double
)

data class AnswerOption(val label: String, val text: String)

data class DetectedQuestion(
    val id: String,
    val questionText: String,
    val subject: String,
    val chapter: String,
    val topic: String,
    val examLevel: String,
    val questionType: String,
    val options: List<AnswerOption>,
    val answer: String,
    val answerType: String,
    val confidence: Double,
    val boundingBox: BoundingBox,
    val needsClarification: Boolean
)

data class AiUsage(val promptTokens: Int = 0, val completionTokens: Int = 0) {
    val totalTokens: Int get() = promptTokens + completionTokens
}

data class ScanResult(
    val questions: List<DetectedQuestion>,
    val usage: AiUsage,
    val rawContent: String
)
