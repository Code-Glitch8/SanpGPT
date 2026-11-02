package com.snapgpt.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import com.snapgpt.settings.AppSettings
import com.snapgpt.util.Screen
import kotlin.math.hypot

/** The round SnapGPT logo with a soft shadow and an optional "working" spinner arc. */
class BubbleView(context: Context, private val padPx: Int) : View(context) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val star = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFFFFFFFF.toInt()
        strokeCap = Paint.Cap.ROUND
    }
    private val path = Path()
    private var angle = 0f
    private var spinner: ValueAnimator? = null

    var busy: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (value) startSpin() else stopSpin()
            invalidate()
        }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null) // needed for the shadow layer
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width, height) / 2f - padPx
        fill.shader = LinearGradient(cx - r, cy - r, cx + r, cy + r, 0xFF4F46E5.toInt(), 0xFF9333EA.toInt(), Shader.TileMode.CLAMP)
        fill.setShadowLayer(padPx * 0.9f, 0f, padPx * 0.3f, 0x66000000)
        canvas.drawCircle(cx, cy, r, fill)

        val s = r * 0.55f
        val w = s * 0.24f
        path.reset()
        path.moveTo(cx, cy - s); path.lineTo(cx + w, cy - w); path.lineTo(cx + s, cy); path.lineTo(cx + w, cy + w)
        path.lineTo(cx, cy + s); path.lineTo(cx - w, cy + w); path.lineTo(cx - s, cy); path.lineTo(cx - w, cy - w)
        path.close()
        canvas.drawPath(path, star)

        if (busy) {
            arc.strokeWidth = r * 0.09f
            val ar = r * 0.82f
            canvas.drawArc(cx - ar, cy - ar, cx + ar, cy + ar, angle, 100f, false, arc)
        }
    }

    private fun startSpin() {
        spinner?.cancel()
        spinner = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 900
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { angle = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun stopSpin() {
        spinner?.cancel()
        spinner = null
    }

    override fun onDetachedFromWindow() {
        stopSpin()
        super.onDetachedFromWindow()
    }
}

/**
 * Owns the draggable circle window. Touching/dragging: 100% opacity and a slight scale-up. Released: back to the idle
 * opacity (default about 30%). Position is remembered as fractions so it survives rotation; optional edge snapping.
 */
class FloatingBubble(
    private val ctx: Context,
    private val wm: WindowManager,
    private val callbacks: Callbacks,
) {
    interface Callbacks {
        fun onTap()
        fun onPositionSaved(fx: Float, fy: Float)
    }

    private var view: BubbleView? = null
    private var lp: WindowManager.LayoutParams? = null
    private var settings = AppSettings()
    private var holdEmphasis = false
    private var fx = 1f
    private var fy = 0.45f
    private val pad = Screen.dpI(ctx, 6f)
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop * 1.5f

    private var downX = 0f
    private var downY = 0f
    private var startX = 0
    private var startY = 0
    private var dragging = false

    val isShown: Boolean get() = view != null

    fun show(s: AppSettings) {
        settings = s
        if (view != null) { applyAppearance(); return }
        if (s.lastPosX >= 0f && s.lastPosY >= 0f) { fx = s.lastPosX; fy = s.lastPosY } else { fx = s.defaultPosition.fx; fy = s.defaultPosition.fy }
        val size = sizePx()
        val params = OverlayWindows.params(size, size)
        val v = BubbleView(ctx, pad)
        v.alpha = idleAlpha()
        v.setOnTouchListener(touch)
        lp = params
        applyPosition()
        runCatching { wm.addView(v, params) }.onSuccess { view = v }
    }

    fun hide() {
        view?.let { runCatching { wm.removeView(it) } }
        view = null
    }

    fun update(s: AppSettings) {
        settings = s
        if (view != null) applyAppearance()
    }

    /** Call after rotation or a display change. */
    fun reposition() {
        if (view != null) { applyPosition(); push() }
    }

    fun setBusy(busy: Boolean) { view?.busy = busy }

    fun setHold(hold: Boolean) {
        holdEmphasis = hold
        emphasize(hold)
    }

    fun setCaptureHidden(hidden: Boolean) { view?.visibility = if (hidden) View.INVISIBLE else View.VISIBLE }

    /** Screen rectangle of the visible circle (for placing the menu next to it). */
    fun bounds(): FRect {
        val p = lp ?: return FRect(0f, 0f, 0f, 0f)
        val size = p.width
        return FRect.ofSize((p.x + pad).toFloat(), (p.y + pad).toFloat(), (size - 2 * pad).toFloat(), (size - 2 * pad).toFloat())
    }

    private fun sizePx() = Screen.dpI(ctx, settings.circleSizeDp.toFloat()) + 2 * pad
    private fun idleAlpha() = settings.idleOpacityPercent / 100f

    private fun applyAppearance() {
        val p = lp ?: return
        val size = sizePx()
        p.width = size
        p.height = size
        applyPosition()
        push()
        if (!holdEmphasis && !dragging) view?.alpha = idleAlpha()
    }

    private fun minX(u: FRect) = (u.left - pad).toInt()
    private fun maxX(u: FRect, size: Int) = maxOf(minX(u), (u.right - size + pad).toInt())
    private fun minY(u: FRect) = (u.top - pad).toInt()
    private fun maxY(u: FRect, size: Int) = maxOf(minY(u), (u.bottom - size + pad).toInt())

    private fun applyPosition() {
        val p = lp ?: return
        val u = Screen.usableRect(ctx)
        p.x = minX(u) + (fx.coerceIn(0f, 1f) * (maxX(u, p.width) - minX(u))).toInt()
        p.y = minY(u) + (fy.coerceIn(0f, 1f) * (maxY(u, p.height) - minY(u))).toInt()
    }

    private fun push() {
        val v = view ?: return
        val p = lp ?: return
        runCatching { wm.updateViewLayout(v, p) }
    }

    private fun emphasize(on: Boolean) {
        val v = view ?: return
        val active = on || holdEmphasis
        v.animate().cancel()
        v.animate().alpha(if (active) 1f else idleAlpha()).scaleX(if (on) 1.12f else 1f).scaleY(if (on) 1.12f else 1f).setDuration(150).start()
    }

    private val touch = View.OnTouchListener { v, e ->
        val p = lp ?: return@OnTouchListener false
        val u = Screen.usableRect(ctx)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX; downY = e.rawY; startX = p.x; startY = p.y; dragging = false
                emphasize(true)
                true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - downX
                val dy = e.rawY - downY
                if (!dragging && hypot(dx, dy) > slop) dragging = true // small jitters never count as a drag or a tap
                if (dragging) {
                    p.x = (startX + dx).toInt().coerceIn(minX(u), maxX(u, p.width))
                    p.y = (startY + dy).toInt().coerceIn(minY(u), maxY(u, p.height))
                    push()
                }
                true
            }
            MotionEvent.ACTION_UP -> {
                emphasize(false)
                if (dragging) finishDrag(u) else { v.performClick(); callbacks.onTap() }
                dragging = false
                true
            }
            MotionEvent.ACTION_CANCEL -> {
                emphasize(false)
                if (dragging) finishDrag(u)
                dragging = false
                true
            }
            else -> false
        }
    }

    private fun finishDrag(u: FRect) {
        val p = lp ?: return
        if (!settings.edgeSnapping) { savePosition(u); return }
        val centre = p.x + p.width / 2f
        val target = if (centre < (u.left + u.right) / 2f) minX(u) else maxX(u, p.width)
        ValueAnimator.ofInt(p.x, target).apply {
            duration = 180
            addUpdateListener { p.x = it.animatedValue as Int; push() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) { savePosition(u) }
            })
            start()
        }
    }

    private fun savePosition(u: FRect) {
        val p = lp ?: return
        val spanX = (maxX(u, p.width) - minX(u)).coerceAtLeast(1)
        val spanY = (maxY(u, p.height) - minY(u)).coerceAtLeast(1)
        fx = ((p.x - minX(u)).toFloat() / spanX).coerceIn(0f, 1f)
        fy = ((p.y - minY(u)).toFloat() / spanY).coerceIn(0f, 1f)
        callbacks.onPositionSaved(fx, fy)
    }
}
