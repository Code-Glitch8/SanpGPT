package com.snapgpt.voice

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * State and control for the voice panel. It lives in a ViewModel so a screen rotation does not lose the transcript or
 * stop a running recognition, while [onCleared] and the UI's own stop/cancel paths always release the microphone.
 *
 * Nothing here listens until [start] is called, and [start] is only called after the user taps the microphone button
 * (and has granted the permission). The recogniser is never started when the app starts.
 */
class VoiceInputViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(VoiceUiState())
    val state: StateFlow<VoiceUiState> = _state.asStateFlow()

    private var ticker: Job? = null
    private var stopWatchdog: Job? = null
    private var startedAt = 0L

    private val manager = SpeechRecognizerManager(app, object : SpeechRecognizerManager.Listener {
        override fun onListening(usingOnDevice: Boolean) {
            _state.update { if (it.phase == VoicePhase.LISTENING) it.copy(onDevice = usingOnDevice) else it }
        }

        override fun onLevel(rmsDb: Float) {
            _state.update { if (it.phase == VoicePhase.LISTENING) it.copy(levels = WaveformLevels.push(it.levels, WaveformLevels.normalize(rmsDb))) else it }
        }

        override fun onEndOfSpeech() {
            _state.update { if (it.phase == VoicePhase.LISTENING) it.copy(phase = VoicePhase.PROCESSING) else it }
            armWatchdog()
        }

        override fun onPartial(text: String) {
            _state.update { if (it.isBusy) it.copy(partial = text) else it }
        }

        override fun onFinal(text: String) = finishWithText(text)

        override fun onError(code: Int) = finishWithError(code)
    })

    /** True when the device has any speech recognition service (checked when the panel opens). */
    fun isRecognitionAvailable(): Boolean = manager.isAvailable()

    /**
     * Starts listening. The caller must already hold the microphone permission. A second call while a session is
     * running is ignored, so a double tap can never create two recognition sessions.
     */
    fun start() {
        if (_state.value.isBusy || manager.isActive) return
        if (!manager.isAvailable()) {
            _state.update { it.copy(phase = VoicePhase.ERROR, partial = "", levels = emptyList(), error = RecognitionErrors.unavailable) }
            return
        }
        _state.update { it.copy(phase = VoicePhase.LISTENING, partial = "", elapsedMs = 0L, levels = emptyList(), error = null) }
        if (!manager.start()) {
            _state.update { it.copy(phase = VoicePhase.ERROR, error = RecognitionErrors.unavailable) }
            return
        }
        startTicker()
    }

    /** The user pressed stop: ask the recogniser for what it heard so far. */
    fun stop() {
        if (_state.value.phase != VoicePhase.LISTENING) return
        _state.update { it.copy(phase = VoicePhase.PROCESSING) }
        manager.stopListening()
        armWatchdog()
    }

    /** Erases the transcript. A running recording is ended first so no stale words appear afterwards. */
    fun clear() {
        endSession()
        _state.update { it.copy(phase = VoicePhase.IDLE, text = "", partial = "", levels = emptyList(), error = null, elapsedMs = 0L) }
    }

    /** Throws the current transcript away and listens again. */
    fun retry() {
        clear()
        start()
    }

    /** The user edited the transcript by hand (only possible while nothing is being recorded). */
    fun onTextEdited(text: String) {
        _state.update { if (it.isBusy) it else it.copy(text = text.take(TranscriptBuffer.MAX_CHARS), error = null, phase = if (it.phase == VoicePhase.ERROR) VoicePhase.REVIEW else it.phase) }
    }

    /** Cancel / dismiss: forget everything and make sure the microphone is free. */
    fun discard() {
        endSession()
        _state.value = VoiceUiState()
    }

    /** The app went to the background: stop recording but keep the words that were already recognised. */
    fun onBackgrounded() {
        if (!_state.value.isBusy && !manager.isActive) return
        val partial = _state.value.partial
        endSession()
        _state.update {
            val kept = TranscriptBuffer.append(it.text, partial)
            it.copy(phase = if (TranscriptBuffer.isUsable(kept)) VoicePhase.REVIEW else VoicePhase.IDLE, text = kept, partial = "", levels = emptyList())
        }
    }

    /** The microphone permission was refused. [blocked] means Android will not ask again. */
    fun onPermissionDenied(blocked: Boolean) {
        endSession()
        _state.update {
            it.copy(
                phase = VoicePhase.ERROR, partial = "", levels = emptyList(),
                error = if (blocked) RecognitionErrors.permissionBlocked else RecognitionErrors.permissionDenied,
            )
        }
    }

    // ------------------------------------------------------------------ internals

    private fun finishWithText(text: String) {
        stopTimers()
        _state.update {
            val merged = TranscriptBuffer.append(it.text, text.ifBlank { it.partial })
            if (TranscriptBuffer.isUsable(merged)) {
                it.copy(phase = VoicePhase.REVIEW, text = merged, partial = "", levels = emptyList(), error = null)
            } else {
                it.copy(phase = VoicePhase.ERROR, text = merged, partial = "", levels = emptyList(), error = RecognitionErrors.map(RecognitionErrors.NO_MATCH))
            }
        }
    }

    private fun finishWithError(code: Int) {
        stopTimers()
        _state.update {
            // Whatever was heard before the failure is kept: it can still be edited and sent.
            val merged = TranscriptBuffer.append(it.text, it.partial)
            val usable = TranscriptBuffer.isUsable(merged)
            if (RecognitionErrors.isSilence(code) && usable) {
                it.copy(phase = VoicePhase.REVIEW, text = merged, partial = "", levels = emptyList(), error = null)
            } else {
                it.copy(phase = VoicePhase.ERROR, text = merged, partial = "", levels = emptyList(), error = RecognitionErrors.map(code))
            }
        }
    }

    /** If the recogniser never answers after "stop" or end of speech, do not leave the panel waiting forever. */
    private fun armWatchdog() {
        stopWatchdog?.cancel()
        stopWatchdog = viewModelScope.launch {
            delay(RESULT_WAIT_MS)
            if (_state.value.phase == VoicePhase.PROCESSING) {
                manager.release()
                finishWithText("")
            }
        }
    }

    private fun startTicker() {
        ticker?.cancel()
        startedAt = SystemClock.elapsedRealtime()
        ticker = viewModelScope.launch {
            while (isActive) {
                delay(250)
                val e = SystemClock.elapsedRealtime() - startedAt
                _state.update { if (it.phase == VoicePhase.LISTENING) it.copy(elapsedMs = e) else it }
            }
        }
    }

    private fun stopTimers() {
        ticker?.cancel(); ticker = null
        stopWatchdog?.cancel(); stopWatchdog = null
    }

    private fun endSession() {
        stopTimers()
        manager.release()
    }

    override fun onCleared() {
        endSession()
        super.onCleared()
    }

    private companion object {
        const val RESULT_WAIT_MS = 4000L
    }
}
