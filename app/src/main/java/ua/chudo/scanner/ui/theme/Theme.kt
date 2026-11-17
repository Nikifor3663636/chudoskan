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
    bg = Color(0xFF121317), surface = Color(0xFF1C1D22), card = Color(0xFF26272D), line = Color(0xFF3A3B42),
    outline = Color(0xFF4A4B52), text = Color(0xFFF2EEEA), textSecondary = Color(0xFFB9B4AE),
    accent = Color(0xFFFF8A24), onAccent = Color(0xFF1A0E04), accentTone = Color(0xFF3A1F08),
    danger = Color(0xFF5A1A14), onDanger = Color(0xFFF6D6D0), success = Color(0xFF3DCB6C),
    warning = Color(0xFFFFB020), warningText = Color(0xFFFFC27A), field = Color(0xFF2A2B31),
    sheet = Color(0xFF1E1F24), link = Color(0xFFFF8A24), logout = Color(0xFFFF8A6A),
)

val MineLight = MineColors(
    dark = false,
    bg = Color(0xFFF5F6F8), surface = Color(0xFFFFFFFF), card = Color(0xFFFFFFFF), line = Color(0xFFDDE0E5),
    outline = Color(0xFFCDD1D8), text = Color(0xFF1C1D22), textSecondary = Color(0xFF5F6470),
    accent = Color(0xFFFF7A00), onAccent = Color(0xFF1A0E04), accentTone = Color(0xFFFFE6D5),
    danger = Color(0xFFFBDAD4), onDanger = Color(0xFF7A1E12), success = Color(0xFF1E9E4A),
    warning = Color(0xFFE08A00), warningText = Color(0xFF8A5200), field = Color(0xFFF0F1F4),
    sheet = Color(0xFFFFFFFF), link = Color(0xFFB44A00), logout = Color(0xFFD2452B),
)

val LocalMine = staticCompositionLocalOf { MineDark }

/** Токены текущей темы: `Mine.colors.accent`. */
object Mine {
    val colors: MineColors @Composable get() = LocalMine.current
}

val InStockGreen = Color(0xFF22A846)
val OutOfStockGrey = Color(0xFF9AA0A8)
private val Sun = Color(0xFFFFD43B)

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
        headlineMedium = headlineMedium.copy(fontWeight = FontWeight.Black, letterSpacing = (-0.5).sp),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.Bold),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

val PriceStyle = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Black, letterSpacing = (-0.5).sp)

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
                small = RoundedCornerShape(8.dp),
                medium = RoundedCornerShape(14.dp),
                large = RoundedCornerShape(20.dp),
                extraLarge = RoundedCornerShape(28.dp),
            ),
            content = content,
        )
    }
}
