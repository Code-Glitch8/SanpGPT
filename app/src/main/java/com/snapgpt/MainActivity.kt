package com.snapgpt

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.snapgpt.ui.AccountsScreen
import com.snapgpt.ui.HistoryScreen
import com.snapgpt.ui.HomeScreen
import com.snapgpt.ui.MainViewModel
import com.snapgpt.ui.SessionScreen
import com.snapgpt.ui.SettingsScreen
import com.snapgpt.ui.SnapGptTheme
import com.snapgpt.ui.UsageScreen

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    /** Set when the floating menu's "Settings" item opens the app on a specific screen. */
    private var requestedScreen by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestedScreen = intent?.getStringExtra(EXTRA_SCREEN)
        setContent {
            val settings by vm.settings.collectAsStateWithLifecycle()
            SnapGptTheme(settings.theme) {
                Surface { AppRoot(vm, requestedScreen) { requestedScreen = null } }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        requestedScreen = intent.getStringExtra(EXTRA_SCREEN)
    }

    companion object {
        const val EXTRA_SCREEN = "screen"
        const val SCREEN_SETTINGS = "settings"
    }
}

private const val HOME = "home"
private const val SETTINGS = "settings"
private const val HISTORY = "history"
private const val SESSION = "session"
private const val ACCOUNTS = "accounts"
private const val USAGE = "usage"

@Composable
private fun AppRoot(vm: MainViewModel, requested: String?, onRequestHandled: () -> Unit) {
    var screen by rememberSaveable { mutableStateOf(HOME) }
    var sessionId by rememberSaveable { mutableLongStateOf(-1L) }

    LaunchedEffect(requested) {
        if (requested == MainActivity.SCREEN_SETTINGS) {
            screen = SETTINGS
            onRequestHandled()
        }
    }

    BackHandler(enabled = screen != HOME) {
        screen = when (screen) {
            SESSION -> HISTORY
            USAGE -> ACCOUNTS
            else -> HOME
        }
    }

    when (screen) {
        SETTINGS -> SettingsScreen(vm, onBack = { screen = HOME }, onAccounts = { screen = ACCOUNTS }, onUsage = { screen = USAGE })
        ACCOUNTS -> AccountsScreen(vm, onBack = { screen = HOME }, onUsage = { screen = USAGE })
        USAGE -> UsageScreen(vm, onBack = { screen = ACCOUNTS })
        HISTORY -> HistoryScreen(vm, onBack = { screen = HOME }, onOpen = { sessionId = it; screen = SESSION })
        SESSION -> SessionScreen(sessionId, onBack = { screen = HISTORY })
        else -> HomeScreen(
            vm,
            onSettings = { screen = SETTINGS },
            onAccounts = { screen = ACCOUNTS },
            onUsage = { screen = USAGE },
            onHistory = { screen = HISTORY },
            onOpenSession = { sessionId = it; screen = SESSION },
        )
    }
}
