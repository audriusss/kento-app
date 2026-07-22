package lt.sturmanas.bajeristas.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import lt.sturmanas.bajeristas.voice.VoiceListeningState

/**
 * Reusable microphone button used on both [StartScreen] and [NavigationScreen].
 *
 * Visual states:
 * - [VoiceListeningState.IDLE]       — primary colour, mic icon
 * - [VoiceListeningState.LISTENING]  — red, pulsing scale animation, "Kentas klauso…"
 * - [VoiceListeningState.PROCESSING] — primary colour, spinner overlay
 * - [VoiceListeningState.ERROR]      — error colour, mic icon, error message
 *
 * When [sessionActive] is true and the state is IDLE (i.e. the session loop is running
 * but currently waiting for a restart), a green border ring is drawn around the button
 * to indicate that hands-free mode is on.
 *
 * @param state         Current recognition state, drives visuals.
 * @param statusText    Text shown below the button (partial result, error, "Išgirdau: …").
 * @param enabled       False when the safety system blocks conversation or the engine
 *                      is still initialising.
 * @param sessionActive True when the continuous hands-free session loop is running.
 *                      Adds a persistent indicator ring so the user knows mic is always-on.
 * @param size          Diameter of the circular button. Defaults to 64.dp.
 * @param onClick       Called when the button is tapped. Calls [MainViewModel.toggleSession].
 */
@Composable
fun MicButton(
    state: VoiceListeningState,
    statusText: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 64.dp,
    sessionActive: Boolean = false,
) {
    val isListening = state == VoiceListeningState.LISTENING

    // Pulsing scale animation — only runs while LISTENING.
    val infiniteTransition = rememberInfiniteTransition(label = "mic_pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isListening) 1.15f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 600),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "mic_pulse_scale",
    )

    val bgColor = when {
        !enabled -> Color.Gray
        state == VoiceListeningState.LISTENING  -> Color(0xFFD32F2F) // red while listening
        state == VoiceListeningState.ERROR       -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }

    // Session-active ring: visible when hands-free loop is on but state is IDLE
    // (loop is between TTS and next listen window).
    val showSessionRing = sessionActive && state == VoiceListeningState.IDLE
    val sessionRingColor = Color(0xFF43A047) // green

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Outer box carries the session-active ring; inner box carries bg + scale animation.
        Box(
            modifier = if (showSessionRing)
                Modifier
                    .size(size + 6.dp)
                    .border(3.dp, sessionRingColor, CircleShape)
                    .padding(3.dp)
            else
                Modifier.size(size),
            contentAlignment = Alignment.Center,
        ) {
        Box(
            modifier = Modifier
                .size(size)
                .scale(pulseScale)
                .background(color = bgColor, shape = CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            when (state) {
                VoiceListeningState.PROCESSING -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(size * 0.55f),
                        color = Color.White,
                        strokeWidth = 3.dp,
                    )
                }
                else -> {
                    IconButton(
                        onClick = { if (enabled) onClick() },
                        modifier = Modifier.size(size),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = when (state) {
                                VoiceListeningState.LISTENING  -> "Kentas klauso"
                                VoiceListeningState.PROCESSING -> "Apdorojama"
                                VoiceListeningState.ERROR      -> "Klaida"
                                VoiceListeningState.IDLE       -> "Kalbėti"
                            },
                            tint = Color.White,
                            modifier = Modifier.size(size * 0.5f),
                        )
                    }
                }
            }
        }
        } // end outer session-ring Box

        if (statusText.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodySmall,
                color = when (state) {
                    VoiceListeningState.ERROR -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
            )
        }
    }
}

/**
 * Compact mic button row used on [StartScreen].
 * Shows the button centred with an "arba kalbėkite" label beside it.
 */
@Composable
fun StartScreenMicRow(
    state: VoiceListeningState,
    statusText: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MicButton(
                state = state,
                statusText = "",      // status shown separately below
                enabled = enabled,
                onClick = onClick,
                size = 56.dp,
            )
        }
        if (statusText.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodySmall,
                color = when (state) {
                    VoiceListeningState.ERROR -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
