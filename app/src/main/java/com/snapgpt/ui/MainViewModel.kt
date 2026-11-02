package com.snapgpt.ui

import android.app.Application
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.snapgpt.SnapGptApp
import com.snapgpt.ai.ConnectionResult
import com.snapgpt.ai.ConnectionState
import com.snapgpt.ai.RateLimitInfo
import com.snapgpt.ai.UsageStats
import com.snapgpt.database.SessionEntity
import com.snapgpt.services.AssistantState
import com.snapgpt.settings.ApiAccount
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

    private val _hasKey = MutableStateFlow(c.keyStore.hasAnyKey())
    val hasKey: StateFlow<Boolean> = _hasKey.asStateFlow()

    private val _connection = MutableStateFlow(ConnectionUi(null, "Add an API account and test it."))
    val connection: StateFlow<ConnectionUi> = _connection.asStateFlow()

    private val _accountConnections = MutableStateFlow<Map<String, ConnectionUi>>(emptyMap())
    val accountConnections: StateFlow<Map<String, ConnectionUi>> = _accountConnections.asStateFlow()

    private val _rateLimit = MutableStateFlow(RateLimitInfo())
    val rateLimit: StateFlow<RateLimitInfo> = _rateLimit.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    fun consumeNotice() { _notice.value = null }
    fun connectionFor(accountId: String): ConnectionUi = _accountConnections.value[accountId] ?: ConnectionUi(null, "Not tested")
    fun hasApiKey(accountId: String): Boolean = c.keyStore.hasKey(accountId)
    fun maskedApiKey(accountId: String): String = c.keyStore.masked(accountId)

    private fun readPermissions() = Permissions(
        overlay = Settings.canDrawOverlays(app),
        notifications = NotificationManagerCompat.from(app).areNotificationsEnabled(),
    )

    fun refreshPermissions() { _perms.value = readPermissions() }

    fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { c.settings.update(transform) }
    }

    fun addApiAccount(account: ApiAccount, apiKey: String) {
        viewModelScope.launch {
            if (!account.isConfigurationValid()) {
                _notice.value = "Enter a name, HTTPS base URL and image-capable model."
                return@launch
            }
            if (apiKey.isBlank()) {
                _notice.value = "Enter an API key for this account."
                return@launch
            }
            runCatching { c.keyStore.save(account.id, apiKey) }
                .onSuccess {
                    c.settings.update { it.copy(apiAccounts = it.apiAccounts + account) }
                    _hasKey.value = c.keyStore.hasAnyKey()
                    _notice.value = "${account.name} added. You can add as many API accounts as you need."
                }
                .onFailure { _notice.value = "Could not securely save this API key on this device." }
        }
    }

    fun updateApiAccount(account: ApiAccount) {
        viewModelScope.launch {
            if (!account.isConfigurationValid()) {
                _notice.value = "Check the account name, HTTPS base URL and model before saving."
                return@launch
            }
            c.ai.invalidateAccount(account.id)
            c.settings.update { current ->
                current.copy(apiAccounts = current.apiAccounts.map { if (it.id == account.id) account else it })
            }
            _notice.value = "${account.name} settings saved."
        }
    }

    fun saveApiKey(accountId: String, key: String) {
        val value = key.trim()
        if (value.isBlank()) { _notice.value = "Enter an API key first."; return }
        runCatching { c.keyStore.save(accountId, value) }
            .onSuccess {
                c.ai.resetAccountCooldown(accountId)
                _hasKey.value = c.keyStore.hasAnyKey()
                val name = settings.value.apiAccounts.firstOrNull { it.id == accountId }?.name ?: "API account"
                _accountConnections.value = _accountConnections.value + (accountId to ConnectionUi(null, "Key saved. Test the connection."))
                _notice.value = "$name key saved securely on this device."
            }
            .onFailure { _notice.value = "Could not securely save this API key on this device." }
    }

    fun deleteApiKey(accountId: String) {
        c.keyStore.delete(accountId)
        _hasKey.value = c.keyStore.hasAnyKey()
        _accountConnections.value = _accountConnections.value + (accountId to ConnectionUi(null, "No key saved"))
        _notice.value = "API key deleted."
    }

    fun removeApiAccount(accountId: String) {
        c.ai.invalidateAccount(accountId)
        c.keyStore.delete(accountId)
        _accountConnections.value = _accountConnections.value - accountId
        viewModelScope.launch {
            c.settings.update { it.copy(apiAccounts = it.apiAccounts.filterNot { a -> a.id == accountId }) }
            _hasKey.value = c.keyStore.hasAnyKey()
            _notice.value = "API account removed."
        }
    }

    fun moveApiAccount(accountId: String, direction: Int) {
        viewModelScope.launch {
            c.settings.update { current ->
                val list = current.apiAccounts.toMutableList()
                val from = list.indexOfFirst { it.id == accountId }
                val to = from + direction
                if (from >= 0 && to in list.indices) {
                    val item = list.removeAt(from)
                    list.add(to, item)
                }
                current.copy(apiAccounts = list)
            }
        }
    }

    fun testApiAccount(accountId: String) {
        val previous = _accountConnections.value[accountId]
        if (previous?.testing == true) return
        viewModelScope.launch {
            _accountConnections.value = _accountConnections.value + (accountId to ConnectionUi(null, "Testing connection…", true))
            val result = c.ai.testConnection(accountId)
            _accountConnections.value = _accountConnections.value + (accountId to ConnectionUi(result.state, result.message))
            _connection.value = ConnectionUi(result.state, result.message)
            _rateLimit.value = c.ai.lastRateLimit
        }
    }

    /** Compatibility action used by the Home screen: test the highest-priority configured account. */
    fun testConnection() {
        val account = settings.value.apiAccounts.firstOrNull { it.enabled && c.keyStore.hasKey(it.id) }
        if (account == null) {
            _connection.value = ConnectionUi(ConnectionState.INVALID_KEY, "Add and enable an API account in Settings > AI APIs.")
            _notice.value = "Add an API account before testing the connection."
        } else {
            testApiAccount(account.id)
        }
    }

    fun saveKey(key: String) = saveApiKey("groq-primary", key)
    fun deleteKey() = deleteApiKey("groq-primary")

    fun resetUsage() { viewModelScope.launch { c.usage.reset() } }
    fun clearHistory() { viewModelScope.launch { c.history.clearAll(); _notice.value = "History cleared." } }
    fun deleteScreenshots() { viewModelScope.launch { c.history.deleteScreenshots(); _notice.value = "Saved screenshot thumbnails deleted." } }
    fun clearConversations() { viewModelScope.launch { c.history.clearConversations(); _notice.value = "Explanations and conversations cleared." } }
    fun deleteSession(id: Long) { viewModelScope.launch { c.history.delete(id) } }
}
