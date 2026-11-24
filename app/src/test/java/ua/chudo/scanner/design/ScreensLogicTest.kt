package ua.chudo.scanner.design

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ua.chudo.scanner.ui.common.StockLevel
import ua.chudo.scanner.ui.common.pcs
import ua.chudo.scanner.ui.common.stockLevel
import ua.chudo.scanner.ui.common.stockPaint
import ua.chudo.scanner.ui.print.tagsWord
import ua.chudo.scanner.ui.product.shopsWord
import ua.chudo.scanner.ui.theme.MineDark
import ua.chudo.scanner.ui.theme.MineLight

/** Логіка екранів дизайн-системи: пороги залишку, кількість, відмінки. */
class ScreensLogicTest {
    @Test fun stockThresholds() {
        assertEquals(StockLevel.OUT, stockLevel(0.0))
        assertEquals(StockLevel.OUT, stockLevel(-1.0))
        assertEquals(StockLevel.LOW, stockLevel(0.5))
        assertEquals(StockLevel.LOW, stockLevel(2.0))
        assertEquals(StockLevel.IN, stockLevel(2.5))
        assertEquals(StockLevel.IN, stockLevel(3.0))
    }

    @Test fun levelsDifferBesidesColor() {
        assertEquals(3, StockLevel.entries.map { it.icon }.toSet().size)
        assertEquals(3, StockLevel.entries.map { it.word }.toSet().size)
        for (m in listOf(MineLight, MineDark)) assertEquals(3, StockLevel.entries.map { stockPaint(it, m) }.toSet().size)
    }

    @Test fun pieces() {
        assertEquals("3 шт", pcs(3.0))
        assertEquals("0 шт", pcs(0.0))
        assertEquals("2,5 шт", pcs(2.5))
    }

    @Test fun stockTabsAsInDesign() {
        assertEquals(listOf("Свій магазин", "Місто", "Мережа", "Змінені"), ua.chudo.scanner.ui.stock.StockTab.entries.map { it.title })
    }

    @Test fun employeeSeesOnlyOwnShop() {
        val employee = ua.chudo.scanner.ui.stock.StockTab.visibleTo(admin = false)
        assertEquals(listOf(ua.chudo.scanner.ui.stock.StockTab.OWN, ua.chudo.scanner.ui.stock.StockTab.CHANGED), employee)
        assertEquals(4, ua.chudo.scanner.ui.stock.StockTab.visibleTo(admin = true).size)
    }

    @Test fun ukrainianPlurals() {
        mapOf(1 to "1 магазин", 2 to "2 магазини", 4 to "4 магазини", 5 to "5 магазинів", 11 to "11 магазинів",
            12 to "12 магазинів", 21 to "21 магазин", 22 to "22 магазини", 0 to "0 магазинів").forEach { (n, s) -> assertEquals(s, shopsWord(n)) }
        mapOf(1 to "1 цінник", 3 to "3 цінники", 7 to "7 цінників", 14 to "14 цінників", 101 to "101 цінник")
            .forEach { (n, s) -> assertEquals(s, tagsWord(n)) }
        assertTrue(tagsWord(111).endsWith("цінників"))
    }
}
