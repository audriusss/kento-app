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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import lt.sturmanas.bajeristas.navigation.ManeuverType
import lt.sturmanas.bajeristas.navigation.NavigationController
import lt.sturmanas.bajeristas.navigation.NavigationState
import lt.sturmanas.bajeristas.safety.ConversationPermission

@Composable
fun NavigationScreen(
    navigationState: NavigationState,
    navigationController: NavigationController,
    conversationPermission: ConversationPermission,
    aiStatusMessage: String,
    isMuted: Boolean,
    onMicPress: () -> Unit,
    onMuteToggle: () -> Unit,
    onEnableStandardVoice: () -> Unit,
    onStopNavigation: () -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val engine = remember { navigationController.engine }

    Column(modifier = Modifier.fillMaxSize()) {

        // ── Navigation map — Google Navigation SDK view ────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            AndroidView(
                factory = { ctx -> navigationController.createNavigationView(ctx) },
                modifier = Modifier.fillMaxSize(),
            )

            // Rerouting overlay
            if (navigationState.isRerouting) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 16.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text("Perskaičiuojamas maršrutas…", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            // Arrival overlay
            if (navigationState.hasArrived) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xCC1B6CA8),
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            "Atvykote!",
                            style = MaterialTheme.typography.headlineLarge,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            navigationState.destinationName,
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color.White,
                        )
                    }
                }
            }
        }

        // Forward lifecycle events to NavigationView (required by SDK)
        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> engine.onResume()
                    Lifecycle.Event.ON_PAUSE -> engine.onPause()
                    Lifecycle.Event.ON_STOP -> engine.onStop()
                    Lifecycle.Event.ON_DESTROY -> engine.onDestroy()
                    else -> {}
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }

        // ── Bottom panel ──────────────────────────────────────────────────
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {

            // Error message banner
            navigationState.errorMessage?.let { error ->
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(
                        text = error,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }

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
                            text = maneuverLabel(navigationState.maneuverType),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        val roadInfo = when {
                            navigationState.nextRoadName.isNotBlank() -> navigationState.nextRoadName
                            navigationState.currentRoadName.isNotBlank() -> navigationState.currentRoadName
                            else -> "—"
                        }
                        Text(text = roadInfo, style = MaterialTheme.typography.bodySmall)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        val dist = navigationState.distanceToNextManeuverMeters
                        Text(
                            text = if (dist == Int.MAX_VALUE) "—" else "$dist m",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        val mins = navigationState.remainingDurationSeconds / 60
                        Text(text = "~$mins min", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Safety / conversation status
            val (permColor, permText) = when (conversationPermission) {
                ConversationPermission.ALLOWED -> Color(0xFF2E7D32) to "Pokalbis leidžiamas"
                ConversationPermission.SHORT_ONLY -> Color(0xFFF57F17) to "Tik trumpai — artėja manevras"
                ConversationPermission.BLOCKED -> Color(0xFFC62828) to "Navigacija turi prioritetą"
            }
            Text(text = permText, style = MaterialTheme.typography.labelMedium, color = permColor)

            if (aiStatusMessage.isNotBlank()) {
                Text(
                    text = aiStatusMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Controls row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onMuteToggle) {
                    Icon(
                        imageVector = if (isMuted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                        contentDescription = if (isMuted) "AI nutildytas" else "AI įjungtas",
                        tint = if (isMuted) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurface,
                    )
                }

                // Push-to-talk mic button (Phase 3: replace onClick with pointerInput press/release)
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

// ── Label helpers ─────────────────────────────────────────────────────────────

private fun maneuverLabel(type: ManeuverType): String = when (type) {
    ManeuverType.NONE, ManeuverType.STRAIGHT -> "Tiesiai"
    ManeuverType.TURN_LEFT -> "← Kairėn"
    ManeuverType.TURN_RIGHT -> "→ Dešinėn"
    ManeuverType.SLIGHT_LEFT -> "↖ Šiek tiek kairėn"
    ManeuverType.SLIGHT_RIGHT -> "↗ Šiek tiek dešinėn"
    ManeuverType.SHARP_LEFT -> "↰ Staigiai kairėn"
    ManeuverType.SHARP_RIGHT -> "↱ Staigiai dešinėn"
    ManeuverType.UTURN -> "↩ Apsisukimas"
    ManeuverType.ROUNDABOUT -> "↻ Žiedas"
    ManeuverType.MOTORWAY_EXIT -> "↘ Išvažiavimas"
    ManeuverType.LANE_CHANGE -> "⇒ Juostos keitimas"
    ManeuverType.COMPLEX_JUNCTION -> "✦ Sudėtinga sankryža"
    ManeuverType.MERGE -> "⇒ Įsijungimas į srautą"
    ManeuverType.FORK -> "⑂ Kelio šakojimasis"
    ManeuverType.ARRIVE -> "✓ Atvykote"
    ManeuverType.UNKNOWN -> "Tiesiai"
}
