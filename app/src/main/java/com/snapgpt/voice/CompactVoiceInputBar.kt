package com.snapgpt.voice

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Compact floating voice bar. The header is the drag handle; recording shows a live waveform,
 * Stop swaps it for the editable transcript, and Send is available even before recording stops.
 */
@Composable
fun CompactVoiceInputBar(
    state: VoiceUiState,
    actions: VoiceActions,
    onDrag: (Float, Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.width(320.dp),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 10.dp,
        shadowElevation = 12.dp,
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .pointerInput(Unit) {
                        detectDragGestures { change, amount ->
                            change.consume()
                            onDrag(amount.x, amount.y)
                        }
                    }
                    .padding(bottom = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(
                    if (state.isListening) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                ))
                Text(
                    when {
                        state.isListening -> "Listening"
                        state.phase == VoicePhase.PROCESSING -> "Finishing transcript…"
                        state.phase == VoicePhase.REVIEW -> "Edit or send"
                        else -> "Voice input"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text("DRAG", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.isListening) {
                    CompactWaveform(state.levels, Modifier.weight(1f).height(38.dp))
                } else {
                    Column(Modifier.weight(1f)) {
                        if (state.displayText.isNotBlank()) {
                            BasicTextField(
                                value = state.displayText,
                                onValueChange = actions.onTextChange,
                                readOnly = state.isBusy,
                                textStyle = TextStyle(
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontSize = 14.sp,
                                    lineHeight = 19.sp,
                                ),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                maxLines = 4,
                            )
                        } else {
                            Text(
                                state.error?.message ?: if (state.phase == VoicePhase.PROCESSING) "Transcribing…" else "No speech detected yet",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (state.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                            )
                        }
                    }
                }

                if (state.isListening) {
                    CompactControl(
                        description = "Stop recording",
                        color = MaterialTheme.colorScheme.error,
                        enabled = true,
                        onClick = actions.onMicClick,
                    )
                } else if (state.phase == VoicePhase.ERROR && state.error?.retryable == true) {
                    CompactControl(
                        description = "Try recording again",
                        glyph = "🎙",
                        color = MaterialTheme.colorScheme.primary,
                        enabled = true,
                        onClick = actions.onRetry,
                    )
                }

                CompactControl(
                    description = "Send voice transcript",
                    glyph = "➤",
                    color = MaterialTheme.colorScheme.primary,
                    enabled = state.canSend,
                    onClick = actions.onSend,
                )
            }

            if (state.error != null && state.error.problem == VoiceProblem.PERMISSION_BLOCKED) {
                Spacer(Modifier.height(4.dp))
                Text(
                    state.error.message + " Open SnapGPT settings to allow microphone access.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun CompactWaveform(levels: List<Float>, modifier: Modifier = Modifier) {
    val bars = WaveformLevels.padded(levels, 24)
    Row(
        modifier = modifier.semantics { contentDescription = "Live microphone waveform" },
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        bars.forEachIndexed { index, level ->
            val animated by animateFloatAsState(level, tween(90), label = "compact-wave-$index")
            Box(
                Modifier
                    .weight(1f)
                    .height((5f + animated * 29f).dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

@Composable
private fun CompactControl(
    description: String,
    glyph: String? = null,
    color: Color,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(42.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(color.copy(alpha = if (enabled) 1f else 0.35f))
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        if (glyph == null) {
            Box(Modifier.size(15.dp).clip(RoundedCornerShape(3.dp)).background(Color.White))
        } else {
            Text(glyph, color = Color.White, fontSize = if (glyph == "➤") 25.sp else 19.sp, fontWeight = FontWeight.Bold)
        }
    }
}
