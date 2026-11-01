package com.snapgpt.capture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.graphics.RectF
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.content.ContextCompat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.snapgpt.ai.GroqClient
import com.snapgpt.data.HistoryDatabase
import com.snapgpt.data.ScanHistory
import com.snapgpt.data.SettingsStore
import com.snapgpt.overlay.FloatingAssistantService
import com.snapgpt.overlay.OverlaySession
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min

class CropActivity : ComponentActivity() {
    private lateinit var originalFile: File
    private lateinit var sourceBitmap: Bitmap
    private var screenWidth = 1
    private var screenHeight = 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra(EXTRA_IMAGE_PATH) ?: run { finish(); return }
        originalFile = File(path)
        sourceBitmap = BitmapFactory.decodeFile(path) ?: run { finish(); return }
        screenWidth = intent.getIntExtra(EXTRA_SCREEN_WIDTH, sourceBitmap.width).coerceAtLeast(1)
        screenHeight = intent.getIntExtra(EXTRA_SCREEN_HEIGHT, sourceBitmap.height).coerceAtLeast(1)
        setContent {
            MaterialTheme {
                CropScreen(
                    bitmap = sourceBitmap,
                    onCancel = { finish() },
                    onAnalyze = { rect -> analyzeSelection(rect) }
                )
            }
        }
    }

    private fun analyzeSelection(rect: RectF) {
        lifecycleScope.launch {
            try {
                val left = rect.left.toInt().coerceIn(0, sourceBitmap.width - 1)
                val top = rect.top.toInt().coerceIn(0, sourceBitmap.height - 1)
                val right = rect.right.toInt().coerceIn(left + 1, sourceBitmap.width)
                val bottom = rect.bottom.toInt().coerceIn(top + 1, sourceBitmap.height)
                val crop = Bitmap.createBitmap(sourceBitmap, left, top, right - left, bottom - top)
                val cropFile = File(cacheDir, "selected_${System.currentTimeMillis()}.jpg")
                FileOutputStream(cropFile).use { crop.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                crop.recycle()
                val result = GroqClient(this@CropActivity).analyzeScreenshot(cropFile)
                val mapped = result.questions.map { q ->
                    val b = q.boundingBox
                    val mappedX = (left + b.x * (right - left)) / sourceBitmap.width.toDouble()
                    val mappedY = (top + b.y * (bottom - top)) / sourceBitmap.height.toDouble()
                    val mappedW = b.width * (right - left) / sourceBitmap.width.toDouble()
                    val mappedH = b.height * (bottom - top) / sourceBitmap.height.toDouble()
                    q.copy(boundingBox = q.boundingBox.copy(x = mappedX, y = mappedY, width = mappedW, height = mappedH))
                }
                OverlaySession.questions = mapped
                OverlaySession.screenshot = originalFile
                OverlaySession.sourceRegion = Rect(0, 0, screenWidth, screenHeight)
                OverlaySession.inputTokens = result.usage.promptTokens
                OverlaySession.outputTokens = result.usage.completionTokens
                val usagePrefs = getSharedPreferences("api_usage", MODE_PRIVATE)
                usagePrefs.edit()
                    .putInt("input_tokens", usagePrefs.getInt("input_tokens", 0) + result.usage.promptTokens)
                    .putInt("output_tokens", usagePrefs.getInt("output_tokens", 0) + result.usage.completionTokens)
                    .putInt("requests", usagePrefs.getInt("requests", 0) + 1).apply()
                if (SettingsStore(this@CropActivity).settings.first().saveHistory) {
                    mapped.forEach { q -> HistoryDatabase.get(this@CropActivity).historyDao().insert(
                        ScanHistory(
                            createdAt = System.currentTimeMillis(), subject = q.subject, chapter = q.chapter,
                            question = q.questionText, answer = q.answer, screenshotPath = originalFile.absolutePath,
                            inputTokens = result.usage.promptTokens, outputTokens = result.usage.completionTokens
                        )
                    ) }
                }
                val overlay = FloatingAssistantService.current
                if (overlay != null) {
                    overlay.showResults(mapped, originalFile, Rect(0, 0, screenWidth, screenHeight))
                    overlay.showMessage("Found ${mapped.size} question(s). Tap an answer bubble for an explanation.")
                } else {
                    ContextCompat.startForegroundService(this@CropActivity,
                        Intent(this@CropActivity, FloatingAssistantService::class.java).setAction(FloatingAssistantService.ACTION_START))
                }
                finish()
            } catch (e: Exception) {
                FloatingAssistantService.current?.showMessage("Scan failed: ${e.message ?: "Unknown error"}")
                finish()
            }
        }
    }

    override fun onDestroy() {
        if (::sourceBitmap.isInitialized && !sourceBitmap.isRecycled) sourceBitmap.recycle()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_IMAGE_PATH = "image_path"
        const val EXTRA_SCREEN_WIDTH = "screen_width"
        const val EXTRA_SCREEN_HEIGHT = "screen_height"
    }
}

