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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

/**
 * Destination-entry screen shown before navigation starts.
 *
 * Simplified — personality pickers (ConversationMode, TripMode, HumorIntensity)
 * and voice-driven destination entry have been removed.
 * Destination is now manual-only via the text field.
 *
 * ## Location state
 *
 * [locationLoading] — true while the fused provider hasn't yet delivered a fix AND the
 * 10 s graceful timeout hasn't fired.  Shows the "Gaunama GPS vieta…" spinner badge.
 * Going false does NOT mean a real fix arrived — it may have timed out.
 *
 * [locationServicesDisabled] — true when the device Location switch is off.
 * Shows a clear banner prompting the user to enable Location Services.
 * The Start button is NOT disabled: the user can still type a destination and navigate;
 * routing will use whatever location is available (or degrade gracefully if none).
 *
 * [permissionDenied] — true when ACCESS_FINE_LOCATION was denied.
 * Shows a permission-denied message; destination entry remains usable.
 */
@Composable
fun StartScreen(
    /** Non-null if address resolution or permission failed; displayed above the button. */
    errorMessage: String? = null,
    /** False while the navigation engine is still initialising. */
    engineReady: Boolean = true,
    /**
     * True while waiting for the first fused location fix (and timeout not yet fired).
     * Hides the spinner badge once false.
     */
    locationLoading: Boolean = false,
    /**
     * True when the device's Location Services switch is disabled.
     * Shows a banner asking the user to enable Location in Settings.
     */
    locationServicesDisabled: Boolean = false,
    /**
     * True when ACCESS_FINE_LOCATION was denied by the user.
     * A permission-denied message is shown; manual destination entry remains usable.
     */
    permissionDenied: Boolean = false,
    /** Called when the user taps the gear icon to open Settings. */
    onOpenSettings: () -> Unit = {},
    onStartNavigation: (destination: String) -> Unit,
) {
    var destination by remember { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current

    Box(modifier = Modifier.fillMaxSize()) {

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 64.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // ── Header ────────────────────────────────────────────────────
            Text(
                text = "Šturmanas Bajeristas",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "AI vairavimo palydovas",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // ── Engine initialising badge ─────────────────────────────────
            if (!engineReady) {
                Spacer(modifier = Modifier.height(12.dp))
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(
                        text = "Navigacija inicializuojama…",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            }

            // ── GPS loading badge ─────────────────────────────────────────
            // Shown while fused location hasn't delivered a first fix yet.
            // Disappears once the fix arrives OR after the 10 s timeout.
            if (locationLoading) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        Text(
                            text = "Gaunama GPS vieta…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }

            // ── Location services disabled banner ─────────────────────────
            // Shown when the device's Location toggle is off.  The user must
            // enable it in Settings for the map and navigation to work.
            if (locationServicesDisabled) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.LocationOff,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Text(
                            text = "Vietovės paslaugos išjungtos. Įjunkite vietovę telefono nustatymuose, kad navigacija veiktų.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }

            // ── Permission denied banner ──────────────────────────────────
            // The errorMessage param carries the denied-permission text from MainActivity.
            // permissionDenied is passed so we can apply specific wording when needed.
            if (permissionDenied && errorMessage == null) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(
                        text = "Vietos leidimas atmestas. Suteikite leidimą telefono nustatymuose, kad navigacija žinotų jūsų poziciją.",
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }

            Spacer(modifier = Modifier.height(40.dp))

            // ── Destination field ─────────────────────────────────────────
            OutlinedTextField(
                value = destination,
                onValueChange = { destination = it },
                label = { Text("Tikslas") },
                placeholder = { Text("Adresas, vieta, koordinatės…") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }),
            )

            Spacer(modifier = Modifier.height(24.dp))

            // ── Error banner ──────────────────────────────────────────────
            errorMessage?.let { error ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(
                        text = error,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            // ── Start button ──────────────────────────────────────────────
            // The Start button is NOT disabled by missing location — the user must
            // always be able to type a destination and start.  Location is used for
            // routing bias but is not required to begin a navigation session.
            Button(
                onClick = {
                    keyboard?.hide()
                    onStartNavigation(destination.trim())
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = destination.isNotBlank() && engineReady,
            ) {
                Text("Pradėti navigaciją", style = MaterialTheme.typography.titleMedium)
            }
        }

        // ── Settings gear button ──────────────────────────────────────────
        IconButton(
            onClick = {
                keyboard?.hide()
                onOpenSettings()
            },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 8.dp, end = 8.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = "Nustatymai",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
