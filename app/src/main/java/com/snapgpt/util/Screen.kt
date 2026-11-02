package com.snapgpt.util

import android.content.Context
import android.graphics.Point
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowInsets
import android.view.WindowManager
import com.snapgpt.overlay.FRect

/** Screen geometry helpers that work from a Service context and across rotation. */
object Screen {
    fun wm(context: Context): WindowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    fun dp(context: Context, v: Float): Float = v * context.resources.displayMetrics.density
    fun dpI(context: Context, v: Float): Int = (dp(context, v) + 0.5f).toInt()

    fun densityDpi(context: Context): Int = context.resources.displayMetrics.densityDpi

    /** Full physical display size in pixels, including status and navigation bars. */
    fun realSize(context: Context): Pair<Int, Int> {
        val wm = wm(context)
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.currentWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            dm.widthPixels to dm.heightPixels
        }
    }

    /** Screen minus system bars and display cutouts (where the floating circle may sit). */
    fun usableRect(context: Context): FRect {
        val (w, h) = realSize(context)
        val wm = wm(context)
        if (Build.VERSION.SDK_INT >= 30) {
            val ins = wm.currentWindowMetrics.windowInsets
                .getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            return FRect(ins.left.toFloat(), ins.top.toFloat(), (w - ins.right).toFloat(), (h - ins.bottom).toFloat())
        }
        val res = context.resources
        val id = res.getIdentifier("status_bar_height", "dimen", "android")
        val statusBar = if (id > 0) res.getDimensionPixelSize(id) else dpI(context, 24f)
        val size = Point()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getSize(size)
        val bottom = (h - size.y).coerceAtLeast(0)
        val right = (w - size.x).coerceAtLeast(0)
        return FRect(0f, statusBar.toFloat(), (w - right).toFloat(), (h - bottom).toFloat())
    }
}
