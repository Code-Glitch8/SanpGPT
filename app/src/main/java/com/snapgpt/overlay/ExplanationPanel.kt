package com.snapgpt.overlay

import android.content.Context
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.snapgpt.util.Screen

/** Root of the panel; turns the Back key into a callback (the window only becomes focusable while typing). */
private class PanelRoot(context: Context) : LinearLayout(context) {
    var onBack: (() -> Unit)? = null
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
            onBack?.invoke()
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}

/**
 * Movable explanation window (Stage 2): independent scrolling, expand/collapse, close, copy answer, copy explanation,
 * regenerate and a follow-up input. The app underneath stays usable because the window only covers its own rectangle.
 */
class ExplanationPanel(private val ctx: Context, private val wm: WindowManager) {
    interface Listener {
        fun onClose()
        fun onCopyAnswer()
        fun onCopyExplanation()
        fun onRegenerate()
        fun onDetailed()
        fun onFollowUp(text: String)
    }

    private var root: PanelRoot? = null
    private var lp: WindowManager.LayoutParams? = null
    private var palette: Palette = Palette(false)
    private lateinit var titleView: TextView
    private lateinit var verifyView: TextView
    private lateinit var detailed: TextView
    private lateinit var body: TextView
    private lateinit var scroll: ScrollView
    private lateinit var actions: LinearLayout
    private lateinit var actionsScroll: android.widget.HorizontalScrollView
    private lateinit var footer: LinearLayout
    private lateinit var input: EditText
    private lateinit var collapse: TextView
    private lateinit var regenerate: TextView
    private lateinit var ask: TextView
    private var expanded = true
    private var busy = false
    private var listener: Listener? = null

    val isShowing: Boolean get() = root != null

    fun show(palette: Palette, title: String, listener: Listener) {
        this.listener = listener
        if (root != null) { titleView.text = title; return }
        this.palette = palette
        expanded = true
        busy = false
        build(title)
        val v = root ?: return
        layoutForScreen()
        runCatching { wm.addView(v, lp) }.onFailure { root = null }
        v.alpha = 0f
        v.animate().alpha(1f).setDuration(160).start()
    }

    fun setTitle(title: String) { if (root != null) titleView.text = title }

    /** One line under the title: verification status and note. Amber when the answer should be treated with caution. */
    fun setVerification(text: String?, uncertain: Boolean) {
        if (root == null) return
        if (text.isNullOrBlank()) { verifyView.visibility = View.GONE; return }
        verifyView.text = text
        verifyView.setTextColor(if (uncertain) palette.warning else palette.onSurfaceMuted)
        verifyView.visibility = View.VISIBLE
    }

    /** The "Detailed solution" chip is hidden once the full step-by-step answer is already shown. */
    fun setDetailedAvailable(available: Boolean) { if (root != null) detailed.visibility = if (available) View.VISIBLE else View.GONE }

    fun setBody(text: CharSequence, scrollToEnd: Boolean = false) {
        if (root == null) return
        body.text = text
        scroll.post { if (scrollToEnd) scroll.fullScroll(View.FOCUS_DOWN) else scroll.scrollTo(0, 0) }
    }

    fun setBusy(b: Boolean) {
        busy = b
        if (root == null) return
        regenerate.alpha = if (b) 0.4f else 1f
        detailed.alpha = if (b) 0.4f else 1f
        ask.alpha = if (b) 0.4f else 1f
    }

    fun clearInput() { if (root != null) input.setText("") }

    fun relayout() {
        if (root == null) return
        layoutForScreen()
        runCatching { wm.updateViewLayout(root, lp) }
    }

    fun hide() {
        val r = root ?: return
        hideKeyboard()
        root = null
        runCatching { wm.removeView(r) }
    }

    fun setCaptureHidden(hidden: Boolean) { root?.visibility = if (hidden) View.INVISIBLE else View.VISIBLE }

    // ------------------------------------------------------------------ construction

    private fun dp(v: Float) = Screen.dpI(ctx, v)

