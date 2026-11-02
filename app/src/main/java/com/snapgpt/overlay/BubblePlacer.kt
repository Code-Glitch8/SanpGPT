package com.snapgpt.overlay

/** A bubble to place. [questionRect] is the question's on-screen rectangle (px); width/height are the bubble's size (px). */
data class BubbleRequest(val id: String, val questionRect: FRect?, val width: Float, val height: Float)

data class PlacedBubble(val id: String, val rect: FRect)

/**
 * Chooses a screen position for every answer bubble.
 *
 * Goals, in order: stay fully inside the usable screen; do not overlap other bubbles; do not cover any question's text;
 * sit beside the question the bubble answers (right side first, then left, above, below).
 */
object BubblePlacer {

    fun place(requests: List<BubbleRequest>, usable: FRect, margin: Float): List<PlacedBubble> {
        val questionRects = requests.mapNotNull { it.questionRect }
        val placed = mutableListOf<PlacedBubble>()
        var fallbackIndex = 0

        // Top-to-bottom keeps stacked bubbles in reading order.
        val ordered = requests.sortedBy { it.questionRect?.top ?: Float.MAX_VALUE }
        for (req in ordered) {
            val q = req.questionRect
            val candidates = if (q != null) candidatesFor(req, q, usable, margin) else {
                // No usable position: stack along the right edge from the top.
                val y = usable.top + margin + fallbackIndex * (req.height + margin)
                fallbackIndex++
                listOf(FRect.ofSize(usable.right - req.width - margin, y, req.width, req.height))
            }

            var best: FRect? = null
            var bestScore = Float.MAX_VALUE
            for ((order, raw) in candidates.withIndex()) {
                val c = clamp(raw, usable, margin)
                var score = order * 1.5f
                score += placed.sumOf { it.rect.intersectionArea(c).toDouble() }.toFloat() * 1000f
                for (qr in questionRects) score += qr.intersectionArea(c) * 40f
                // Distance from the preferred position (the clamp may have moved it).
                score += Math.abs(c.left - raw.left) * 0.2f + Math.abs(c.top - raw.top) * 0.2f
                if (score < bestScore) { bestScore = score; best = c }
            }
            placed += PlacedBubble(req.id, best ?: clamp(candidates.first(), usable, margin))
        }
        return requests.map { r -> placed.first { it.id == r.id } }
    }

    private fun candidatesFor(req: BubbleRequest, q: FRect, usable: FRect, margin: Float): List<FRect> {
        val w = req.width
        val h = req.height
        val step = h + margin
        val bases = listOf(
            FRect.ofSize(q.right + margin, q.top, w, h),                  // right of the question, aligned to its top
            FRect.ofSize(q.right + margin, q.top + (q.height - h) / 2f, w, h), // right, centred
            FRect.ofSize(q.left - margin - w, q.top, w, h),               // left of the question
            FRect.ofSize(q.right - w, q.top - h - margin, w, h),          // above, right aligned
            FRect.ofSize(q.right - w, q.bottom + margin, w, h),           // below, right aligned
            FRect.ofSize(usable.right - w - margin, q.top, w, h),         // screen right edge (may overlap wide questions)
        )
        val out = ArrayList<FRect>(bases.size * 5)
        for (b in bases) {
            out += b
            for (k in 1..3) { out += b.translate(0f, k * step); out += b.translate(0f, -k * step) }
        }
        return out
    }

    private fun clamp(r: FRect, usable: FRect, margin: Float): FRect {
        val maxLeft = maxOf(usable.left + margin, usable.right - margin - r.width)
        val maxTop = maxOf(usable.top + margin, usable.bottom - margin - r.height)
        val left = r.left.coerceIn(usable.left + margin, maxLeft)
        val top = r.top.coerceIn(usable.top + margin, maxTop)
        return FRect.ofSize(left, top, r.width, r.height)
    }
}
