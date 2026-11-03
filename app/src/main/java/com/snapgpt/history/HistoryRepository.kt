package com.snapgpt.history

import android.content.Context
import android.graphics.Bitmap
import com.snapgpt.ai.BoundingBox
import com.snapgpt.ai.ChatMessage
import com.snapgpt.ai.ChoiceOption
import com.snapgpt.ai.DetectedQuestion
import com.snapgpt.ai.TokenUsage
import com.snapgpt.capture.BitmapCodec
import com.snapgpt.database.MessageEntity
import com.snapgpt.database.QuestionEntity
import com.snapgpt.database.SessionEntity
import com.snapgpt.database.SnapDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.io.File

/** A stored question with its saved explanation and follow-up conversation. */
data class StoredQuestion(val rowId: Long, val question: DetectedQuestion, val explanation: String?)

data class StoredSession(val session: SessionEntity, val questions: List<StoredQuestion>)

/** Local scan history (Room). Screenshots are never uploaded; only a small thumbnail is kept, and only if enabled. */
class HistoryRepository(private val context: Context, private val db: SnapDatabase) {
    private val dao = db.dao()
    private val thumbDir get() = File(context.filesDir, "thumbs").apply { mkdirs() }

    fun observeSessions(): Flow<List<SessionEntity>> = dao.observeSessions()

    suspend fun saveSession(live: LiveSession, examMode: String, model: String, saveThumbnail: Boolean): Long = withContext(Dispatchers.IO) {
        val summary = live.questions.map { it.question.subject }.filter { it.isNotBlank() && it != "Unknown" }.distinct().joinToString(", ")
        val id = dao.insertSession(
            SessionEntity(
                createdAt = live.createdAt, scanType = live.scanType.name, examMode = examMode, model = model, thumbnailPath = null,
                promptTokens = live.usage.promptTokens, completionTokens = live.usage.completionTokens, totalTokens = live.usage.totalTokens,
                summary = summary,
            ),
        )
        if (saveThumbnail) live.screenshot?.let { writeThumbnail(id, it) }
        live.questions.forEach { lq -> lq.rowId = dao.insertQuestion(lq.question.toEntity(id, lq.explanation)) }
        live.dbId = id
        id
    }

    private suspend fun writeThumbnail(sessionId: Long, bmp: Bitmap) {
        val file = File(thumbDir, "$sessionId.jpg")
        file.writeBytes(BitmapCodec.thumbnail(bmp))
        dao.setThumbnail(sessionId, file.absolutePath)
    }

    suspend fun load(id: Long): StoredSession? = withContext(Dispatchers.IO) {
        val s = dao.session(id) ?: return@withContext null
        StoredSession(s, dao.questions(id).map { StoredQuestion(it.rowId, it.toQuestion(), it.explanation) })
    }

    suspend fun saveExplanation(rowId: Long, text: String?) = withContext(Dispatchers.IO) { dao.setExplanation(rowId, text) }

    suspend fun addTokens(sessionId: Long, usage: TokenUsage?) {
        if (usage == null) return
        withContext(Dispatchers.IO) { dao.addTokens(sessionId, usage.promptTokens, usage.completionTokens, usage.totalTokens) }
    }

    suspend fun addMessage(rowId: Long, role: String, content: String) = withContext(Dispatchers.IO) {
        dao.insertMessage(MessageEntity(questionRowId = rowId, role = role, content = content, createdAt = System.currentTimeMillis()))
    }

    suspend fun messages(rowId: Long): List<ChatMessage> = withContext(Dispatchers.IO) { dao.messages(rowId).map { ChatMessage(it.role, it.content) } }

    suspend fun delete(id: Long) = withContext(Dispatchers.IO) {
        dao.thumbnailPath(id)?.let { File(it).delete() }
        dao.deleteSession(id)
    }

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        deleteThumbFiles()
        dao.deleteAllSessions()
    }

    /** Privacy > Delete screenshots: removes thumbnails but keeps questions and answers. */
    suspend fun deleteScreenshots() = withContext(Dispatchers.IO) {
        deleteThumbFiles()
        dao.clearThumbnailPaths()
    }

    /** Privacy > Clear conversations: removes follow-up messages and saved explanations. */
    suspend fun clearConversations() = withContext(Dispatchers.IO) {
        dao.deleteAllMessages()
        dao.clearAllExplanations()
    }

    private fun deleteThumbFiles() {
        thumbDir.listFiles()?.forEach { it.delete() }
    }

    // ---- mapping

    private fun DetectedQuestion.toEntity(sessionId: Long, explanation: String?) = QuestionEntity(
        sessionId = sessionId, qid = id, questionText = questionText, subject = subject, chapter = chapter, topic = topic,
        examLevel = examLevel, questionType = questionType, answer = answer, answerType = answerType, confidence = confidence,
        boxX = boundingBox?.x, boxY = boundingBox?.y, boxW = boundingBox?.width, boxH = boundingBox?.height,
        needsClarification = needsClarification, hasFigure = hasFigure, optionsJson = optionsToJson(options), note = note,
        explanation = explanation, verification = verification.name, verificationNote = verificationNote, originalAnswer = originalAnswer,
    )

    private fun QuestionEntity.toQuestion(): DetectedQuestion {
        val box = if (boxX != null && boxY != null && boxW != null && boxH != null) BoundingBox(boxX, boxY, boxW, boxH) else null
        return DetectedQuestion(
            id = qid, questionText = questionText, subject = subject, chapter = chapter, topic = topic, examLevel = examLevel,
            questionType = questionType, answer = answer, answerType = answerType, confidence = confidence, boundingBox = box,
            needsClarification = needsClarification, options = optionsFromJson(optionsJson), hasFigure = hasFigure, note = note,
            verification = com.snapgpt.ai.VerificationStatus.entries.firstOrNull { it.name == verification } ?: com.snapgpt.ai.VerificationStatus.NOT_RUN,
            verificationNote = verificationNote, originalAnswer = originalAnswer,
        )
    }

    private fun optionsToJson(options: List<ChoiceOption>): String = buildJsonArray {
        options.forEach { add(buildJsonObject { put("label", it.label); put("text", it.text) }) }
    }.toString()

    private fun optionsFromJson(s: String): List<ChoiceOption> = try {
        (Json.parseToJsonElement(s) as JsonArray).mapNotNull {
            val o = it as? JsonObject ?: return@mapNotNull null
            ChoiceOption((o["label"] as? JsonPrimitive)?.contentOrNull ?: "", (o["text"] as? JsonPrimitive)?.contentOrNull ?: "")
        }
    } catch (_: Exception) {
        emptyList()
    }
}
