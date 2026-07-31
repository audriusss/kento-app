package lt.sturmanas.bajeristas.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.paint
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.libraries.places.api.model.AutocompletePrediction
import kotlinx.coroutines.delay
import lt.sturmanas.bajeristas.R
import lt.sturmanas.bajeristas.navigation.PlacesAutocompleteClient
import lt.sturmanas.bajeristas.ui.theme.NearBlack
import lt.sturmanas.bajeristas.ui.theme.NeonCyan
import lt.sturmanas.bajeristas.ui.theme.NeonGreen
import lt.sturmanas.bajeristas.ui.theme.OnSurfaceVariantLight
import lt.sturmanas.bajeristas.ui.theme.SurfaceVariantPetrol

/**
 * Start / destination-entry screen — dark neon Kentas theme.
 *
 * ## Design
 *  - Near-black background with neon green / cyan accents.
 *  - Full-bleed hero shot: black sports car on a wet mountain road at night,
 *    rear licence plate reading "KENTAS", green glowing taillights.
 *  - Brand row (nav icon + ŠTURMANAS / BAJERISTAS) overlaid on the car image.
 *  - Small speech bubble near the car: "Aš tavo šturmanas. Tu vairuoji."
 *  - Large "KUR / VAROM?" title below the hero.
 *  - Rounded translucent address input (dark card, neon accent).
 *  - Gradient "VAŽIUOJAM" action button (green → cyan).
 *  - Pulsing "KENTAS KLAUSOSI…" mic indicator at the bottom.
 *
 * ## Functional preservation
 *  All autocomplete, suggestion-selection, keyboard, voice-destination, and
 *  navigation-start logic is unchanged.  Only presentation differs from the
 *  previous version.
 */
