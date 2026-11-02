package com.snapgpt.overlay

import android.content.Context
import android.graphics.PorterDuff
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.snapgpt.util.Screen

data class MenuItem(val iconRes: Int, val label: String, val action: () -> Unit)

/** Compact action menu shown beside the circle. Touching anywhere outside dismisses it (the touch still reaches the app below). */
class FloatingMenu(private val ctx: Context, private val wm: WindowManager) {
    private var root: LinearLayout? = null
    private var onDismissed: (() -> Unit)? = null

    val isShowing: Boolean get() = root != null

    fun show(anchor: FRect, items: List<MenuItem>, palette: Palette, onDismissed: () -> Unit) {
        dismiss(notify = false)
        this.onDismissed = onDismissed
        val radius = Screen.dp(ctx, 18f)
        val menu = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val p = Screen.dpI(ctx, 6f)
            setPadding(p, p, p, p)
            background = roundRect(palette.surface, radius, palette.outline, 1)
            elevation = Screen.dp(ctx, 8f)
        }
        items.forEach { item ->
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val ph = Screen.dpI(ctx, 14f)
                val pv = Screen.dpI(ctx, 11f)
                setPadding(ph, pv, ph, pv)
                background = pressable(roundRect(0, Screen.dp(ctx, 12f)), palette.pressed, Screen.dp(ctx, 12f))
                isClickable = true
                setOnClickListener {
                    dismiss(notify = true)
                    item.action()
                }
            }
            val icon = ImageView(ctx).apply {
                setImageResource(item.iconRes)
                setColorFilter(palette.primary, PorterDuff.Mode.SRC_IN)
                val s = Screen.dpI(ctx, 22f)
                layoutParams = LinearLayout.LayoutParams(s, s)
            }
            val label = TextView(ctx).apply {
                text = item.label
                setTextColor(palette.onSurface)
                textSize = 14.5f
                setPadding(Screen.dpI(ctx, 12f), 0, 0, 0)
            }
            row.addView(icon)
            row.addView(label)
            menu.addView(row, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val u = Screen.usableRect(ctx)
        menu.measure(
            View.MeasureSpec.makeMeasureSpec((u.width * 0.8f).toInt(), View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val w = menu.measuredWidth
        val h = menu.measuredHeight
        val gap = Screen.dpI(ctx, 8f)
        val onRight = (anchor.left + anchor.right) / 2f < (u.left + u.right) / 2f
        val x = (if (onRight) anchor.right + gap else anchor.left - gap - w).toInt().coerceIn(u.left.toInt(), maxOf(u.left.toInt(), (u.right - w).toInt()))
        val y = anchor.top.toInt().coerceIn(u.top.toInt(), maxOf(u.top.toInt(), (u.bottom - h).toInt()))

        val lp = OverlayWindows.params(w, h, OverlayWindows.BASE_FLAGS or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH)
        lp.x = x
        lp.y = y
        menu.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_OUTSIDE) { dismiss(notify = true); true } else false
        }
        menu.alpha = 0f
        menu.scaleX = 0.85f
        menu.scaleY = 0.85f
        menu.pivotX = if (onRight) 0f else w.toFloat()
        menu.pivotY = 0f
        runCatching { wm.addView(menu, lp) }.onSuccess {
            root = menu
            menu.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(140).start()
        }
    }

    fun dismiss(notify: Boolean = true) {
        val r = root ?: return
        root = null
        runCatching { wm.removeView(r) }
        if (notify) onDismissed?.invoke()
    }

    fun setCaptureHidden(hidden: Boolean) { root?.visibility = if (hidden) View.INVISIBLE else View.VISIBLE }
}
