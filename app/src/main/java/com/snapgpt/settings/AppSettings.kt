package com.snapgpt.settings

import com.snapgpt.capture.ImageQuality
import com.snapgpt.jee.ExamMode
import com.snapgpt.jee.JeeConfig

enum class ThemeMode(val key: String, val label: String) {
    SYSTEM("system", "System default"), LIGHT("light", "Light"), DARK("dark", "Dark");

    companion object {
        fun fromKey(key: String?) = entries.firstOrNull { it.key == key } ?: SYSTEM
    }
}

/** Where the floating circle first appears (fractions of the usable screen area). */
enum class DefaultPosition(val key: String, val label: String, val fx: Float, val fy: Float) {
    RIGHT_MIDDLE("right_middle", "Right edge, middle", 1f, 0.45f),
    LEFT_MIDDLE("left_middle", "Left edge, middle", 0f, 0.45f),
    RIGHT_TOP("right_top", "Right edge, top", 1f, 0.12f),
    RIGHT_BOTTOM("right_bottom", "Right edge, bottom", 1f, 0.8f);

    companion object {
        fun fromKey(key: String?) = entries.firstOrNull { it.key == key } ?: RIGHT_MIDDLE
    }
}

/** Every user setting with its default value (defaults follow the product spec). */
data class AppSettings(
    // Floating assistant
    val circleSizeDp: Int = 52,
    val idleOpacityPercent: Int = 30,
    val edgeSnapping: Boolean = true,
    val defaultPosition: DefaultPosition = DefaultPosition.RIGHT_MIDDLE,
    /** Last position as fractions of the usable area; negative = not saved yet. Survives rotation. */
    val lastPosX: Float = -1f,
    val lastPosY: Float = -1f,
    // Automatic API failover (accounts themselves live in the encrypted account store)
    val autoFailover: Boolean = true,
    val maxRetriesPerAccount: Int = 2,
    // Answer verification
    val verificationEnabled: Boolean = true,
    val verifyDifferentAccount: Boolean = true,
    // JEE
    val examMode: ExamMode = ExamMode.AUTOMATIC,
    val syllabusRestriction: Boolean = true,
    val showChapterTopic: Boolean = true,
    val showAlternativeMethods: Boolean = false,
    // Scanning
    val maxQuestionsPerScan: Int = 5,
    val imageQuality: ImageQuality = ImageQuality.BALANCED,
    val autoAnswerPlacement: Boolean = true,
    val alwaysAttachImageInExplanation: Boolean = false,
    // Appearance
    val theme: ThemeMode = ThemeMode.SYSTEM,
    // Privacy
    val historyEnabled: Boolean = true,
    val saveThumbnails: Boolean = true,
) {
    val jee: JeeConfig
        get() = JeeConfig(examMode, syllabusRestriction, showChapterTopic, showAlternativeMethods)

    companion object {
        const val MIN_CIRCLE_DP = 40
        const val MAX_CIRCLE_DP = 80
        const val MIN_OPACITY = 15
        const val MAX_OPACITY = 60
        const val MIN_QUESTIONS = 1
        const val MAX_QUESTIONS = 10
        const val MIN_RETRIES = 0
        const val MAX_RETRIES = 4
    }
}
