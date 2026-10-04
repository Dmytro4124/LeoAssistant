package com.dmytro.leo.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.dmytro.leo.R

/** House colors: a gold lion on crimson. */
object LionColors {
    val Gold = Color(0xFFD9A93F)
    val GoldLight = Color(0xFFF3D58A)
    val GoldDeep = Color(0xFF8A6417)
    val Crimson = Color(0xFF7A1016)
    val CrimsonBright = Color(0xFFA3242B)
    val CrimsonDark = Color(0xFF3E0709)
    val Night = Color(0xFF140B0B)
    val Parchment = Color(0xFFF6EEDD)
}

/** Heraldic small caps for titles (Cormorant SC, with Cyrillic); body text stays in the system font for legibility. */
val Heraldic = FontFamily(Font(R.font.cormorant_sc_bold, FontWeight.Bold))

private val DarkColors = darkColorScheme(
    primary = LionColors.Gold,
    onPrimary = LionColors.CrimsonDark,
    primaryContainer = LionColors.Crimson,
    onPrimaryContainer = LionColors.GoldLight,
    secondary = LionColors.GoldLight,
    onSecondary = LionColors.CrimsonDark,
    secondaryContainer = Color(0xFF3A2416),
    onSecondaryContainer = LionColors.GoldLight,
    tertiary = Color(0xFFE8B4A0),
    tertiaryContainer = Color(0xFF4A1A14),
    onTertiaryContainer = Color(0xFFFFDBCF),
    background = LionColors.Night,
    onBackground = Color(0xFFF1E6D6),
    surface = LionColors.Night,
    onSurface = Color(0xFFF1E6D6),
    surfaceVariant = Color(0xFF3A2C24),
    onSurfaceVariant = Color(0xFFCDBBA6),
    surfaceContainerLowest = Color(0xFF0E0707),
    surfaceContainerLow = Color(0xFF1C1111),
    surfaceContainer = Color(0xFF221515),
    surfaceContainerHigh = Color(0xFF2C1C1A),
    surfaceContainerHighest = Color(0xFF372421),
    outline = Color(0xFF8A6D4E),
    outlineVariant = Color(0xFF4E3B2E),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val LightColors = lightColorScheme(
    primary = LionColors.Crimson,
    onPrimary = LionColors.GoldLight,
    primaryContainer = LionColors.Crimson,
    onPrimaryContainer = LionColors.GoldLight,
    secondary = LionColors.GoldDeep,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF1DDB0),
    onSecondaryContainer = Color(0xFF3A2A06),
    tertiary = Color(0xFF8C4A3A),
    tertiaryContainer = Color(0xFFFFDBCF),
    onTertiaryContainer = Color(0xFF3A0B02),
    background = LionColors.Parchment,
    onBackground = Color(0xFF231A14),
    surface = LionColors.Parchment,
    onSurface = Color(0xFF231A14),
    surfaceVariant = Color(0xFFEADBC4),
    onSurfaceVariant = Color(0xFF5C4A3A),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFBF4E6),
    surfaceContainer = Color(0xFFF2E7D2),
    surfaceContainerHigh = Color(0xFFEDE0C8),
    surfaceContainerHighest = Color(0xFFE6D7BC),
    outline = Color(0xFFA08463),
    outlineVariant = Color(0xFFD8C4A4),
)

private val LeoTypography = Typography().let { base ->
    fun TextStyle.heraldic() = copy(fontFamily = Heraldic, fontWeight = FontWeight.Bold)
    base.copy(
        displaySmall = base.displaySmall.heraldic(),
        headlineMedium = base.headlineMedium.heraldic(),
        headlineSmall = base.headlineSmall.heraldic(),
        titleLarge = base.titleLarge.heraldic(),
        titleMedium = base.titleMedium.heraldic(),
    )
}

@Composable
fun LeoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = LeoTypography,
        content = content,
    )
}
