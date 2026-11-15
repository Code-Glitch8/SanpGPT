package com.snapgpt.voice

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import android.view.Gravity
import android.view.WindowManager
import kotlin.math.roundToInt
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.snapgpt.container
import com.snapgpt.settings.AppSettings
import com.snapgpt.ui.SnapGptTheme

/**
 * Delivers the floating assistant's voice result back to the running assistant. The service registers [onSend] while it
 * is alive; the activity calls [send] only when the user presses Send in the voice panel.
 */
object VoiceBridge {
    @Volatile
    var onSend: ((String) -> Unit)? = null

    /** Called whenever the voice screen closes (sent, cancelled or dismissed) so the floating panel can reappear. */
    @Volatile
    var onAsk: ((String) -> Unit)? = null

    @Volatile
    var onClosed: (() -> Unit)? = null

    internal fun ask(text: String): Boolean {
        val cb = onAsk ?: return false
        cb(text)
        return true
    }

    internal fun send(text: String): Boolean {
        val cb = onSend ?: return false
        cb(text)
        return true
    }

    internal fun closed() { onClosed?.invoke() }
}

/**
 * Voice input for the floating helper. The floating panel is a plain overlay window and cannot ask for the microphone
 * permission or host Compose, so it opens this transparent screen. Pressing Send hands the transcript to the same
 * follow-up path as typed text; Cancel or tapping outside discards it and frees the microphone.
 */
class VoiceInputActivity : ComponentActivity() {
    companion object {
        const val EXTRA_VOICE_ASK = "com.snapgpt.voice.EXTRA_VOICE_ASK"
    }

    private val vm: VoiceInputViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Compact transparent floating window; the app behind it stays visible.
        window.setBackgroundDrawableResource(android.R.color.transparent)
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
        window.setLayout(
            (resources.displayMetrics.density * 340f).roundToInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
        )
        window.attributes = window.attributes.apply {
            gravity = Gravity.CENTER
            dimAmount = 0f
        }
        setContent {
            val settings by container.settings.flow.collectAsStateWithLifecycle(initialValue = AppSettings())
            SnapGptTheme(settings.theme) { Content() }
        }
    }

    @Composable
    private fun Content() {
        val state by vm.state.collectAsStateWithLifecycle()
        val actions = rememberVoiceActions(
            vm,
            onCancel = { finish() },
            onSend = { text ->
                val delivered = if (intent.getBooleanExtra(EXTRA_VOICE_ASK, false)) {
                    VoiceBridge.ask(text)
                } else {
                    VoiceBridge.send(text)
                }
                if (!delivered) {
                    android.widget.Toast.makeText(this, "The SnapGPT assistant is not running.", android.widget.Toast.LENGTH_SHORT).show()
                }
                finish()
            },
        )
        BackHandler { actions.onCancel() }
        CompactVoiceInputBar(
            state = state,
            actions = actions,
            onDrag = { dx, dy ->
                window.attributes = window.attributes.apply {
                    x += dx.roundToInt()
                    y += dy.roundToInt()
                }
            },
            modifier = androidx.compose.ui.Modifier.padding(6.dp),
        )
    }

    override fun onDestroy() {
        // Leaving this screen for any reason must free the microphone and let the floating panel come back.
        if (isFinishing) {
            vm.discard()
            VoiceBridge.closed()
        }
        super.onDestroy()
    }
}
