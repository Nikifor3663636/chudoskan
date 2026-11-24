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
    /** Натиснута головна кнопка. */
    val accentPressed: Color,
    /** Текст на акценте. */
    val onAccent: Color,
    /** Тон акцента: подложка иконок, выделения. */
    val accentTone: Color,
    /** Акцентний текст: текстова кнопка, активна вкладка, бейдж. */
    val accentText: Color,
    /** Кнопка-ошибка и знижка. */
    val danger: Color,
    val onDanger: Color,
    /** Текст помилки. */
    val error: Color,
    /** Підкладка банера помилки. */
    val errorBg: Color,
    val success: Color,
    val warning: Color,
    val warningText: Color,
    /** Залишок «є»: число й іконка, підкладка. */
    val stockIn: Color,
    val stockInBg: Color,
    /** Залишок «мало». */
    val stockLow: Color,
    val stockLowBg: Color,
    /** Залишок «немає». */
    val stockOut: Color,
    val stockOutBg: Color,
    /** Подложка «+380» в поле телефона. */
    val field: Color,
    /** Нижний лист. */
    val sheet: Color,
    val link: Color,
    /** «Вийти». */
    val logout: Color,
    /** Затемнення під листом і діалогом, плашки поверх камери. */
    val scrim: Color,
    /** Фон видошукача камери й текст поверх нього. */
    val camera: Color,
    val onCamera: Color,
)

val MineDark = MineColors(
    dark = true,
    bg = Color(0xFF121317), surface = Color(0xFF1C1D22), card = Color(0xFF26272D), line = Color(0xFF3A3B42),
    outline = Color(0xFF74757D), text = Color(0xFFF2EEEA), textSecondary = Color(0xFFB9B4AE),
    accent = Color(0xFFFF8A24), accentPressed = Color(0xFFFF7A00), onAccent = Color(0xFF1A0E04),
    accentTone = Color(0xFF3A1F08), accentText = Color(0xFFFF8A24),
    danger = Color(0xFF5A1A14), onDanger = Color(0xFFF6D6D0), error = Color(0xFFFF8A6A), errorBg = Color(0xFF4A1A14),
    success = Color(0xFF3DCB6C), warning = Color(0xFFFFB020), warningText = Color(0xFFFFC27A),
    stockIn = Color(0xFF3DCB6C), stockInBg = Color(0xFF12301D), stockLow = Color(0xFFFFC27A),
    stockLowBg = Color(0xFF3A2A0A), stockOut = Color(0xFFB9B4AE), stockOutBg = Color(0xFF2A2B31),
    field = Color(0xFF2A2B31), sheet = Color(0xFF1E1F24), link = Color(0xFFFF8A24), logout = Color(0xFFFF8A6A),
    scrim = Color(0x99000000), camera = Color(0xFF121317), onCamera = Color(0xFFF2EEEA),
)

val MineLight = MineColors(
    dark = false,
    bg = Color(0xFFF5F6F8), surface = Color(0xFFFFFFFF), card = Color(0xFFFFFFFF), line = Color(0xFFDDE0E5),
    outline = Color(0xFF868D9A), text = Color(0xFF1C1D22), textSecondary = Color(0xFF5F6470),
    accent = Color(0xFFFF7A00), accentPressed = Color(0xFFE06C00), onAccent = Color(0xFF1A0E04),
    accentTone = Color(0xFFFFE6D5), accentText = Color(0xFFB04800),
    danger = Color(0xFFFBDAD4), onDanger = Color(0xFF7A1E12), error = Color(0xFFB42318), errorBg = Color(0xFFFDE4E1),
    success = Color(0xFF15803D), warning = Color(0xFFE08A00), warningText = Color(0xFF8A5200),
    stockIn = Color(0xFF15803D), stockInBg = Color(0xFFEAF9EE), stockLow = Color(0xFF8A5200),
    stockLowBg = Color(0xFFFFF0D6), stockOut = Color(0xFF5F6470), stockOutBg = Color(0xFFECEEF1),
    field = Color(0xFFF0F1F4), sheet = Color(0xFFFFFFFF), link = Color(0xFFB44A00), logout = Color(0xFFB42318),
    scrim = Color(0x73000000), camera = Color(0xFF121317), onCamera = Color(0xFFF2EEEA),
)

val LocalMine = staticCompositionLocalOf { MineDark }

/** Токены текущей темы: `Mine.colors.accent`. */
object Mine {
    val colors: MineColors @Composable get() = LocalMine.current
}

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
        error = m.error, errorContainer = m.errorBg, scrim = m.scrim,
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
        error = m.error, errorContainer = m.errorBg, scrim = m.scrim,
    )
}

/** Manrope — шрифт інтерфейсу (design/tokens.json → font). Кирилиця є в усіх накресленнях. */
val Manrope = FontFamily(
    Font(R.font.manrope_medium, FontWeight.Medium),
    Font(R.font.manrope_semibold, FontWeight.SemiBold),
    Font(R.font.manrope_bold, FontWeight.Bold),
    Font(R.font.manrope_extrabold, FontWeight.ExtraBold),
)

internal val AppTypography = Typography().run {
    fun TextStyle.m(weight: FontWeight? = null) = copy(fontFamily = Manrope, fontWeight = weight ?: fontWeight)
    copy(
        displayLarge = displayLarge.m(), displayMedium = displayMedium.m(), displaySmall = displaySmall.m(),
        headlineLarge = headlineLarge.m(),
        headlineMedium = headlineMedium.copy(
            fontFamily = Manrope, fontWeight = FontWeight.ExtraBold, fontSize = 26.sp, lineHeight = 32.sp,
            letterSpacing = (-0.3).sp,
        ),
        headlineSmall = headlineSmall.m(),
        titleLarge = titleLarge.m(FontWeight.Bold),
        titleMedium = titleMedium.m(FontWeight.SemiBold),
        titleSmall = titleSmall.m(),
        bodyLarge = bodyLarge.m(FontWeight.Medium), bodyMedium = bodyMedium.m(FontWeight.Medium),
        bodySmall = bodySmall.m(FontWeight.Medium),
        labelLarge = labelLarge.m(FontWeight.SemiBold), labelMedium = labelMedium.m(FontWeight.SemiBold),
        labelSmall = labelSmall.m(FontWeight.SemiBold),
    )
}

/** Ціна в картці товару: читається з відстані руки; цифри однакової ширини. */
val PriceStyle = TextStyle(
    fontFamily = Manrope, fontSize = 40.sp, lineHeight = 44.sp, fontWeight = FontWeight.ExtraBold,
    letterSpacing = (-0.8).sp, fontFeatureSettings = "tnum",
)

/** Кількість на складі. */
val QtyStyle = TextStyle(
    fontFamily = Manrope, fontSize = 28.sp, lineHeight = 32.sp, fontWeight = FontWeight.ExtraBold,
    letterSpacing = (-0.4).sp, fontFeatureSettings = "tnum",
)

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
