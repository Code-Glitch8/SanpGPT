package com.snapgpt.database

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAt: Long,
    val scanType: String,
    val examMode: String,
    val model: String,
    val thumbnailPath: String?,
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int,
    val summary: String,
)

@Entity(
    tableName = "questions",
    foreignKeys = [ForeignKey(entity = SessionEntity::class, parentColumns = ["id"], childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("sessionId")],
)
data class QuestionEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val sessionId: Long,
    val qid: String,
    val questionText: String,
    val subject: String,
    val chapter: String,
    val topic: String,
    val examLevel: String,
    val questionType: String,
    val answer: String,
    val answerType: String,
    val confidence: Float,
    val boxX: Float?,
    val boxY: Float?,
    val boxW: Float?,
    val boxH: Float?,
    val needsClarification: Boolean,
    val hasFigure: Boolean,
    val optionsJson: String,
    val note: String,
    val explanation: String?,
)

@Entity(
    tableName = "messages",
    foreignKeys = [ForeignKey(entity = QuestionEntity::class, parentColumns = ["rowId"], childColumns = ["questionRowId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("questionRowId")],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val questionRowId: Long,
    val role: String,
    val content: String,
    val createdAt: Long,
)

@Dao
interface SnapDao {
    @Insert
    suspend fun insertSession(s: SessionEntity): Long

    @Insert
    suspend fun insertQuestion(q: QuestionEntity): Long

    @Insert
    suspend fun insertMessage(m: MessageEntity): Long

    @Query("SELECT * FROM sessions ORDER BY createdAt DESC")
    fun observeSessions(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun session(id: Long): SessionEntity?

    @Query("SELECT * FROM questions WHERE sessionId = :sessionId ORDER BY rowId")
    suspend fun questions(sessionId: Long): List<QuestionEntity>

    @Query("SELECT COUNT(*) FROM questions WHERE sessionId = :sessionId")
    suspend fun questionCount(sessionId: Long): Int

    @Query("SELECT * FROM messages WHERE questionRowId = :rowId ORDER BY id")
    suspend fun messages(rowId: Long): List<MessageEntity>

    @Query("UPDATE questions SET explanation = :text WHERE rowId = :rowId")
    suspend fun setExplanation(rowId: Long, text: String?)

    @Query(
        "UPDATE sessions SET promptTokens = promptTokens + :p, completionTokens = completionTokens + :c, totalTokens = totalTokens + :t WHERE id = :id",
    )
    suspend fun addTokens(id: Long, p: Int, c: Int, t: Int)

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun deleteSession(id: Long)

    @Query("DELETE FROM sessions")
    suspend fun deleteAllSessions()

    @Query("DELETE FROM messages")
    suspend fun deleteAllMessages()

    @Query("UPDATE questions SET explanation = NULL")
    suspend fun clearAllExplanations()

    @Query("SELECT thumbnailPath FROM sessions WHERE thumbnailPath IS NOT NULL")
    suspend fun thumbnailPaths(): List<String>

    @Query("SELECT thumbnailPath FROM sessions WHERE id = :id")
    suspend fun thumbnailPath(id: Long): String?

    @Query("UPDATE sessions SET thumbnailPath = :path WHERE id = :id")
    suspend fun setThumbnail(id: Long, path: String?)

    @Query("UPDATE sessions SET thumbnailPath = NULL")
    suspend fun clearThumbnailPaths()
}

@Database(entities = [SessionEntity::class, QuestionEntity::class, MessageEntity::class], version = 1, exportSchema = false)
abstract class SnapDatabase : RoomDatabase() {
    abstract fun dao(): SnapDao

    companion object {
        fun create(context: Context): SnapDatabase =
            Room.databaseBuilder(context.applicationContext, SnapDatabase::class.java, "snapgpt.db").build()
    }
}
