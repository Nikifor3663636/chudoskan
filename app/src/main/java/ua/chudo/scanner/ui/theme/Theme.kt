package ua.chudo.scanner.ui.theme

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

// Яркий оранжевый «магазина игрушек», белые карточки, графитовый текст.
private val Orange = Color(0xFFFF7500)
private val OrangeDark = Color(0xFFFF8A26)
private val Graphite = Color(0xFF2C2E32)
private val Sun = Color(0xFFFFD43B)

val InStockGreen = Color(0xFF22A846)
val OutOfStockGrey = Color(0xFF9AA0A8)

private val Light = lightColorScheme(
    primary = Orange,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFF0E3),
    onPrimaryContainer = Color(0xFF8A3A00),
    secondary = Color(0xFFE5352B),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFE4DF),
    onSecondaryContainer = Color(0xFF5C0F08),
    tertiary = Color(0xFF6B5300),
    tertiaryContainer = Sun,
    onTertiaryContainer = Color(0xFF3A2A00),
    background = Color.White,
    surface = Color.White,
    onBackground = Graphite,
    onSurface = Graphite,
    onSurfaceVariant = Color(0xFF6B7078),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF7F8FA),
    surfaceContainer = Color(0xFFF4F5F7),
    surfaceContainerHigh = Color(0xFFECEEF1),
    outlineVariant = Color(0xFFE6E8EC),
)

private val Dark = darkColorScheme(
    primary = OrangeDark,
    onPrimary = Color(0xFF1A0D00),
    primaryContainer = Color(0xFF3A2310),
    onPrimaryContainer = Color(0xFFFFC08A),
    secondary = Color(0xFFFF6B5E),
    onSecondary = Color(0xFF3A0500),
    secondaryContainer = Color(0xFF5C1A12),
    tertiary = Sun,
    tertiaryContainer = Color(0xFF5E4800),
    onTertiaryContainer = Color(0xFFFFE6A0),
    background = Color(0xFF121316),
    surface = Color(0xFF121316),
    onBackground = Color(0xFFF1F2F4),
    onSurface = Color(0xFFF1F2F4),
    onSurfaceVariant = Color(0xFFA3A8B0),
    surfaceContainerLowest = Color(0xFF0E0F11),
    surfaceContainerLow = Color(0xFF1C1D21),
    surfaceContainer = Color(0xFF202226),
    surfaceContainerHigh = Color(0xFF25272C),
)

private val AppTypography = Typography().run {
    copy(
        headlineMedium = headlineMedium.copy(fontWeight = FontWeight.Black, letterSpacing = (-0.5).sp),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.Bold),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

val PriceStyle = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Black, letterSpacing = (-0.5).sp)

@Composable
fun ChudoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        typography = AppTypography,
        shapes = Shapes(
            small = RoundedCornerShape(6.dp),
            medium = RoundedCornerShape(12.dp),
            large = RoundedCornerShape(16.dp),
        ),
        content = content,
    )
}
