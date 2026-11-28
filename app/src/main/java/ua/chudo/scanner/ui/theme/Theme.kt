package ua.chudo.scanner.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
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
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import ua.chudo.scanner.ChudoApp
import ua.chudo.scanner.R

/** Тема оформлення в профілі. */
enum class ThemeMode(val title: String) { DARK("Темна"), LIGHT("Світла"), SYSTEM("Як у системі") }

/** Кольори застосунку — ролі Material 3 під іменами, якими користуються екрани. */
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
    val brandLine: Color,
    val textTertiary: Color,
    val successTint: Color,
    val warningTint: Color,
    val info: Color,
    val infoTint: Color,
    val hover: Color,
    val press: Color,
    val scrim: Color,
)

// Material 3: схема Fidelity від фірмового помаранчевого #FF7500 (material-color-utilities,
// як «Color match» у Material Theme Builder): primaryContainer — сам фірмовий колір,
// primary — темніший тон для тексту й кнопок з достатнім контрастом.
private val M3Light = lightColorScheme(
    primary = Color(0xFF9C4500),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFF7500),
    onPrimaryContainer = Color(0xFF5B2500),
    inversePrimary = Color(0xFFFFB68E),
    secondary = Color(0xFF914C20),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFDA370),
    onSecondaryContainer = Color(0xFF76370C),
    tertiary = Color(0xFF00639A),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFF00A5FD),
    onTertiaryContainer = Color(0xFF00385A),
    background = Color(0xFFFFF8F6),
    onBackground = Color(0xFF251912),
    surface = Color(0xFFFFF8F6),
    onSurface = Color(0xFF251912),
    surfaceVariant = Color(0xFFFEDCCB),
    onSurfaceVariant = Color(0xFF594236),
    surfaceTint = Color(0xFF9C4500),
    inverseSurface = Color(0xFF3C2D26),
    inverseOnSurface = Color(0xFFFFEDE5),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF93000A),
    outline = Color(0xFF8C7163),
    outlineVariant = Color(0xFFE0C0B0),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFFF8F6),
    surfaceDim = Color(0xFFEDD5CA),
    surfaceContainer = Color(0xFFFFEAE0),
    surfaceContainerHigh = Color(0xFFFCE3D7),
    surfaceContainerHighest = Color(0xFFF6DED2),
    surfaceContainerLow = Color(0xFFFFF1EB),
    surfaceContainerLowest = Color(0xFFFFFFFF),
)

private val M3Dark = darkColorScheme(
    primary = Color(0xFFFFB68E),
    onPrimary = Color(0xFF542200),
    primaryContainer = Color(0xFFFF7500),
    onPrimaryContainer = Color(0xFF5B2500),
    inversePrimary = Color(0xFF9C4500),
    secondary = Color(0xFFFFB68E),
    onSecondary = Color(0xFF542200),
    secondaryContainer = Color(0xFF76370C),
    onSecondaryContainer = Color(0xFFFCA36F),
    tertiary = Color(0xFF96CCFF),
    onTertiary = Color(0xFF003353),
    tertiaryContainer = Color(0xFF00A5FD),
    onTertiaryContainer = Color(0xFF00385A),
    background = Color(0xFF1C110A),
    onBackground = Color(0xFFF6DED2),
    surface = Color(0xFF1C110A),
    onSurface = Color(0xFFF6DED2),
    surfaceVariant = Color(0xFF594236),
    onSurfaceVariant = Color(0xFFE0C0B0),
    surfaceTint = Color(0xFFFFB68E),
    inverseSurface = Color(0xFFF6DED2),
    inverseOnSurface = Color(0xFF3C2D26),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFFA88B7C),
    outlineVariant = Color(0xFF594236),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF45362E),
    surfaceDim = Color(0xFF1C110A),
    surfaceContainer = Color(0xFF2A1D16),
    surfaceContainerHigh = Color(0xFF35271F),
    surfaceContainerHighest = Color(0xFF41322A),
    surfaceContainerLow = Color(0xFF251912),
    surfaceContainerLowest = Color(0xFF170C06),
)


