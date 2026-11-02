package com.snapgpt.history

import android.graphics.Bitmap
import com.snapgpt.ai.ChatMessage
import com.snapgpt.ai.DetectedQuestion
import com.snapgpt.ai.ScanType
import com.snapgpt.ai.TokenUsage
import com.snapgpt.capture.BitmapCodec
import com.snapgpt.overlay.FRect

/** One detected question while its session is on screen (in memory only; persisted if history is enabled). */
class LiveQuestion(val question: DetectedQuestion) {
    var rowId: Long? = null
    var explanation: String? = null
    var screenRect: FRect? = null

    /** Follow-up conversation after the explanation: alternating user / assistant messages. */
    val followUps = mutableListOf<ChatMessage>()
}

/**
 * The current scan. [screenshot] stays in memory only for this session so Stage 2 can crop the question without
 * asking the screen again; it is recycled when a new scan replaces the session.
 */
class LiveSession(
    val createdAt: Long,
    val scanType: ScanType,
    /** Part of the physical screen (px) that the analysed image represents. */
    val region: FRect,
    var screenshot: Bitmap?,
) {
    var dbId: Long? = null
    var usage: TokenUsage = TokenUsage.ZERO
    val questions = mutableListOf<LiveQuestion>()

    fun find(id: String): LiveQuestion? = questions.firstOrNull { it.question.id == id }

    fun cropFor(q: DetectedQuestion): Bitmap? {
        val shot = screenshot ?: return null
        val box = q.boundingBox ?: return null
        return BitmapCodec.cropNormalized(shot, box, 0.02f)
    }

    fun release() {
        screenshot?.let { if (!it.isRecycled) it.recycle() }
        screenshot = null
    }
}
