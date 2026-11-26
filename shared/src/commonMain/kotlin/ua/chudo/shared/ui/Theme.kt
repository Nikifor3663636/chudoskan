package ua.chudo.shared.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ua.chudo.shared.data.ThemePreference

// Те же цвета, что в Android-версии: оранжевый «магазина игрушек», белые карточки, графитовый текст.
private val Orange = Color(0xFFFF7500)
private val OrangeDark = Color(0xFFFF7A0A)
private val Graphite = Color(0xFF15171C)
private val Sun = Color(0xFFF4F27A)

val InStockGreen = Color(0xFF1E7F47)
val OutOfStockGrey = Color(0xFF9AA0A8)

private val Light = lightColorScheme(
    primary = Orange, onPrimary = Color(0xFF1C0D00),
    primaryContainer = Color(0xFFFFF0E3), onPrimaryContainer = Color(0xFFA84A00),
    secondary = Color(0xFFE5352B), onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFE4DF), onSecondaryContainer = Color(0xFF5C0F08),
    tertiary = Color(0xFF6B5300), tertiaryContainer = Sun, onTertiaryContainer = Color(0xFF3A2A00),
    background = Color(0xFFF3F4F6), surface = Color.White, onBackground = Graphite, onSurface = Graphite,
    onSurfaceVariant = Color(0xFF545B67),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF7F8F9),
    surfaceContainer = Color(0xFFF7F8F9), surfaceContainerHigh = Color(0xFFF3F4F6),
    outlineVariant = Color(0xFFE1E3E8),
)

private val Dark = darkColorScheme(
    primary = OrangeDark, onPrimary = Color(0xFF1C0D00),
    primaryContainer = Color(0xFF2A1A0C), onPrimaryContainer = Color(0xFFFF9A47),
    secondary = Color(0xFFFF6B5E), onSecondary = Color(0xFF3A0500),
    secondaryContainer = Color(0xFF5C1A12),
    tertiary = Sun, tertiaryContainer = Color(0xFF5E4800), onTertiaryContainer = Color(0xFFFFE6A0),
    background = Color(0xFF0F1114), surface = Color(0xFF171A1E),
    onBackground = Color(0xFFECEEF1), onSurface = Color(0xFFECEEF1), onSurfaceVariant = Color(0xFFA3AAB5),
    surfaceContainerLowest = Color(0xFF0E0F11), surfaceContainerLow = Color(0xFF171A1E),
    surfaceContainer = Color(0xFF1C2025), surfaceContainerHigh = Color(0xFF22262C),
)

private val AppTypography = Typography().run {
    copy(
        headlineMedium = headlineMedium.copy(fontSize = 26.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

val PriceStyle = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp, fontFeatureSettings = "tnum")

@Composable
fun ChudoTheme(preference: ThemePreference = ThemePreference.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (preference) {
        ThemePreference.SYSTEM -> isSystemInDarkTheme()
        ThemePreference.LIGHT -> false
        ThemePreference.DARK -> true
    }
    MaterialTheme(
        colorScheme = if (dark) Dark else Light,
        typography = AppTypography,
        shapes = Shapes(small = RoundedCornerShape(6.dp), medium = RoundedCornerShape(12.dp), large = RoundedCornerShape(16.dp)),
        content = content,
    )
}
