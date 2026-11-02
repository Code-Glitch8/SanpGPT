package com.snapgpt.services

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import com.snapgpt.AppContainer
import com.snapgpt.MainActivity
import com.snapgpt.ai.AiErrorCode
import com.snapgpt.ai.AiException
import com.snapgpt.ai.ChatMessage
import com.snapgpt.ai.DetectedQuestion
import com.snapgpt.ai.ScanType
import com.snapgpt.ai.TokenUsage
import com.snapgpt.capture.BitmapCodec
import com.snapgpt.capture.ConsentActivity
import com.snapgpt.capture.ScreenCaptureManager
import com.snapgpt.history.LiveQuestion
import com.snapgpt.history.LiveSession
import com.snapgpt.overlay.AnswerFormatter
import com.snapgpt.overlay.AnswerItem
import com.snapgpt.overlay.CoordinateMapper
import com.snapgpt.overlay.FRect
import com.snapgpt.overlay.OverlayCallbacks
import com.snapgpt.overlay.OverlayManager
import com.snapgpt.settings.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Orchestrates the two-stage flow for the floating assistant:
 *  scan -> capture (user-triggered, own windows hidden) -> Stage 1 (concise answers) -> answer bubbles
 *  tap bubble -> Stage 2 (detailed explanation for that one question) -> explanation panel -> follow-ups.
 */
class ScanCoordinator(private val service: Context, private val container: AppContainer) : OverlayCallbacks {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val overlay = OverlayManager(service, this)
    private val capture = ScreenCaptureManager(service)
    private var settings = AppSettings()

    private var session: LiveSession? = null
    private var scanJob: Job? = null
    private var explainJob: Job? = null
    private var pendingAfterConsent: (() -> Unit)? = null
    private var pendingShot: Bitmap? = null
    private var activeQuestionId: String? = null
    private var bubbleHidden = false

    init {
        capture.onStopped = {
            AssistantState.setProjectionReady(false)
            overlay.toast("Screen capture ended. Tap Scan to allow it again.")
        }
        AssistantState.consentDeniedListener = {
            pendingAfterConsent = null
            overlay.toast("Screen capture was not allowed, so nothing was captured.")
        }
        scope.launch { container.settings.flow.collect { settings = it; overlay.applySettings(it) } }
    }

    // ------------------------------------------------------------------ lifecycle

    fun onProjectionGranted(resultCode: Int, data: Intent) {
        capture.attach(resultCode, data)
        AssistantState.setProjectionReady(true)
        scope.launch {
            settings = container.settings.current()
            overlay.applySettings(settings)
            if (!bubbleHidden && !overlay.isBubbleShown) overlay.showBubble()
            val next = pendingAfterConsent
            pendingAfterConsent = null
            next?.invoke()
        }
    }

    /** Returns true if the circle is now hidden. */
    fun toggleBubble(): Boolean {
        if (bubbleHidden) { bubbleHidden = false; overlay.showBubble() } else { bubbleHidden = true; overlay.hideBubble() }
        return bubbleHidden
    }

    fun onConfigurationChanged() {
        if (overlay.isSelecting) {
            overlay.hideSelection()
            pendingShot?.recycle()
            pendingShot = null
            overlay.toast("Selection cancelled because the screen rotated.")
        }
        overlay.onConfigurationChanged()
    }

    fun shutdown() {
        scanJob?.cancel()
        explainJob?.cancel()
        overlay.shutdown()
        capture.release()
        session?.release()
        session = null
        pendingShot?.recycle()
        pendingShot = null
        AssistantState.consentDeniedListener = null
        AssistantState.setProjectionReady(false)
        scope.cancel()
    }

    // ------------------------------------------------------------------ menu actions

    override fun onScanFull() = startScan(ScanType.FULL_SCREEN)
    override fun onSelectArea() = startScan(ScanType.AREA)

    override fun onHideAssistant() {
        bubbleHidden = true
        overlay.hideBubble()
        overlay.toast("Assistant hidden. Use the notification action \"Show circle\" to bring it back.")
        (service as? OverlayService)?.let {
            val nm = it.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.notify(NotificationHelper.NOTIFICATION_ID, NotificationHelper.build(it, true))
        }
    }

    override fun onOpenSettings() {
        val i = Intent(service, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_SCREEN, MainActivity.SCREEN_SETTINGS)
        service.startActivity(i)
    }

    override fun onClearAnswers() {
        overlay.clearAnswers()
    }

    override fun onPositionSaved(fx: Float, fy: Float) {
        scope.launch { container.settings.savePosition(fx, fy) }
    }

    // ------------------------------------------------------------------ scanning (Stage 1)

