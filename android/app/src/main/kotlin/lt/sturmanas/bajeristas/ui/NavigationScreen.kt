package lt.sturmanas.bajeristas.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import lt.sturmanas.bajeristas.navigation.NavigationState
import lt.sturmanas.bajeristas.safety.ConversationPermission

@Composable
fun NavigationScreen(
    navigationState: NavigationState,
    conversationPermission: ConversationPermission,
    aiStatusMessage: String,
    isMuted: Boolean,
    onMicPress: () -> Unit,
    onMicRelease: () -> Unit,
    onMuteToggle: () -> Unit,
    onEnableStandardVoice: () -> Unit,
    onStopNavigation: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {

        // ── Map area (Phase 2: replace with AndroidView → NavFragment) ────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color(0xFFCDE9C5)),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("🗺", style = MaterialTheme.typography.displayMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Google Navigation SDK",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "Integruojama 2 fazėje",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF444444),
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Tikslas: ${navigationState.destination}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        // ── Bottom panel ──────────────────────────────────────────────────
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {

            // Maneuver info card
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(
                            text = maneuverLabel(navigationState),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = navigationState.currentStreet.ifBlank { "—" },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "${navigationState.distanceToManeuverMeters} m",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        val mins = navigationState.remainingTimeSeconds / 60
                        Text(
                            text = "~$mins min",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Safety status indicator
            val (permColor, permText) = when (conversationPermission) {
                ConversationPermission.ALLOWED -> Color(0xFF2E7D32) to "Pokalbis leidžiamas"
                ConversationPermission.SHORT_ONLY -> Color(0xFFF57F17) to "Tik trumpai — artėja manevras"
                ConversationPermission.BLOCKED -> Color(0xFFC62828) to "Navigacija turi prioritetą"
            }
            Text(
                text = permText,
                style = MaterialTheme.typography.labelMedium,
                color = permColor,
            )

            if (aiStatusMessage.isNotBlank()) {
                Text(
                    text = aiStatusMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Controls row: mute — mic — stop
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Mute toggle
                IconButton(onClick = onMuteToggle) {
                    Icon(
                        imageVector = if (isMuted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                        contentDescription = if (isMuted) "AI nutildytas" else "AI įjungtas",
                        tint = if (isMuted) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurface,
                    )
                }

                // Large push-to-talk mic button
                val micEnabled = conversationPermission != ConversationPermission.BLOCKED && !isMuted
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .background(
                            color = if (micEnabled) MaterialTheme.colorScheme.primary else Color.Gray,
                            shape = CircleShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    // Phase 3: replace with pointerInput { detectTapGestures(onPress = …) }
                    // for true press-and-hold push-to-talk
                    IconButton(
                        onClick = { if (micEnabled) onMicPress() },
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = "Kalbėti",
                            tint = Color.White,
                            modifier = Modifier.size(36.dp),
                        )
                    }
                }

                // Stop navigation
                TextButton(onClick = onStopNavigation) {
                    Text("Baigti", color = MaterialTheme.colorScheme.error)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Emergency fallback — always visible, always functional
            OutlinedButton(
                onClick = onEnableStandardVoice,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Įjungti įprastą navigacijos balsą")
            }
        }
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

private fun maneuverLabel(state: NavigationState): String = when (state.nextManeuver) {
    lt.sturmanas.bajeristas.navigation.ManeuverType.NONE -> "Tiesiai"
    lt.sturmanas.bajeristas.navigation.ManeuverType.STRAIGHT -> "Tiesiai"
    lt.sturmanas.bajeristas.navigation.ManeuverType.TURN_LEFT -> "← Kairėn"
    lt.sturmanas.bajeristas.navigation.ManeuverType.TURN_RIGHT -> "→ Dešinėn"
    lt.sturmanas.bajeristas.navigation.ManeuverType.ROUNDABOUT -> "↻ Žiedas"
    lt.sturmanas.bajeristas.navigation.ManeuverType.MOTORWAY_EXIT -> "↘ Išvažiavimas"
    lt.sturmanas.bajeristas.navigation.ManeuverType.LANE_CHANGE -> "⇒ Juostos keitimas"
    lt.sturmanas.bajeristas.navigation.ManeuverType.COMPLEX_JUNCTION -> "✦ Sudėtinga sankryža"
    lt.sturmanas.bajeristas.navigation.ManeuverType.UTURN -> "↩ Apsisukimas"
}
