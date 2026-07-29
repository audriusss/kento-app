package lt.sturmanas.bajeristas.ui

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.android.libraries.places.api.model.AutocompletePrediction
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import lt.sturmanas.bajeristas.navigation.ManeuverType
import lt.sturmanas.bajeristas.navigation.NavigationController
import lt.sturmanas.bajeristas.navigation.NavigationPhase
import lt.sturmanas.bajeristas.navigation.NavigationState
import lt.sturmanas.bajeristas.navigation.PlacesAutocompleteClient

private const val NAV_SCREEN_TAG = "NavScreen"

/**
 * Holds a resolved destination selection while the confirmation dialog is visible.
 * Created after [PlacesAutocompleteClient.resolveCoordinates] returns successfully.
 */
private data class PendingReroute(
    /** Human-readable name shown in the confirmation dialog. */
    val displayName: String,
    /** Pre-resolved "lat,lng" coordinate string ready for [NavigationController.startNavigation]. */
    val coordsString: String,
)

@Composable
fun NavigationScreen(
    navigationState: NavigationState,
    navigationController: NavigationController,
    onStopNavigation: () -> Unit,
    /** Called with a "lat,lng" string when the user confirms a reroute via the floating search. */
    onRerouteNavigation: (destination: String) -> Unit = {},
    aiStatus: String = "IDLE",
) {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val engine = remember { navigationController.engine }
    val navView = remember(engine) { engine.createNavigationView(ctx) }

    // ── Floating search state — fully local, does not affect voice / AI flow ──
    val scope    = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    var searchExpanded   by remember { mutableStateOf(false) }
    var searchQuery      by remember { mutableStateOf("") }
    var searchSuggestions by remember { mutableStateOf<List<AutocompletePrediction>>(emptyList()) }
    var pendingReroute   by remember { mutableStateOf<PendingReroute?>(null) }
    val searchFocusRequester = remember { FocusRequester() }

    // Debounced autocomplete — mirrors StartScreen debounce pattern.
    LaunchedEffect(searchQuery) {
        if (searchQuery.isBlank()) {
            searchSuggestions = emptyList()
            return@LaunchedEffect
        }
        delay(300)
        searchSuggestions = PlacesAutocompleteClient.getSuggestions(ctx, searchQuery)
    }

    // Request focus when the search panel expands.
    LaunchedEffect(searchExpanded) {
        if (searchExpanded) {
            delay(60) // brief wait for recomposition + layout
            try { searchFocusRequester.requestFocus() } catch (_: Exception) {}
        }
    }

    // ── Lifecycle observer for the Google Nav SDK view ────────────────────────
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

    // ── Reroute confirmation dialog ───────────────────────────────────────────
    // Shown after the user taps a suggestion and coordinates have been resolved.
    // State is local — no effect on voice destination choices or navigation speech.
    pendingReroute?.let { pr ->
        AlertDialog(
            onDismissRequest = {
                Log.i(NAV_SCREEN_TAG, "NAV_ROUTE_CHANGE_CANCELLED")
                pendingReroute = null
            },
            title = { Text("Keisti maršrutą?") },
            text  = { Text("Keisti maršrutą į ${pr.displayName}?") },
            confirmButton = {
                TextButton(onClick = {
                    Log.i(NAV_SCREEN_TAG, "NAV_ROUTE_CHANGE_CONFIRMED dest='${pr.displayName}'")
                    onRerouteNavigation(pr.coordsString)
                    pendingReroute    = null
                    searchExpanded    = false
                    searchQuery       = ""
                    searchSuggestions = emptyList()
                    keyboard?.hide()
                }) { Text("Taip") }
            },
            dismissButton = {
                TextButton(onClick = {
                    Log.i(NAV_SCREEN_TAG, "NAV_ROUTE_CHANGE_CANCELLED")
                    pendingReroute = null
                }) { Text("Atšaukti") }
            },
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // ── Map view fills available space above the bottom controls ──────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            AndroidView(
                factory  = { navView },
                modifier = Modifier.fillMaxSize(),
            )

            // Top overlay — floating search + phase/AI status chips
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // ── Floating destination search ────────────────────────────
                if (searchExpanded) {
                    // Expanded: full input field with autocomplete.
                    DestinationSearchField(
                        query             = searchQuery,
                        onQueryChange     = { searchQuery = it },
                        suggestions       = searchSuggestions,
                        onSuggestionSelected = { prediction ->
                            // Resolve coordinates before showing confirmation dialog.
                            scope.launch {
                                val coords = PlacesAutocompleteClient.resolveCoordinates(
                                    ctx, prediction.placeId,
                                )
                                val coordsString = if (coords != null) {
                                    "${coords.first},${coords.second}"
                                } else {
                                    // Places fetch failed — fall back to name-based geocoding.
                                    prediction.getPrimaryText(null).toString()
                                }
                                val displayName = prediction.getPrimaryText(null).toString()
                                Log.i(NAV_SCREEN_TAG,
                                    "NAV_FLOATING_PLACE_SELECTED place='$displayName'")
                                pendingReroute    = PendingReroute(displayName, coordsString)
                                searchSuggestions = emptyList()
                            }
                        },
                        onClear = {
                            searchQuery       = ""
                            searchSuggestions = emptyList()
                        },
                        placeholder      = "Ieškoti kitos vietos",
                        focusRequester   = searchFocusRequester,
                        onDone           = { keyboard?.hide() },
                        // Slightly translucent surface so the map is still recognisable.
                        surfaceColor     = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
                        contentColor     = MaterialTheme.colorScheme.onSurface,
                        accentColor      = MaterialTheme.colorScheme.primary,
                        hintColor        = MaterialTheme.colorScheme.onSurfaceVariant,
                        dividerColor     = MaterialTheme.colorScheme.outlineVariant,
                        modifier         = Modifier.fillMaxWidth(),
                    )

                    // "Uždaryti paiešką" link below the field.
                    TextButton(
                        onClick = {
                            searchExpanded    = false
                            searchQuery       = ""
                            searchSuggestions = emptyList()
                            keyboard?.hide()
                        },
                    ) {
                        Text(
                            text  = "Uždaryti paiešką",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                } else {
                    // Collapsed: compact pill button.
                    Surface(
                        onClick = {
                            Log.i(NAV_SCREEN_TAG, "NAV_FLOATING_SEARCH_OPENED")
                            searchExpanded = true
                        },
                        shape         = RoundedCornerShape(24.dp),
                        color         = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                        shadowElevation = 4.dp,
                        modifier      = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(
                                imageVector        = Icons.Default.Search,
                                contentDescription = null,
                                tint               = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier           = Modifier.size(18.dp),
                            )
                            Text(
                                text  = "Ieškoti kitos vietos",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                // ── Phase / AI status chips (existing) ────────────────────
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
                            CircularProgressIndicator(
                                modifier    = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
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
                            text     = aiStatus,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            style    = MaterialTheme.typography.labelSmall,
                            color    = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                    }
                }
            }
        }

        // ── Bottom controls — maneuver info + stop button ─────────────────────
        // navigationBarsPadding() ensures this column is never hidden behind
        // the Android 3-button or gesture navigation bar.
        Column(
            modifier = Modifier
                .padding(start = 16.dp, top = 12.dp, end = 16.dp)
                .navigationBarsPadding()
                .padding(bottom = 8.dp),
        ) {
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
                        Text(
                            text  = navigationState.nextRoadName,
                            style = MaterialTheme.typography.bodySmall,
                        )
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

            Spacer(modifier = Modifier.height(4.dp))

            // "Baigti navigaciją" — full-width button; min 48 dp touch target.
            Button(
                onClick  = onStopNavigation,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor   = MaterialTheme.colorScheme.onErrorContainer,
                ),
            ) {
                Text(
                    "Baigti navigaciją",
                    style      = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
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
