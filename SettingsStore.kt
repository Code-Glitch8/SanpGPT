package com.snapgpt.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.snapDataStore by preferencesDataStore(name = "snapgpt_settings")

data class UserSettings(
    val model: String = "qwen/qwen3.8-27b",
    val examMode: String = "Automatic",
    val syllabusRestriction: Boolean = true,
    val showChapter: Boolean = true,
    val maxQuestions: Int = 8,
    val saveHistory: Boolean = true,
    val bubbleOpacity: Float = 0.30f
)

class SettingsStore(private val context: Context) {
    private val modelKey = stringPreferencesKey("model")
    private val examModeKey = stringPreferencesKey("exam_mode")
    private val syllabusKey = booleanPreferencesKey("syllabus_restriction")
    private val chapterKey = booleanPreferencesKey("show_chapter")
    private val maxQuestionsKey = stringPreferencesKey("max_questions")
    private val historyKey = booleanPreferencesKey("save_history")
    private val opacityKey = stringPreferencesKey("bubble_opacity")

    val settings: Flow<UserSettings> = context.snapDataStore.data.map { p ->
        UserSettings(
            model = p[modelKey] ?: "qwen/qwen3.8-27b",
            examMode = p[examModeKey] ?: "Automatic",
            syllabusRestriction = p[syllabusKey] ?: true,
            showChapter = p[chapterKey] ?: true,
            maxQuestions = (p[maxQuestionsKey]?.toIntOrNull() ?: 8).coerceIn(1, 15),
            saveHistory = p[historyKey] ?: true,
            bubbleOpacity = (p[opacityKey]?.toFloatOrNull() ?: 0.30f).coerceIn(0.15f, 1f)
        )
    }

    suspend fun update(transform: (UserSettings) -> UserSettings) {
        context.snapDataStore.edit { p ->
            val current = UserSettings(
                model = p[modelKey] ?: "qwen/qwen3.8-27b",
                examMode = p[examModeKey] ?: "Automatic",
                syllabusRestriction = p[syllabusKey] ?: true,
                showChapter = p[chapterKey] ?: true,
                maxQuestions = (p[maxQuestionsKey]?.toIntOrNull() ?: 8).coerceIn(1, 15),
                saveHistory = p[historyKey] ?: true,
                bubbleOpacity = (p[opacityKey]?.toFloatOrNull() ?: 0.30f).coerceIn(0.15f, 1f)
            )
            val next = transform(current)
            p[modelKey] = next.model
            p[examModeKey] = next.examMode
            p[syllabusKey] = next.syllabusRestriction
            p[chapterKey] = next.showChapter
            p[maxQuestionsKey] = next.maxQuestions.toString()
            p[historyKey] = next.saveHistory
            p[opacityKey] = next.bubbleOpacity.toString()
        }
    }
}
