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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
 * Minimal settings screen for saving home and work addresses.
 *
 * Does not require account login. Addresses are stored locally using
 * [lt.sturmanas.bajeristas.voice.SavedPlacesRepository] (SharedPreferences).
 *
 * @param homeAddress  Current saved home address (empty string if not set).
 * @param workAddress  Current saved work address (empty string if not set).
 * @param onSaveHome   Called with the new home address when the user taps Save.
 * @param onSaveWork   Called with the new work address when the user taps Save.
 * @param onClearHome  Called when the user taps the clear (×) button for home.
 * @param onClearWork  Called when the user taps the clear (×) button for work.
 * @param onBack       Called when the user taps the back arrow.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    homeAddress: String,
    workAddress: String,
    onSaveHome: (String) -> Unit,
    onSaveWork: (String) -> Unit,
    onClearHome: () -> Unit,
    onClearWork: () -> Unit,
    onBack: () -> Unit,
) {
    var homeInput by remember(homeAddress) { mutableStateOf(homeAddress) }
    var workInput by remember(workAddress) { mutableStateOf(workAddress) }
    val keyboard = LocalSoftwareKeyboardController.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Nustatymai") },
                navigationIcon = {
                    IconButton(onClick = {
                        keyboard?.hide()
                        onBack()
                    }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Grįžti",
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(innerPadding)
                .padding(horizontal = 24.dp, vertical = 16.dp),
        ) {
            // ── Intro banner ──────────────────────────────────────────────
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = MaterialTheme.shapes.small,
            ) {
                Text(
                    text = "Išsaugoti adresai leidžia sakyti:\n„Važiuojam namo" arba „Važiuojam į darbą"",
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // ── Home address ──────────────────────────────────────────────
            SectionHeader("🏠  Namų adresas")
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = homeInput,
                onValueChange = { homeInput = it },
                label = { Text("Adresas") },
                placeholder = { Text("pvz. Taikos prospektas 61, Klaipėda") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }),
                trailingIcon = {
                    if (homeInput.isNotBlank()) {
                        IconButton(onClick = {
                            homeInput = ""
                            onClearHome()
                        }) {
                            Icon(Icons.Default.Clear, contentDescription = "Išvalyti")
                        }
                    }
                },
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = {
                        keyboard?.hide()
                        onSaveHome(homeInput)
                    },
                    modifier = Modifier.weight(1f),
                    enabled = homeInput.isNotBlank(),
                ) {
                    Text("Išsaugoti")
                }
                OutlinedButton(
                    onClick = {
                        homeInput = ""
                        onClearHome()
                    },
                    modifier = Modifier.weight(1f),
                    enabled = homeAddress.isNotBlank(),
                ) {
                    Text("Ištrinti")
                }
            }
            if (homeAddress.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Išsaugota: $homeAddress",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(24.dp))

            // ── Work address ──────────────────────────────────────────────
            SectionHeader("💼  Darbo adresas")
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = workInput,
                onValueChange = { workInput = it },
                label = { Text("Adresas") },
                placeholder = { Text("pvz. Gedimino prospektas 9, Vilnius") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }),
                trailingIcon = {
                    if (workInput.isNotBlank()) {
                        IconButton(onClick = {
                            workInput = ""
                            onClearWork()
                        }) {
                            Icon(Icons.Default.Clear, contentDescription = "Išvalyti")
                        }
                    }
                },
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = {
                        keyboard?.hide()
                        onSaveWork(workInput)
                    },
                    modifier = Modifier.weight(1f),
                    enabled = workInput.isNotBlank(),
                ) {
                    Text("Išsaugoti")
                }
                OutlinedButton(
                    onClick = {
                        workInput = ""
                        onClearWork()
                    },
                    modifier = Modifier.weight(1f),
                    enabled = workAddress.isNotBlank(),
                ) {
                    Text("Ištrinti")
                }
            }
            if (workAddress.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Išsaugota: $workAddress",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 2.dp),
    )
}
