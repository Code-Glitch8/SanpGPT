package com.snapgpt.capture

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.util.Base64
import com.snapgpt.ai.AiErrorCode
import com.snapgpt.ai.AiException
import com.snapgpt.ai.BoundingBox
import com.snapgpt.overlay.FRect
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

object BitmapCodec {
    class Encoded(val jpegBytes: ByteArray, val width: Int, val height: Int) {
        fun dataUrl(): String = "data:image/jpeg;base64," + Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
        fun sha256(): String = MessageDigest.getInstance("SHA-256").digest(jpegBytes).joinToString("") { "%02x".format(it) }
    }

    /**
     * Compresses to JPEG (correct MIME type "image/jpeg"), capping resolution per the quality preset. If the result is
     * still over the request budget, quality drops to the preset floor, then resolution shrinks in small steps.
     */
    fun encode(source: Bitmap, quality: ImageQuality): Encoded {
        val (tw, th) = ImageSizing.targetSize(source.width, source.height, quality.maxLongSide)
        var bmp = flatten(source, tw, th)
        var q = quality.jpegQuality
        var bytes = compress(bmp, q)
        var guard = 0
        while (bytes.size > ImageSizing.maxImageBytes() && guard++ < 14) {
            if (q > quality.minJpegQuality) {
                q = (q - 5).coerceAtLeast(quality.minJpegQuality)
            } else {
                val nw = (bmp.width * 0.85f).toInt().coerceAtLeast(64)
                val nh = (bmp.height * 0.85f).toInt().coerceAtLeast(64)
                val smaller = Bitmap.createScaledBitmap(bmp, nw, nh, true)
                if (bmp !== source) bmp.recycle()
                bmp = smaller
            }
            bytes = compress(bmp, q)
        }
        if (bytes.size > ImageSizing.maxImageBytes()) {
            throw AiException(AiErrorCode.IMAGE_TOO_LARGE, "The screenshot is too large to send. Select a smaller area or lower the image quality.")
        }
        val out = Encoded(bytes, bmp.width, bmp.height)
        if (bmp !== source) bmp.recycle()
        return out
    }

    /** JPEG has no alpha channel, so composite over white at the target size. */
    private fun flatten(src: Bitmap, w: Int, h: Int): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.WHITE)
        c.drawBitmap(src, null, android.graphics.Rect(0, 0, w, h), null)
        return out
    }

    private fun compress(bmp: Bitmap, quality: Int): ByteArray {
        val bos = ByteArrayOutputStream(256 * 1024)
        bmp.compress(Bitmap.CompressFormat.JPEG, quality, bos)
        return bos.toByteArray()
    }

    fun crop(src: Bitmap, rect: FRect): Bitmap {
        val l = rect.left.toInt().coerceIn(0, src.width - 1)
        val t = rect.top.toInt().coerceIn(0, src.height - 1)
        val w = (rect.right.toInt() - l).coerceIn(1, src.width - l)
        val h = (rect.bottom.toInt() - t).coerceIn(1, src.height - t)
        return Bitmap.createBitmap(src, l, t, w, h)
    }

    /** Crops a normalised box (plus a small padding fraction) out of [src]. */
    fun cropNormalized(src: Bitmap, box: BoundingBox, pad: Float): Bitmap {
        val x0 = (box.x - pad).coerceAtLeast(0f)
        val y0 = (box.y - pad).coerceAtLeast(0f)
        val x1 = (box.right + pad).coerceAtMost(1f)
        val y1 = (box.bottom + pad).coerceAtMost(1f)
        return crop(src, FRect(x0 * src.width, y0 * src.height, x1 * src.width, y1 * src.height))
    }

    fun thumbnail(src: Bitmap, maxSide: Int = 360): ByteArray {
        val (w, h) = ImageSizing.targetSize(src.width, src.height, maxSide)
        val t = flatten(src, w, h)
        val bytes = compress(t, 70)
        t.recycle()
        return bytes
    }

    /**
     * Protected content (FLAG_SECURE: banking apps, some video/exam apps, DRM) is delivered by Android as black pixels.
     * SnapGPT does not and cannot bypass that; it detects it and tells the user.
     */
    fun looksBlank(bmp: Bitmap): Boolean {
        val stepX = (bmp.width / 24).coerceAtLeast(1)
        val stepY = (bmp.height / 24).coerceAtLeast(1)
        var y = 0
        while (y < bmp.height) {
            var x = 0
            while (x < bmp.width) {
                val p = bmp.getPixel(x, y)
                val lum = (Color.red(p) + Color.green(p) + Color.blue(p)) / 3
                if (Color.alpha(p) > 8 && lum > 10) return false
                x += stepX
            }
            y += stepY
        }
        return true
    }
}
