package com.snapgpt.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.snapgpt.MainActivity
import com.snapgpt.R
import com.snapgpt.ai.DetectedQuestion
import com.snapgpt.ai.GroqClient
import com.snapgpt.capture.CaptureConsentActivity
import com.snapgpt.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class FloatingAssistantService : Service() {
    private lateinit var windowManager: WindowManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var floatingView: TextView? = null
    private var floatingParams: WindowManager.LayoutParams? = null
    private var menuView: View? = null
    private var menuParams: WindowManager.LayoutParams? = null
    private val answerViews = mutableListOf<View>()
    private var explanationView: View? = null
    private var explanationParams: WindowManager.LayoutParams? = null
    private var currentQuestion: DetectedQuestion? = null
    private var currentScreenshot: File? = null
    private var bubbleOpacity = 0.30f

    override fun onCreate() {
        super.onCreate()
        current = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        startAssistantForeground()
        createFloatingButton()
        scope.launch {
            bubbleOpacity = SettingsStore(this@FloatingAssistantService).settings.first().bubbleOpacity
            floatingView?.alpha = bubbleOpacity
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_HIDE -> { removeFloatingButton(); return START_STICKY }
            ACTION_START, null -> if (floatingView == null) createFloatingButton()
        }
        if (OverlaySession.questions.isNotEmpty() && OverlaySession.screenshot?.exists() == true) {
            showResults(OverlaySession.questions, OverlaySession.screenshot!!,
                OverlaySession.sourceRegion ?: screenRect())
        }
        return START_STICKY
    }

    private fun startAssistantForeground() {
        val channel = "snapgpt_assistant"
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(
            NotificationChannel(channel, "SnapGPT assistant", NotificationManager.IMPORTANCE_LOW)
        )
        val stopIntent = PendingIntent.getService(
            this, 21, Intent(this, FloatingAssistantService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(this, channel)
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentTitle("SnapGPT is ready")
            .setContentText(getString(R.string.assistant_notification))
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopIntent)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun createFloatingButton() {
        if (!android.provider.Settings.canDrawOverlays(this)) {
            showMessage("Allow Display over other apps in Android Settings first.")
            stopSelf()
            return
        }
        removeFloatingButton()
        val density = resources.displayMetrics.density
        val size = (52 * density).toInt()
        val button = TextView(this).apply {
            text = "S"
            textSize = 21f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = rounded(Color.rgb(42, 166, 147), 100f, Color.WHITE, 1.2f)
            alpha = bubbleOpacity
            elevation = 10f
            contentDescription = "SnapGPT floating assistant. Tap for scan options."
        }
        val prefs = getSharedPreferences("overlay_position", MODE_PRIVATE)
        val params = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_SECURE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt("x", (16 * density).toInt())
            y = prefs.getInt("y", (200 * density).toInt())
        }
        button.setOnTouchListener(object : View.OnTouchListener {
            private var downX = 0f
            private var downY = 0f
            private var startX = 0
            private var startY = 0
            private var moved = false
            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.rawX; downY = event.rawY
                        startX = params.x; startY = params.y; moved = false
                        v.alpha = 1f; v.scaleX = 1.08f; v.scaleY = 1.08f
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - downX).toInt()
                        val dy = (event.rawY - downY).toInt()
                        if (abs(dx) > 5 || abs(dy) > 5) moved = true
                        val (screenW, screenH) = screenSize()
                        params.x = (startX + dx).coerceIn(0, (screenW - size).coerceAtLeast(0))
                        params.y = (startY + dy).coerceIn(0, (screenH - size).coerceAtLeast(0))
                        try { windowManager.updateViewLayout(v, params) } catch (_: Exception) { }
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.alpha = bubbleOpacity; v.scaleX = 1f; v.scaleY = 1f
                        if (moved) {
                            val (screenW, screenH) = screenSize()
                            val rightSide = params.x > screenW / 2
                            params.x = if (rightSide) max(0, screenW - size) else 0
                            params.y = params.y.coerceIn(0, (screenH - size).coerceAtLeast(0))
                            try { windowManager.updateViewLayout(v, params) } catch (_: Exception) { }
                            prefs.edit().putInt("x", params.x).putInt("y", params.y).apply()
                        } else openMenu()
                        return true
                    }
                }
                return false
            }
        })
        floatingView = button
        floatingParams = params
        try { windowManager.addView(button, params) } catch (_: Exception) { floatingView = null }
    }

    private fun openMenu() {
        removeMenu()
        val density = resources.displayMetrics.density
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((10 * density).toInt(), (8 * density).toInt(), (10 * density).toInt(), (8 * density).toInt())
            background = rounded(Color.rgb(22, 34, 52), 20f, Color.rgb(75, 96, 124), 1f)
            elevation = 14f
        }
        addMenuAction(panel, "▣  Scan entire screen") { launchCapture(CaptureConsentActivity.MODE_FULL) }
        addMenuAction(panel, "⌗  Select question area") { launchCapture(CaptureConsentActivity.MODE_AREA) }
        addMenuAction(panel, "⚙  Settings") {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        }
        addMenuAction(panel, "×  Hide assistant") { removeMenu(); removeFloatingButton() }
        val p = WindowManager.LayoutParams(
            (220 * density).toInt(), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_SECURE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val fp = floatingParams
            x = ((fp?.x ?: 20) + 8 * density).toInt().coerceAtMost((screenSize().first - 230 * density).toInt().coerceAtLeast(0))
            y = ((fp?.y ?: 180) - 8 * density).toInt().coerceAtLeast(30)
        }
        menuView = panel; menuParams = p
        try { windowManager.addView(panel, p) } catch (_: Exception) { menuView = null }
    }

    private fun addMenuAction(parent: LinearLayout, label: String, action: () -> Unit) {
        val density = resources.displayMetrics.density
        val item = TextView(this).apply {
            text = label; textSize = 14f; setTextColor(Color.WHITE)
            setPadding((12 * density).toInt(), (13 * density).toInt(), (12 * density).toInt(), (13 * density).toInt())
            isClickable = true
            setOnClickListener { removeMenu(); action() }
        }
        parent.addView(item, LinearLayout.LayoutParams(-1, -2))
    }

    private fun launchCapture(mode: String) {
        val activity = Intent(this, com.snapgpt.capture.CaptureConsentActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(CaptureConsentActivity.EXTRA_MODE, mode)
        try { startActivity(activity) } catch (e: Exception) { showMessage("Could not open screen-capture permission: ${e.message}") }
    }

    fun showResults(questions: List<DetectedQuestion>, screenshot: File, region: Rect) {
        removeAnswerViews()
        currentScreenshot = screenshot
        OverlaySession.questions = questions
        OverlaySession.screenshot = screenshot
        OverlaySession.sourceRegion = region
        val (screenW, screenH) = screenSize()
        val density = resources.displayMetrics.density
        val occupied = mutableListOf<Rect>()
        questions.take(15).forEach { q ->
            if (q.answer.isBlank() || q.needsClarification) return@forEach
            val width = (max(68f, min(175f, q.answer.length * 9f + 30f)) * density).toInt()
            val height = (42 * density).toInt()
            val xEdge = region.left + ((q.boundingBox.x + q.boundingBox.width) * region.width()).toInt()
            val yCenter = region.top + ((q.boundingBox.y + q.boundingBox.height / 2.0) * region.height()).toInt()
            var x = (xEdge + 4 * density).toInt().coerceIn(0, (screenW - width).coerceAtLeast(0))
            var y = (yCenter - height / 2).coerceIn(24, (screenH - height - 24).coerceAtLeast(24))
            var candidate = Rect(x, y, x + width, y + height)
            var attempts = 0
            while (occupied.any { Rect.intersects(it, candidate) } && attempts < 8) {
                y = (y + height + (6 * density).toInt()).coerceAtMost(screenH - height - 24)
                candidate = Rect(x, y, x + width, y + height)
                attempts++
            }
            occupied += candidate
            val answerView = TextView(this).apply {
                text = if (q.questionType.contains("multiple", true) || q.answerType.contains("multiple", true)) q.answer.take(8) else q.answer.take(24)
                textSize = 14f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
                setPadding((10 * density).toInt(), (6 * density).toInt(), (10 * density).toInt(), (6 * density).toInt())
                background = rounded(Color.rgb(38, 143, 128), 28f, Color.WHITE, 1f)
                elevation = 12f; isClickable = true
                contentDescription = "Answer ${q.answer}. Tap for detailed explanation."
                setOnClickListener { openExplanation(q) }
            }
            val lp = WindowManager.LayoutParams(width, height,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_SECURE,
                PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; this.x = x; this.y = y }
            try { windowManager.addView(answerView, lp); answerViews += answerView } catch (_: Exception) { }
        }
        showMessage("Found ${questions.size} question(s). Tap an answer bubble for a JEE-level explanation.")
    }

    private fun openExplanation(question: DetectedQuestion) {
        removeExplanation()
        currentQuestion = question
        val density = resources.displayMetrics.density
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * density).toInt(), (14 * density).toInt(), (16 * density).toInt(), (14 * density).toInt())
            background = rounded(Color.rgb(19, 29, 45), 22f, Color.rgb(100, 130, 160), 1f)
            elevation = 18f
        }
        val title = TextView(this).apply {
            text = "${question.subject} · ${question.chapter}\n${question.answer}"
            textSize = 16f; setTextColor(Color.rgb(117, 224, 193)); setPadding(0, 0, 0, (8 * density).toInt())
        }
        var lastExplanation = ""
        val body = TextView(this).apply {
            text = "Generating a JEE-level explanation…"; textSize = 14f; setTextColor(Color.WHITE)
            setPadding(0, (4 * density).toInt(), 0, (12 * density).toInt())
        }
        val scroll = ScrollView(this).apply { addView(body) }
        val close = TextView(this).apply {
            text = "Close"; textSize = 14f; setTextColor(Color.rgb(117, 224, 193)); gravity = Gravity.END
            setPadding(0, (10 * density).toInt(), 0, 0); setOnClickListener { removeExplanation() }
        }
        val follow = EditText(this).apply {
            hint = "Ask a follow-up about this solution"; textSize = 13f; setTextColor(Color.WHITE); setHintTextColor(Color.LTGRAY)
            setSingleLine(true); setPadding((8 * density).toInt(), (4 * density).toInt(), (8 * density).toInt(), (4 * density).toInt())
        }
        val ask = TextView(this).apply {
            text = "Ask"; textSize = 14f; setTextColor(Color.rgb(117, 224, 193)); gravity = Gravity.CENTER
            setPadding((8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt())
        }
        ask.setOnClickListener {
            val prompt = follow.text.toString().trim()
            if (prompt.isNotEmpty()) {
                body.text = "Thinking…"
                scope.launch {
                    try {
                        val (answer, usage) = GroqClient(this@FloatingAssistantService).followUp(question, lastExplanation, prompt)
                        lastExplanation = answer
                        body.text = answer
                        addUsage(usage.promptTokens, usage.completionTokens)
                    } catch (e: Exception) { body.text = "Could not answer the follow-up: ${e.message}" }
                }
            }
        }
        panel.addView(title)
        panel.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        panel.addView(follow, LinearLayout.LayoutParams(-1, (48 * density).toInt()))
        panel.addView(ask)
        panel.addView(close)
        val (screenW, screenH) = screenSize()
        val panelWidth = min((360 * density).toInt(), screenW - (24 * density).toInt()).coerceAtLeast((250 * density).toInt())
        val panelHeight = (min(screenH * 0.72f, 590 * density)).toInt()
        val params = WindowManager.LayoutParams(panelWidth, panelHeight,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_SECURE,
            PixelFormat.TRANSLUCENT).apply {
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            x = (10 * density).toInt()
        }
        explanationView = panel; explanationParams = params
        try { windowManager.addView(panel, params) } catch (_: Exception) { explanationView = null }

        scope.launch {
            try {
                val file = currentScreenshot ?: OverlaySession.screenshot ?: error("Screenshot session expired. Scan again.")
                val (answer, usage) = withContext(Dispatchers.IO) { GroqClient(this@FloatingAssistantService).explain(question, file) }
                lastExplanation = answer
                body.text = answer
                addUsage(usage.promptTokens, usage.completionTokens)
            } catch (e: Exception) {
                body.text = "Explanation unavailable: ${e.message ?: "Unknown error"}"
            }
        }
    }

    private fun addUsage(input: Int, output: Int) {
        getSharedPreferences("api_usage", MODE_PRIVATE).edit()
            .putInt("input_tokens", getSharedPreferences("api_usage", MODE_PRIVATE).getInt("input_tokens", 0) + input)
            .putInt("output_tokens", getSharedPreferences("api_usage", MODE_PRIVATE).getInt("output_tokens", 0) + output)
            .putInt("requests", getSharedPreferences("api_usage", MODE_PRIVATE).getInt("requests", 0) + 1).apply()
    }

    fun showMessage(message: String) {
        Toast.makeText(this, message.take(220), Toast.LENGTH_LONG).show()
    }

    private fun screenSize(): Pair<Int, Int> {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        return metrics.widthPixels to metrics.heightPixels
    }

    private fun screenRect(): Rect { val (w, h) = screenSize(); return Rect(0, 0, w, h) }

    private fun rounded(fill: Int, radiusDp: Float, stroke: Int, strokeDp: Float): GradientDrawable = GradientDrawable().apply {
        setColor(fill); cornerRadius = radiusDp * resources.displayMetrics.density
        setStroke((strokeDp * resources.displayMetrics.density).toInt().coerceAtLeast(1), stroke)
    }

    private fun removeMenu() { menuView?.let { try { windowManager.removeView(it) } catch (_: Exception) { } }; menuView = null }
    private fun removeExplanation() { explanationView?.let { try { windowManager.removeView(it) } catch (_: Exception) { } }; explanationView = null; explanationParams = null }
    private fun removeAnswerViews() { answerViews.forEach { try { windowManager.removeView(it) } catch (_: Exception) { } }; answerViews.clear() }
    private fun removeFloatingButton() { floatingView?.let { try { windowManager.removeView(it) } catch (_: Exception) { } }; floatingView = null; floatingParams = null; removeMenu() }

    override fun onDestroy() {
        current = null
        removeMenu(); removeExplanation(); removeAnswerViews(); removeFloatingButton()
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        @Volatile var current: FloatingAssistantService? = null
            private set
        const val ACTION_START = "com.snapgpt.overlay.START"
        const val ACTION_STOP = "com.snapgpt.overlay.STOP"
        const val ACTION_HIDE = "com.snapgpt.overlay.HIDE"
        private const val NOTIFICATION_ID = 5001
    }
}
