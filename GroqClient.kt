package com.snapgpt.ai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.snapgpt.data.SecureKeyStore
import com.snapgpt.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

class GroqClient(private val context: android.content.Context) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .callTimeout(110, TimeUnit.SECONDS)
        .build()

    suspend fun testConnection(): String = withContext(Dispatchers.IO) {
        val key = SecureKeyStore.read(context) ?: error("Add your Groq API key in Settings first.")
        val req = Request.Builder()
            .url("https://api.groq.com/openai/v1/models")
            .header("Authorization", "Bearer $key")
            .get().build()
        http.newCall(req).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw apiError(response.code, body)
            "Connected to Groq."
        }
    }

    suspend fun analyzeScreenshot(imageFile: File): ScanResult = withContext(Dispatchers.IO) {
        val key = SecureKeyStore.read(context) ?: error("Add your Groq API key in SnapGPT Settings.")
        val settings = SettingsStore(context).settings.first()
        val image = encodeImage(imageFile)
        val content = JSONArray()
            .put(JSONObject().put("type", "text").put("text", JeePrompts.stageOne(settings)))
            .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", image)))
        val message = JSONObject().put("role", "user").put("content", content)
        val payload = JSONObject()
            .put("model", settings.model)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", JeePrompts.system(settings)))
                .put(message))
            .put("temperature", 0.2)
            .put("max_completion_tokens", 2600)
            .put("response_format", JSONObject().put("type", "json_object"))
        val json = post(key, payload)
        val contentText = json.getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").optString("content")
        val parsed = parseQuestions(contentText)
        val usage = readUsage(json)
        ScanResult(parsed, usage, contentText)
    }

    suspend fun explain(question: DetectedQuestion, screenshot: File): Pair<String, AiUsage> = withContext(Dispatchers.IO) {
        val key = SecureKeyStore.read(context) ?: error("Add your Groq API key in Settings.")
        val settings = SettingsStore(context).settings.first()
        val image = cropQuestion(screenshot, question.boundingBox)
        val userContent = JSONArray()
            .put(JSONObject().put("type", "text").put("text", JeePrompts.stageTwo(question, settings)))
            .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", image)))
        val payload = JSONObject()
            .put("model", settings.model)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", JeePrompts.system(settings)))
                .put(JSONObject().put("role", "user").put("content", userContent)))
            .put("temperature", 0.3)
            .put("max_completion_tokens", 1800)
        val json = post(key, payload)
        val answer = json.getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").optString("content", "The model returned an empty explanation.")
        answer to readUsage(json)
    }

    suspend fun followUp(question: DetectedQuestion, previousExplanation: String, userMessage: String): Pair<String, AiUsage> = withContext(Dispatchers.IO) {
        val key = SecureKeyStore.read(context) ?: error("Add your Groq API key in SnapGPT Settings.")
        val settings = SettingsStore(context).settings.first()
        val contextText = "Question: ${question.questionText}\nDetected answer: ${question.answer}\nSubject/chapter: ${question.subject} / ${question.chapter}\nPrevious explanation: ${previousExplanation.take(9000)}\nStudent follow-up: ${userMessage}\nAnswer the follow-up in the context of this JEE practice question. Keep to the selected JEE syllabus mode."
        val payload = JSONObject()
            .put("model", settings.model)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", JeePrompts.system(settings)))
                .put(JSONObject().put("role", "user").put("content", contextText)))
            .put("temperature", 0.3)
            .put("max_completion_tokens", 1000)
        val json = post(key, payload)
        val answer = json.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content")
        answer to readUsage(json)
    }

    private fun post(key: String, payload: JSONObject): JSONObject {
        val request = Request.Builder()
            .url("https://api.groq.com/openai/v1/chat/completions")
            .header("Authorization", "Bearer $key")
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        val response = http.newCall(request).execute()
        return try {
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw apiError(response.code, text)
            JSONObject(text)
        } finally {
            response.close()
        }
    }

    private fun apiError(code: Int, body: String): Exception {
        val message = try { JSONObject(body).optJSONObject("error")?.optString("message") } catch (_: Exception) { null }
        return when (code) {
            401 -> IllegalStateException("Groq rejected the API key (HTTP 401). Check the key in Settings.")
            429 -> IllegalStateException("Groq rate limit or quota reached (HTTP 429). Please check your Groq console and retry later.")
            400 -> IllegalStateException("Groq could not process this request (HTTP 400): ${message ?: "Check the selected model and image size."}")
            else -> IllegalStateException("Groq request failed (HTTP $code): ${message ?: body.take(240)}")
        }
    }

    private fun encodeImage(file: File): String {
        val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: error("Could not read the captured screenshot.")
        return try { bitmapToDataUrl(bitmap) } finally { bitmap.recycle() }
    }

    private fun cropQuestion(file: File, box: BoundingBox): String {
        val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: error("Could not read the captured screenshot.")
        return try {
            val left = (box.x.coerceIn(0.0, 1.0) * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
            val top = (box.y.coerceIn(0.0, 1.0) * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
            val right = ((box.x + box.width).coerceIn(0.0, 1.0) * bitmap.width).toInt().coerceIn(left + 1, bitmap.width)
            val bottom = ((box.y + box.height).coerceIn(0.0, 1.0) * bitmap.height).toInt().coerceIn(top + 1, bitmap.height)
            val crop = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
            try { bitmapToDataUrl(crop) } finally { crop.recycle() }
        } finally { bitmap.recycle() }
    }

    private fun bitmapToDataUrl(source: Bitmap): String {
        var bitmap = source
        var owned: Bitmap? = null
        try {
            val maxDimension = 2300
            if (bitmap.width > maxDimension || bitmap.height > maxDimension) {
                val scale = minOf(maxDimension.toFloat() / bitmap.width, maxDimension.toFloat() / bitmap.height)
                owned = Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
                bitmap = owned
            }
            var quality = 84
            var bytes: ByteArray
            do {
                val out = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
                bytes = out.toByteArray()
                quality -= 8
            } while (bytes.size > 12 * 1024 * 1024 && quality >= 48)
            require(bytes.size <= 14 * 1024 * 1024) { "Screenshot remains too large after compression. Try selecting a smaller area." }
            return "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
        } finally { owned?.recycle() }
    }

    private fun parseQuestions(text: String): List<DetectedQuestion> {
        val clean = text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val root = JSONObject(clean)
        val array = root.optJSONArray("questions") ?: JSONArray()
        return (0 until array.length()).mapNotNull { index ->
            val q = array.optJSONObject(index) ?: return@mapNotNull null
            val b = q.optJSONObject("bounding_box") ?: JSONObject()
            val optArray = q.optJSONArray("options") ?: JSONArray()
            val options = (0 until optArray.length()).mapNotNull { i ->
                val o = optArray.optJSONObject(i) ?: return@mapNotNull null
                AnswerOption(o.optString("label"), o.optString("text"))
            }
            DetectedQuestion(
                id = q.optString("id", "q${index + 1}"),
                questionText = q.optString("question_text"),
                subject = q.optString("subject", "uncertain"),
                chapter = q.optString("chapter", "uncertain"),
                topic = q.optString("topic", "uncertain"),
                examLevel = q.optString("exam_level", "uncertain"),
                questionType = q.optString("question_type", "other"),
                options = options,
                answer = q.optString("answer"),
                answerType = q.optString("answer_type", "text"),
                confidence = q.optDouble("confidence", 0.0).coerceIn(0.0, 1.0),
                boundingBox = BoundingBox(
                    b.optDouble("x", 0.0).coerceIn(0.0, 1.0),
                    b.optDouble("y", 0.0).coerceIn(0.0, 1.0),
                    b.optDouble("width", 0.1).coerceIn(0.01, 1.0),
                    b.optDouble("height", 0.1).coerceIn(0.01, 1.0)
                ),
                needsClarification = q.optBoolean("needs_clarification", false)
            )
        }
    }

    private fun readUsage(json: JSONObject): AiUsage {
        val usage = json.optJSONObject("usage") ?: return AiUsage()
        return AiUsage(usage.optInt("prompt_tokens"), usage.optInt("completion_tokens"))
    }
}
