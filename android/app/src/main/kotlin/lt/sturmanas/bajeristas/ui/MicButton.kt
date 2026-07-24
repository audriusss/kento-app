package lt.sturmanas.bajeristas.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
 * Reusable microphone button used on [NavigationScreen].
 *
 * ## Visual states
 *
 * | State          | Colour  | Animation | Inner widget |
 * |----------------|---------|-----------|--------------|
 * | IDLE           | primary | none      | mic icon     |
 * | LISTENING      | red     | pulsing   | mic icon     |
 * | USER_SPEAKING  | red     | pulsing   | mic icon     |
 * | THINKING       | primary | none      | spinner      |
 * | SPEAKING       | primary | none      | mic icon     |
 *
 * LISTENING and USER_SPEAKING are the only states where the mic is genuinely hot.
 *
 * When [isConversationActive] is true and the mic is not hot, a green ring is
 * shown to indicate the conversation session is running (even while Kentas speaks).
 *
 * @param state                Current voice state, drives visuals.
 * @param statusText           Text shown below the button.
 * @param enabled              False when permission missing or safety blocked.
 * @param isConversationActive True when the conversation loop is running.
 * @param size                 Diameter of the circular button.
 * @param onClick              Called when the button is tapped.
 */
@Composable
fun MicButton(
    state: VoiceListeningState,
    statusText: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 64.dp,
    isConversationActive: Boolean = false,
) {
    val isListening = state == VoiceListeningState.LISTENING ||
                      state == VoiceListeningState.USER_SPEAKING

    // Pulse animation — only while mic is genuinely hot.
    val infiniteTransition = rememberInfiniteTransition(label = "mic_pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue  = if (isListening) 1.15f else 1f,
        animationSpec = infiniteRepeatable(
            animation  = tween(durationMillis = 600),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "mic_pulse_scale",
    )

    val bgColor = when {
        !enabled   -> Color.Gray
        isListening -> Color(0xFFD32F2F)          // red while mic is hot
        else        -> MaterialTheme.colorScheme.primary
    }

    // Green session ring: conversation is active but mic is not currently hot.
    val showSessionRing = isConversationActive && !isListening
    val sessionRingColor = Color(0xFF43A047)

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
                    VoiceListeningState.THINKING -> {
                        CircularProgressIndicator(
                            modifier   = Modifier.size(size * 0.55f),
                            color      = Color.White,
                            strokeWidth = 3.dp,
                        )
                    }
                    else -> {
                        IconButton(
                            onClick   = { if (enabled) onClick() },
                            modifier  = Modifier.size(size),
                        ) {
                            Icon(
                                imageVector  = Icons.Default.Mic,
                                contentDescription = when (state) {
                                    VoiceListeningState.LISTENING     -> "Kentas klauso"
                                    VoiceListeningState.USER_SPEAKING -> "Kentas klauso"
                                    VoiceListeningState.SPEAKING      -> "Kentas kalba"
                                    VoiceListeningState.THINKING      -> "Kentas galvoja"
                                    VoiceListeningState.IDLE          -> "Kalbėti"
                                },
                                tint     = Color.White,
                                modifier = Modifier.size(size * 0.5f),
                            )
                        }
                    }
                }
            }
        }

        if (statusText.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text  = statusText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier  = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
            )
        }
    }
}
