package com.snapgpt.overlay

import android.graphics.Rect
import com.snapgpt.ai.DetectedQuestion
import java.io.File

/** In-process handoff between the capture pipeline and the active overlay service. */
object OverlaySession {
    @Volatile var questions: List<DetectedQuestion> = emptyList()
    @Volatile var screenshot: File? = null
    @Volatile var sourceRegion: Rect? = null
    @Volatile var inputTokens: Int = 0
    @Volatile var outputTokens: Int = 0
}
