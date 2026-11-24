package ua.chudo.scanner.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ua.chudo.scanner.ui.theme.AppTypography
import ua.chudo.scanner.ui.theme.MineColors
import ua.chudo.scanner.ui.theme.MineDark
import ua.chudo.scanner.ui.theme.MineLight
import ua.chudo.scanner.ui.theme.PriceStyle
import ua.chudo.scanner.ui.theme.QtyStyle
import java.io.File
import kotlin.math.pow

/** design/tokens.json ↔ Theme.kt: ті самі значення, контраст дизайн-системи, кирилиця в шрифті. */
class DesignTokensTest {

    private val json = File("../design/tokens.json").readText()

    private val tokens: Map<String, (MineColors) -> Color> = linkedMapOf(
        "bg" to { it.bg }, "surface" to { it.surface }, "card" to { it.card }, "line" to { it.line },
        "outline" to { it.outline }, "text" to { it.text }, "textSecondary" to { it.textSecondary },
        "accent" to { it.accent }, "accentPressed" to { it.accentPressed }, "onAccent" to { it.onAccent },
        "accentTone" to { it.accentTone }, "accentText" to { it.accentText },
        "danger" to { it.danger }, "onDanger" to { it.onDanger }, "error" to { it.error }, "errorBg" to { it.errorBg },
        "success" to { it.success }, "warning" to { it.warning }, "warningText" to { it.warningText },
        "stockIn" to { it.stockIn }, "stockInBg" to { it.stockInBg }, "stockLow" to { it.stockLow },
        "stockLowBg" to { it.stockLowBg }, "stockOut" to { it.stockOut }, "stockOutBg" to { it.stockOutBg },
        "field" to { it.field }, "sheet" to { it.sheet }, "link" to { it.link }, "logout" to { it.logout },
        "scrim" to { it.scrim }, "camera" to { it.camera }, "onCamera" to { it.onCamera },
    )

