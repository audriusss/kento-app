package lt.sturmanas.bajeristas.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ── Petrol-green dark palette ─────────────────────────────────────────────────
// All colour decisions documented here so they are not scattered across screens.
//
//  Background   – deep petrol/teal  (#0B2520) — the main canvas
//  Surface      – dark card surface (#163833) — rounded cards, input fields
//  Primary      – bright mint-teal  (#34C98A) — CTAs, active accent
//  OnPrimary    – near-black        (#001412) — text/icon on CTA buttons
//  Secondary    – mid teal          (#1A8C5F) — secondary accents
//  OnBackground – near-white        (#E8F5F0)
//  OnSurface    – near-white        (#DFF2EC)
//  Error        – warm red          (#FF6B6B)
// ─────────────────────────────────────────────────────────────────────────────

// ── Neon accents (StartScreen dark theme) ────────────────────────────────────
val NearBlack             = Color(0xFF060E0C)   // near-black StartScreen background
val NeonGreen             = Color(0xFF00FF88)   // primary neon green
val NeonCyan              = Color(0xFF00E5FF)   // secondary neon cyan

val BackgroundPetrol      = Color(0xFF0B2520)
val SurfacePetrol         = Color(0xFF163833)
val SurfaceVariantPetrol  = Color(0xFF1E4540)
val PrimaryMint           = Color(0xFF34C98A)
val OnPrimaryDark         = Color(0xFF001412)
val SecondaryTeal         = Color(0xFF1A8C5F)
val OnSecondaryWhite      = Color.White
val OnBackgroundLight     = Color(0xFFE8F5F0)
val OnSurfaceLight        = Color(0xFFDFF2EC)
val OnSurfaceVariantLight = Color(0xFFB0CFCA)
val ErrorWarm             = Color(0xFFFF6B6B)
val OnErrorDark           = Color(0xFF2D0000)

private val DarkPetrolColors = darkColorScheme(
    primary             = PrimaryMint,
    onPrimary           = OnPrimaryDark,
    primaryContainer    = Color(0xFF1A5043),
    onPrimaryContainer  = Color(0xFF9EECD4),
    secondary           = SecondaryTeal,
    onSecondary         = OnSecondaryWhite,
    secondaryContainer  = Color(0xFF1A3D39),
    onSecondaryContainer = Color(0xFFB2DFDB),
    tertiary            = Color(0xFF5AA888),
    onTertiary          = Color(0xFF003826),
    tertiaryContainer   = Color(0xFF1E4A42),
    onTertiaryContainer = Color(0xFFA8D5C4),
    background          = BackgroundPetrol,
    onBackground        = OnBackgroundLight,
    surface             = SurfacePetrol,
    onSurface           = OnSurfaceLight,
    surfaceVariant      = SurfaceVariantPetrol,
    onSurfaceVariant    = OnSurfaceVariantLight,
    outline             = Color(0xFF3A6B64),
    error               = ErrorWarm,
    onError             = OnErrorDark,
    errorContainer      = Color(0xFF4D1A1A),
    onErrorContainer    = Color(0xFFFFB3B3),
)

@Composable
fun SturmanasTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkPetrolColors,
        content = content,
    )
}