@Composable
private fun CropScreen(bitmap: Bitmap, onCancel: () -> Unit, onAnalyze: (RectF) -> Unit) {
    var dragStart by remember { mutableStateOf<Offset?>(null) }
    var dragEnd by remember { mutableStateOf<Offset?>(null) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("Drag around one question or a group of questions.") }

    Column(
        Modifier.fillMaxSize().background(Color(0xFF101827)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Select question area", color = Color.White, style = MaterialTheme.typography.titleLarge)
        Text(message, color = Color(0xFFBDCADB), style = MaterialTheme.typography.bodySmall)
        Box(Modifier.weight(1f).fillMaxWidth().background(Color(0xFF172337), RoundedCornerShape(16.dp)).padding(4.dp)) {
            Canvas(
                Modifier.fillMaxSize()
                    .onSizeChanged { canvasSize = it }
                    .pointerInput(bitmap, canvasSize) {
                        detectDragGestures(
                            onDragStart = { start -> dragStart = start; dragEnd = start },
                            onDrag = { change, _ -> dragEnd = change.position },
                            onDragEnd = { }
                        )
                    }
            ) {
                val scale = min(size.width / bitmap.width.toFloat(), size.height / bitmap.height.toFloat())
                val drawW = bitmap.width * scale
                val drawH = bitmap.height * scale
                val offsetX = (size.width - drawW) / 2f
                val offsetY = (size.height - drawH) / 2f
                drawImage(
                    image = bitmap.asImageBitmap(),
                    dstOffset = androidx.compose.ui.unit.IntOffset(offsetX.toInt(), offsetY.toInt()),
                    dstSize = IntSize(drawW.toInt(), drawH.toInt())
                )
                val a = dragStart
                val b = dragEnd
                if (a != null && b != null) {
                    val l = max(offsetX, min(a.x, b.x))
                    val t = max(offsetY, min(a.y, b.y))
                    val r = min(offsetX + drawW, max(a.x, b.x))
                    val bot = min(offsetY + drawH, max(a.y, b.y))
                    if (r > l && bot > t) {
                        drawRect(Color(0x5575E0C1), Offset(l, t), Size(r - l, bot - t))
                        drawRect(Color(0xFF75E0C1), Offset(l, t), Size(r - l, bot - t), style = Stroke(width = 3f))
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f), enabled = !busy) { Text("Cancel") }
            Button(
                onClick = {
                    val a = dragStart; val b = dragEnd
                    if (a == null || b == null || canvasSize.width == 0 || canvasSize.height == 0) {
                        message = "Select an area first."
                    } else {
                        val scale = min(canvasSize.width / bitmap.width.toFloat(), canvasSize.height / bitmap.height.toFloat())
                        val drawW = bitmap.width * scale
                        val drawH = bitmap.height * scale
                        val offsetX = (canvasSize.width - drawW) / 2f
                        val offsetY = (canvasSize.height - drawH) / 2f
                        val left = (min(a.x, b.x) - offsetX).coerceIn(0f, drawW) / scale
                        val top = (min(a.y, b.y) - offsetY).coerceIn(0f, drawH) / scale
                        val right = (max(a.x, b.x) - offsetX).coerceIn(0f, drawW) / scale
                        val bottom = (max(a.y, b.y) - offsetY).coerceIn(0f, drawH) / scale
                        if (right - left < 20 || bottom - top < 20) message = "Please select a larger area."
                        else {
                            busy = true
                            message = "Analysing selected area with Groq…"
                            onAnalyze(RectF(left, top, right, bottom))
                        }
                    }
                }, modifier = Modifier.weight(1f), enabled = !busy
            ) { if (busy) CircularProgressIndicator() else Text("Analyse selection") }
        }
        Spacer(Modifier.height(2.dp))
    }
}