/** Токени застосунку = ролі Material 3 (+ успіх / попередження / інфо, гармонізовані з фірмовим кольором). */
private fun mine(c: ColorScheme, dark: Boolean) = MineColors(
    dark = dark,
    bg = c.surface,
    surface = c.surfaceContainerHigh,
    card = c.surfaceContainerLow,
    line = c.outlineVariant,
    outline = c.outline,
    text = c.onSurface,
    textSecondary = c.onSurfaceVariant,
    accent = c.primary,
    onAccent = c.onPrimary,
    accentTone = if (dark) Color(0xFF773300) else Color(0xFFFFDBCA),
    danger = c.errorContainer,
    onDanger = c.onErrorContainer,
    success = if (dark) Color(0xFF99D77F) else Color(0xFF336B21),
    warning = if (dark) Color(0xFFFFB870) else Color(0xFF8B5000),
    warningText = if (dark) Color(0xFFFFB870) else Color(0xFF8B5000),
    field = c.surfaceContainerHighest,
    sheet = c.surfaceContainerLow,
    link = c.primary,
    logout = c.error,
    brandLine = c.primaryContainer,
    textTertiary = c.outline,
    successTint = if (dark) Color(0xFF1B5208) else Color(0xFFB4F398),
    warningTint = if (dark) Color(0xFF693C00) else Color(0xFFFFDCBE),
    info = if (dark) Color(0xFFBFC1FF) else Color(0xFF4C51C0),
    infoTint = if (dark) Color(0xFF3337A7) else Color(0xFFE1E0FF),
    // Шари стану M3: наведення 8 %, натискання 10 %.
    hover = c.onSurface.copy(alpha = 0.08f),
    press = c.onSurface.copy(alpha = 0.10f),
    scrim = c.scrim.copy(alpha = 0.32f),
)

val MineDark = mine(M3Dark, dark = true)
val MineLight = mine(M3Light, dark = false)

val LocalMine = staticCompositionLocalOf { MineDark }

/** Токены текущей темы: `Mine.colors.accent`. */
object Mine {
    val spacing4 = 4.dp
    val spacing8 = 8.dp
    val spacing12 = 12.dp
    val spacing16 = 16.dp
    val spacing20 = 20.dp
    val spacing24 = 24.dp
    val spacing32 = 32.dp
    val spacing40 = 40.dp
    val spacing48 = 48.dp
    val spacing64 = 64.dp

    val fieldShape = RoundedCornerShape(8.dp)
    val cardShape = RoundedCornerShape(12.dp)
    val pillShape = RoundedCornerShape(50)
    val colors: MineColors @Composable get() = LocalMine.current
}

val InStockGreen = Color(0xFF1E7F47)
val OutOfStockGrey = Color(0xFF9AA0A8)

private val DarkScheme = M3Dark
private val LightScheme = M3Light

val CodeStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Medium,
    fontSize = 13.sp,
)

val PriceStyle = TextStyle(
    fontSize = 30.sp,
    fontWeight = FontWeight.SemiBold,
    letterSpacing = (-0.3).sp,
    fontFeatureSettings = "tnum",
)

/** Шкала шрифтів Material 3 (Roboto); цифри — табличні, щоб ціни й кількості не «стрибали». */
private val AppTypography = Typography().let { t ->
    fun TextStyle.tnum() = copy(fontFeatureSettings = "tnum")
    Typography(
        displayLarge = t.displayLarge.tnum(), displayMedium = t.displayMedium.tnum(), displaySmall = t.displaySmall.tnum(),
        headlineLarge = t.headlineLarge.tnum(), headlineMedium = t.headlineMedium.tnum(), headlineSmall = t.headlineSmall.tnum(),
        titleLarge = t.titleLarge.tnum(), titleMedium = t.titleMedium.tnum(), titleSmall = t.titleSmall.tnum(),
        bodyLarge = t.bodyLarge.tnum(), bodyMedium = t.bodyMedium.tnum(), bodySmall = t.bodySmall.tnum(),
        labelLarge = t.labelLarge.tnum(), labelMedium = t.labelMedium.tnum(), labelSmall = t.labelSmall.tnum(),
    )
}

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
    MaterialTheme(
        colorScheme = if (m.dark) DarkScheme else LightScheme,
        typography = AppTypography,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(4.dp),
            small = RoundedCornerShape(6.dp),
            medium = Mine.fieldShape,
            large = Mine.cardShape,
            extraLarge = Mine.cardShape,
        ),
    ) {
        CompositionLocalProvider(
            LocalMine provides m,
            LocalContentColor provides m.text,
            LocalTextSelectionColors provides TextSelectionColors(m.accent, m.accent.copy(alpha = 0.3f)),
            content = content,
        )
    }
}
