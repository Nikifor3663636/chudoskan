package ua.chudo.scanner.ui.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutMatchTest {
    private val m = BrandMatcher(mapOf("hotweels" to "hotwheels", "lol" to "l.o.l", "лего" to "lego"))
    private fun label(t: String, x: Float = 0f) = MapLabel(t, Box(x, 0f, 0.1f, 0.1f))

    @Test fun normalize() {
        assertEquals("hotwheels", BrandMatcher.norm("Hot-Wheels"))
        assertEquals("xshot", BrandMatcher.norm("X-Shot"))
        assertEquals("lol", BrandMatcher.norm("L.O.L"))
        assertEquals("ok", BrandMatcher.norm("Ок")) // кириллица-двойник → латиница
        assertEquals("pawpatrol", BrandMatcher.norm("Paw ’Patrol"))
    }

    @Test fun brands() {
        assertTrue(m.same("Hot Wheels", "Hot-Weels"))
        assertTrue(BrandMatcher().same("Hot Wheels", "Hot-Weels")) // одна опечатка и без словаря
        assertTrue(m.same("X-Shot", "X-shot"))
        assertTrue(m.same("L.O.L", "LOL"))
        assertTrue(m.same("LOL Surprise", "L.O.L"))
        assertTrue(m.same("LEGO", "LEGO / Duplo"))
        assertTrue(m.same("Лего", "LEGO"))
        assertFalse(m.same("LEGO", "Barbie"))
        assertFalse(m.same("", "LEGO"))
    }

    @Test fun brandFromName() {
        assertEquals("Hot Wheels", BrandMatcher.brandOf("ТМ Hot Wheels, машинка 1:64"))
        assertEquals(null, BrandMatcher.brandOf("Машинка Hot Wheels"))
    }

    @Test fun findAllPlacesAndFallbackToWords() {
        val labels = listOf(label("LEGO"), label("Barbie", 0.2f), label("LEGO Duplo", 0.4f), label("Hot Wheels", 0.6f))
        assertEquals(listOf(0, 2), m.find(labels, "LEGO", "ТМ LEGO, Поїзд"))
        // Бренда нет — ищем по словам названия, в т.ч. по паре «Hot Wheels».
        assertEquals(listOf(3), m.find(labels, null, "Машинка Hot Wheels 1:64"))
        assertEquals(emptyList<Int>(), m.find(labels, null, "М'яч гумовий"))
    }

    @Test fun cameraMapsBoxToScreen() {
        // Картинка 1000×500 в экране 500×500: масштаб 0.5, по вертикали по центру.
        val c = MapCamera(1000f, 500f, 500f, 500f).clamped()
        assertEquals(0f, c.tx, 1e-3f)
        assertEquals(125f, c.ty, 1e-3f)
        assertEquals(250f to 250f, c.toScreen(0.5f, 0.5f))
        // Зум 2× к центру: центр остаётся на месте, угол уходит за экран.
        val z = c.zoomAt(250f, 250f, 2f)
        assertEquals(250f to 250f, z.toScreen(0.5f, 0.5f))
        assertEquals(-250f to 0f, z.toScreen(0f, 0f))
        val (fx, fy) = z.toImage(250f, 250f)
        assertEquals(0.5f, fx, 1e-4f)
        assertEquals(0.5f, fy, 1e-4f)
        // Не больше 6× и не меньше 1×.
        assertEquals(6f, c.zoomAt(0f, 0f, 50f).zoom, 0f)
        assertEquals(1f, c.zoomAt(0f, 0f, 0.1f).zoom, 0f)
    }

    @Test fun fitShowsAllBoxes() {
        val c = MapCamera(1000f, 1000f, 500f, 500f).clamped()
        val boxes = listOf(Box(0.1f, 0.1f, 0.05f, 0.05f), Box(0.3f, 0.3f, 0.05f, 0.05f))
        val f = c.fit(boxes, pad = 0f)
        assertTrue(f.zoom > 1f)
        boxes.forEach { b ->
            val (x0, y0) = f.toScreen(b.x, b.y)
            val (x1, y1) = f.toScreen(b.x + b.w, b.y + b.h)
            assertTrue(x0 >= -0.5f && y0 >= -0.5f && x1 <= 500.5f && y1 <= 500.5f)
        }
    }
}
