package dev.janakhpon.monocr.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.janakhpon.monocr.R

// Mon language font — PyidaungSu supports Mon/Myanmar script
val PyidaungSuFamily = FontFamily(
    Font(R.font.pyidaungsu_regular, FontWeight.Normal),
    Font(R.font.pyidaungsu_bold, FontWeight.Bold),
)

// Display font for UI chrome — system default sans
val MonTypography = Typography(
    // Constitution: Title (20), Section (15), Body (14), Secondary (12), Meta (11)
    headlineMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.5).sp
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.sp
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp, 
        lineHeight = 22.sp,
        letterSpacing = 0.sp
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp, 
        lineHeight = 20.sp,
        letterSpacing = 0.sp
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    ),
    labelSmall = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.sp
    )
)

/** Apply PyidaungSu for Mon script rendering */
val monScriptStyle = TextStyle(
    fontFamily = PyidaungSuFamily,
    fontWeight = FontWeight.Normal,
    fontSize = 16.sp,
    lineHeight = 28.sp,
    letterSpacing = 0.sp
)

/**
 * Myanmar script stacks marks above and below the line, so it needs about 1.6-1.8x the
 * font size where Latin text is set at 1.3-1.4x. The chrome used the Latin line heights in
 * the system font for Mon and Burmese too, which clips stacked marks.
 */
internal const val MYANMAR_SCRIPT_LINE_HEIGHT = 1.7f

/** Languages whose UI text is in Myanmar script. */
internal fun usesMyanmarScript(language: String?): Boolean = language == "my" || language == "mnw"

private fun TextStyle.forMyanmarScript(): TextStyle =
    if (!fontSize.isSp) copy(fontFamily = PyidaungSuFamily)
    else copy(fontFamily = PyidaungSuFamily, lineHeight = (fontSize.value * MYANMAR_SCRIPT_LINE_HEIGHT).sp)

/** [this] with PyidaungSu and Myanmar-script line heights in every style. */
internal fun Typography.forMyanmarScript(): Typography = Typography(
    displayLarge = displayLarge.forMyanmarScript(),
    displayMedium = displayMedium.forMyanmarScript(),
    displaySmall = displaySmall.forMyanmarScript(),
    headlineLarge = headlineLarge.forMyanmarScript(),
    headlineMedium = headlineMedium.forMyanmarScript(),
    headlineSmall = headlineSmall.forMyanmarScript(),
    titleLarge = titleLarge.forMyanmarScript(),
    titleMedium = titleMedium.forMyanmarScript(),
    titleSmall = titleSmall.forMyanmarScript(),
    bodyLarge = bodyLarge.forMyanmarScript(),
    bodyMedium = bodyMedium.forMyanmarScript(),
    bodySmall = bodySmall.forMyanmarScript(),
    labelLarge = labelLarge.forMyanmarScript(),
    labelMedium = labelMedium.forMyanmarScript(),
    labelSmall = labelSmall.forMyanmarScript()
)
