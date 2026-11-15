package com.snapgpt.voice

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.snapgpt.R

// ---------------------------------------------------------------------------------------------- permission

fun hasMicPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

fun openAppSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

/**
 * Returns a function that shows Android's microphone permission dialog. [onResult] reports whether it was granted and,
 * when it was not, whether Android will refuse to ask again (so the panel can offer the system settings instead).
 */
@Composable
private fun rememberMicPermissionRequest(onResult: (granted: Boolean, blocked: Boolean) -> Unit): () -> Unit {
    val ctx = LocalContext.current
    val activity = remember(ctx) { ctx.findActivity() }
    val latest by rememberUpdatedState(onResult)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val blocked = !granted && activity != null &&
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.RECORD_AUDIO)
        latest(granted, blocked)
    }
    return remember(launcher) { { launcher.launch(Manifest.permission.RECORD_AUDIO) } }
}

// ---------------------------------------------------------------------------------------------- actions

class VoiceActions(
    val onMicClick: () -> Unit,
    val onCancel: () -> Unit,
    val onSend: () -> Unit,
    val onClear: () -> Unit,
    val onRetry: () -> Unit,
    val onTextChange: (String) -> Unit,
    val onOpenSettings: () -> Unit,
)

/**
 * Wires the panel to the view model: permission handling, stopping the microphone when the app goes to the background,
 * and the Cancel/Send paths. Listening starts only after the user opened the panel (never at app start) and only with
 * the permission granted. [onSend] receives exactly the text a user would otherwise have typed.
 */
@Composable
fun rememberVoiceActions(vm: VoiceInputViewModel, onCancel: () -> Unit, onSend: (String) -> Unit): VoiceActions {
    val ctx = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()
    val currentState by rememberUpdatedState(state)
    val cancelCb by rememberUpdatedState(onCancel)
    val sendCb by rememberUpdatedState(onSend)

    val askPermission = rememberMicPermissionRequest { granted, blocked ->
        if (granted) vm.start() else vm.onPermissionDenied(blocked)
    }
    val begin: () -> Unit = remember(ctx, vm, askPermission) {
        { if (hasMicPermission(ctx)) vm.start() else askPermission() }
    }

    // Recording never continues in the background.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.onBackgrounded() }

    // The user just opened the panel by tapping the microphone: start. After a rotation the state is not idle, so this is skipped.
    LaunchedEffect(Unit) {
        val s = vm.state.value
        if (s.phase == VoicePhase.IDLE && s.text.isBlank() && s.error == null) begin()
    }

    return remember(vm, begin) {
        VoiceActions(
            onMicClick = { if (currentState.isListening) vm.stop() else begin() },
            onCancel = { vm.discard(); cancelCb() },
            onSend = {
                val s = currentState
                if (s.canSend) {
                    val text = TranscriptBuffer.finalText(s.text)
                    vm.discard()
                    sendCb(text)
                }
            },
            onClear = vm::clear,
            onRetry = { vm.clear(); begin() },
            onTextChange = vm::onTextEdited,
            onOpenSettings = { openAppSettings(ctx) },
        )
    }
}

// ---------------------------------------------------------------------------------------------- waveform

/**
 * Vertical bars driven by the real microphone level (see [WaveformLevels]). Louder speech gives taller bars, silence
 * gives short flat bars, and every height change is animated so the motion is smooth rather than jumpy.
 * Nothing here is random.
 */
@Composable
fun WaveformBars(levels: List<Float>, active: Boolean, modifier: Modifier = Modifier) {
    val bars = WaveformLevels.padded(levels)
    val activeColor = MaterialTheme.colorScheme.primary
    val idleColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
    Row(
        modifier.fillMaxWidth().height(64.dp).semantics { contentDescription = if (active) "Microphone level" else "Microphone idle" },
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        bars.forEachIndexed { index, level ->
            val target = if (active) level else 0f
            val animated by animateFloatAsState(targetValue = target, animationSpec = tween(durationMillis = 90), label = "bar$index")
            val fraction = (MIN_BAR + (1f - MIN_BAR) * animated).coerceIn(MIN_BAR, 1f)
            Box(
                Modifier.weight(1f).fillMaxHeight(fraction).clip(RoundedCornerShape(50)).background(if (active) activeColor else idleColor),
            )
        }
    }
}

