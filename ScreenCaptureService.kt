package com.snapgpt.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.snapgpt.R
import com.snapgpt.ai.GroqClient
import com.snapgpt.data.HistoryDatabase
import com.snapgpt.data.ScanHistory
import com.snapgpt.overlay.FloatingAssistantService
import com.snapgpt.overlay.OverlaySession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class ScreenCaptureService : Service() {
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var captureMode = CaptureConsentActivity.MODE_FULL
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var alreadyHandled = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_CAPTURE) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        captureMode = intent.getStringExtra(EXTRA_MODE) ?: CaptureConsentActivity.MODE_FULL
        startCaptureForeground()
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        @Suppress("DEPRECATION")
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            intent.getParcelableExtra(EXTRA_RESULT_DATA) as? Intent
        }
        if (resultCode != android.app.Activity.RESULT_OK || data == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        beginSingleCapture(resultCode, data, startId)
        return START_NOT_STICKY
    }

    private fun startCaptureForeground() {
        val channelId = "snapgpt_capture"
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(channelId, "Screen capture", NotificationManager.IMPORTANCE_LOW))
        }
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("SnapGPT screen scan")
            .setContentText(getString(R.string.capture_notification))
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else startForeground(NOTIFICATION_ID, notification)
    }

    private fun beginSingleCapture(resultCode: Int, data: Intent, startId: Int) {
        try {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(resultCode, data)
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
            val width = metrics.widthPixels.coerceAtLeast(1)
            val height = metrics.heightPixels.coerceAtLeast(1)
            thread = HandlerThread("SnapGPTCapture").also { it.start() }
            val handler = Handler(thread!!.looper)
            reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            projection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    projection = null
                    releaseDisplayAndReader()
                    thread?.quitSafely()
                    thread = null
                }
            }, handler)
            reader?.setOnImageAvailableListener({ imageReader ->
                if (alreadyHandled) return@setOnImageAvailableListener
                val image = imageReader.acquireLatestImage() ?: return@setOnImageAvailableListener
                alreadyHandled = true
                val bitmap = try { imageToBitmap(image) } catch (e: Exception) {
                    image.close(); fail("Could not process the screen image: ${e.message}", startId); return@setOnImageAvailableListener
                }
                image.close()
                releaseProjectionOnly()
                scope.launch {
                    try {
                        val file = saveBitmap(bitmap)
                        bitmap.recycle()
                        if (captureMode == CaptureConsentActivity.MODE_AREA) {
                            withContext(Dispatchers.Main) {
                                startActivity(Intent(this@ScreenCaptureService, CropActivity::class.java)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    .putExtra(CropActivity.EXTRA_IMAGE_PATH, file.absolutePath)
                                    .putExtra(CropActivity.EXTRA_SCREEN_WIDTH, width)
                                    .putExtra(CropActivity.EXTRA_SCREEN_HEIGHT, height))
                            }
                            stopSelf(startId)
                        } else {
                            analyzeFullScreen(file, width, height, startId)
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) { FloatingAssistantService.current?.showMessage("Scan failed: ${e.message ?: "Unknown error"}") }
                        stopSelf(startId)
                    }
                }
            }, handler)
            virtualDisplay = projection?.createVirtualDisplay(
                "SnapGPTScreenCapture", width, height, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader?.surface, null, handler
            )
            handler.postDelayed({
                if (!alreadyHandled) fail("No screen frame was received. Please try again.", startId)
            }, 7000)
        } catch (e: Exception) {
            fail("Screen capture could not start: ${e.message}", startId)
        }
    }

    private suspend fun analyzeFullScreen(file: File, width: Int, height: Int, startId: Int) {
        val result = GroqClient(this).analyzeScreenshot(file)
        OverlaySession.questions = result.questions
        OverlaySession.screenshot = file
        OverlaySession.sourceRegion = android.graphics.Rect(0, 0, width, height)
        OverlaySession.inputTokens = result.usage.promptTokens
        OverlaySession.outputTokens = result.usage.completionTokens
        getSharedPreferences("api_usage", MODE_PRIVATE).edit()
            .putInt("input_tokens", getSharedPreferences("api_usage", MODE_PRIVATE).getInt("input_tokens", 0) + result.usage.promptTokens)
            .putInt("output_tokens", getSharedPreferences("api_usage", MODE_PRIVATE).getInt("output_tokens", 0) + result.usage.completionTokens)
            .putInt("requests", getSharedPreferences("api_usage", MODE_PRIVATE).getInt("requests", 0) + 1).apply()
        if (com.snapgpt.data.SettingsStore(this).settings.first().saveHistory) {
            val dao = HistoryDatabase.get(this).historyDao()
            result.questions.forEach { q -> dao.insert(ScanHistory(
                createdAt = System.currentTimeMillis(), subject = q.subject, chapter = q.chapter,
                question = q.questionText, answer = q.answer, screenshotPath = file.absolutePath,
                inputTokens = result.usage.promptTokens, outputTokens = result.usage.completionTokens
            )) }
        }
        withContext(Dispatchers.Main) {
            FloatingAssistantService.current?.showResults(result.questions, file, android.graphics.Rect(0, 0, width, height))
                ?: run {
                    ContextCompat.startForegroundService(this@ScreenCaptureService,
                        Intent(this@ScreenCaptureService, FloatingAssistantService::class.java).setAction(FloatingAssistantService.ACTION_START))
                }
        }
        stopSelf(startId)
    }

    private fun imageToBitmap(image: android.media.Image): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width
        val paddedWidth = image.width + rowPadding / pixelStride
        val padded = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
        buffer.rewind()
        padded.copyPixelsFromBuffer(buffer)
        val result = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
        padded.recycle()
        return result
    }

    private fun saveBitmap(bitmap: Bitmap): File {
        val dir = File(cacheDir, "screenshots").apply { mkdirs() }
        val file = File(dir, "scan_${System.currentTimeMillis()}.jpg")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        return file
    }

    private fun fail(message: String, startId: Int) {
        android.os.Handler(mainLooper).post { FloatingAssistantService.current?.showMessage(message) }
        releaseCaptureResources()
        stopSelf(startId)
    }

    private fun releaseDisplayAndReader() {
        try { virtualDisplay?.release() } catch (_: Exception) { }
        virtualDisplay = null
        try { reader?.close() } catch (_: Exception) { }
        reader = null
    }

    private fun releaseProjectionOnly() {
        releaseDisplayAndReader()
        val activeProjection = projection
        projection = null
        try { activeProjection?.stop() } catch (_: Exception) { }
        thread?.quitSafely()
        thread = null
    }

    private fun releaseCaptureResources() {
        releaseProjectionOnly()
    }

    override fun onDestroy() {
        releaseCaptureResources()
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        const val ACTION_CAPTURE = "com.snapgpt.action.CAPTURE"
        const val EXTRA_RESULT_CODE = "capture_result_code"
        const val EXTRA_RESULT_DATA = "capture_result_data"
        const val EXTRA_MODE = "capture_mode"
        private const val NOTIFICATION_ID = 5002
    }
}
