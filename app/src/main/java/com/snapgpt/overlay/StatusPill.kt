package com.snapgpt.overlay

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.snapgpt.util.Screen

/** Small non-touchable banner at the top of the screen that narrates failover ("Switching to Gemini Primary..."). */
class StatusPill(private val ctx: Context, private val wm: WindowManager) {
    private var view: TextView? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val hideRunnable = Runnable { hide() }

    fun show(text: String, palette: Palette, autoHideMs: Long = 0) {
        handler.removeCallbacks(hideRunnable)
        val existing = view
        if (existing != null) {
            existing.text = text
        } else {
            val tv = TextView(ctx).apply {
                this.text = text
                textSize = 13f
                setTextColor(0xFFFFFFFF.toInt())
                gravity = Gravity.CENTER
                maxLines = 3
                val ph = Screen.dpI(ctx, 16f)
                val pv = Screen.dpI(ctx, 9f)
                setPadding(ph, pv, ph, pv)
                background = roundRect(0xE6232230.toInt(), Screen.dp(ctx, 18f))
                elevation = Screen.dp(ctx, 6f)
            }
            val u = Screen.usableRect(ctx)
            val flags = OverlayWindows.BASE_FLAGS or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            val lp = OverlayWindows.params(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, flags).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = u.top.toInt() + Screen.dpI(ctx, 12f)
                width = minOf((u.width * 0.9f).toInt(), Screen.dpI(ctx, 420f))
            }
            if (runCatching { wm.addView(tv, lp) }.isSuccess) view = tv
        }
        if (autoHideMs > 0) handler.postDelayed(hideRunnable, autoHideMs)
    }

    fun setCaptureHidden(hidden: Boolean) { view?.visibility = if (hidden) View.INVISIBLE else View.VISIBLE }

    fun hide() {
        handler.removeCallbacks(hideRunnable)
        view?.let { runCatching { wm.removeView(it) } }
        view = null
    }
}