@Composable
fun StartScreen(
    viewModel: lt.sturmanas.bajeristas.MainViewModel,
    errorMessage: String? = null,
    engineReady: Boolean = true,
    /** True while the destination STT session is actively recording. */
    isDestinationListening: Boolean = false,
    /**
     * Called when the user taps the mic button.
     * The caller must call [onVoiceResult] when a transcript arrives so the
     * text lands in the search field and triggers the autocomplete flow.
     */
    onMicClick: (onVoiceResult: (String) -> Unit) -> Unit = {},
    onStartNavigation: (destination: String) -> Unit,
) {
    var destination by remember { mutableStateOf("") }
    var suggestions by remember { mutableStateOf<List<AutocompletePrediction>>(emptyList()) }

    val context  = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current

    // ── Debounced autocomplete — UNCHANGED ───────────────────────────────────
    LaunchedEffect(destination) {
        if (destination.isBlank()) {
            suggestions = emptyList()
            return@LaunchedEffect
        }
        delay(300)
        suggestions = PlacesAutocompleteClient.getSuggestions(context, destination)
    }

    // ── Suggestion selection handler — UNCHANGED ─────────────────────────────
    fun onSuggestionSelected(prediction: AutocompletePrediction) {
        val displayText = prediction.getPrimaryText(null).toString()
        destination  = displayText
        suggestions  = emptyList()
        keyboard?.hide()
        
        viewModel.resolveCoordinates(
            context      = context,
            placeId      = prediction.placeId,
            fallbackName = displayText,
            onResult     = { resolved -> onStartNavigation(resolved) }
        )
    }

    // ── Mic pulse animation ───────────────────────────────────────────────────
    val infiniteTransition = rememberInfiniteTransition(label = "mic_pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue  = 1.0f,
        animationSpec = infiniteRepeatable(
            animation  = tween(durationMillis = 1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse_alpha",
    )
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue  = 1.20f,
        animationSpec = infiniteRepeatable(
            animation  = tween(durationMillis = 1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse_scale",
    )

    val isActionEnabled = destination.isNotBlank() && engineReady

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NearBlack)
            .safeDrawingPadding()
            .imePadding(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {

            // ═══════════════════════════════════════════════════════════════════
            // 1.  HERO IMAGE  ─  brand row and speech bubble overlaid on car art
            // ═══════════════════════════════════════════════════════════════════
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp)
                    .paint(
                        painter      = painterResource(id = R.drawable.hero_kentas_car),
                        contentScale = ContentScale.Crop,
                    ),
            ) {
                // Gradient scrim: darkens top (brand legibility) and fades
                // seamlessly into NearBlack at the bottom.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0.00f to Color(0xBB000000),
                                0.35f to Color(0x44000000),
                                1.00f to NearBlack,
                            )
                        ),
                )

                // ── Brand row (top-start) ─────────────────────────────────────
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 16.dp, top = 14.dp),
                    verticalAlignment    = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .background(NeonGreen.copy(alpha = 0.12f), CircleShape)
                            .border(1.dp, NeonGreen.copy(alpha = 0.65f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Navigation,
                            contentDescription = null,
                            tint = NeonGreen,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Column {
                        Text(
                            text       = "ŠTURMANAS",
                            color      = Color.White,
                            fontSize   = 12.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 2.sp,
                        )
                        Text(
                            text       = "BAJERISTAS",
                            color      = NeonGreen,
                            fontSize   = 12.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 2.sp,
                        )
                    }
                }

                // ── Speech bubble (bottom-start, near the car) ────────────────
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 16.dp, bottom = 22.dp),
                    shape = RoundedCornerShape(
                        topStart    = 2.dp,
                        topEnd      = 12.dp,
                        bottomEnd   = 12.dp,
                        bottomStart = 12.dp,
                    ),
                    color = Color(0xCC0D2520),
                    tonalElevation = 0.dp,
                ) {
                    Text(
                        text     = "Aš tavo šturmanas.\nTu vairuoji.",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        color    = OnSurfaceVariantLight,
                        style    = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            // ═══════════════════════════════════════════════════════════════════
            // 2.  MAIN TITLE  ─  KUR / VAROM?
            // ═══════════════════════════════════════════════════════════════════
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text       = "KUR",
                    color      = Color.White,
                    fontSize   = 62.sp,
                    fontWeight = FontWeight.Black,
                    lineHeight = 62.sp,
                    textAlign  = TextAlign.Center,
                    modifier   = Modifier.fillMaxWidth(),
                )
                Text(
                    text       = "VAROM?",
                    color      = NeonGreen,
                    fontSize   = 62.sp,
                    fontWeight = FontWeight.Black,
                    lineHeight = 62.sp,
                    textAlign  = TextAlign.Center,
                    modifier   = Modifier.fillMaxWidth(),
                    // Subtle neon glow via text shadow
                    style = TextStyle(
                        shadow = Shadow(
                            color      = NeonGreen.copy(alpha = 0.65f),
                            offset     = Offset.Zero,
                            blurRadius = 20f,
                        ),
                    ),
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ═══════════════════════════════════════════════════════════════════
            // 3.  ADDRESS INPUT  +  BANNERS  +  ACTION BUTTON
            // ═══════════════════════════════════════════════════════════════════
            Column(
                modifier = Modifier.padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Input field — neon border drawn around the whole search block
                // (includes suggestions when open, which looks intentional).
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            width  = 1.5.dp,
                            color  = NeonGreen.copy(alpha = 0.45f),
                            shape  = RoundedCornerShape(16.dp),
                        ),
                ) {
                    DestinationSearchField(
                        query                = destination,
                        onQueryChange        = { destination = it },
                        suggestions          = suggestions,
                        onSuggestionSelected = ::onSuggestionSelected,
                        onClear              = { destination = ""; suggestions = emptyList() },
                        placeholder          = "Įvesk adresą arba vietą",
                        onDone               = {
                            keyboard?.hide()
                            if (destination.isNotBlank() && engineReady) {
                                suggestions = emptyList()
                                onStartNavigation(destination.trim())
                            }
                        },
                        surfaceColor  = Color(0xEE0D2420),
                        contentColor  = Color.White,
                        accentColor   = NeonGreen,
                        hintColor     = OnSurfaceVariantLight,
                        dividerColor  = Color(0xFF1E4540),
                    )
                }

                // Error banner
                errorMessage?.let { error ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color    = Color(0xEE4D1A1A),
                        shape    = RoundedCornerShape(12.dp),
                    ) {
                        Text(
                            text     = error,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                            style    = MaterialTheme.typography.bodySmall,
                            color    = Color(0xFFFFB3B3),
                        )
                    }
                }

                // Engine initialising banner
                if (!engineReady) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color    = Color(0xEE1A3530),
                        shape    = RoundedCornerShape(12.dp),
                    ) {
                        Text(
                            text     = "Navigacija inicializuojama…",
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                            style    = MaterialTheme.typography.bodySmall,
                            color    = OnSurfaceVariantLight,
                        )
                    }
                }

                // ── VAŽIUOJAM — gradient action button ────────────────────────
                // Uses a Box+clickable instead of Material Button so the gradient
                // background renders without fighting ButtonDefaults colour system.
                // Disabled state falls back to the muted SurfaceVariantPetrol swatch.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(60.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(
                            brush = if (isActionEnabled)
                                Brush.horizontalGradient(listOf(NeonGreen, NeonCyan))
                            else
                                Brush.horizontalGradient(
                                    listOf(SurfaceVariantPetrol, SurfaceVariantPetrol)
                                ),
                        )
                        .clickable(enabled = isActionEnabled) {
                            keyboard?.hide()
                            suggestions = emptyList()
                            onStartNavigation(destination.trim())
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment     = Alignment.CenterVertically,
                    ) {
                        Text(
                            text      = "VAŽIUOJAM",
                            fontSize  = 17.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 2.sp,
                            color = if (isActionEnabled) NearBlack else OnSurfaceVariantLight,
                        )
                        Text(
                            text  = "→",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isActionEnabled) NearBlack else OnSurfaceVariantLight,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            // ═══════════════════════════════════════════════════════════════════
            // 4.  KENTAS LISTENING INDICATOR  ─  pulsing mic at the bottom
            // ═══════════════════════════════════════════════════════════════════
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 28.dp),
                horizontalAlignment  = Alignment.CenterHorizontally,
                verticalArrangement  = Arrangement.spacedBy(8.dp),
            ) {
                // Mic button — pulsing outer ring + tappable inner circle
                Box(contentAlignment = Alignment.Center) {
                    // Outer glow ring — only rendered while STT is active
                    if (isDestinationListening) {
                        Box(
                            modifier = Modifier
                                .scale(pulseScale)
                                .size(68.dp)
                                .background(
                                    NeonGreen.copy(alpha = 0.09f * pulseAlpha),
                                    CircleShape,
                                ),
                        )
                    }
                    // Inner mic button — tappable; border animates only while listening
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .background(Color(0xFF0F2820), CircleShape)
                            .border(
                                width  = 1.5.dp,
                                color  = if (isDestinationListening)
                                    NeonGreen.copy(alpha = pulseAlpha)
                                else
                                    NeonGreen.copy(alpha = 0.40f),
                                shape  = CircleShape,
                            )
                            .clickable { onMicClick { text -> destination = text } },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector        = Icons.Default.Mic,
                            contentDescription = if (isDestinationListening)
                                "Sustabdyti klausymą" else "Pradėti klausymą",
                            tint               = NeonGreen,
                            modifier           = Modifier.size(22.dp),
                        )
                    }
                }

                // Status label — idle hint OR active listening label
                Text(
                    text      = if (isDestinationListening)
                        "KENTAS KLAUSOSI..."
                    else
                        "Paspausk mikrofoną ir pasakyk, kur važiuojam",
                    color     = if (isDestinationListening)
                        NeonGreen.copy(alpha = pulseAlpha)
                    else
                        OnSurfaceVariantLight,
                    fontSize  = if (isDestinationListening) 11.sp
                                else MaterialTheme.typography.bodyMedium.fontSize,
                    fontWeight = if (isDestinationListening) FontWeight.SemiBold
                                 else FontWeight.Normal,
                    letterSpacing = if (isDestinationListening) 1.5.sp else 0.sp,
                    textAlign = TextAlign.Center,
                    modifier  = Modifier.padding(horizontal = 32.dp),
                )
            }
        }
    }
}
