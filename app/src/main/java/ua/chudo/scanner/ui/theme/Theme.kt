package ua.chudo.scanner.ui.theme

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

/** Кольори застосунку (палітра chudoskan-redesign.html); форми й компоненти — Material 3. */
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

val MineDark = MineColors(
    dark = true,
    bg = Color(0xFF0F1114), surface = Color(0xFF1C2025), card = Color(0xFF171A1E), line = Color(0xFF2A2F36),
    outline = Color(0xFF3A4049), text = Color(0xFFECEEF1), textSecondary = Color(0xFFA3AAB5),
    accent = Color(0xFFFF7A0A), onAccent = Color(0xFF1C0D00), accentTone = Color(0xFF2A1A0C),
    danger = Color(0xFF2E1715), onDanger = Color(0xFFFF7A70), success = Color(0xFF4CC27E),
    warning = Color(0xFFF2B33D), warningText = Color(0xFFF2B33D), field = Color(0xFF1C2025),
    sheet = Color(0xFF22262C), link = Color(0xFFFF9A47), logout = Color(0xFFFF7A70),
    brandLine = Color(0xFF5A3413), textTertiary = Color(0xFF8A919C),
    successTint = Color(0xFF13291D), warningTint = Color(0xFF2B2210),
    info = Color(0xFF7BA7F5), infoTint = Color(0xFF152238),
    hover = Color.White.copy(alpha = 0.05f), press = Color.White.copy(alpha = 0.09f),
    scrim = Color.Black.copy(alpha = 0.60f),
)

val MineLight = MineColors(
    dark = false,
    bg = Color(0xFFF3F4F6), surface = Color(0xFFF7F8F9), card = Color(0xFFFFFFFF), line = Color(0xFFE1E3E8),
    outline = Color(0xFFC9CDD4), text = Color(0xFF15171C), textSecondary = Color(0xFF545B67),
    accent = Color(0xFFFF7500), onAccent = Color(0xFF1C0D00), accentTone = Color(0xFFFFF0E3),
    danger = Color(0xFFFCEAE8), onDanger = Color(0xFFB42620), success = Color(0xFF1E7F47),
    warning = Color(0xFFF2B33D), warningText = Color(0xFF8F5600), field = Color(0xFFF7F8F9),
    sheet = Color(0xFFFFFFFF), link = Color(0xFFA84A00), logout = Color(0xFFB42620),
    brandLine = Color(0xFFFFD2AD), textTertiary = Color(0xFF666C77),
    successTint = Color(0xFFE6F4EC), warningTint = Color(0xFFFFF4DC),
    info = Color(0xFF1F5BC2), infoTint = Color(0xFFE7EFFC),
    hover = Color(0xFF15171C).copy(alpha = 0.05f), press = Color(0xFF15171C).copy(alpha = 0.09f),
    scrim = Color(0xFF0C0E12).copy(alpha = 0.48f),
)

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

private fun scheme(m: MineColors) =
    (if (m.dark) darkColorScheme() else lightColorScheme()).copy(
        primary = m.accent,
        onPrimary = m.onAccent,
        primaryContainer = m.accentTone,
        onPrimaryContainer = m.link,
        inversePrimary = if (m.dark) MineLight.accent else MineDark.accent,
        secondary = m.textSecondary,
        onSecondary = m.card,
        secondaryContainer = m.accentTone,
        onSecondaryContainer = m.link,
        tertiary = m.info,
        onTertiary = m.card,
        tertiaryContainer = m.infoTint,
        onTertiaryContainer = m.info,
        background = m.bg,
        onBackground = m.text,
        surface = m.card,
        onSurface = m.text,
        surfaceVariant = m.surface,
        onSurfaceVariant = m.textSecondary,
        surfaceTint = m.accent,
        inverseSurface = if (m.dark) MineLight.text else MineDark.text,
        inverseOnSurface = if (m.dark) MineLight.card else MineDark.card,
        surfaceDim = m.bg,
        surfaceBright = m.sheet,
        surfaceContainerLowest = if (m.dark) m.bg else m.card,
        surfaceContainerLow = m.card,
        surfaceContainer = m.surface,
        surfaceContainerHigh = m.sheet,
        surfaceContainerHighest = m.sheet,
        outline = m.outline,
        outlineVariant = m.line,
        error = m.onDanger,
        onError = m.danger,
        errorContainer = m.danger,
        onErrorContainer = m.onDanger,
        scrim = m.scrim,
    )

private val DarkScheme = scheme(MineDark)
private val LightScheme = scheme(MineLight)

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
            LocalTextSelectionColors provides TextSelectionColors(m.info, m.info.copy(alpha = 0.25f)),
            content = content,
        )
    }
}