private const val MIN_BAR = 0.08f

// ---------------------------------------------------------------------------------------------- panel

@Composable
fun VoiceInputPanel(state: VoiceUiState, actions: VoiceActions, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().imePadding().padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatusRow(state)
        WaveformBars(state.levels, active = state.isListening)

        state.error?.let { err ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(err.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                Row {
                    if (err.problem == VoiceProblem.PERMISSION_BLOCKED) TextButton(onClick = actions.onOpenSettings) { Text("Open app settings") }
                    if (err.retryable) TextButton(onClick = actions.onRetry) { Text("Try again") }
                }
            }
        }

        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { MicButton(state, actions.onMicClick) }

        OutlinedTextField(
            value = state.displayText,
            onValueChange = actions.onTextChange,
            readOnly = state.isBusy,
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            maxLines = 6,
            label = { Text("Your question") },
            placeholder = { Text(if (state.isBusy) "" else "Tap the microphone and speak, or type here") },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        )

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = actions.onClear, enabled = state.text.isNotBlank() || state.partial.isNotBlank()) { Text("Clear") }
            TextButton(onClick = actions.onRetry, enabled = !state.isBusy) { Text("Retry") }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = actions.onCancel, modifier = Modifier.weight(1f)) { Text("Cancel") }
            Button(onClick = actions.onSend, enabled = state.canSend, modifier = Modifier.weight(1f)) { Text("Send") }
        }

        Text(
            when (state.onDevice) {
                true -> "Recognised on this device. Only the text you send goes to your AI provider."
                false -> "Recognised by your device's speech service, which may process audio online depending on your phone. Only the text you send goes to your AI provider."
                null -> "Speech is recognised by your device's speech service (some phones process audio online). Only the text you send goes to your AI provider."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StatusRow(state: VoiceUiState) {
    val pulse by rememberInfiniteTransition(label = "rec").animateFloat(
        initialValue = 0.35f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "pulse",
    )
    val dotColor = when (state.phase) {
        VoicePhase.LISTENING -> MaterialTheme.colorScheme.error
        VoicePhase.PROCESSING -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    }
    val label = when (state.phase) {
        VoicePhase.LISTENING -> "Recording"
        VoicePhase.PROCESSING -> "Processing…"
        VoicePhase.REVIEW -> "Review and edit, then Send"
        VoicePhase.ERROR -> "Voice input problem"
        VoicePhase.IDLE -> "Ready"
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).alpha(if (state.isListening) pulse else 1f).clip(CircleShape).background(dotColor))
        Text(label, Modifier.padding(start = 8.dp).weight(1f), style = MaterialTheme.typography.titleSmall)
        Text(VoiceTime.format(state.elapsedMs), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MicButton(state: VoiceUiState, onClick: () -> Unit) {
    val listening = state.isListening
    val enabled = state.phase != VoicePhase.PROCESSING
    val container = if (listening) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Box(
        Modifier
            .size(64.dp)
            .alpha(if (enabled) 1f else 0.5f)
            .clip(CircleShape)
            .background(container)
            .semantics { contentDescription = if (listening) "Stop recording" else "Start recording" }
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (listening) {
            Box(Modifier.size(20.dp).clip(RoundedCornerShape(4.dp)).background(Color.White))
        } else {
            Icon(painterResource(R.drawable.ic_mic), contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(30.dp))
        }
    }
}

// ---------------------------------------------------------------------------------------------- sheet

/** The panel in a Material 3 bottom sheet, used inside the app. Dismissing the sheet always frees the microphone. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceInputSheet(vm: VoiceInputViewModel, onDismiss: () -> Unit, onSend: (String) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val actions = rememberVoiceActions(vm, onCancel = onDismiss, onSend = { text -> onSend(text); onDismiss() })
    ModalBottomSheet(
        onDismissRequest = { vm.discard(); onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        VoiceInputPanel(state, actions)
    }
}
