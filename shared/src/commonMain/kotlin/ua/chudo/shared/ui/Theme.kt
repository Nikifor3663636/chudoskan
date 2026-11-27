package ua.chudo.shared.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.Font
import ua.chudo.shared.data.ThemePreference
import ua.chudo.shared.res.Res
import ua.chudo.shared.res.ibm_plex_mono_500
import ua.chudo.shared.res.ibm_plex_sans_400
import ua.chudo.shared.res.ibm_plex_sans_500
import ua.chudo.shared.res.ibm_plex_sans_600
import ua.chudo.shared.res.ibm_plex_sans_700

// Кольори дизайн-системи chudoskan-redesign.html.
private val Orange = Color(0xFFFF7500)
private val OrangeDark = Color(0xFFFF7A0A)
private val Graphite = Color(0xFF15171C)
private val LightInfo = Color(0xFF1F5BC2)
private val DarkInfo = Color(0xFF7BA7F5)

val InStockGreen = Color(0xFF1E7F47)
val OutOfStockGrey = Color(0xFF9AA0A8)

private val Light = lightColorScheme(
    primary = Orange, onPrimary = Color(0xFF1C0D00),
    primaryContainer = Color(0xFFFFF0E3), onPrimaryContainer = Color(0xFFA84A00),
    secondary = Color(0xFF545B67), onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFF0E3), onSecondaryContainer = Color(0xFFA84A00),
    tertiary = LightInfo, onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE7EFFC), onTertiaryContainer = LightInfo,
    inversePrimary = OrangeDark, inverseSurface = Color(0xFFECEEF1), inverseOnSurface = Color(0xFF171A1E),
    surfaceTint = Orange, surfaceVariant = Color(0xFFF7F8F9),
    surfaceDim = Color(0xFFF3F4F6), surfaceBright = Color.White,
    background = Color(0xFFF3F4F6), surface = Color.White, onBackground = Graphite, onSurface = Graphite,
    onSurfaceVariant = Color(0xFF545B67),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color.White,
    surfaceContainer = Color(0xFFF7F8F9), surfaceContainerHigh = Color.White,
    surfaceContainerHighest = Color.White, outline = Color(0xFFC9CDD4), outlineVariant = Color(0xFFE1E3E8),
    error = Color(0xFFB42620), onError = Color(0xFFFCEAE8),
    errorContainer = Color(0xFFFCEAE8), onErrorContainer = Color(0xFFB42620),
    scrim = Color(0xFF0C0E12).copy(alpha = 0.48f),
)

private val Dark = darkColorScheme(
    primary = OrangeDark, onPrimary = Color(0xFF1C0D00),
    primaryContainer = Color(0xFF2A1A0C), onPrimaryContainer = Color(0xFFFF9A47),
    secondary = Color(0xFFA3AAB5), onSecondary = Color(0xFF171A1E),
    secondaryContainer = Color(0xFF2A1A0C), onSecondaryContainer = Color(0xFFFF9A47),
    tertiary = DarkInfo, onTertiary = Color(0xFF171A1E),
    tertiaryContainer = Color(0xFF152238), onTertiaryContainer = DarkInfo,
    inversePrimary = Orange, inverseSurface = Graphite, inverseOnSurface = Color.White,
    surfaceTint = OrangeDark, surfaceVariant = Color(0xFF1C2025),
    surfaceDim = Color(0xFF0F1114), surfaceBright = Color(0xFF22262C),
    background = Color(0xFF0F1114), surface = Color(0xFF171A1E),
    onBackground = Color(0xFFECEEF1), onSurface = Color(0xFFECEEF1), onSurfaceVariant = Color(0xFFA3AAB5),
    surfaceContainerLowest = Color(0xFF0F1114), surfaceContainerLow = Color(0xFF171A1E),
    surfaceContainer = Color(0xFF1C2025), surfaceContainerHigh = Color(0xFF22262C),
    surfaceContainerHighest = Color(0xFF22262C), outline = Color(0xFF3A4049), outlineVariant = Color(0xFF2A2F36),
    error = Color(0xFFFF7A70), onError = Color(0xFF2E1715),
    errorContainer = Color(0xFF2E1715), onErrorContainer = Color(0xFFFF7A70),
    scrim = Color.Black.copy(alpha = 0.60f),
)


