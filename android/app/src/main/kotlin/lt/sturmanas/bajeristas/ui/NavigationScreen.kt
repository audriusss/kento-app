package lt.sturmanas.bajeristas.ui

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import lt.sturmanas.bajeristas.community.CommunityMarkerRepository
import lt.sturmanas.bajeristas.navigation.ManeuverType
import lt.sturmanas.bajeristas.navigation.NavigationController
import lt.sturmanas.bajeristas.navigation.NavigationPhase
import lt.sturmanas.bajeristas.navigation.NavigationState
import lt.sturmanas.bajeristas.safety.ConversationPermission
import lt.sturmanas.bajeristas.voice.VoiceListeningState

@Composable
fun NavigationScreen(
    navigationState: NavigationState,
    navigationController: NavigationController,
    conversationPermission: ConversationPermission,
    /** Current conversation state — drives MicButton visual. */
    voiceListeningState: VoiceListeningState = VoiceListeningState.IDLE,
    /** True while a conversation session is active — shows the green ring. */
    isConversationActive: Boolean = false,
    onMicPress: () -> Unit,
    onStopNavigation: () -> Unit,
    /** Called when the user taps the speed-camera report button. */
    onReportMarker: (type: CommunityMarkerRepository.MarkerType, lat: Double, lng: Double) -> Unit,
) {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val engine = remember { navigationController.engine }

    // Create the NavigationView during composition (inside remember), NOT inside
    // the AndroidView factory. The factory runs at layout time — after side-effects.
    // Creating it here guarantees the view (and its onCreate call) completes before
    // any side-effects fire.
    val navView = remember(engine) { engine.createNavigationView(ctx) }

    // ── NavigationView lifecycle management ───────────────────────────────
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START  -> engine.onStart()
                Lifecycle.Event.ON_RESUME -> engine.onResume()
                Lifecycle.Event.ON_PAUSE  -> engine.onPause()
                Lifecycle.Event.ON_STOP   -> engine.onStop()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            val state = lifecycleOwner.lifecycle.currentState
            if (state.isAtLeast(Lifecycle.State.RESUMED)) engine.onPause()
            if (state.isAtLeast(Lifecycle.State.STARTED)) engine.onStop()
            // IMPORTANT: call onViewDestroy(), NOT onDestroy().
            // onViewDestroy() tears down NavigationView only; Navigator stays alive
            // so startNavigation() works again without re-initialising the SDK.
            engine.onViewDestroy()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {

        // ── Navigation map ─────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            AndroidView(
                factory  = { navView },
                modifier = Modifier.fillMaxSize(),
            )

            // Phase-based loading overlays
            val phaseLabel = when (navigationState.phase) {
                NavigationPhase.RESOLVING_ADDRESS -> "Ieškomas adresas…"
                NavigationPhase.CALCULATING_ROUTE -> "Skaičiuojamas maršrutas…"
                else -> null
            }
            if (phaseLabel != null) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 16.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(phaseLabel, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

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

        // ── Bottom panel ──────────────────────────────────────────────────
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {

            // Error banner
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
                modifier  = Modifier.fillMaxWidth(),
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
                            text       = maneuverLabel(navigationState.maneuverType),
                            style      = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        val roadInfo = when {
                            navigationState.nextRoadName.isNotBlank()    -> navigationState.nextRoadName
                            navigationState.currentRoadName.isNotBlank() -> navigationState.currentRoadName
                            else -> "—"
                        }
                        Text(text = roadInfo, style = MaterialTheme.typography.bodySmall)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        val dist = navigationState.distanceToNextManeuverMeters
                        Text(
                            text       = if (dist == Int.MAX_VALUE) "—" else "$dist m",
                            style      = MaterialTheme.typography.titleSmall,
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
                ConversationPermission.ALLOWED    -> Color(0xFF2E7D32) to "Pokalbis leidžiamas"
                ConversationPermission.SHORT_ONLY -> Color(0xFFF57F17) to "Tik trumpai — artėja manevras"
                ConversationPermission.BLOCKED    -> Color(0xFFC62828) to "Navigacija turi prioritetą"
            }
            Text(text = permText, style = MaterialTheme.typography.labelMedium, color = permColor)

            Spacer(modifier = Modifier.height(12.dp))

            // Controls row: mic | stop | marker report
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val micEnabled = conversationPermission != ConversationPermission.BLOCKED
                MicButton(
                    state                = if (micEnabled) voiceListeningState else VoiceListeningState.IDLE,
                    statusText           = "",
                    enabled              = micEnabled,
                    isConversationActive = isConversationActive,
                    onClick              = onMicPress,
                    size                 = 80.dp,
                )

                TextButton(onClick = onStopNavigation) {
                    Text("Baigti", color = MaterialTheme.colorScheme.error)
                }

                // Speed-camera / police report button
                OutlinedButton(
                    onClick = {
                        // TODO: get current location from LocationProvider.cachedLocation
                        val loc = lt.sturmanas.bajeristas.navigation.LocationProvider.cachedLocation
                        if (loc != null) {
                            onReportMarker(
                                CommunityMarkerRepository.MarkerType.SPEED_CAMERA,
                                loc.latitude,
                                loc.longitude,
                            )
                        }
                    },
                ) {
                    androidx.compose.material3.Icon(
                        imageVector        = Icons.Default.CameraAlt,
                        contentDescription = "Pranešti apie radaro/policijos postą",
                        modifier           = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.size(4.dp))
                    Text("Radaras", style = MaterialTheme.typography.labelMedium)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Divider before ETA bar (removed in simplification — kept for spacing)
        }
    }
}

// ── Label helpers ─────────────────────────────────────────────────────────────

private fun maneuverLabel(type: ManeuverType): String = when (type) {
    ManeuverType.NONE, ManeuverType.STRAIGHT -> "Tiesiai"
    ManeuverType.TURN_LEFT          -> "← Kairėn"
    ManeuverType.TURN_RIGHT         -> "→ Dešinėn"
    ManeuverType.SLIGHT_LEFT        -> "↖ Šiek tiek kairėn"
    ManeuverType.SLIGHT_RIGHT       -> "↗ Šiek tiek dešinėn"
    ManeuverType.SHARP_LEFT         -> "↰ Staigiai kairėn"
    ManeuverType.SHARP_RIGHT        -> "↱ Staigiai dešinėn"
    ManeuverType.UTURN              -> "↩ Apsisukimas"
    ManeuverType.ROUNDABOUT         -> "↻ Žiedas"
    ManeuverType.MOTORWAY_EXIT      -> "↘ Išvažiavimas"
    ManeuverType.LANE_CHANGE        -> "⇒ Juostos keitimas"
    ManeuverType.COMPLEX_JUNCTION   -> "✦ Sudėtinga sankryža"
    ManeuverType.MERGE              -> "⇒ Įsijungimas į srautą"
    ManeuverType.FORK               -> "⑂ Kelio šakojimasis"
    ManeuverType.ARRIVE             -> "✓ Atvykote"
    ManeuverType.UNKNOWN            -> "Tiesiai"
}
