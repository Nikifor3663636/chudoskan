package ua.chudo.scanner.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Кошик: сума до копійки, повторний скан, акції, рядки без ціни. */
class CartTest {
    private fun line(bc: String, price: Long?, qty: Int = 1, regular: Long? = null, inShop: Double? = null) =
        CartLine(bc, "Товар $bc", priceKop = price, regularKop = regular, qty = qty, inShop = inShop)

    @Test fun kopecksWithoutFloatErrors() {
        assertEquals(19990L, Cart.kop(199.9))
        assertEquals(10L, Cart.kop(0.1))
        // 0,1 + 0,2 у Double — 0,30000000000000004; у копійках — рівно 30.
        assertEquals(30L, Cart.kop(0.1)!! + Cart.kop(0.2)!!)
        assertNull(Cart.kop(null))
        assertNull(Cart.kop(0.0))
        assertNull(Cart.kop(Double.NaN))
    }

    @Test fun totalUnitsAndUnpriced() {
        val lines = listOf(line("1", 19990, qty = 2), line("2", 4550), line("3", null))
        assertEquals(19990L * 2 + 4550, Cart.total(lines))
        assertEquals(4, Cart.units(lines))
        assertEquals(1, Cart.unpriced(lines))
        assertEquals("445,30 ₴", Cart.money(Cart.total(lines)))
        assertEquals("1 234,50 ₴", Cart.money(123450))
        assertEquals("649 ₴", Cart.money(64900))
        assertEquals("15 885 ₴", Cart.money(1588500))
    }

    @Test fun sameBarcodeAddsQuantityNewGoesOnTop() {
        var (lines, isNew) = Cart.add(emptyList(), "482")
        assertTrue(isNew)
        assertTrue(lines.single().loading)
        lines = lines.map { it.copy(loading = false, priceKop = 100) }
        Cart.add(lines, "482").let { (l, n) -> assertFalse(n); assertEquals(2, l.single().qty) }
        Cart.add(lines, "999").let { (l, n) -> assertTrue(n); assertEquals(listOf("999", "482"), l.map { it.barcode }) }
    }

    @Test fun quantityZeroRemovesAndIsCapped() {
        val lines = listOf(line("1", 100), line("2", 200))
        assertEquals(listOf("2"), Cart.setQty(lines, "1", 0).map { it.barcode })
        assertEquals(Cart.MAX_QTY, Cart.setQty(lines, "1", 5000).first().qty)
    }

    @Test fun promoSavingsAndOverStock() {
        val lines = listOf(line("1", 15000, qty = 2, regular = 20000), line("2", 5000))
        assertEquals(10000L, Cart.savings(lines))
        assertTrue(Cart.overStock(line("3", 100, qty = 3, inShop = 2.0)))
        assertFalse(Cart.overStock(line("3", 100, qty = 2, inShop = 2.0)))
        assertFalse(Cart.overStock(line("3", 100, qty = 9, inShop = null)))
    }
}
