package com.snapgpt.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "scan_history")
data class ScanHistory(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAt: Long,
    val subject: String,
    val chapter: String,
    val question: String,
    val answer: String,
    val screenshotPath: String,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0
)

@Dao
interface HistoryDao {
    @Insert suspend fun insert(row: ScanHistory)
    @Query("SELECT * FROM scan_history ORDER BY createdAt DESC LIMIT 100") fun observeAll(): Flow<List<ScanHistory>>
    @Query("DELETE FROM scan_history") suspend fun clear()
}

@Database(entities = [ScanHistory::class], version = 1, exportSchema = false)
abstract class HistoryDatabase : RoomDatabase() {
    abstract fun historyDao(): HistoryDao
    companion object {
        @Volatile private var instance: HistoryDatabase? = null
        fun get(context: Context): HistoryDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, HistoryDatabase::class.java, "snapgpt_history.db")
                .fallbackToDestructiveMigration().build().also { instance = it }
        }
    }
}
