package com.snapgpt

import android.app.Application
import android.content.Context
import com.snapgpt.ai.AiService
import com.snapgpt.ai.GroqClient
import com.snapgpt.database.SnapDatabase
import com.snapgpt.history.HistoryRepository
import com.snapgpt.settings.SecureKeyStore
import com.snapgpt.settings.SettingsRepository
import com.snapgpt.settings.UsageRepository

/** Simple manual dependency container (no DI framework needed for an app this size). */
class AppContainer(app: Application) {
    val settings = SettingsRepository(app)
    val keyStore = SecureKeyStore(app)
    val usage = UsageRepository(app)
    val database: SnapDatabase by lazy { SnapDatabase.create(app) }
    val history: HistoryRepository by lazy { HistoryRepository(app, database) }

    /** The AI service creates a separate compatible client and rate-limit gate for each configured API account. */
    val ai = AiService(settings, keyStore, usage)
}

class SnapGptApp : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}

val Context.container: AppContainer get() = (applicationContext as SnapGptApp).container
