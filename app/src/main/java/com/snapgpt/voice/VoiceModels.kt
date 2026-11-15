package com.snapgpt.voice

/** Where the voice panel is in its life cycle. */
enum class VoicePhase { IDLE, LISTENING, PROCESSING, REVIEW, ERROR }

/** Why voice input could not produce text. Decides which recovery buttons the panel offers. */
enum class VoiceProblem {
    /** The user refused the microphone permission this time; asking again is allowed. */
    PERMISSION_DENIED,

    /** Android will not show the permission dialog again; only the system settings can grant it now. */
    PERMISSION_BLOCKED,

    /** No speech recognition service is installed or enabled on this device. */
    UNAVAILABLE,

    /** The microphone was open but nothing recognisable was said. */
    NO_SPEECH,

    /** The recogniser needed a network connection and could not reach its service. */
    NETWORK,

    /** The microphone is in use by another app or was interrupted. */
    AUDIO_BUSY,

    OTHER,
}

data class VoiceError(val problem: VoiceProblem, val message: String, val retryable: Boolean)

/**
 * Everything the voice panel draws. [text] is the editable transcript (earlier results plus the user's own edits);
 * [partial] is the live guess for the sentence being spoken right now and is never part of [text] until it is final.
 */
data class VoiceUiState(
    val phase: VoicePhase = VoicePhase.IDLE,
    val text: String = "",
    val partial: String = "",
    val elapsedMs: Long = 0L,
    /** Newest level last, each 0..1, at most [WaveformLevels.BAR_COUNT] entries. Measured from the real microphone signal. */
    val levels: List<Float> = emptyList(),
    val error: VoiceError? = null,
    /** True when the on-device recogniser is in use, false for the default (possibly network-backed) one, null before any session. */
    val onDevice: Boolean? = null,
) {
    val isListening: Boolean get() = phase == VoicePhase.LISTENING
    val isBusy: Boolean get() = phase == VoicePhase.LISTENING || phase == VoicePhase.PROCESSING

    /** Send is only possible when nothing is being recorded and the transcript contains something to ask. */
    val canSend: Boolean get() = !isBusy && TranscriptBuffer.isUsable(text)

    /** What the transcript field shows: the committed text plus the live partial while listening. */
    val displayText: String get() = if (isListening || phase == VoicePhase.PROCESSING) TranscriptBuffer.append(text, partial) else text
}

/** Pure helpers for building and judging the transcript. */
object TranscriptBuffer {
    /** A spoken or edited question never needs to be longer than this; it also bounds what is sent to a provider. */
    const val MAX_CHARS = 4000

    /** Usable = contains at least one letter or digit. Pure whitespace or punctuation is not a question. */
    fun isUsable(text: String): Boolean = text.any { it.isLetterOrDigit() }

    /** Joins [addition] after [existing] with exactly one space. */
    fun append(existing: String, addition: String): String {
        val add = addition.trim()
        if (add.isEmpty()) return existing.take(MAX_CHARS)
        val base = existing.trimEnd()
        if (base.isEmpty()) return add.take(MAX_CHARS)
        return (base + " " + add).take(MAX_CHARS)
    }

    /** The text that is actually submitted: exactly what a user would have typed, trimmed. */
    fun finalText(text: String): String = text.trim()
}

/** Mapping between the recogniser's loudness reports and the bars the user sees. */
object WaveformLevels {
    const val BAR_COUNT = 32

    // SpeechRecognizer reports a loudness in dB: roughly -2 for silence up to about +10 for loud speech.
    private const val MIN_DB = -2f
    private const val MAX_DB = 10f
    private const val NOISE_GATE = 0.04f

    /** 0 for silence, 1 for loud speech; values below a tiny noise gate become exactly 0 so silence stays flat. */
    fun normalize(rmsDb: Float): Float {
        if (rmsDb.isNaN()) return 0f
        val n = ((rmsDb - MIN_DB) / (MAX_DB - MIN_DB)).coerceIn(0f, 1f)
        return if (n < NOISE_GATE) 0f else n
    }

