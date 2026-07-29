package lt.sturmanas.bajeristas.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.google.android.libraries.places.api.model.AutocompletePrediction

/**
 * Reusable destination input field with a debounced autocomplete suggestions list.
 *
 * Used by [StartScreen] (full-screen destination entry) and by the floating search
 * overlay inside [NavigationScreen] (map overlay, reroute flow).
 *
 * Both callers provide their own debounce + [PlacesAutocompleteClient] calls;
 * this composable is purely presentational.
 *
 * @param query               Current text in the input field.
 * @param onQueryChange       Called on every keystroke.
 * @param suggestions         Autocomplete predictions to display below the input.
 * @param onSuggestionSelected Called when the user taps a suggestion row.
 * @param onClear             Called when the user taps the trailing ✕ icon.
 * @param placeholder         Input hint text.
 * @param modifier            Applied to the outer [Column].
 * @param onDone              Optional keyboard Done-action callback. Null = hide keyboard only.
 * @param focusRequester      Optional — attach to request focus programmatically (e.g. on expand).
 * @param surfaceColor        Background for the input card and suggestions card.
 * @param contentColor        Primary text / icon colour.
 * @param accentColor         Leading icon / cursor colour.
 * @param hintColor           Placeholder / secondary text colour.
 * @param dividerColor        Colour of dividers between suggestion rows.
 */
@Composable
fun DestinationSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    suggestions: List<AutocompletePrediction>,
    onSuggestionSelected: (AutocompletePrediction) -> Unit,
    onClear: () -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    onDone: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null,
    surfaceColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    accentColor: Color = MaterialTheme.colorScheme.primary,
    hintColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    dividerColor: Color = MaterialTheme.colorScheme.outlineVariant,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // ── Input card ────────────────────────────────────────────────────────
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = surfaceColor,
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
                    tint = accentColor,
                    modifier = Modifier
                        .padding(start = 12.dp)
                        .size(24.dp),
                )
                TextField(
                    value = query,
                    onValueChange = onQueryChange,
                    placeholder = {
                        Text(
                            text = placeholder,
                            color = hintColor,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    },
                    modifier = Modifier
                        .weight(1f)
                        .then(
                            if (focusRequester != null) Modifier.focusRequester(focusRequester)
                            else Modifier
                        ),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor   = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor   = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        focusedTextColor        = contentColor,
                        unfocusedTextColor      = contentColor,
                        cursorColor             = accentColor,
                    ),
                    textStyle = MaterialTheme.typography.bodyLarge,
                    trailingIcon = if (query.isNotEmpty()) {
                        {
                            IconButton(onClick = onClear) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Išvalyti",
                                    tint = hintColor,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    } else null,
                )
            }
        }

        // ── Autocomplete suggestions ──────────────────────────────────────────
        if (suggestions.isNotEmpty()) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = surfaceColor,
                tonalElevation = 2.dp,
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    suggestions.forEachIndexed { index, prediction ->
                        if (index > 0) {
                            HorizontalDivider(color = dividerColor, thickness = 1.dp)
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
                                tint = accentColor,
                                modifier = Modifier.size(18.dp),
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = prediction.getPrimaryText(null).toString(),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = contentColor,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                )
                                val secondary = prediction.getSecondaryText(null).toString()
                                if (secondary.isNotBlank()) {
                                    Text(
                                        text = secondary,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = hintColor,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
