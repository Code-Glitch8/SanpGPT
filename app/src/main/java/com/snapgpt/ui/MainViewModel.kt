package com.snapgpt.ui

import android.app.Application
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.snapgpt.SnapGptApp
import com.snapgpt.ai.providers.ApiAccount
import com.snapgpt.ai.providers.Endpoints
import com.snapgpt.ai.providers.ProviderType
import com.snapgpt.database.SessionEntity
import com.snapgpt.services.AssistantState
import com.snapgpt.settings.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class Permissions(val overlay: Boolean, val notifications: Boolean)

class MainViewModel(private val app: Application) : AndroidViewModel(app) {
    private val c = (app as SnapGptApp).container

    val settings: StateFlow<AppSettings> = c.settings.flow.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    val accounts: StateFlow<List<ApiAccount>> = c.accounts.flow.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val hasUsableAccount: StateFlow<Boolean> = c.accounts.hasUsableAccount.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val sessions: StateFlow<List<SessionEntity>> = c.history.observeSessions().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val running: StateFlow<Boolean> = AssistantState.running
    val projectionReady: StateFlow<Boolean> = AssistantState.projectionReady

    private val _perms = MutableStateFlow(readPermissions())
    val perms: StateFlow<Permissions> = _perms.asStateFlow()

    /** Account ids with a connection test running, and the latest test message per account. */
    private val _testing = MutableStateFlow<Set<String>>(emptySet())
    val testing: StateFlow<Set<String>> = _testing.asStateFlow()
    private val _testMessages = MutableStateFlow<Map<String, String>>(emptyMap())
    val testMessages: StateFlow<Map<String, String>> = _testMessages.asStateFlow()

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

    // ------------------------------------------------------------------ API accounts

    /** Returns an error message for the form, or null when the account was saved. */
    suspend fun saveAccount(
        id: String?, name: String, provider: ProviderType, endpoint: String, model: String, imagesOk: Boolean?, apiKey: String,
    ): String? {
        Endpoints.validate(provider, endpoint)?.let { return it }
        if (model.isBlank()) return "Enter the model name for this account (SnapGPT never picks a model for you)."
        return try {
            if (id == null) {
                if (apiKey.isBlank()) return "Paste the API key for this account."
                c.accounts.add(name, provider, endpoint, model, apiKey, imagesOk)
            } else {
                c.accounts.update(id, name, provider, endpoint, model, imagesOk, apiKey.ifBlank { null })
            }
            null
        } catch (e: Exception) {
            "Could not save the account on this device (Keystore error)."
        }
    }

    fun setEnabled(id: String, enabled: Boolean) { viewModelScope.launch { c.accounts.setEnabled(id, enabled) } }
    fun move(id: String, delta: Int) { viewModelScope.launch { c.accounts.move(id, delta) } }
    fun deleteAccount(id: String) { viewModelScope.launch { c.accounts.delete(id); _notice.value = "Account deleted, including its stored key." } }
    fun duplicateAccount(id: String, copyKey: Boolean) {
        viewModelScope.launch {
            c.accounts.duplicate(id, copyKey)
            _notice.value = if (copyKey) "Account duplicated with a copy of its key." else "Account duplicated without the key. Edit it to add one."
        }
    }
    fun deleteKey(id: String) { viewModelScope.launch { c.accounts.deleteKey(id); _notice.value = "Stored key deleted." } }
    fun deleteAllKeys() { viewModelScope.launch { c.accounts.deleteAllKeys(); _notice.value = "All stored API keys were deleted." } }
    fun clearCooldown(id: String) { viewModelScope.launch { c.accounts.clearCooldown(id) } }
    fun resetStats(id: String) { viewModelScope.launch { c.accounts.resetStats(id) } }
    suspend fun maskedKey(id: String): String = c.accounts.maskedKey(id)

    fun testAccount(a: ApiAccount) {
        if (a.id in _testing.value) return
        viewModelScope.launch {
            _testing.update { it + a.id }
            _testMessages.update { it - a.id }
            try {
                val r = c.ai.tester.test(a)
                _testMessages.update { it + (a.id to r.message) }
            } finally {
                _testing.update { it - a.id }
            }
        }
    }

    fun clearHistory() { viewModelScope.launch { c.history.clearAll(); _notice.value = "History cleared." } }
    fun deleteScreenshots() { viewModelScope.launch { c.history.deleteScreenshots(); _notice.value = "Saved screenshot thumbnails deleted." } }
    fun clearConversations() { viewModelScope.launch { c.history.clearConversations(); _notice.value = "Explanations and conversations cleared." } }
    fun deleteSession(id: Long) { viewModelScope.launch { c.history.delete(id) } }
}
