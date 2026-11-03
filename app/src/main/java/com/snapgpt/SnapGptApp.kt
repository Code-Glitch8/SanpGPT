package com.snapgpt

import android.app.Application
import android.content.Context
import com.snapgpt.ai.AiPipeline
import com.snapgpt.ai.AiService
import com.snapgpt.ai.failover.AccountTester
import com.snapgpt.ai.failover.FailoverManager
import com.snapgpt.ai.providers.Adapters
import com.snapgpt.database.SnapDatabase
import com.snapgpt.history.HistoryRepository
import com.snapgpt.networking.UrlConnectionTransport
import com.snapgpt.security.KeyCipher
import com.snapgpt.settings.AccountRepository
import com.snapgpt.settings.SettingsRepository

/** Simple manual dependency container (no DI framework needed for an app this size). */
class AppContainer(app: Application) {
    val settings = SettingsRepository(app)
    val database: SnapDatabase by lazy { SnapDatabase.create(app) }
    val history: HistoryRepository by lazy { HistoryRepository(app, database) }

    /** Every API key is encrypted individually with its own Android Keystore key. */
    val accounts: AccountRepository by lazy { AccountRepository(database.accountDao(), KeyCipher()) }

    /**
     * To use a backend proxy later (recommended for public releases so no key ships on devices), give the accounts proxy
     * endpoints (each account has an editable endpoint) and move key handling to the server.
     */
    private val adapters = Adapters.standard(UrlConnectionTransport())
    private val failover: FailoverManager by lazy { FailoverManager(accounts, adapters) }
    val ai: AiService by lazy { AiService(AiPipeline(failover), failover, AccountTester(accounts, adapters), settings) }
}

class SnapGptApp : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}

val Context.container: AppContainer get() = (applicationContext as SnapGptApp).container
