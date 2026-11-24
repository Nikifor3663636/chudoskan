package ua.chudo.scanner.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ua.chudo.scanner.ui.common.BannerKind
import ua.chudo.scanner.ui.common.MineTone
import ua.chudo.scanner.ui.common.bannerPaint
import ua.chudo.scanner.ui.common.buttonPaint
import ua.chudo.scanner.ui.theme.MineColors
import ua.chudo.scanner.ui.theme.MineDark
import ua.chudo.scanner.ui.theme.MineLight
import kotlin.math.pow

/** Кнопки й банери: кольори станів із токенів і читабельний текст у кожному стані. */
class MineComponentsTest {

    private val themes = listOf(MineLight, MineDark)

    private fun lum(c: Color): Double {
        fun f(v: Float) = if (v <= 0.03928f) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        return 0.2126 * f(c.red) + 0.7152 * f(c.green) + 0.0722 * f(c.blue)
    }

    private fun ratio(a: Color, b: Color): Double {
        val (hi, lo) = listOf(lum(a), lum(b)).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    private fun name(m: MineColors) = if (m.dark) "dark" else "light"

    /** Прозора кнопка лежить на картці — контраст рахуємо до неї. */
    @Test fun buttonTextReadableInEveryState() {
        val bad = mutableListOf<String>()
        for (m in themes) for (tone in MineTone.entries) for (pressed in listOf(false, true)) for (enabled in listOf(true, false)) {
            val p = buttonPaint(tone, pressed, enabled, m)
            val r = ratio(p.content, p.container.compositeOver(m.card))
            if (r < 4.5) bad += "$tone pressed=$pressed enabled=$enabled (${name(m)}): ${"%.2f".format(r)}:1"
        }
        assertTrue("Текст кнопки < 4,5:1:\n" + bad.joinToString("\n"), bad.isEmpty())
    }

    @Test fun pressedStateIsVisible() {
        for (m in themes) for (tone in MineTone.entries) {
            assertNotEquals("$tone (${name(m)})", buttonPaint(tone, false, true, m).container, buttonPaint(tone, true, true, m).container)
        }
    }

    @Test fun pressedUsesDesignTokens() {
        for (m in themes) {
            assertEquals(m.accentPressed, buttonPaint(MineTone.ACCENT, true, true, m).container)
            assertEquals(m.errorBg, buttonPaint(MineTone.DANGER, true, true, m).container)
            assertEquals(m.field, buttonPaint(MineTone.OUTLINE, true, true, m).container)
            assertEquals(m.accentTone, buttonPaint(MineTone.TEXT, true, true, m).container)
            assertEquals(m.accentText, buttonPaint(MineTone.TEXT, false, true, m).content)
        }
    }

    @Test fun onlyOutlineHasBorderAndDisabledHasNone() {
        for (m in themes) for (tone in MineTone.entries) {
            assertEquals("$tone", tone == MineTone.OUTLINE, buttonPaint(tone, false, true, m).border != null)
            assertNull("$tone неактивна", buttonPaint(tone, false, false, m).border)
        }
    }

    /** Текст і текстова кнопка банера ≥ 4,5:1, іконка ≥ 3:1. */
    @Test fun bannersReadable() {
        val bad = mutableListOf<String>()
        for (m in themes) for (kind in BannerKind.entries) {
            val (bg, icon, fg) = bannerPaint(kind, m)
            val action = buttonPaint(MineTone.TEXT, false, true, m).content
            if (ratio(fg, bg) < 4.5) bad += "$kind текст (${name(m)}): ${"%.2f".format(ratio(fg, bg))}"
            if (ratio(action, bg) < 4.5) bad += "$kind дія (${name(m)}): ${"%.2f".format(ratio(action, bg))}"
            if (ratio(icon, bg) < 3.0) bad += "$kind іконка (${name(m)}): ${"%.2f".format(ratio(icon, bg))}"
        }
        assertTrue("Банер:\n" + bad.joinToString("\n"), bad.isEmpty())
    }
}
