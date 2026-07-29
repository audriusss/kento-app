package lt.sturmanas.bajeristas.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.google.android.libraries.places.api.model.AutocompletePrediction
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import lt.sturmanas.bajeristas.navigation.PlacesAutocompleteClient
import lt.sturmanas.bajeristas.ui.theme.BackgroundPetrol
import lt.sturmanas.bajeristas.ui.theme.OnBackgroundLight
import lt.sturmanas.bajeristas.ui.theme.OnSurfaceVariantLight
import lt.sturmanas.bajeristas.ui.theme.PrimaryMint
import lt.sturmanas.bajeristas.ui.theme.SurfacePetrol
import lt.sturmanas.bajeristas.ui.theme.SurfaceVariantPetrol

/**
 * Start / destination-entry screen.
 *
 * ## Design intent
 *  - Dark petrol-green background, matching the active navigation overlay colour.
 *  - "Šturmanas Bajeristas" identity at the top.
 *  - Large conversational heading "Kur varom?" — car-friendly, readable at a glance.
 *  - Rounded destination input with clear contrast.
 *  - **Google Places autocomplete dropdown** appears below the input while the user
 *    types; tapping a suggestion resolves its coordinates and starts navigation
 *    immediately without touching [GoogleNavigationEngine]'s existing logic.
 *  - Prominent "Važiuojam" CTA — 56 dp tall, full-width, only enabled when
 *    text is present and the engine is ready.
 *  - All bottom controls sit above the Android system navigation bar via
 *    [safeDrawingPadding] + [imePadding] so nothing is hidden by 3-button or
 *    gesture bars, and the keyboard does not occlude the primary action.
 */
@Composable
fun StartScreen(
    errorMessage: String? = null,
    engineReady: Boolean = true,
    onStartNavigation: (destination: String) -> Unit,
) {
    var destination by remember { mutableStateOf("") }
    var suggestions by remember { mutableStateOf<List<AutocompletePrediction>>(emptyList()) }

    val context  = LocalContext.current
    val scope    = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current

    // ── Debounced autocomplete (300 ms after last keystroke) ──────────────────
    // LaunchedEffect cancels the previous job on each keystroke, giving a
    // natural debounce with zero extra dependencies.
    LaunchedEffect(destination) {
        if (destination.isBlank()) {
            suggestions = emptyList()
            return@LaunchedEffect
        }
        delay(300)
        suggestions = PlacesAutocompleteClient.getSuggestions(context, destination)
    }

    // ── Suggestion selection handler ──────────────────────────────────────────
    // Fetches lat/lng from the Places SDK, then delegates to onStartNavigation
    // with a raw-coordinate string (e.g. "54.6872,25.2797").
    // GoogleNavigationEngine.resolveAddress() already handles "lat,lng" strings
    // in its first branch — no changes needed there.
    // Falls back to the typed text on any Places error so the existing Geocoder
    // path in GoogleNavigationEngine takes over.
    fun onSuggestionSelected(prediction: AutocompletePrediction) {
        val displayText = prediction.getPrimaryText(null).toString()
        destination  = displayText
        suggestions  = emptyList()
        keyboard?.hide()
        scope.launch {
            val coords = PlacesAutocompleteClient.resolveCoordinates(context, prediction.placeId)
            if (coords != null) {
                onStartNavigation("${coords.first},${coords.second}")
            } else {
                // Places coordinate fetch failed — let the existing Geocoder resolve it.
                onStartNavigation(displayText)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundPetrol)
            // safeDrawingPadding handles status bar + navigation bar + display cutout.
            .safeDrawingPadding()
            // imePadding lifts the content above the software keyboard.
            .imePadding(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            // ── Top identity block ────────────────────────────────────────
            Column(
                modifier = Modifier.padding(top = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // App icon accent row
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(PrimaryMint, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Navigation,
                            contentDescription = null,
                            tint = Color(0xFF001412),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Text(
                        text = "Šturmanas Bajeristas",
                        style = MaterialTheme.typography.labelLarge,
                        color = PrimaryMint,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Main conversational heading
                Text(
                    text = "Kur varom?",
                    style = MaterialTheme.typography.displaySmall,
                    color = OnBackgroundLight,
                    fontWeight = FontWeight.Bold,
                )
            }

            // ── Input + action block ──────────────────────────────────────
            Column(
                modifier = Modifier.padding(top = 40.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Destination input card
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = SurfaceVariantPetrol,
                    tonalElevation = 2.dp,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Default.LocationOn,
                            contentDescription = null,
                            tint = PrimaryMint,
                            modifier = Modifier
                                .padding(start = 12.dp)
                                .size(24.dp),
                        )
                        TextField(
                            value = destination,
                            onValueChange = { destination = it },
                            placeholder = {
                                Text(
                                    "Įvesk adresą arba vietą",
                                    color = OnSurfaceVariantLight,
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(
                                onDone = {
                                    keyboard?.hide()
                                    if (destination.isNotBlank() && engineReady) {
                                        suggestions = emptyList()
                                        onStartNavigation(destination.trim())
                                    }
                                },
                            ),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor   = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor   = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                focusedTextColor        = OnBackgroundLight,
                                unfocusedTextColor      = OnBackgroundLight,
                                cursorColor             = PrimaryMint,
                            ),
                            textStyle = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }

                // ── Autocomplete suggestions dropdown ─────────────────────
                // Shown only when the Places SDK returns results.
                // Dismissed automatically when a suggestion is selected or
                // the field is cleared.
                if (suggestions.isNotEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = SurfaceVariantPetrol,
                        tonalElevation = 2.dp,
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            suggestions.forEachIndexed { index, prediction ->
                                if (index > 0) {
                                    HorizontalDivider(
                                        color = SurfacePetrol,
                                        thickness = 1.dp,
                                    )
                                }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onSuggestionSelected(prediction) }
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.LocationOn,
                                        contentDescription = null,
                                        tint = PrimaryMint,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = prediction.getPrimaryText(null).toString(),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = OnBackgroundLight,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1,
                                        )
                                        val secondary = prediction.getSecondaryText(null).toString()
                                        if (secondary.isNotBlank()) {
                                            Text(
                                                text = secondary,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = OnSurfaceVariantLight,
                                                maxLines = 1,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Error banner
                errorMessage?.let { error ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(
                            text = error,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }

                // Not-ready banner (engine initialising)
                if (!engineReady) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = SurfacePetrol,
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(
                            text = "Navigacija inicializuojama…",
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = OnSurfaceVariantLight,
                        )
                    }
                }

                // Primary CTA — "Važiuojam"
                Button(
                    onClick = {
                        keyboard?.hide()
                        suggestions = emptyList()
                        onStartNavigation(destination.trim())
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    enabled = destination.isNotBlank() && engineReady,
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor         = PrimaryMint,
                        contentColor           = Color(0xFF001412),
                        disabledContainerColor = SurfacePetrol,
                        disabledContentColor   = OnSurfaceVariantLight,
                    ),
                ) {
                    Text(
                        text = "Važiuojam",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}
