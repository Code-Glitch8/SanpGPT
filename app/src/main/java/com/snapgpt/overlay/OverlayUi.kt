package com.snapgpt.overlay

import android.content.Context
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import com.snapgpt.settings.ThemeMode

/** Colours for the floating UI (light / dark), resolved from the Appearance setting. */
class Palette(val dark: Boolean) {
    val surface: Int = if (dark) 0xFF24232B.toInt() else 0xFFFFFFFF.toInt()
    val surfaceVariant: Int = if (dark) 0xFF35343E.toInt() else 0xFFEFEDF7.toInt()
    val onSurface: Int = if (dark) 0xFFEDEBF5.toInt() else 0xFF1B1B22.toInt()
    val onSurfaceMuted: Int = if (dark) 0xFFB5B3C2.toInt() else 0xFF5F5E6B.toInt()
    val primary: Int = 0xFF4F46E5.toInt()
    val onPrimary: Int = 0xFFFFFFFF.toInt()
    val accent: Int = if (dark) 0xFFA5B4FC.toInt() else 0xFF4338CA.toInt()
    val outline: Int = if (dark) 0xFF4A4957.toInt() else 0xFFD9D7E5.toInt()
    val warning: Int = 0xFFF59E0B.toInt()
    val pressed: Int = if (dark) 0x33FFFFFF else 0x14000000
}

fun resolvePalette(context: Context, theme: ThemeMode): Palette {
    val systemDark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    return Palette(
        when (theme) {
            ThemeMode.DARK -> true
            ThemeMode.LIGHT -> false
            ThemeMode.SYSTEM -> systemDark
        },
    )
}

object OverlayWindows {
    /** Windows that never take focus and are laid out in raw screen coordinates (so AI coordinates map 1:1). */
    const val BASE_FLAGS = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

    fun params(width: Int, height: Int, flags: Int = BASE_FLAGS): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            width, height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
}

fun roundRect(color: Int, radius: Float, strokeColor: Int = 0, strokePx: Int = 0): GradientDrawable =
    GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radius
        setColor(color)
        if (strokePx > 0) setStroke(strokePx, strokeColor)
    }

/** Background that darkens slightly while pressed (no theme attributes needed inside a Service context). */
fun pressable(base: android.graphics.drawable.Drawable, pressedColor: Int, radius: Float): StateListDrawable =
    StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), android.graphics.drawable.LayerDrawable(arrayOf(base, roundRect(pressedColor, radius))))
        addState(intArrayOf(), base)
    }

@Suppress("unused")
private val transparent = ColorDrawable(0)
