package com.snapgpt.services

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Process-wide, read-only view of the assistant's state for the main UI. */
object AssistantState {
    private val _running = MutableStateFlow(false)
    private val _projectionReady = MutableStateFlow(false)

    /** True while the foreground service (and therefore the floating circle) is alive. */
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /** True while Android's screen-capture authorisation is active. */
    val projectionReady: StateFlow<Boolean> = _projectionReady.asStateFlow()

    @Volatile
    var consentDeniedListener: (() -> Unit)? = null

    internal fun setRunning(v: Boolean) { _running.value = v }
    internal fun setProjectionReady(v: Boolean) { _projectionReady.value = v }

    fun consentDenied() {
        consentDeniedListener?.invoke()
    }
}
