package com.snapgpt.capture

import com.snapgpt.ai.AiDefaults

/** Screenshot compression presets. Image token cost is constant per image, so size mostly affects speed and legibility. */
enum class ImageQuality(val key: String, val label: String, val maxLongSide: Int, val jpegQuality: Int, val minJpegQuality: Int) {
    LOW("low", "Low (fastest)", 1600, 70, 55),
    BALANCED("balanced", "Balanced", 2400, 85, 70),
    HIGH("high", "High (best for small text)", 3200, 92, 80);

    companion object {
        fun fromKey(key: String?): ImageQuality = entries.firstOrNull { it.key == key } ?: BALANCED
    }
}

object ImageSizing {
    /** Never upscales. Keeps the aspect ratio, caps the long side and SnapGPT's 33-megapixel safety cap. */
    fun targetSize(width: Int, height: Int, maxLongSide: Int): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return 1 to 1
        var scale = 1.0
        val longSide = maxOf(width, height)
        if (longSide > maxLongSide) scale = maxLongSide.toDouble() / longSide
        val pixels = width.toDouble() * height * scale * scale
        if (pixels > AiDefaults.MAX_PIXELS) scale *= Math.sqrt(AiDefaults.MAX_PIXELS / pixels)
        return maxOf(1, Math.round(width * scale).toInt()) to maxOf(1, Math.round(height * scale).toInt())
    }

    fun base64Length(byteCount: Int): Int = ((byteCount + 2) / 3) * 4

    /** Largest image (in bytes) that fits the request budget once base64-encoded, leaving room for the prompt. */
    fun maxImageBytes(): Int = ((AiDefaults.REQUEST_BUDGET_BYTES - 200_000) / 4) * 3
}
