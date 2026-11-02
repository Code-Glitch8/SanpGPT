package com.snapgpt.ui

import android.app.Application
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.snapgpt.SnapGptApp
import com.snapgpt.ai.ConnectionState
import com.snapgpt.ai.RateLimitInfo
import com.snapgpt.ai.UsageStats
import com.snapgpt.database.SessionEntity
import com.snapgpt.services.AssistantState
import com.snapgpt.settings.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class Permissions(val overlay: Boolean, val notifications: Boolean)

data class ConnectionUi(val state: ConnectionState?, val message: String, val testing: Boolean = false)

class MainViewModel(private val app: Application) : AndroidViewModel(app) {
    private val c = (app as SnapGptApp).container

    val settings: StateFlow<AppSettings> = c.settings.flow.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    val usage: StateFlow<UsageStats> = c.usage.flow.stateIn(viewModelScope, SharingStarted.Eagerly, UsageStats())
    val sessions: StateFlow<List<SessionEntity>> = c.history.observeSessions().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val running: StateFlow<Boolean> = AssistantState.running
    val projectionReady: StateFlow<Boolean> = AssistantState.projectionReady

    private val _perms = MutableStateFlow(readPermissions())
    val perms: StateFlow<Permissions> = _perms.asStateFlow()

    private val _hasKey = MutableStateFlow(c.keyStore.hasKey())
    val hasKey: StateFlow<Boolean> = _hasKey.asStateFlow()
    private val _maskedKey = MutableStateFlow(c.keyStore.masked())
    val maskedKey: StateFlow<String> = _maskedKey.asStateFlow()

    private val _connection = MutableStateFlow(ConnectionUi(null, "Not tested yet"))
    val connection: StateFlow<ConnectionUi> = _connection.asStateFlow()

    private val _rateLimit = MutableStateFlow(RateLimitInfo())
    val rateLimit: StateFlow<RateLimitInfo> = _rateLimit.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    fun consumeNotice() { _notice.value = null }

    private fun readPermissions() = Permissions(
        overlay = Settings.canDrawOverlays(app),
        notifications = NotificationManagerCompat.from(app).areNotificationsEnabled(),
    )

    fun refreshPermissions() { _perms.value = readPermissions() }

    fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { c.settings.update(transform) }
    }

    fun saveKey(key: String) {
        val k = key.trim()
        if (k.isEmpty()) { _notice.value = "Enter a key first."; return }
        runCatching { c.keyStore.save(k) }
            .onSuccess {
                _hasKey.value = true
                _maskedKey.value = c.keyStore.masked()
                _connection.value = ConnectionUi(null, "Key saved. Press Test connection.")
                _notice.value = if (k.startsWith("gsk_")) "API key saved securely." else "Saved, but Groq keys normally start with \"gsk_\". Check it with Test connection."
            }
            .onFailure { _notice.value = "Could not save the key on this device (Keystore error)." }
    }

    fun deleteKey() {
        c.keyStore.delete()
        _hasKey.value = false
        _maskedKey.value = ""
        _connection.value = ConnectionUi(null, "No API key saved")
        _notice.value = "API key deleted."
    }

    fun testConnection() {
        if (_connection.value.testing) return
        viewModelScope.launch {
            _connection.value = ConnectionUi(null, "Testing…", testing = true)
            val r = c.ai.testConnection()
            _connection.value = ConnectionUi(r.state, r.message)
            _rateLimit.value = c.groq.lastRateLimit
        }
    }

    fun resetUsage() { viewModelScope.launch { c.usage.reset() } }

    fun clearHistory() { viewModelScope.launch { c.history.clearAll(); _notice.value = "History cleared." } }
    fun deleteScreenshots() { viewModelScope.launch { c.history.deleteScreenshots(); _notice.value = "Saved screenshot thumbnails deleted." } }
    fun clearConversations() { viewModelScope.launch { c.history.clearConversations(); _notice.value = "Explanations and conversations cleared." } }
    fun deleteSession(id: Long) { viewModelScope.launch { c.history.delete(id) } }
}
