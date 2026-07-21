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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import lt.sturmanas.bajeristas.personality.Personality

@Composable
fun StartScreen(
    onStartNavigation: (destination: String, personality: Personality, humorIntensity: Int) -> Unit,
) {
    var destination by remember { mutableStateOf("") }
    var selectedPersonality by remember { mutableStateOf(Personality.KENTAS) }
    var humorIntensity by remember { mutableFloatStateOf(50f) }
    val keyboard = LocalSoftwareKeyboardController.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 48.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
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

        Spacer(modifier = Modifier.height(40.dp))

        // ── Destination ───────────────────────────────────────────────────
        OutlinedTextField(
            value = destination,
            onValueChange = { destination = it },
            label = { Text("Tikslas") },
            placeholder = { Text("Įveskite adresą…") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }),
        )

        Spacer(modifier = Modifier.height(32.dp))

        // ── Personality selector ──────────────────────────────────────────
        Text(
            text = "Asmenybė",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(4.dp))

        Personality.entries.forEach { personality ->
            val label = when (personality) {
                Personality.RAMUS -> "Ramus"
                Personality.KENTAS -> "Kentas"   // fully implemented
                Personality.SARKASTISKAS -> "Sarkastiškas"
                Personality.JUODAS_HUMORAS -> "Juodas humoras"
            }
            val subtitle = when (personality) {
                Personality.KENTAS -> "V1 įgyvendinta"
                else -> "Netrukus"
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                RadioButton(
                    selected = selectedPersonality == personality,
                    onClick = { selectedPersonality = personality },
                )
                Column {
                    Text(label, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (personality == Personality.KENTAS)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // ── Humor intensity ───────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Humoro intensyvumas", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text("${humorIntensity.toInt()}", style = MaterialTheme.typography.bodyMedium)
        }
        Slider(
            value = humorIntensity,
            onValueChange = { humorIntensity = it },
            valueRange = 0f..100f,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(40.dp))

        // ── Start button ──────────────────────────────────────────────────
        Button(
            onClick = {
                keyboard?.hide()
                onStartNavigation(destination.trim(), selectedPersonality, humorIntensity.toInt())
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = destination.isNotBlank(),
        ) {
            Text("Pradėti navigaciją", style = MaterialTheme.typography.titleMedium)
        }
    }
}