    private fun chip(label: String, onClick: () -> Unit) = TextView(ctx).apply {
        text = label
        textSize = 12.5f
        setTextColor(palette.accent)
        gravity = Gravity.CENTER
        setPadding(dp(12f), dp(7f), dp(12f), dp(7f))
        background = pressable(roundRect(palette.surfaceVariant, Screen.dp(ctx, 16f)), palette.pressed, Screen.dp(ctx, 16f))
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun build(title: String) {
        val r = PanelRoot(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = roundRect(palette.surface, Screen.dp(ctx, 20f), palette.outline, 1)
            elevation = Screen.dp(ctx, 10f)
            onBack = { disableInput() }
        }

        // Header (drag handle)
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14f), dp(8f), dp(6f), dp(8f))
        }
        titleView = TextView(ctx).apply {
            text = title
            textSize = 14f
            setTextColor(palette.onSurface)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        header.addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        fun iconButton(label: String, onClick: () -> Unit) = TextView(ctx).apply {
            text = label
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(palette.onSurfaceMuted)
            minWidth = dp(40f)
            minHeight = dp(36f)
            isClickable = true
            setOnClickListener { onClick() }
        }
        collapse = iconButton("–") { toggleExpanded() }
        header.addView(collapse)
        header.addView(iconButton("✕") { listener?.onClose() })
        attachDrag(header)
        r.addView(header, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        verifyView = TextView(ctx).apply {
            textSize = 12f
            visibility = View.GONE
            setPadding(dp(16f), 0, dp(16f), dp(4f))
        }
        r.addView(verifyView, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        // Body
        body = TextView(ctx).apply {
            textSize = 14.5f
            setTextColor(palette.onSurface)
            setLineSpacing(0f, 1.15f)
            setPadding(dp(16f), dp(4f), dp(16f), dp(12f))
        }
        scroll = ScrollView(ctx).apply { isVerticalScrollBarEnabled = true; addView(body) }
        r.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // Actions
        actions = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(10f), dp(4f), dp(10f), dp(4f))
        }
        val gap = dp(6f)
        actions.addView(chip("Copy answer") { listener?.onCopyAnswer() })
        actions.addView(chip("Copy steps") { listener?.onCopyExplanation() }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = gap })
        regenerate = chip("Regenerate") { if (!busy) listener?.onRegenerate() }
        actions.addView(regenerate, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = gap })
        detailed = chip("Detailed solution") { if (!busy) listener?.onDetailed() }
        actions.addView(detailed, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = gap })
        // Four chips can be wider than a narrow panel, so the row scrolls sideways instead of clipping.
        actionsScroll = android.widget.HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(actions) }
        r.addView(actionsScroll, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        // Follow-up input
        footer = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10f), dp(4f), dp(10f), dp(10f))
        }
        input = EditText(ctx).apply {
            hint = "Ask a follow-up…"
            textSize = 14f
            setTextColor(palette.onSurface)
            setHintTextColor(palette.onSurfaceMuted)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_SEND
            setSingleLine(true)
            setPadding(dp(14f), dp(8f), dp(14f), dp(8f))
            background = roundRect(palette.surfaceVariant, Screen.dp(ctx, 20f))
            setOnTouchListener { _, e -> if (e.action == MotionEvent.ACTION_DOWN) enableInput(); false }
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) { submit(); true } else false
            }
        }
        ask = TextView(ctx).apply {
            text = "Ask"
            textSize = 14f
            setTextColor(palette.onPrimary)
            gravity = Gravity.CENTER
            setPadding(dp(16f), dp(9f), dp(16f), dp(9f))
            background = roundRect(palette.primary, Screen.dp(ctx, 20f))
            isClickable = true
            setOnClickListener { submit() }
        }
        footer.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        footer.addView(ask, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8f) })
        r.addView(footer, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        root = r
        lp = OverlayWindows.params(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun submit() {
        val t = input.text.toString().trim()
        if (t.isEmpty() || busy) return
        disableInput()
        listener?.onFollowUp(t)
    }

    // ------------------------------------------------------------------ layout / behaviour

    private fun layoutForScreen() {
        val p = lp ?: return
        val u = Screen.usableRect(ctx)
        val land = u.width > u.height
        val margin = dp(8f)
        val width = if (land) minOf((u.width * 0.5f).toInt(), dp(460f)) else minOf(u.width.toInt() - 2 * margin, dp(440f))
        val height = (u.height * (if (land) 0.86f else 0.5f)).toInt()
        p.width = width
        p.height = if (expanded) height else ViewGroup.LayoutParams.WRAP_CONTENT
        // Right side when practical; bottom in portrait so the question above stays visible.
        p.x = (u.right - width - margin).toInt().coerceAtLeast(u.left.toInt())
        if (p.y == 0 || !expanded) p.y = if (land) (u.top + margin).toInt() else (u.bottom - height - margin).toInt().coerceAtLeast(u.top.toInt())
        else p.y = p.y.coerceIn(u.top.toInt(), maxOf(u.top.toInt(), (u.bottom - height).toInt()))
    }

    private fun toggleExpanded() {
        expanded = !expanded
        val vis = if (expanded) View.VISIBLE else View.GONE
        scroll.visibility = vis
        actionsScroll.visibility = vis
        footer.visibility = vis
        collapse.text = if (expanded) "–" else "+"
        if (!expanded) disableInput()
        val p = lp ?: return
        val u = Screen.usableRect(ctx)
        val h = (u.height * (if (u.width > u.height) 0.86f else 0.5f)).toInt()
        p.height = if (expanded) h else ViewGroup.LayoutParams.WRAP_CONTENT
        if (expanded) p.y = p.y.coerceIn(u.top.toInt(), maxOf(u.top.toInt(), (u.bottom - h).toInt()))
        runCatching { wm.updateViewLayout(root, p) }
    }

    private fun attachDrag(handle: View) {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        handle.setOnTouchListener { _, e ->
            val p = lp ?: return@setOnTouchListener false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = p.x; startY = p.y; disableInput(); true }
                MotionEvent.ACTION_MOVE -> {
                    val u = Screen.usableRect(ctx)
                    p.x = (startX + e.rawX - downX).toInt().coerceIn(u.left.toInt() - dp(60f), (u.right).toInt() - dp(60f))
                    p.y = (startY + e.rawY - downY).toInt().coerceIn(u.top.toInt(), (u.bottom).toInt() - dp(48f))
                    runCatching { wm.updateViewLayout(root, p) }
                    true
                }
                else -> true
            }
        }
    }

    // ------------------------------------------------------------------ keyboard (window is only focusable while typing)

    private fun enableInput() {
        val p = lp ?: return
        val r = root ?: return
        if (p.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE == 0) return
        p.flags = p.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        p.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN or WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
        runCatching { wm.updateViewLayout(r, p) }
        input.post {
            input.requestFocus()
            (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun disableInput() {
        val p = lp ?: return
        val r = root ?: return
        hideKeyboard()
        if (p.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0) return
        input.clearFocus()
        p.flags = p.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        p.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED
        runCatching { wm.updateViewLayout(r, p) }
    }

    private fun hideKeyboard() {
        val r = root ?: return
        (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(r.windowToken, 0)
    }
}
