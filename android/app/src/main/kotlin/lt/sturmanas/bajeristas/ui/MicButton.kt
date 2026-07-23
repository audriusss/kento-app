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
 * ## Visual states
 *
 * | State           | Colour  | Animation | Inner widget   |
 * |-----------------|---------|-----------|----------------|
 * | IDLE            | primary | none      | mic icon       |
 * | STARTING        | primary | none      | mic icon       |
 * | LISTENING       | red     | pulsing   | mic icon       |
 * | USER_SPEAKING   | red     | pulsing   | mic icon       |
 * | FINALIZING      | red     | pulsing   | mic icon       |
 * | PROCESSING      | primary | none      | spinner        |
 * | THINKING        | primary | none      | spinner        |
 * | SPEAKING        | primary | none      | mic icon       |
 * | RESTART_WAIT    | primary | none      | mic icon       |
 * | ERROR           | error   | none      | mic icon       |
 *
 * LISTENING / USER_SPEAKING / FINALIZING are the only states where the mic is
 * genuinely hot — only they show red + pulsing.
 *
 * STARTING / RESTART_WAIT show a neutral primary colour so the user is never
 * told "Kentas klauso" when the recognizer is not yet ready.
 *
 * The green session ring is shown when [sessionActive] is true and the state is
 * one of IDLE, STARTING, RESTART_WAIT, SPEAKING — i.e. the hands-free loop is
 * running but the mic is not currently active.
 *
 * @param state         Current recognition state, drives visuals.
 * @param statusText    Text shown below the button.
 * @param enabled       False when permission is missing or engine not ready.
 * @param sessionActive True when the continuous hands-free session loop is running.
 * @param size          Diameter of the circular button. Defaults to 64.dp.
 * @param onClick       Called when the button is tapped.
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
    // Red + pulsing only while the microphone is genuinely hot.
    val isListening = state == VoiceListeningState.LISTENING ||
                      state == VoiceListeningState.USER_SPEAKING ||
                      state == VoiceListeningState.FINALIZING

    // Pulse animation — runs while mic is active.
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
        isListening -> Color(0xFFD32F2F)                      // red while mic is hot
        state == VoiceListeningState.ERROR -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary             // neutral for all other states
    }

    // Session-active ring: shown when hands-free is on but the mic is not currently hot.
    // Covers IDLE (stopped between sessions), STARTING, RESTART_WAIT, SPEAKING.
    val showSessionRing = sessionActive && !isListening && state != VoiceListeningState.ERROR
    val sessionRingColor = Color(0xFF43A047) // green

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
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
                    VoiceListeningState.PROCESSING,
                    VoiceListeningState.THINKING -> {
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
                                    VoiceListeningState.LISTENING     -> "Kentas klauso"
                                    VoiceListeningState.USER_SPEAKING -> "Kentas klauso"
                                    VoiceListeningState.FINALIZING    -> "Kentas klauso"
                                    VoiceListeningState.STARTING      -> "Kentas ruošiasi"
                                    VoiceListeningState.RESTART_WAIT  -> "Kentas ruošiasi"
                                    VoiceListeningState.SPEAKING      -> "Kentas kalba"
                                    VoiceListeningState.PROCESSING    -> "Apdorojama"
                                    VoiceListeningState.THINKING      -> "Kentas galvoja"
                                    VoiceListeningState.ERROR         -> "Klaida"
                                    VoiceListeningState.IDLE          -> "Kalbėti"
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
 * Shows the button centred with status text below.
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
