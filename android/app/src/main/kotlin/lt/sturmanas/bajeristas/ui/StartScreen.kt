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
 */
@Composable
fun StartScreen(
    /** Non-null if address resolution or permission failed; displayed above the button. */
    errorMessage: String? = null,
    /** False while the navigation engine is still initialising. */
    engineReady: Boolean = true,
    /**
     * False while no usable GPS location is available yet.
     * Shows a small loading badge. Does NOT disable the Start button — the user
     * can still type a destination and start; routing uses whatever location is
     * available when they tap Start.
     */
    locationReady: Boolean = true,
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

            if (!locationReady) {
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
