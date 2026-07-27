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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import lt.sturmanas.bajeristas.navigation.ManeuverType
import lt.sturmanas.bajeristas.navigation.NavigationController
import lt.sturmanas.bajeristas.navigation.NavigationPhase
import lt.sturmanas.bajeristas.navigation.NavigationState

@Composable
fun NavigationScreen(
    navigationState: NavigationState,
    navigationController: NavigationController,
    onStopNavigation: () -> Unit,
    aiStatus: String = "IDLE",
) {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val engine = remember { navigationController.engine }
    val navView = remember(engine) { engine.createNavigationView(ctx) }

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
            engine.onViewDestroy()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            AndroidView(
                factory  = { navView },
                modifier = Modifier.fillMaxSize(),
            )

            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val phaseLabel = when (navigationState.phase) {
                    NavigationPhase.RESOLVING_ADDRESS -> "Ieškomas adresas…"
                    NavigationPhase.CALCULATING_ROUTE -> "Skaičiuojamas maršrutas…"
                    else -> null
                }
                if (phaseLabel != null) {
                    Surface(
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

                if (aiStatus != "IDLE") {
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Text(
                            text = aiStatus,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                }
            }
        }

        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
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
                        Text(text = navigationState.nextRoadName, style = MaterialTheme.typography.bodySmall)
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

            TextButton(onClick = onStopNavigation, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text("Baigti navigaciją", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

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
