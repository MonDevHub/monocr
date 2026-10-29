package dev.janakhpon.monocr.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.core.os.ConfigurationCompat
import androidx.compose.ui.unit.dp

// ─── MonOCR Palette (mirrors monocr-web CSS vars) ──────────────────────────
// Every Material 3 role is set in both schemes. A role left out falls back to the
// baseline lavender palette, which is what chips, dialogs, the status chip and the
// drawer selection were showing. ThemeTest fails if a role is left at its default.
//
// Contrast: outline is the unfocused text-field border, so it holds 3:1 against the
// surfaces (WCAG 1.4.11); outlineVariant is for decorative hairlines only.
// onSurfaceVariant holds 4.5:1 for secondary text (1.4.3), so text uses it as is,
// never at reduced alpha.

// Dark scheme  — matches: --bg-canvas:#09090b, --fg-primary:#e4e4e7
internal val MonDarkScheme = darkColorScheme(
    background              = Color(0xFF151517),  // Softened black (Zinc 950-ish)
    onBackground            = Color(0xFFE4E4E7),  // Zinc 200
    surface                 = Color(0xFF18181B),  // Zinc 900
    onSurface               = Color(0xFFE4E4E7),
    surfaceVariant          = Color(0xFF27272A),  // Zinc 800
    onSurfaceVariant        = Color(0xFFD4D4D8),  // Zinc 300
    surfaceTint             = Color(0xFFF4F4F5),
    surfaceDim              = Color(0xFF111113),
    surfaceBright           = Color(0xFF3F3F46),  // Zinc 700
    surfaceContainerLowest  = Color(0xFF111113),
    surfaceContainerLow     = Color(0xFF1C1C1F),
    surfaceContainer        = Color(0xFF202023),
    surfaceContainerHigh    = Color(0xFF27272A),  // Zinc 800
    surfaceContainerHighest = Color(0xFF2F2F33),
    inverseSurface          = Color(0xFFE4E4E7),
    inverseOnSurface        = Color(0xFF18181B),
    primary                 = Color(0xFFF4F4F5),  // Zinc 100
    onPrimary               = Color(0xFF09090B),
    primaryContainer        = Color(0xFF3F3F46),  // Zinc 700
    onPrimaryContainer      = Color(0xFFF4F4F5),
    inversePrimary          = Color(0xFF18181B),
    secondary               = Color(0xFF52525B),  // Zinc 600
    onSecondary             = Color(0xFFE4E4E7),
    secondaryContainer      = Color(0xFF3F3F46),  // Zinc 700 — selected chip
    onSecondaryContainer    = Color(0xFFF4F4F5),
    tertiary                = Color(0xFF34D399),  // Emerald 400
    onTertiary              = Color(0xFF09090B),
    tertiaryContainer       = Color(0xFF064E3B),  // Emerald 900 — dark bg for ready chip
    onTertiaryContainer     = Color(0xFFA7F3D0),  // Emerald 200
    error                   = Color(0xFFF87171),  // Red 400
    onError                 = Color(0xFF09090B),
    errorContainer          = Color(0xFF7F1D1D),  // Red 900 — dark bg for error chip
    onErrorContainer        = Color(0xFFFECACA),  // Red 200
    outline                 = Color(0xFF71717A),  // Zinc 500 — 3.7:1 on surface
    outlineVariant          = Color(0xFF27272A),  // Zinc 800 — hairlines
    scrim                   = Color(0xFF09090B),
)

// Light scheme — matches: --bg-canvas:#f8f9fa, --fg-primary:#18181b
internal val MonLightScheme = lightColorScheme(
    background              = Color(0xFFFBFBFB),  // Soft off-white (paper-like)
    onBackground            = Color(0xFF18181B),  // Zinc 900
    surface                 = Color(0xFFFAFAFA),  // Zinc 50
    onSurface               = Color(0xFF18181B),
    surfaceVariant          = Color(0xFFF4F4F5),  // Zinc 100
    onSurfaceVariant        = Color(0xFF52525B),  // Zinc 600
    surfaceTint             = Color(0xFF18181B),
    surfaceDim              = Color(0xFFE4E4E7),  // Zinc 200
    surfaceBright           = Color(0xFFFFFFFF),
    surfaceContainerLowest  = Color(0xFFFFFFFF),
    surfaceContainerLow     = Color(0xFFF7F7F8),
    surfaceContainer        = Color(0xFFF4F4F5),  // Zinc 100
    surfaceContainerHigh    = Color(0xFFEFEFF1),
    surfaceContainerHighest = Color(0xFFE4E4E7),  // Zinc 200
    inverseSurface          = Color(0xFF27272A),
    inverseOnSurface        = Color(0xFFF4F4F5),
    primary                 = Color(0xFF18181B),
    onPrimary               = Color(0xFFFFFFFF),
    primaryContainer        = Color(0xFFE4E4E7),  // Zinc 200
    onPrimaryContainer      = Color(0xFF18181B),
    inversePrimary          = Color(0xFFE4E4E7),
    secondary               = Color(0xFF71717A),  // Zinc 500
    onSecondary             = Color(0xFFFFFFFF),
    secondaryContainer      = Color(0xFFE4E4E7),  // Zinc 200 — selected chip
    onSecondaryContainer    = Color(0xFF18181B),
    tertiary                = Color(0xFF059669),  // Emerald 600
    onTertiary              = Color(0xFFFFFFFF),
    tertiaryContainer       = Color(0xFFD1FAE5),  // Emerald 100 — light bg for ready chip
    onTertiaryContainer     = Color(0xFF064E3B),  // Emerald 900
    error                   = Color(0xFFDC2626),  // Red 600
    onError                 = Color(0xFFFFFFFF),
    errorContainer          = Color(0xFFFEE2E2),  // Red 100 — light bg for error chip
    onErrorContainer        = Color(0xFF7F1D1D),  // Red 900
    outline                 = Color(0xFF85858E),  // 3.5:1 on surface
    outlineVariant          = Color(0xFFE4E4E7),  // Zinc 200 — hairlines
    scrim                   = Color(0xFF09090B),
)

@Composable
fun MonOCRTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,          // disabled — use curated Mon palette
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        // Dynamic colour needs Android 12 (API 31); the app supports API 24 and up.
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        darkTheme -> MonDarkScheme
        else      -> MonLightScheme
    }

    val language = ConfigurationCompat.getLocales(LocalConfiguration.current)[0]?.language
    val typography = remember(language) {
        if (usesMyanmarScript(language)) MonTypography.forMyanmarScript() else MonTypography
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = typography,
        shapes = androidx.compose.material3.Shapes(
            small = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
            medium = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            large = androidx.compose.foundation.shape.RoundedCornerShape(24.dp)
        ),
        content = content
    )
}
