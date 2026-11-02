package com.snapgpt.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.snapgpt.util.Screen
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Draws the frozen screenshot dimmed, with the user's rectangle shown bright, its size, and corner handles. */
class SelectionCanvas(context: Context, private val bmp: Bitmap) : View(context) {
    private val dim = Paint().apply { color = 0xAA000000.toInt() }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = Screen.dp(context, 2f) }
    private val handle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF818CF8.toInt() }
    private val labelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF4F46E5.toInt() }
    private val labelText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); textSize = Screen.dp(context, 13f) }
    private val imgPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val minSide = Screen.dp(context, 48f)

    private var sx = 0f
    private var sy = 0f
    private var ex = 0f
    private var ey = 0f
    private var dragging = false
    private var valid = false

    var onSelectionChanged: ((Boolean) -> Unit)? = null

    private fun rect() = RectF(min(sx, ex), min(sy, ey), max(sx, ex), max(sy, ey))

    /** Selection in the screenshot's pixel space (what gets cropped and sent), or null if nothing valid is selected. */
    fun selectionInBitmap(): FRect? {
        if (!valid || width == 0 || height == 0) return null
        val r = rect()
        val kx = bmp.width.toFloat() / width
        val ky = bmp.height.toFloat() / height
        return FRect(r.left * kx, r.top * ky, r.right * kx, r.bottom * ky)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawBitmap(bmp, null, RectF(0f, 0f, width.toFloat(), height.toFloat()), imgPaint)
        val r = rect()
        if (dragging || valid) {
            val p = Path().apply {
                fillType = Path.FillType.EVEN_ODD
                addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
                addRect(r, Path.Direction.CW)
            }
            canvas.drawPath(p, dim)
            canvas.drawRect(r, border)
            val hr = Screen.dp(context, 6f)
            for ((x, y) in listOf(r.left to r.top, r.right to r.top, r.left to r.bottom, r.right to r.bottom)) canvas.drawCircle(x, y, hr, handle)
            val kx = bmp.width.toFloat() / max(1, width)
            val ky = bmp.height.toFloat() / max(1, height)
            val label = "${(r.width() * kx).toInt()} × ${(r.height() * ky).toInt()} px"
            val tw = labelText.measureText(label)
            val padH = Screen.dp(context, 10f)
            val lh = Screen.dp(context, 26f)
            val lx = r.left.coerceAtMost(width - tw - 2 * padH).coerceAtLeast(0f)
            val ly = if (r.top - lh - 6f > 0) r.top - lh - 6f else r.top + 6f
            canvas.drawRoundRect(lx, ly, lx + tw + 2 * padH, ly + lh, lh / 2, lh / 2, labelBg)
            canvas.drawText(label, lx + padH, ly + lh / 2 - (labelText.ascent() + labelText.descent()) / 2, labelText)
        } else {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dim)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { sx = e.x; sy = e.y; ex = e.x; ey = e.y; dragging = true; setValid(false) }
            MotionEvent.ACTION_MOVE -> { ex = e.x.coerceIn(0f, width.toFloat()); ey = e.y.coerceIn(0f, height.toFloat()); invalidate() }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                ex = e.x.coerceIn(0f, width.toFloat()); ey = e.y.coerceIn(0f, height.toFloat())
                dragging = false
                setValid(abs(ex - sx) >= minSide && abs(ey - sy) >= minSide)
            }
        }
        invalidate()
        return true
    }

    private fun setValid(v: Boolean) {
        valid = v
        onSelectionChanged?.invoke(v)
    }
}

/** Full-screen "select question area" overlay: dimmed frozen frame, drag a rectangle, Confirm / Cancel. */
class SelectionOverlay(private val ctx: Context, private val wm: WindowManager) {
    interface Listener {
        fun onConfirm(rect: FRect)
        fun onCancel()
    }

    private var root: FrameLayout? = null
    val isShowing: Boolean get() = root != null

    fun show(frozen: Bitmap, palette: Palette, listener: Listener) {
        hide()
        val (w, h) = Screen.realSize(ctx)
        val u = Screen.usableRect(ctx)
        val canvas = SelectionCanvas(ctx, frozen)

        val hint = TextView(ctx).apply {
            text = "Drag to select the question area"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 14f
            val ph = Screen.dpI(ctx, 16f)
            val pv = Screen.dpI(ctx, 8f)
            setPadding(ph, pv, ph, pv)
            background = roundRect(0xCC000000.toInt(), Screen.dp(ctx, 20f))
        }
        fun button(text: String, bg: Int, fg: Int) = TextView(ctx).apply {
            this.text = text
            setTextColor(fg)
            textSize = 15f
            gravity = Gravity.CENTER
            val ph = Screen.dpI(ctx, 26f)
            val pv = Screen.dpI(ctx, 12f)
            setPadding(ph, pv, ph, pv)
            background = roundRect(bg, Screen.dp(ctx, 24f))
            isClickable = true
        }
        val cancel = button("Cancel", 0xFF3A3947.toInt(), 0xFFFFFFFF.toInt())
        val confirm = button("Confirm", palette.primary, 0xFFFFFFFF.toInt()).apply { alpha = 0.4f; isEnabled = false }
        canvas.onSelectionChanged = { ok -> confirm.isEnabled = ok; confirm.alpha = if (ok) 1f else 0.4f }
        cancel.setOnClickListener { listener.onCancel() }
        confirm.setOnClickListener { canvas.selectionInBitmap()?.let { listener.onConfirm(it) } }

        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val gap = Screen.dpI(ctx, 14f)
        bar.addView(cancel)
        bar.addView(confirm, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = gap })

        val frame = FrameLayout(ctx)
        frame.addView(canvas, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        frame.addView(hint, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = u.top.toInt() + Screen.dpI(ctx, 16f)
        })
        frame.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = (h - u.bottom).toInt() + Screen.dpI(ctx, 28f)
        })

        val lp = OverlayWindows.params(w, h)
        runCatching { wm.addView(frame, lp) }.onSuccess { root = frame }
    }

    fun hide() {
        root?.let { runCatching { wm.removeView(it) } }
        root = null
    }
}
