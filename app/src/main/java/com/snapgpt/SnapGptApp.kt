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

    /**
     * To use a backend proxy later (recommended for public releases so no key ships on devices), construct the client with
     * `GroqClient(baseUrl = "https://your-proxy.example.com/openai/v1")` and move key handling to the server.
     */
    val groq = GroqClient()
    val ai = AiService(groq, settings, keyStore, usage)
}

class SnapGptApp : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}

val Context.container: AppContainer get() = (applicationContext as SnapGptApp).container
