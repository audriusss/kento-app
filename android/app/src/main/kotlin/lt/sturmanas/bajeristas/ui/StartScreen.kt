package lt.sturmanas.bajeristas.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import lt.sturmanas.bajeristas.personality.ConversationMode
import lt.sturmanas.bajeristas.personality.HumorIntensity
import lt.sturmanas.bajeristas.personality.SessionConfig
import lt.sturmanas.bajeristas.personality.TripMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StartScreen(
    /** Non-null if address resolution or permission failed; displayed above the button. */
    errorMessage: String? = null,
    /** False while the navigation engine is still initialising. */
    engineReady: Boolean = true,
    onStartNavigation: (destination: String, config: SessionConfig) -> Unit,
) {
    var destination by remember { mutableStateOf("") }
    var conversationMode by remember { mutableStateOf(ConversationMode.SOFT) }
    var tripMode by remember { mutableStateOf(TripMode.SOLO) }
    var humorIntensity by remember { mutableStateOf(HumorIntensity.NORMAL) }
    val keyboard = LocalSoftwareKeyboardController.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 48.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // ── Header ────────────────────────────────────────────────────────
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

        // Engine not-ready notice (shown while initialising or after permission denial)
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

        Spacer(modifier = Modifier.height(32.dp))

        // ── Destination ───────────────────────────────────────────────────
        OutlinedTextField(
            value = destination,
            onValueChange = { destination = it },
            label = { Text("Tikslas") },
            placeholder = { Text("Adresas arba platuma,ilguma (pvz. 54.6872,25.2797)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }),
        )

        Spacer(modifier = Modifier.height(28.dp))

        // ── Conversation mode: Soft / Hard ────────────────────────────────
        SectionLabel("Kalbėjimo stilius")
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            ConversationMode.entries.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = conversationMode == mode,
                    onClick = { conversationMode = mode },
                    shape = SegmentedButtonDefaults.itemShape(index, ConversationMode.entries.size),
                    label = {
                        Text(
                            when (mode) {
                                ConversationMode.SOFT -> "Soft"
                                ConversationMode.HARD -> "Hard"
                            }
                        )
                    },
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // ── Trip mode: Solo / Duo / Group ─────────────────────────────────
        SectionLabel("Kelionės režimas")
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            TripMode.entries.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = tripMode == mode,
                    onClick = { tripMode = mode },
                    shape = SegmentedButtonDefaults.itemShape(index, TripMode.entries.size),
                    label = {
                        Text(
                            when (mode) {
                                TripMode.SOLO -> "Solo"
                                TripMode.DUO -> "Duo"
                                TripMode.GROUP -> "Grupė"
                            }
                        )
                    },
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // ── Humor intensity: Light / Normal / Strong ───────────────────────
        SectionLabel("Humoro intensyvumas")
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            HumorIntensity.entries.forEachIndexed { index, intensity ->
                SegmentedButton(
                    selected = humorIntensity == intensity,
                    onClick = { humorIntensity = intensity },
                    shape = SegmentedButtonDefaults.itemShape(index, HumorIntensity.entries.size),
                    label = {
                        Text(
                            when (intensity) {
                                HumorIntensity.LIGHT -> "Lengvas"
                                HumorIntensity.NORMAL -> "Normalus"
                                HumorIntensity.STRONG -> "Stiprus"
                            }
                        )
                    },
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // ── Error banner (address not found, permission denied, etc.) ─────
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

        // ── Start button ──────────────────────────────────────────────────
        Button(
            onClick = {
                keyboard?.hide()
                onStartNavigation(
                    destination.trim(),
                    SessionConfig(
                        conversationMode = conversationMode,
                        tripMode = tripMode,
                        humorIntensity = humorIntensity,
                    ),
                )
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = destination.isNotBlank() && engineReady,
        ) {
            Text("Pradėti navigaciją", style = MaterialTheme.typography.titleMedium)
        }
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

@Composable
private fun SectionLabel(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp),
        horizontalArrangement = Arrangement.Start,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