    private fun requestConsent(then: () -> Unit) {
        pendingAfterConsent = then
        overlay.toast("Android will ask you to allow screen capture.")
        service.startActivity(Intent(service, ConsentActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun startScan(type: ScanType) {
        if (scanJob?.isActive == true) { overlay.toast("A scan is already running."); return }
        if (overlay.isSelecting) return
        if (!capture.isReady) { requestConsent { startScan(type) }; return }
        scanJob = scope.launch { runScan(type) }
    }

    private suspend fun runScan(type: ScanType) {
        try {
            settings = container.settings.current()
            val hasEnabledAccount = settings.apiAccounts.any { it.enabled && container.keyStore.hasKey(it.id) }
            if (!hasEnabledAccount) {
                throw AiException(AiErrorCode.NO_API_KEY, "No enabled API key yet. Open SnapGPT > Settings > AI APIs and add or enable one.")
            }
            overlay.clearAnswers()
            val shot = grabCleanScreenshot()
            if (BitmapCodec.looksBlank(shot)) {
                shot.recycle()
                overlay.toast("The screen came back blank. The app in front probably blocks screenshots (protected content). Android does not allow capturing it, and SnapGPT does not try to bypass that.")
                return
            }
            if (type == ScanType.AREA) {
                pendingShot = shot
                overlay.showSelection(shot) // Stage 1 continues in onAreaConfirmed()
                return
            }
            analyze(shot, FRect(0f, 0f, shot.width.toFloat(), shot.height.toFloat()), ScanType.FULL_SCREEN)
        } catch (e: CancellationException) {
            throw e
        } catch (e: AiException) {
            overlay.toast(e.message ?: "Scan failed.")
        } catch (e: SecurityException) {
            overlay.toast("Android blocked the screen capture. Tap Scan again and allow it.")
        } catch (e: Exception) {
            overlay.toast("Scan failed (${e.javaClass.simpleName}). Please try again.")
        } finally {
            overlay.setCaptureHidden(false)
            overlay.setBusy(false)
        }
    }

    /** Hide SnapGPT's own windows, wait for the compositor, capture once, then show them again. */
    private suspend fun grabCleanScreenshot(): Bitmap {
        overlay.setCaptureHidden(true)
        try {
            delay(260)
            return capture.capture()
        } finally {
            overlay.setCaptureHidden(false)
        }
    }

    override fun onAreaCancelled() {
        overlay.hideSelection()
        pendingShot?.recycle()
        pendingShot = null
    }

    override fun onAreaConfirmed(rect: FRect) {
        val shot = pendingShot ?: return
        pendingShot = null
        overlay.hideSelection()
        scanJob = scope.launch {
            try {
                val cropped = BitmapCodec.crop(shot, rect)
                val region = FRect(rect.left, rect.top, rect.left + cropped.width, rect.top + cropped.height)
                if (cropped !== shot) shot.recycle()
                analyze(cropped, region, ScanType.AREA) // Only the selected region is sent to the selected API account.
            } catch (e: CancellationException) {
                throw e
            } catch (e: AiException) {
                overlay.toast(e.message ?: "Scan failed.")
            } catch (e: Exception) {
                overlay.toast("Scan failed (${e.javaClass.simpleName}). Please try again.")
            } finally {
                overlay.setBusy(false)
            }
        }
    }

    private suspend fun analyze(shot: Bitmap, region: FRect, type: ScanType) {
        overlay.setBusy(true)
        val outcome = container.ai.stage1(shot, type)
        val live = LiveSession(System.currentTimeMillis(), type, region, shot)
        outcome.usage?.let { live.usage = it }
        outcome.questions.forEach { live.questions += LiveQuestion(it) }
        session?.release()
        session = live
        activeQuestionId = null
        overlay.hidePanel()

        if (outcome.questions.isEmpty()) {
            overlay.toast("No questions were found on the screen.")
            return
        }
        val items = live.questions.map { lq ->
            val q = lq.question
            val rect = if (settings.autoAnswerPlacement) q.boundingBox?.let { CoordinateMapper.toScreen(it, region) } else null
            lq.screenRect = rect
            AnswerItem(q.id, AnswerFormatter.bubbleText(q), AnswerFormatter.isUncertain(q), rect)
        }
        overlay.showAnswers(items)
        if (outcome.fromCache) overlay.toast("Same screen as your last scan: answered from the local cache (no tokens used).")
        else {
            val status = outcome.warnings.firstOrNull() ?: "Answered using ${outcome.providerUsed}."
            overlay.toast(status)
        }

        if (settings.historyEnabled) {
            runCatching { container.history.saveSession(live, settings.examMode.key, (settings.apiAccounts.firstOrNull()?.model ?: settings.model), settings.saveThumbnails) }
        }
    }

    // ------------------------------------------------------------------ explanations (Stage 2)

    override fun onAnswerDismiss(id: String) {
        overlay.removeAnswer(id)
        if (activeQuestionId == id) { explainJob?.cancel(); activeQuestionId = null; overlay.hidePanel() }
    }

    override fun onAnswerTap(id: String) {
        val s = session ?: return
        val lq = s.find(id) ?: return
        if (activeQuestionId == id && overlay.isPanelShowing && explainJob?.isActive == true) return // no duplicate requests
        explainJob?.cancel()
        activeQuestionId?.let { overlay.setAnswerLoading(it, false) }
        activeQuestionId = id
        overlay.showPanel(titleFor(lq.question))
        if (lq.explanation != null) {
            overlay.setPanelBusy(false)
            overlay.setPanelBody(transcript(lq))
        } else {
            requestExplanation(s, lq)
        }
    }

    private fun requestExplanation(s: LiveSession, lq: LiveQuestion) {
        val id = lq.question.id
        explainJob = scope.launch {
            overlay.setAnswerLoading(id, true)
            overlay.setPanelBusy(true)
            overlay.setPanelBody("Explaining this question…\n\nOnly this question is sent to the selected API account.")
            try {
                val out = container.ai.explain(lq.question, s.cropFor(lq.question))
                lq.explanation = out.text
                lq.followUps.clear()
                recordUsage(s, out.usage)
                lq.rowId?.let { container.history.saveExplanation(it, out.text) }
                if (activeQuestionId == id) {
                    val source = if (out.providerUsed.isBlank()) "" else "_API account: ${out.providerUsed}_\n\n"
                    overlay.setPanelBody(source + transcript(lq))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: AiException) {
                if (activeQuestionId == id) overlay.setPanelBody("Could not get the explanation.\n\n${e.message}\n\nTap Regenerate to try again.")
            } catch (e: Exception) {
                if (activeQuestionId == id) overlay.setPanelBody("Could not get the explanation (${e.javaClass.simpleName}). Tap Regenerate to try again.")
            } finally {
                overlay.setAnswerLoading(id, false)
                if (activeQuestionId == id) overlay.setPanelBusy(false)
            }
        }
    }

    override fun onRegenerate() {
        val s = session ?: return
        val lq = activeQuestionId?.let { s.find(it) } ?: return
        if (explainJob?.isActive == true) return
        lq.explanation = null
        lq.followUps.clear()
        requestExplanation(s, lq)
    }

    override fun onFollowUp(text: String) {
        val s = session ?: return
        val lq = activeQuestionId?.let { s.find(it) } ?: return
        val explanation = lq.explanation ?: return
        if (explainJob?.isActive == true) return
        overlay.clearPanelInput()
        explainJob = scope.launch {
            overlay.setPanelBusy(true)
            overlay.setPanelBody(transcript(lq) + "\n\n──────\n**You:** $text\n\nThinking…", scrollToEnd = true)
            try {
                val out = container.ai.followUp(lq.question, explanation, lq.followUps.toList(), text, s.cropFor(lq.question))
                lq.followUps += ChatMessage("user", text)
                lq.followUps += ChatMessage("assistant", out.text)
                recordUsage(s, out.usage)
                lq.rowId?.let {
                    container.history.addMessage(it, "user", text)
                    container.history.addMessage(it, "assistant", out.text)
                }
                val source = if (out.providerUsed.isBlank()) "" else "_Latest reply by ${out.providerUsed}_\n\n"
                overlay.setPanelBody(source + transcript(lq), scrollToEnd = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AiException) {
                overlay.setPanelBody(transcript(lq) + "\n\n──────\n**Follow-up failed:** ${e.message}", scrollToEnd = true)
            } catch (e: Exception) {
                overlay.setPanelBody(transcript(lq) + "\n\n──────\n**Follow-up failed** (${e.javaClass.simpleName}).", scrollToEnd = true)
            } finally {
                overlay.setPanelBusy(false)
            }
        }
    }

    override fun onPanelClose() {
        explainJob?.cancel()
        activeQuestionId?.let { overlay.setAnswerLoading(it, false) }
        activeQuestionId = null
        overlay.hidePanel()
    }

    override fun onCopyAnswer() {
        val lq = activeQuestionId?.let { session?.find(it) } ?: return
        copy("SnapGPT answer", lq.question.answer)
        overlay.toast("Answer copied.")
    }

    override fun onCopyExplanation() {
        val lq = activeQuestionId?.let { session?.find(it) } ?: return
        val text = lq.explanation ?: return
        copy("SnapGPT explanation", transcript(lq).let { if (lq.followUps.isEmpty()) text else it })
        overlay.toast("Explanation copied.")
    }

    // ------------------------------------------------------------------ helpers

    private suspend fun recordUsage(s: LiveSession, usage: TokenUsage?) {
        if (usage == null) return
        s.usage += usage
        s.dbId?.let { container.history.addTokens(it, usage) }
    }

    private fun titleFor(q: DetectedQuestion): String {
        val parts = mutableListOf<String>()
        if (q.subject.isNotBlank() && q.subject != "Unknown") parts += q.subject
        if (settings.showChapterTopic && q.chapter.isNotBlank()) parts += q.chapter
        val head = parts.joinToString(" · ").ifBlank { "Question" }
        return "$head  →  ${AnswerFormatter.bubbleText(q)}"
    }

    private fun transcript(lq: LiveQuestion): String = buildString {
        append(lq.explanation.orEmpty())
        var i = 0
        while (i + 1 < lq.followUps.size) {
            append("\n\n──────\n**You:** ").append(lq.followUps[i].text).append("\n\n").append(lq.followUps[i + 1].text)
            i += 2
        }
    }

    private fun copy(label: String, text: String) {
        val cm = service.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
    }
}
