package com.snapgpt.overlay

import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.snapgpt.util.Screen
import kotlin.math.hypot

/**
 * Small floating answer bubble: round for short answers ("B", "42"), pill-shaped for longer ones ("9.8 m/s²").
 * Tap = explain (Stage 2). The little x badge = dismiss this answer.
 */
class AnswerBubbleView(context: Context, private val palette: Palette) : View(context) {
    var text: String = ""
    var round: Boolean = true
    var uncertain: Boolean = false
    var onTap: (() -> Unit)? = null
    var onDismiss: (() -> Unit)? = null

    private val density = context.resources.displayMetrics.density
    private val overhang = 9f * density
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.primary }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = palette.warning; strokeWidth = 2.5f * density }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.onPrimary; textSize = 15f * density; isFakeBoldText = true; textAlign = Paint.Align.CENTER }
    private val badgeFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.surface }
    private val badgeX = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.onSurface; strokeWidth = 1.6f * density; strokeCap = Paint.Cap.ROUND }
    private val badgeRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = palette.outline; strokeWidth = 1f * density }
    private var pulse: ObjectAnimator? = null
    private var pressedDown = false

    init { setLayerType(LAYER_TYPE_SOFTWARE, null) }

    private fun bodyW(): Float {
        val h = bodyH()
        return if (round) h else maxOf(h, textPaint.measureText(text) + 28f * density)
    }

    private fun bodyH(): Float = if (round) 42f * density else 38f * density

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension((bodyW() + overhang).toInt() + 1, (bodyH() + overhang).toInt() + 1)
    }

    private fun body() = RectF(0f, overhang, bodyW(), overhang + bodyH())

    override fun onDraw(c: Canvas) {
        val b = body()
        bodyPaint.setShadowLayer(4f * density, 0f, 2f * density, 0x55000000)
        val r = b.height() / 2f
        c.save()
        if (pressedDown) c.scale(0.95f, 0.95f, b.centerX(), b.centerY())
        c.drawRoundRect(b, r, r, bodyPaint)
        if (uncertain) c.drawRoundRect(b, r, r, ring)
        c.drawText(text, b.centerX(), b.centerY() - (textPaint.ascent() + textPaint.descent()) / 2f, textPaint)
        c.restore()
        // dismiss badge on the top-right corner
        val br = 8f * density
        c.drawCircle(b.right, b.top, br, badgeFill)
        c.drawCircle(b.right, b.top, br, badgeRing)
        val d = 3f * density
        c.drawLine(b.right - d, b.top - d, b.right + d, b.top + d, badgeX)
        c.drawLine(b.right - d, b.top + d, b.right + d, b.top - d, badgeX)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val b = body()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { pressedDown = true; invalidate(); return true }
            MotionEvent.ACTION_UP -> {
                pressedDown = false
                invalidate()
                if (hypot(e.x - b.right, e.y - b.top) <= 14f * density) onDismiss?.invoke()
                else if (e.x in 0f..width.toFloat() && e.y in 0f..height.toFloat()) { performClick(); onTap?.invoke() }
                return true
            }
            MotionEvent.ACTION_CANCEL -> { pressedDown = false; invalidate(); return true }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    /** Gentle pulsing while the Stage 2 explanation is being generated. */
    fun setLoading(loading: Boolean) {
        pulse?.cancel()
        pulse = null
        if (loading) {
            pulse = ObjectAnimator.ofFloat(this, ALPHA, 1f, 0.5f).apply {
                duration = 600
                repeatCount = ObjectAnimator.INFINITE
                repeatMode = ObjectAnimator.REVERSE
                start()
            }
        } else alpha = 1f
    }

    override fun onDetachedFromWindow() {
        pulse?.cancel()
        super.onDetachedFromWindow()
    }
}

data class AnswerItem(val id: String, val text: String, val uncertain: Boolean, val questionRect: FRect?)

/** One tiny window per answer bubble, so taps anywhere else pass straight through to the app underneath. */
class AnswerLayer(private val ctx: Context, private val wm: WindowManager) {
    interface Listener {
        fun onTap(id: String)
        fun onDismiss(id: String)
    }

    private class Entry(val view: AnswerBubbleView, val lp: WindowManager.LayoutParams)

    private val entries = LinkedHashMap<String, Entry>()
    val ids: List<String> get() = entries.keys.toList()
    val isEmpty: Boolean get() = entries.isEmpty()

    fun show(items: List<AnswerItem>, palette: Palette, listener: Listener) {
        clear()
        val views = items.map { item ->
            AnswerBubbleView(ctx, palette).also { v ->
                v.text = item.text
                v.round = AnswerFormatter.isRound(item.text)
                v.uncertain = item.uncertain
                v.onTap = { listener.onTap(item.id) }
                v.onDismiss = { listener.onDismiss(item.id) }
                v.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
            }
        }
        val usable = Screen.usableRect(ctx)
        val requests = items.mapIndexed { i, it -> BubbleRequest(it.id, it.questionRect, views[i].measuredWidth.toFloat(), views[i].measuredHeight.toFloat()) }
        val placed = BubblePlacer.place(requests, usable, Screen.dp(ctx, 6f)).associateBy { it.id }
        items.forEachIndexed { i, item ->
            val v = views[i]
            val rect = placed[item.id]?.rect ?: return@forEachIndexed
            val lp = OverlayWindows.params(v.measuredWidth, v.measuredHeight)
            lp.x = rect.left.toInt()
            lp.y = rect.top.toInt()
            v.alpha = 0f
            if (runCatching { wm.addView(v, lp) }.isSuccess) {
                entries[item.id] = Entry(v, lp)
                v.animate().alpha(1f).setDuration(180).start()
            }
        }
    }

    fun remove(id: String) {
        entries.remove(id)?.let { runCatching { wm.removeView(it.view) } }
    }

    fun clear() {
        entries.values.forEach { runCatching { wm.removeView(it.view) } }
        entries.clear()
    }

    fun setLoading(id: String, loading: Boolean) { entries[id]?.view?.setLoading(loading) }

    fun setCaptureHidden(hidden: Boolean) {
        entries.values.forEach { it.view.visibility = if (hidden) View.INVISIBLE else View.VISIBLE }
    }
}
