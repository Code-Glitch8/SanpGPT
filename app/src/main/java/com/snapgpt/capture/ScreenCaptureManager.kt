package com.snapgpt.capture

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import com.snapgpt.ai.AiErrorCode
import com.snapgpt.ai.AiException
import com.snapgpt.util.Screen
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * User-authorised screen capture through MediaProjection.
 *
 * Privacy design: the virtual display exists for the life of the authorised session (Android 14+ allows only one per
 * projection), but its output surface is detached except for the few hundred milliseconds of a capture that the user
 * triggered. Nothing is recorded, buffered or sent in the background.
 */
class ScreenCaptureManager(private val context: Context) {
    private val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var thread: HandlerThread? = null
    private var bgHandler: Handler? = null

    private var projection: MediaProjection? = null
    private var callback: MediaProjection.Callback? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var curW = 0
    private var curH = 0

    /** Called when the user (or the system) ends the capture session, e.g. via the status-bar chip. */
    var onStopped: (() -> Unit)? = null

    val isReady: Boolean get() = projection != null

    fun attach(resultCode: Int, data: Intent) {
        teardown(stopProjection = true)
        val p = mpm.getMediaProjection(resultCode, data)
        // Android 14+: the callback must be registered before createVirtualDisplay().
        val cb = object : MediaProjection.Callback() {
            override fun onStop() {
                teardown(stopProjection = false)
                onStopped?.invoke()
            }
        }
        p.registerCallback(cb, mainHandler)
        projection = p
        callback = cb
        if (thread == null) {
            thread = HandlerThread("snapgpt-capture").also { it.start() }
            bgHandler = Handler(thread!!.looper)
        }
    }

    fun release() {
        teardown(stopProjection = true)
        thread?.quitSafely()
        thread = null
        bgHandler = null
    }

    private fun teardown(stopProjection: Boolean) {
        runCatching { display?.release() }
        display = null
        runCatching { reader?.close() }
        reader = null
        curW = 0
        curH = 0
        val p = projection
        projection = null
        val cb = callback
        callback = null
        if (p != null) {
            if (cb != null) runCatching { p.unregisterCallback(cb) }
            if (stopProjection) runCatching { p.stop() }
        }
    }

    /** Captures exactly one frame of the real display. Call only in response to a user action. */
    suspend fun capture(): Bitmap {
        val p = projection ?: throw AiException(AiErrorCode.NOT_READY, "Screen capture permission is not active. Tap Scan again and allow screen capture.")
        val (w, h) = Screen.realSize(context)
        ensureDisplay(p, w, h)
        val r = reader!!
        val d = display!!
        // Drop any stale frame left in the reader.
        runCatching { r.acquireLatestImage()?.close() }
        try {
            var bmp = withTimeoutOrNull(900) { waitForFrame(r, d) }
            if (bmp == null) {
                // Static screens may not emit a frame right away: detach and re-attach once.
                d.surface = null
                delay(80)
                bmp = withTimeoutOrNull(2500) { waitForFrame(r, d) }
            }
            return bmp ?: throw AiException(AiErrorCode.CAPTURE, "Android did not deliver a screen frame. Please try again.")
        } finally {
            runCatching { r.setOnImageAvailableListener(null, null) }
            runCatching { d.surface = null } // stop rendering into our buffer immediately
        }
    }

    private fun ensureDisplay(p: MediaProjection, w: Int, h: Int) {
        val dpi = Screen.densityDpi(context)
        if (display == null) {
            reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
            display = p.createVirtualDisplay(
                "SnapGPT", w, h, dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, null, null, null,
            )
        } else if (w != curW || h != curH) {
            runCatching { reader?.close() }
            reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
            display!!.resize(w, h, dpi)
        }
        curW = w
        curH = h
    }

    private suspend fun waitForFrame(r: ImageReader, d: VirtualDisplay): Bitmap = suspendCancellableCoroutine { cont ->
        var done = false
        val listener = ImageReader.OnImageAvailableListener { reader ->
            if (done) return@OnImageAvailableListener
            val image = try { reader.acquireLatestImage() } catch (_: Exception) { null } ?: return@OnImageAvailableListener
            done = true
            try {
                cont.resume(toBitmap(image))
            } catch (t: Throwable) {
                cont.resumeWithException(t)
            } finally {
                image.close()
            }
        }
        r.setOnImageAvailableListener(listener, bgHandler)
        d.surface = r.surface
        cont.invokeOnCancellation {
            runCatching { r.setOnImageAvailableListener(null, null) }
            runCatching { d.surface = null }
        }
    }

    private fun toBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val pixelStride = plane.pixelStride
        val rowPadding = plane.rowStride - pixelStride * image.width
        val wide = Bitmap.createBitmap(image.width + rowPadding / pixelStride, image.height, Bitmap.Config.ARGB_8888)
        wide.copyPixelsFromBuffer(plane.buffer)
        if (rowPadding == 0) return wide
        val exact = Bitmap.createBitmap(wide, 0, 0, image.width, image.height)
        wide.recycle()
        return exact
    }
}
