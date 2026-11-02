package com.snapgpt.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.snapgpt.ai.AiDefaults
import com.snapgpt.capture.ImageQuality
import com.snapgpt.jee.ExamMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.settingsStore by preferencesDataStore(name = "snapgpt_settings")

/** DataStore-backed settings. The API key is NOT stored here (see [SecureKeyStore]). */
class SettingsRepository(private val context: Context) {

    private object K {
        val circleSize = intPreferencesKey("circle_size_dp")
        val opacity = intPreferencesKey("idle_opacity")
        val edgeSnap = booleanPreferencesKey("edge_snapping")
        val defaultPos = stringPreferencesKey("default_position")
        val lastX = floatPreferencesKey("last_pos_x")
        val lastY = floatPreferencesKey("last_pos_y")
        val model = stringPreferencesKey("model")
        val examMode = stringPreferencesKey("exam_mode")
        val syllabus = booleanPreferencesKey("syllabus_restriction")
        val chapter = booleanPreferencesKey("show_chapter_topic")
        val altMethods = booleanPreferencesKey("show_alt_methods")
        val maxQuestions = intPreferencesKey("max_questions")
        val imageQuality = stringPreferencesKey("image_quality")
        val autoPlacement = booleanPreferencesKey("auto_answer_placement")
        val alwaysImage = booleanPreferencesKey("always_attach_image")
        val theme = stringPreferencesKey("theme")
        val history = booleanPreferencesKey("history_enabled")
        val thumbs = booleanPreferencesKey("save_thumbnails")
    }

    val flow: Flow<AppSettings> = context.settingsStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it.toSettings() }

    suspend fun current(): AppSettings = flow.first()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.settingsStore.edit { prefs -> prefs.write(transform(prefs.toSettings())) }
    }

    /** Remembers the circle position as fractions so it survives rotation and different screen sizes. */
    suspend fun savePosition(fx: Float, fy: Float) {
        context.settingsStore.edit {
            it[K.lastX] = fx.coerceIn(0f, 1f)
            it[K.lastY] = fy.coerceIn(0f, 1f)
        }
    }

    private fun Preferences.toSettings(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            circleSizeDp = (this[K.circleSize] ?: d.circleSizeDp).coerceIn(AppSettings.MIN_CIRCLE_DP, AppSettings.MAX_CIRCLE_DP),
            idleOpacityPercent = (this[K.opacity] ?: d.idleOpacityPercent).coerceIn(AppSettings.MIN_OPACITY, AppSettings.MAX_OPACITY),
            edgeSnapping = this[K.edgeSnap] ?: d.edgeSnapping,
            defaultPosition = DefaultPosition.fromKey(this[K.defaultPos]),
            lastPosX = this[K.lastX] ?: d.lastPosX,
            lastPosY = this[K.lastY] ?: d.lastPosY,
            model = (this[K.model] ?: AiDefaults.DEFAULT_MODEL).ifBlank { AiDefaults.DEFAULT_MODEL },
            examMode = ExamMode.fromKey(this[K.examMode]),
            syllabusRestriction = this[K.syllabus] ?: d.syllabusRestriction,
            showChapterTopic = this[K.chapter] ?: d.showChapterTopic,
            showAlternativeMethods = this[K.altMethods] ?: d.showAlternativeMethods,
            maxQuestionsPerScan = (this[K.maxQuestions] ?: d.maxQuestionsPerScan).coerceIn(AppSettings.MIN_QUESTIONS, AppSettings.MAX_QUESTIONS),
            imageQuality = ImageQuality.fromKey(this[K.imageQuality]),
            autoAnswerPlacement = this[K.autoPlacement] ?: d.autoAnswerPlacement,
            alwaysAttachImageInExplanation = this[K.alwaysImage] ?: d.alwaysAttachImageInExplanation,
            theme = ThemeMode.fromKey(this[K.theme]),
            historyEnabled = this[K.history] ?: d.historyEnabled,
            saveThumbnails = this[K.thumbs] ?: d.saveThumbnails,
        )
    }

    private fun androidx.datastore.preferences.core.MutablePreferences.write(s: AppSettings) {
        this[K.circleSize] = s.circleSizeDp
        this[K.opacity] = s.idleOpacityPercent
        this[K.edgeSnap] = s.edgeSnapping
        this[K.defaultPos] = s.defaultPosition.key
        this[K.lastX] = s.lastPosX
        this[K.lastY] = s.lastPosY
        this[K.model] = s.model
        this[K.examMode] = s.examMode.key
        this[K.syllabus] = s.syllabusRestriction
        this[K.chapter] = s.showChapterTopic
        this[K.altMethods] = s.showAlternativeMethods
        this[K.maxQuestions] = s.maxQuestionsPerScan
        this[K.imageQuality] = s.imageQuality.key
        this[K.autoPlacement] = s.autoAnswerPlacement
        this[K.alwaysImage] = s.alwaysAttachImageInExplanation
        this[K.theme] = s.theme.key
        this[K.history] = s.historyEnabled
        this[K.thumbs] = s.saveThumbnails
    }
}