private val PlexSans: FontFamily
    @Composable get() = FontFamily(
        Font(Res.font.ibm_plex_sans_400, FontWeight.Normal),
        Font(Res.font.ibm_plex_sans_500, FontWeight.Medium),
        Font(Res.font.ibm_plex_sans_600, FontWeight.SemiBold),
        Font(Res.font.ibm_plex_sans_700, FontWeight.Bold),
    )

val CodeStyle: TextStyle
    @Composable get() = TextStyle(
        fontFamily = FontFamily(Font(Res.font.ibm_plex_mono_500, FontWeight.Medium)),
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
    )


@Composable
private fun appTypography(): Typography {
    val plexSans = PlexSans
    return Typography(
        displayLarge = TextStyle(fontFamily = plexSans, fontFeatureSettings = "tnum", fontSize = 30.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
        displayMedium = TextStyle(fontFamily = plexSans, fontFeatureSettings = "tnum", fontSize = 30.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
        displaySmall = TextStyle(fontFamily = plexSans, fontFeatureSettings = "tnum", fontSize = 30.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
        headlineLarge = TextStyle(fontFamily = plexSans, fontFeatureSettings = "tnum", fontSize = 26.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.39).sp),
        headlineMedium = TextStyle(fontFamily = plexSans, fontFeatureSettings = "tnum", fontSize = 26.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.39).sp),
        headlineSmall = TextStyle(fontFamily = plexSans, fontFeatureSettings = "tnum", fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
        titleLarge = TextStyle(fontFamily = plexSans, fontFeatureSettings = "tnum", fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
        titleMedium = TextStyle(fontFamily = plexSans, fontFeatureSettings = "tnum", fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
        titleSmall = TextStyle(fontFamily = plexSans, fontFeatureSettings = "tnum", fontSize = 13.sp, fontWeight = FontWeight.Medium),
        bodyLarge = TextStyle(fontFamily = plexSans, fontFeatureSettings = "tnum", fontSize = 16.sp, lineHeight = 24.sp),
        bodyMedium = TextStyle(fontFamily = plexSans, fontFeatureSettings = "tnum", fontSize = 15.sp, lineHeight = 22.5.sp),
        bodySmall = TextStyle(fontFamily = plexSans, fontFeatureSettings = "tnum", fontSize = 13.sp, lineHeight = 19.5.sp),
        labelLarge = TextStyle(fontFamily = plexSans, fontFeatureSettings = "tnum", fontSize = 15.sp, fontWeight = FontWeight.Medium),
        labelMedium = TextStyle(fontFamily = plexSans, fontFeatureSettings = "tnum", fontSize = 13.sp, fontWeight = FontWeight.Medium),
        labelSmall = TextStyle(fontFamily = plexSans, fontFeatureSettings = "tnum", fontSize = 13.sp),
    )
}

val PriceStyle: TextStyle
    @Composable get() = TextStyle(
        fontFamily = PlexSans,
        fontSize = 30.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.3).sp,
        fontFeatureSettings = "tnum",
    )

@Composable
fun ChudoTheme(preference: ThemePreference = ThemePreference.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (preference) {
        ThemePreference.SYSTEM -> isSystemInDarkTheme()
        ThemePreference.LIGHT -> false
        ThemePreference.DARK -> true
    }
    val scheme = if (dark) Dark else Light
    val info = if (dark) DarkInfo else LightInfo
    MaterialTheme(
        colorScheme = scheme,
        typography = appTypography(),
        shapes = Shapes(
            extraSmall = RoundedCornerShape(4.dp),
            small = RoundedCornerShape(6.dp),
            medium = RoundedCornerShape(8.dp),
            large = RoundedCornerShape(12.dp),
            extraLarge = RoundedCornerShape(12.dp),
        ),
    ) {
        CompositionLocalProvider(
            LocalContentColor provides scheme.onSurface,
            LocalTextSelectionColors provides TextSelectionColors(info, info.copy(alpha = 0.25f)),
            content = content,
        )
    }
}