    /** Вміст об'єкта `"name": { … }` (перший збіг після [from]). */
    private fun section(name: String, from: Int = 0): String {
        val start = json.indexOf("\"$name\": {", from).also { require(it >= 0) { "немає «$name» у tokens.json" } }
        var depth = 0
        for (i in json.indexOf('{', start) until json.length) {
            when (json[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return json.substring(start, i + 1)
            }
        }
        error("незакритий «$name»")
    }

    private fun colorsOf(theme: String): Map<String, String> =
        Regex("\"(\\w+)\":\\s*\\{\\s*\"\\\$type\":\\s*\"color\",\\s*\"\\\$value\":\\s*\"#(\\w+)\"")
            .findAll(section(theme, json.indexOf("\"color\"")))
            .associate { it.groupValues[1] to it.groupValues[2].uppercase() }

    private fun hex(c: Color): String {
        val argb = c.toArgb()
        val rgb = "%06X".format(argb and 0xFFFFFF)
        val a = argb ushr 24
        return if (a == 0xFF) rgb else "%02X".format(a) + rgb
    }

    @Test fun darkTokensMatchCode() = check("dark", MineDark)

    @Test fun lightTokensMatchCode() = check("light", MineLight)

    private fun check(theme: String, m: MineColors) {
        val file = colorsOf(theme)
        assertEquals("набір токенів $theme", tokens.keys, file.keys)
        // Кожне поле MineColors (крім dark) має бути в переліку — інакше новий колір не потрапить у tokens.json.
        val fields = MineColors::class.java.declaredFields.map { it.name }.filter { it != "dark" && !it.startsWith("$") }
        assertEquals("поля MineColors", tokens.keys, fields.toSet())
        tokens.forEach { (name, get) -> assertEquals("$theme.$name", file.getValue(name), hex(get(m))) }
    }

    private fun lum(c: Color): Double {
        fun f(v: Float) = if (v <= 0.03928f) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        return 0.2126 * f(c.red) + 0.7152 * f(c.green) + 0.0722 * f(c.blue)
    }

    private fun ratio(a: Color, b: Color): Double {
        val (hi, lo) = listOf(lum(a), lum(b)).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    /** Пари з розділу «01 Основи»: текст ≥ 4,5:1 в обох темах. */
    @Test fun textContrast() {
        val pairs = listOf(
            "text" to "bg", "text" to "card", "textSecondary" to "bg", "textSecondary" to "card",
            "textSecondary" to "field", "onAccent" to "accent", "onAccent" to "accentPressed",
            "accentText" to "card", "accentText" to "accentTone", "onDanger" to "danger",
            "error" to "errorBg", "error" to "card", "stockIn" to "stockInBg", "stockIn" to "card",
            "stockLow" to "stockLowBg", "stockOut" to "stockOutBg", "warningText" to "card",
            "bg" to "text", "onCamera" to "camera", "success" to "card",
        )
        val bad = mutableListOf<String>()
        for (m in listOf(MineLight, MineDark)) for ((fg, bg) in pairs) {
            val r = ratio(tokens.getValue(fg)(m), tokens.getValue(bg)(m))
            if (r < 4.5) bad += "$fg на $bg (${if (m.dark) "dark" else "light"}): ${"%.2f".format(r)}:1"
        }
        assertTrue("Контраст < 4,5:1:\n" + bad.joinToString("\n"), bad.isEmpty())
    }

    /** Рамка кнопок і чипів помітна: ≥ 3:1 до фону, картки й поверхні. */
    @Test fun outlineContrast() {
        val bad = mutableListOf<String>()
        for (m in listOf(MineLight, MineDark)) for ((name, bg) in listOf("bg" to m.bg, "card" to m.card, "surface" to m.surface)) {
            val r = ratio(m.outline, bg)
            if (r < 3.0) bad += "outline на $name (${if (m.dark) "dark" else "light"}): ${"%.2f".format(r)}:1"
        }
        assertTrue("Рамка < 3:1:\n" + bad.joinToString("\n"), bad.isEmpty())
    }

    @Test fun typographyMatchesTokens() {
        val styles: Map<String, TextStyle> = mapOf(
            "headlineMedium" to AppTypography.headlineMedium, "titleLarge" to AppTypography.titleLarge,
            "titleMedium" to AppTypography.titleMedium, "bodyLarge" to AppTypography.bodyLarge,
            "bodyMedium" to AppTypography.bodyMedium, "labelLarge" to AppTypography.labelLarge,
            "price" to PriceStyle, "qty" to QtyStyle,
        )
        val typo = section("typography")
        val bad = mutableListOf<String>()
        for ((name, s) in styles) {
            val v = Regex("\"$name\":\\s*\\{[^}]*\\{([^}]*)\\}").find(typo)?.groupValues?.get(1)
                ?: error("немає $name у typography")
            fun num(key: String) = Regex("\"$key\":\\s*(-?[\\d.]+)").find(v)?.groupValues?.get(1)?.toFloat()
            fun same(key: String, code: Float) {
                val file = num(key) ?: 0f
                if (kotlin.math.abs(file - code) > 0.001f) bad += "$name.$key: у файлі $file, у коді $code"
            }
            same("fontSize", s.fontSize.value)
            same("fontWeight", (s.fontWeight?.weight ?: 0).toFloat())
            same("letterSpacing", s.letterSpacing.value)
            same("lineHeight", s.lineHeight.value)
            if (s.fontFamily == null) bad += "$name: без шрифту Manrope"
        }
        assertTrue("Типографіка:\n" + bad.joinToString("\n"), bad.isEmpty())
        assertEquals("tnum", PriceStyle.fontFeatureSettings)
        assertEquals("tnum", QtyStyle.fontFeatureSettings)
    }

    /** Усі накреслення Manrope показують українські літери (інакше Android підставить інший шрифт). */
    @Test fun manropeHasCyrillic() {
        val sample = "ЧудоСкан Ґґ Єє Іі Її Кам’янець «грн» № 0123456789 Ціна за одиницю товару"
        for (w in listOf("medium", "semibold", "bold", "extrabold")) {
            // java.awt є в JVM тестів, але не в android.jar при компіляції — через рефлексію.
            val font = Class.forName("java.awt.Font")
            val f = font.getMethod("createFont", Int::class.javaPrimitiveType, File::class.java)
                .invoke(null, 0, File("src/main/res/font/manrope_$w.ttf"))
            assertEquals("manrope_$w: перший символ без гліфа", -1, font.getMethod("canDisplayUpTo", String::class.java).invoke(f, sample))
        }
    }
}
