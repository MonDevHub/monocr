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
    // Constitution: Title (20), Section (14), Body (13), Secondary (12), Meta (11)
    headlineMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp, // Reduced from 16
        lineHeight = 20.sp,
        letterSpacing = 0.sp
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp, // Reduced from 14
        lineHeight = 18.sp,
        letterSpacing = 0.sp
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp, // Reduced from 13
        lineHeight = 16.sp,
        letterSpacing = 0.sp
    ),
    labelSmall = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp, // Reduced from 12
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
