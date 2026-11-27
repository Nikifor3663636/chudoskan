package ua.chudo.scanner.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import ua.chudo.scanner.ChudoApp

/** Тема оформлення в профілі. */
enum class ThemeMode(val title: String) { DARK("Темна"), LIGHT("Світла"), SYSTEM("Як у системі") }

/** Токены стиля «Mine» (design/DESIGN.md → «Токени»). */
@Immutable
data class MineColors(
    val dark: Boolean,
    /** Фон экрана. */
    val bg: Color,
    /** Поверхность второго уровня: строки, раскрывашки, поле ввода. */
    val surface: Color,
    /** Карточка. */
    val card: Color,
    /** Разделители и рамки карточек. */
    val line: Color,
    /** Рамка контурных кнопок и чипов. */
    val outline: Color,
    val text: Color,
    val textSecondary: Color,
    val accent: Color,
    /** Текст на акценте. */
    val onAccent: Color,
    /** Тон акцента: подложка иконок, выделения. */
    val accentTone: Color,
    /** Кнопка-ошибка и выбранный чип. */
    val danger: Color,
    val onDanger: Color,
    val success: Color,
    val warning: Color,
    val warningText: Color,
    /** Подложка «+380» в поле телефона. */
    val field: Color,
    /** Нижний лист. */
    val sheet: Color,
    val link: Color,
    /** «Вийти». */
    val logout: Color,
)

val MineDark = MineColors(
    dark = true,
    bg = Color(0xFF0F1114), surface = Color(0xFF1C2025), card = Color(0xFF171A1E), line = Color(0xFF2A2F36),
    outline = Color(0xFF3A4049), text = Color(0xFFECEEF1), textSecondary = Color(0xFFA3AAB5),
    accent = Color(0xFFFF7A0A), onAccent = Color(0xFF1C0D00), accentTone = Color(0xFF2A1A0C),
    danger = Color(0xFF2E1715), onDanger = Color(0xFFFF7A70), success = Color(0xFF4CC27E),
    warning = Color(0xFFF2B33D), warningText = Color(0xFFF2B33D), field = Color(0xFF1C2025),
    sheet = Color(0xFF22262C), link = Color(0xFFFF9A47), logout = Color(0xFFFF7A70),
)

val MineLight = MineColors(
    dark = false,
    bg = Color(0xFFF3F4F6), surface = Color(0xFFFFFFFF), card = Color(0xFFFFFFFF), line = Color(0xFFE1E3E8),
    outline = Color(0xFFC9CDD4), text = Color(0xFF15171C), textSecondary = Color(0xFF545B67),
    accent = Color(0xFFFF7500), onAccent = Color(0xFF1C0D00), accentTone = Color(0xFFFFF0E3),
    danger = Color(0xFFFCEAE8), onDanger = Color(0xFFB42620), success = Color(0xFF1E7F47),
    warning = Color(0xFFF2B33D), warningText = Color(0xFF8F5600), field = Color(0xFFF7F8F9),
    sheet = Color(0xFFFFFFFF), link = Color(0xFFA84A00), logout = Color(0xFFB42620),
)

val LocalMine = staticCompositionLocalOf { MineDark }

/** Токены текущей темы: `Mine.colors.accent`. */
object Mine {
    val fieldShape = RoundedCornerShape(8.dp)
    val cardShape = RoundedCornerShape(12.dp)
    val pillShape = RoundedCornerShape(50)
    val colors: MineColors @Composable get() = LocalMine.current
}

val InStockGreen = Color(0xFF1E7F47)
val OutOfStockGrey = Color(0xFF9AA0A8)
private val Sun = Color(0xFFF4F27A)

/** Material-схема из токенов: старые экраны берут те же цвета. */
private fun scheme(m: MineColors) = if (m.dark) {
    darkColorScheme(
        primary = m.accent, onPrimary = m.onAccent,
        primaryContainer = m.accentTone, onPrimaryContainer = Color(0xFFFFC08A),
        secondary = Color(0xFFFF6B5E), onSecondary = Color(0xFF3A0500),
        secondaryContainer = m.danger, onSecondaryContainer = m.onDanger,
        tertiary = Sun, tertiaryContainer = Color(0xFF5E4800), onTertiaryContainer = Color(0xFFFFE6A0),
        background = m.bg, surface = m.bg, onBackground = m.text, onSurface = m.text, onSurfaceVariant = m.textSecondary,
        surfaceContainerLowest = Color(0xFF0E0F11), surfaceContainerLow = m.surface, surfaceContainer = m.surface,
        surfaceContainerHigh = m.card, surfaceContainerHighest = m.card,
        outline = m.outline, outlineVariant = m.line,
    )
} else {
    lightColorScheme(
        primary = m.accent, onPrimary = m.onAccent,
        primaryContainer = m.accentTone, onPrimaryContainer = Color(0xFF8A3A00),
        secondary = Color(0xFFE5352B), onSecondary = Color.White,
        secondaryContainer = m.danger, onSecondaryContainer = m.onDanger,
        tertiary = Color(0xFF6B5300), tertiaryContainer = Sun, onTertiaryContainer = Color(0xFF3A2A00),
        background = m.bg, surface = m.bg, onBackground = m.text, onSurface = m.text, onSurfaceVariant = m.textSecondary,
        surfaceContainerLowest = Color.White, surfaceContainerLow = m.surface, surfaceContainer = m.surface,
        surfaceContainerHigh = Color(0xFFECEEF1), surfaceContainerHighest = Color(0xFFE6E8EC),
        outline = m.outline, outlineVariant = m.line,
    )
}

private val AppTypography = Typography().run {
    copy(
        headlineMedium = headlineMedium.copy(fontSize = 26.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

val PriceStyle = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp, fontFeatureSettings = "tnum")

private val NoPrefs = MutableStateFlow(ThemeMode.SYSTEM)

/** Текущий режим темы из настроек приложения (в превью — «как в системе»). */
@Composable
fun themeMode(): ThemeMode {
    val prefs = (LocalContext.current.applicationContext as? ChudoApp)?.prefs
    val mode by (prefs?.themeMode ?: NoPrefs).collectAsStateWithLifecycle()
    return mode
}

@Composable
fun isDarkTheme(mode: ThemeMode = themeMode()): Boolean = when (mode) {
    ThemeMode.DARK -> true
    ThemeMode.LIGHT -> false
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
}

/** [dark] = null — по настройке «Тема оформлення» в профиле. */
@Composable
fun ChudoTheme(dark: Boolean? = null, content: @Composable () -> Unit) {
    val m = if (dark ?: isDarkTheme()) MineDark else MineLight
    CompositionLocalProvider(LocalMine provides m) {
        MaterialTheme(
            colorScheme = scheme(m),
            typography = AppTypography,
            shapes = Shapes(
                small = RoundedCornerShape(12.dp),
                medium = Mine.fieldShape,
                large = Mine.cardShape,
                extraLarge = RoundedCornerShape(28.dp),
            ),
            content = content,
        )
    }
}
