package com.snapgpt.overlay

import com.snapgpt.ai.BoundingBox

/** Platform-independent rectangle (so placement logic is unit-testable without Android). */
data class FRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    fun intersectionArea(o: FRect): Float {
        val w = minOf(right, o.right) - maxOf(left, o.left)
        val h = minOf(bottom, o.bottom) - maxOf(top, o.top)
        return if (w <= 0f || h <= 0f) 0f else w * h
    }

    fun translate(dx: Float, dy: Float) = FRect(left + dx, top + dy, right + dx, bottom + dy)

    companion object {
        fun ofSize(left: Float, top: Float, width: Float, height: Float) = FRect(left, top, left + width, top + height)
    }
}

/** Converts normalised AI coordinates into physical screen pixels. */
object CoordinateMapper {
    /**
     * [region] is the part of the physical screen the analysed image represents: the whole screen for a full scan, or the
     * user's selected rectangle for an area scan. The box is relative to that image.
     */
    fun toScreen(box: BoundingBox, region: FRect): FRect = FRect(
        region.left + box.x * region.width,
        region.top + box.y * region.height,
        region.left + box.right * region.width,
        region.top + box.bottom * region.height,
    )
}