    fun push(levels: List<Float>, level: Float, size: Int = BAR_COUNT): List<Float> =
        (levels + level.coerceIn(0f, 1f)).takeLast(size)

    /** Always [size] values for drawing: missing history on the left is padded with silence. */
    fun padded(levels: List<Float>, size: Int = BAR_COUNT): List<Float> {
        val tail = levels.takeLast(size)
        return List(size - tail.size) { 0f } + tail
    }
}

object VoiceTime {
    /** 0:07, 1:05, 12:30 */
    fun format(elapsedMs: Long): String {
        val total = (elapsedMs.coerceAtLeast(0L) / 1000L)
        return "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
    }
}

/**
 * Turns Android's SpeechRecognizer error codes into plain messages. The numbers are the documented, stable values of
 * SpeechRecognizer.ERROR_*; they are listed here so this mapping can be unit-tested without Android.
 */
object RecognitionErrors {
    const val NETWORK_TIMEOUT = 1
    const val NETWORK = 2
    const val AUDIO = 3
    const val SERVER = 4
    const val CLIENT = 5
    const val SPEECH_TIMEOUT = 6
    const val NO_MATCH = 7
    const val RECOGNIZER_BUSY = 8
    const val INSUFFICIENT_PERMISSIONS = 9
    const val TOO_MANY_REQUESTS = 10
    const val SERVER_DISCONNECTED = 11
    const val LANGUAGE_NOT_SUPPORTED = 12
    const val LANGUAGE_UNAVAILABLE = 13

    /** The recogniser ended because nothing (or nothing understandable) was said. */
    fun isSilence(code: Int) = code == SPEECH_TIMEOUT || code == NO_MATCH

    /** The on-device recogniser cannot handle the language; the default recogniser may still be able to. */
    fun isLanguageProblem(code: Int) = code == LANGUAGE_NOT_SUPPORTED || code == LANGUAGE_UNAVAILABLE

    fun map(code: Int): VoiceError = when (code) {
        SPEECH_TIMEOUT, NO_MATCH ->
            VoiceError(VoiceProblem.NO_SPEECH, "I didn't catch anything. Tap the microphone and try again.", retryable = true)
        NETWORK_TIMEOUT, NETWORK, SERVER, SERVER_DISCONNECTED, TOO_MANY_REQUESTS ->
            VoiceError(
                VoiceProblem.NETWORK,
                "Speech recognition could not reach its service. On this device it may need an internet connection. Check your connection and try again.",
                retryable = true,
            )
        AUDIO ->
            VoiceError(VoiceProblem.AUDIO_BUSY, "The microphone could not be used. Another app may be using it. Try again.", retryable = true)
        RECOGNIZER_BUSY ->
            VoiceError(VoiceProblem.OTHER, "Speech recognition is busy. Wait a moment and try again.", retryable = true)
        INSUFFICIENT_PERMISSIONS ->
            VoiceError(VoiceProblem.PERMISSION_DENIED, "Microphone permission is needed for voice input.", retryable = false)
        LANGUAGE_NOT_SUPPORTED, LANGUAGE_UNAVAILABLE ->
            VoiceError(VoiceProblem.UNAVAILABLE, "Speech recognition is not available for your current language on this device.", retryable = false)
        CLIENT ->
            VoiceError(VoiceProblem.OTHER, "Speech recognition was interrupted. Try again.", retryable = true)
        else ->
            VoiceError(VoiceProblem.OTHER, "Speech recognition failed (code $code). Try again.", retryable = true)
    }

    val unavailable = VoiceError(
        VoiceProblem.UNAVAILABLE,
        "This device has no speech recognition service available. Install or enable one (for example the Google app) or type your question instead.",
        retryable = false,
    )
    val permissionDenied = VoiceError(
        VoiceProblem.PERMISSION_DENIED, "Microphone permission was not granted, so voice input cannot listen. You can still type your question.", retryable = true,
    )
    val permissionBlocked = VoiceError(
        VoiceProblem.PERMISSION_BLOCKED, "Microphone permission is turned off for SnapGPT. Open the app settings to allow it, or type your question.", retryable = false,
    )
}
