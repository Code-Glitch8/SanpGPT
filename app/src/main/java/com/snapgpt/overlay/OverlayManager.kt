package com.snapgpt.overlay

import android.content.Context
import android.graphics.Bitmap
import android.view.ContextThemeWrapper
import android.widget.Toast
import com.snapgpt.R
import com.snapgpt.settings.AppSettings

/** Everything the floating UI can ask the app to do. Implemented by the scan coordinator. */
interface OverlayCallbacks {
    fun onScanFull()
    fun onSelectArea()
    fun onHideAssistant()
    fun onOpenSettings()
    fun onClearAnswers()
    fun onPositionSaved(fx: Float, fy: Float)
    fun onAnswerTap(id: String)
    fun onAnswerDismiss(id: String)
    fun onAreaConfirmed(rect: FRect)
    fun onAreaCancelled()
    fun onPanelClose()
    fun onCopyAnswer()
    fun onCopyExplanation()
    fun onRegenerate()
    fun onFollowUp(text: String)
}

/** Owns every overlay window (circle, menu, selection, answer bubbles, explanation panel) and their lifecycle. */
class OverlayManager(private val service: Context, private val cb: OverlayCallbacks) {
    private val wm = com.snapgpt.util.Screen.wm(service)
    private val themed = ContextThemeWrapper(service, R.style.Theme_SnapGPT)
    private var settings = AppSettings()

    private val bubble = FloatingBubble(themed, wm, object : FloatingBubble.Callbacks {
        override fun onTap() = toggleMenu()
        override fun onPositionSaved(fx: Float, fy: Float) = cb.onPositionSaved(fx, fy)
    })
    private val menu = FloatingMenu(themed, wm)
    private val answers = AnswerLayer(themed, wm)
    private val selection = SelectionOverlay(themed, wm)
    private val panel = ExplanationPanel(themed, wm)

    private fun palette() = resolvePalette(themed, settings.theme)

    val isBubbleShown: Boolean get() = bubble.isShown
    val hasAnswers: Boolean get() = !answers.isEmpty
    val isSelecting: Boolean get() = selection.isShowing
    val isPanelShowing: Boolean get() = panel.isShowing

    fun applySettings(s: AppSettings) {
        settings = s
        bubble.update(s)
    }

    // ---- circle + menu

    fun showBubble() = bubble.show(settings)

    fun hideBubble() {
        menu.dismiss(notify = false)
        bubble.setHold(false)
        bubble.hide()
    }

    fun setBusy(busy: Boolean) = bubble.setBusy(busy)

    private fun toggleMenu() {
        if (menu.isShowing) { menu.dismiss(); return }
        val items = buildList {
            add(MenuItem(R.drawable.ic_menu_scan, service.getString(R.string.menu_scan_full)) { cb.onScanFull() })
            add(MenuItem(R.drawable.ic_menu_crop, service.getString(R.string.menu_select_area)) { cb.onSelectArea() })
            if (hasAnswers) add(MenuItem(R.drawable.ic_menu_clear, service.getString(R.string.menu_clear_answers)) { cb.onClearAnswers() })
            add(MenuItem(R.drawable.ic_menu_hide, service.getString(R.string.menu_hide)) { cb.onHideAssistant() })
            add(MenuItem(R.drawable.ic_menu_settings, service.getString(R.string.menu_settings)) { cb.onOpenSettings() })
        }
        bubble.setHold(true)
        menu.show(bubble.bounds(), items, palette()) { bubble.setHold(false) }
    }

    /** Hides every SnapGPT window while the screen is captured so the screenshot contains only the app underneath. */
    fun setCaptureHidden(hidden: Boolean) {
        bubble.setCaptureHidden(hidden)
        menu.setCaptureHidden(hidden)
        answers.setCaptureHidden(hidden)
        panel.setCaptureHidden(hidden)
    }

    // ---- area selection

    fun showSelection(frozen: Bitmap) {
        menu.dismiss(notify = false)
        bubble.setCaptureHidden(true)
        selection.show(frozen, palette(), object : SelectionOverlay.Listener {
            override fun onConfirm(rect: FRect) = cb.onAreaConfirmed(rect)
            override fun onCancel() = cb.onAreaCancelled()
        })
    }

    fun hideSelection() {
        selection.hide()
        bubble.setCaptureHidden(false)
    }

    // ---- answer bubbles

    fun showAnswers(items: List<AnswerItem>) {
        answers.show(items, palette(), object : AnswerLayer.Listener {
            override fun onTap(id: String) = cb.onAnswerTap(id)
            override fun onDismiss(id: String) = cb.onAnswerDismiss(id)
        })
    }

    fun removeAnswer(id: String) = answers.remove(id)
    fun clearAnswers() = answers.clear()
    fun setAnswerLoading(id: String, loading: Boolean) = answers.setLoading(id, loading)

    // ---- explanation panel

    fun showPanel(title: String) {
        panel.show(palette(), title, object : ExplanationPanel.Listener {
            override fun onClose() = cb.onPanelClose()
            override fun onCopyAnswer() = cb.onCopyAnswer()
            override fun onCopyExplanation() = cb.onCopyExplanation()
            override fun onRegenerate() = cb.onRegenerate()
            override fun onFollowUp(text: String) = cb.onFollowUp(text)
        })
        panel.setTitle(title)
    }

    fun setPanelBody(text: String, scrollToEnd: Boolean = false) = panel.setBody(MarkdownLite.render(text, palette().accent), scrollToEnd)
    fun setPanelBusy(busy: Boolean) = panel.setBusy(busy)
    fun clearPanelInput() = panel.clearInput()
    fun hidePanel() = panel.hide()

    // ---- misc

    fun toast(message: String) {
        Toast.makeText(service, message, Toast.LENGTH_LONG).show()
    }

    /** Rotation / display change: answer positions are no longer valid, the circle and panel are re-laid out. */
    fun onConfigurationChanged(): Boolean {
        menu.dismiss(notify = false)
        bubble.setHold(false)
        bubble.reposition()
        panel.relayout()
        val hadAnswers = hasAnswers
        answers.clear()
        return hadAnswers
    }

    fun shutdown() {
        menu.dismiss(notify = false)
        selection.hide()
        answers.clear()
        panel.hide()
        bubble.hide()
    }
}
