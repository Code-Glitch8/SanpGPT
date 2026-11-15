package com.snapgpt.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Thin, defensive wrapper around Android's [SpeechRecognizer]. It owns at most ONE recognition session at a time and
 * ignores every callback that belongs to an older session, so a late callback can never touch newer or destroyed state.
 *
 * Which recogniser is used:
 *  - Android 13+ with an on-device recogniser installed: the on-device one (audio stays on the phone).
 *  - Otherwise the device's default recogniser, which on many phones sends audio to the vendor's servers.
 * SnapGPT never claims recognition is offline; [usingOnDevice] reports what is really in use. Audio is only ever
 * handed to the recogniser; SnapGPT does not record, store or upload it, and it is never sent to an AI provider.
 *
 * The waveform comes from the recogniser's own loudness callback ([Listener.onLevel]) rather than a second
 * AudioRecord, because two simultaneous captures of the microphone can starve one another on many devices.
 *
 * All methods must be called on the main thread.
 */
class SpeechRecognizerManager(context: Context, private val listener: Listener) {

    interface Listener {
        fun onListening(usingOnDevice: Boolean)
        fun onLevel(rmsDb: Float)
        fun onEndOfSpeech()
        fun onPartial(text: String)
        fun onFinal(text: String)
        fun onError(code: Int)
    }

    private val appContext = context.applicationContext
    private var recognizer: SpeechRecognizer? = null
    private var session = 0
    private var active = false
    private var triedDefaultAfterOnDeviceFailure = false

    var usingOnDevice: Boolean = false
        private set

    val isActive: Boolean get() = active

    /** True when this device has any speech recognition service at all. */
    fun isAvailable(): Boolean = hasDefaultService() || hasOnDeviceService()

    private fun hasDefaultService(): Boolean = runCatching { SpeechRecognizer.isRecognitionAvailable(appContext) }.getOrDefault(false)

    private fun hasOnDeviceService(): Boolean =
        Build.VERSION.SDK_INT >= 33 && runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext) }.getOrDefault(false)

    /** Starts a session. Returns false (and does nothing) if one is already running or no service exists. */
    fun start(): Boolean {
        checkMainThread()
        if (active) return false
        triedDefaultAfterOnDeviceFailure = false
        return startSession(preferOnDevice = true)
    }

    private fun startSession(preferOnDevice: Boolean): Boolean {
        destroyRecognizer()
        val id = ++session
        val r = createRecognizer(preferOnDevice) ?: return false
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            private fun current() = active && id == session && recognizer === r

            override fun onReadyForSpeech(params: Bundle?) { if (current()) listener.onListening(usingOnDevice) }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) { if (current()) listener.onLevel(rmsdB) }
            override fun onBufferReceived(buffer: ByteArray?) {} // raw audio is deliberately ignored and never kept
            override fun onEndOfSpeech() { if (current()) listener.onEndOfSpeech() }
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onPartialResults(partialResults: Bundle?) {
                if (!current()) return
                firstResult(partialResults)?.let { listener.onPartial(it) }
            }

            override fun onResults(results: Bundle?) {
                if (!current()) return
                val text = firstResult(results).orEmpty()
                finishSession()
                listener.onFinal(text)
            }

            override fun onError(error: Int) {
                if (!current()) return
                // The on-device model may lack this language; the default recogniser can still try once.
                if (usingOnDevice && RecognitionErrors.isLanguageProblem(error) && !triedDefaultAfterOnDeviceFailure && hasDefaultService()) {
                    triedDefaultAfterOnDeviceFailure = true
                    if (startSession(preferOnDevice = false)) return
                }
                finishSession()
                listener.onError(error)
            }
        })
        active = true
        return try {
            r.startListening(buildIntent())
            true
        } catch (e: Exception) {
            finishSession()
            false
        }
    }

    // The on-device branch is only reached when hasOnDeviceService() (Android 13+) is true.
    @android.annotation.SuppressLint("NewApi")
    private fun createRecognizer(preferOnDevice: Boolean): SpeechRecognizer? = try {
        if (preferOnDevice && hasOnDeviceService()) {
            usingOnDevice = true
            SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext)
        } else if (hasDefaultService()) {
            usingOnDevice = false
            SpeechRecognizer.createSpeechRecognizer(appContext)
        } else {
            null
        }
    } catch (e: Exception) {
        null
    }

    private fun buildIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
        // Hints only: recognisers may ignore them. Prefer local recognition when a local model exists.
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
    }

    /** Asks the recogniser to finish and deliver what it has heard. The result still arrives through the listener. */
    fun stopListening() {
        checkMainThread()
        if (!active) return
        runCatching { recognizer?.stopListening() }
    }

    /** Ends the session immediately and frees the microphone. Safe to call at any time, any number of times. */
    fun release() {
        checkMainThread()
        finishSession()
    }

    private fun finishSession() {
        active = false
        session++ // every callback from the old session is now ignored
        destroyRecognizer()
    }

    private fun destroyRecognizer() {
        val r = recognizer ?: return
        recognizer = null
        runCatching { r.cancel() }
        runCatching { r.destroy() }
    }

    private fun firstResult(b: Bundle?): String? =
        b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }

    private fun checkMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "SpeechRecognizerManager must be used on the main thread" }
    }
}
