package com.snapgpt.ai

import android.graphics.Bitmap
import com.snapgpt.ai.failover.AccountTester
import com.snapgpt.ai.failover.FailoverManager
import com.snapgpt.ai.providers.ApiAccount
import com.snapgpt.capture.BitmapCodec
import com.snapgpt.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android-facing wrapper around the pure [AiPipeline]: compresses bitmaps, reads the current settings and forwards status
 * messages. All provider choice, failover and verification logic lives in the pipeline and the failover manager.
 */
class AiService(
    private val pipeline: AiPipeline,
    private val failover: FailoverManager,
    val tester: AccountTester,
    private val settings: SettingsRepository,
) {
    fun clearCache() = pipeline.clearCache()

    /** The account that would receive a screenshot right now (shown on the confirmation card), or null if none can. */
    suspend fun screenshotDestination(): ApiAccount? {
        val s = settings.current().toPipelineSettings()
        return failover.preview(needsImage = true, config = s.failover)
    }

    private suspend fun encode(bitmap: Bitmap): EncodedImage {
        val s = settings.current()
        return withContext(Dispatchers.Default) {
            val e = BitmapCodec.encode(bitmap, s.imageQuality)
            EncodedImage(e.dataUrl(), e.width, e.height, e.sha256())
        }
    }

    suspend fun analyze(bitmap: Bitmap, scanType: ScanType, onStatus: (String) -> Unit): AnalysisOutcome {
        val s = settings.current().toPipelineSettings()
        return pipeline.analyze(encode(bitmap), scanType, s, onStatus)
    }

    /** [crop] is only encoded if the pipeline decides the image is worth sending to the chosen account. */
    suspend fun explain(q: DetectedQuestion, crop: Bitmap?, detailed: Boolean, onStatus: (String) -> Unit): TextOutcome {
        val s = settings.current()
        return pipeline.explain(q, crop?.let { { encode(it) } }, detailed, s.toPipelineSettings(), onStatus)
    }

    suspend fun followUp(q: DetectedQuestion, explanation: String, history: List<ChatMessage>, ask: String, crop: Bitmap?, onStatus: (String) -> Unit): TextOutcome {
        val s = settings.current()
        return pipeline.followUp(q, explanation, history, ask, crop?.let { { encode(it) } }, s.toPipelineSettings(), onStatus)
    }
}
