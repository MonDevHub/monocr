package dev.janakhpon.monocr.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

// ─── MonOCR Palette (mirrors monocr-web CSS vars) ──────────────────────────
// Dark scheme  — matches: --bg-canvas:#09090b, --fg-primary:#e4e4e7
private val MonDarkScheme = darkColorScheme(
    background            = Color(0xFF151517),  // Softened black (Zinc 950-ish)
    surface               = Color(0xFF18181B),  // Zinc 900
    surfaceVariant        = Color(0xFF27272A),  // Zinc 800
    onBackground          = Color(0xFFE4E4E7),  // Zinc 200
    onSurface             = Color(0xFFE4E4E7),
    onSurfaceVariant      = Color(0xFFD4D4D8),  // Zinc 300
    primary               = Color(0xFFF4F4F5),  // Zinc 100
    onPrimary             = Color(0xFF09090B),
    secondary             = Color(0xFF52525B),  // Zinc 600
    onSecondary           = Color(0xFFE4E4E7),
    outline               = Color(0xFF27272A),
    error                 = Color(0xFFF87171),  // Red 400
    onError               = Color(0xFF09090B),
    errorContainer        = Color(0xFF7F1D1D),  // Red 900 — dark bg for error chip
    onErrorContainer      = Color(0xFFFECACA),  // Red 200
    tertiary              = Color(0xFF34D399),  // Emerald 400
    onTertiary            = Color(0xFF09090B),
    tertiaryContainer     = Color(0xFF064E3B),  // Emerald 900 — dark bg for ready chip
    onTertiaryContainer   = Color(0xFFA7F3D0),  // Emerald 200
)

// Light scheme — matches: --bg-canvas:#f8f9fa, --fg-primary:#18181b
private val MonLightScheme = lightColorScheme(
    background            = Color(0xFFFBFBFB),  // Soft off-white (paper-like)
    surface               = Color(0xFFFAFAFA),  // Zinc 50
    surfaceVariant        = Color(0xFFF4F4F5),  // Zinc 100
    onBackground          = Color(0xFF18181B),  // Zinc 900
    onSurface             = Color(0xFF18181B),
    onSurfaceVariant      = Color(0xFF52525B),  // Zinc 600
    primary               = Color(0xFF18181B),
    onPrimary             = Color(0xFFFFFFFF),
    secondary             = Color(0xFF71717A),  // Zinc 500
    onSecondary           = Color(0xFFFFFFFF),
    outline               = Color(0xFFE4E4E7),  // Zinc 200
    error                 = Color(0xFFDC2626),  // Red 600
    onError               = Color(0xFFFFFFFF),
    errorContainer        = Color(0xFFFEE2E2),  // Red 100 — light bg for error chip
    onErrorContainer      = Color(0xFF7F1D1D),  // Red 900
    tertiary              = Color(0xFF059669),  // Emerald 600
    onTertiary            = Color(0xFFFFFFFF),
    tertiaryContainer     = Color(0xFFD1FAE5),  // Emerald 100 — light bg for ready chip
    onTertiaryContainer   = Color(0xFF064E3B),  // Emerald 900
)

@Composable
fun MonOCRTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,          // disabled — use curated Mon palette
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor -> {
            val ctx = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        darkTheme -> MonDarkScheme
        else      -> MonLightScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = MonTypography,
        shapes = androidx.compose.material3.Shapes(
            small = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
            medium = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            large = androidx.compose.foundation.shape.RoundedCornerShape(24.dp)
        ),
        content = content
    )
}
