package ua.chudo.scanner.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class CartTest {
    private fun item(key: String, priceKop: Long, qty: Int = 1, manual: Boolean = false) =
        CartItem(key, key, "Товар $key", "BOC000$key", "A-$key", qty, priceKop, manual)

    @Test fun `3 товари, один з ціною вручну, знижка 10 відсотків, готівка 1000`() {
        var cart = Cart()
            .add(item("4820000000017", 24_990))
            .add(item("4820000000017", 24_990)) // той самий штрихкод — кількість 2
            .add(item("2000999154010", 0, manual = true))
            .add(item("4820000000024", 8_999))
        cart = cart.setPrice("2000999154010", Money.parse("129")!!)
        assertEquals(3, cart.items.size)
        assertEquals(4, cart.count)
        assertTrue(cart.items.single { it.key == "2000999154010" }.manualPrice)

        cart = cart.withDiscount(Discount.Percent(10))
        assertEquals(71_879, cart.subtotalKop)       // 499,80 + 129,00 + 89,99
        assertEquals(7_188, cart.discountKop)        // 71,879 → 71,88
        assertEquals(64_691, cart.totalKop)          // 646,91
        assertEquals(35_309L, cart.changeKop(Money.parse("1000")!!)) // решта 353,09
        assertNull(cart.changeKop(Money.parse("600")!!))
        assertEquals("646,91 ₴", Money.format(cart.totalKop))
        assertEquals("353,09 ₴", Money.format(35_309))
    }

    @Test fun `кількість, видалення, знижка сумою не більша за суму`() {
        var c = Cart().add(item("1", 10_000)).add(item("2", 5_000))
        c = c.inc("1").inc("1").dec("2").dec("2")
        assertEquals(3, c.items.first().qty)
        assertEquals(1, c.items.last().qty)
        c = c.remove("2")
        assertEquals(30_000, c.subtotalKop)
        assertEquals(0, c.withDiscount(Discount.Amount(50_000)).totalKop)
        assertEquals(25_000, c.withDiscount(Discount.Amount(5_000)).totalKop)
        assertEquals(0, c.withDiscount(Discount.Percent(150)).totalKop)
        assertEquals(30_000, c.withDiscount(Discount.Percent(-5)).totalKop)
    }

    @Test fun `гроші без Double`() {
        assertEquals(64_691L, Money.fromUah(646.91))
        assertEquals(1L, Money.fromUah(0.01))
        assertEquals(29L, Money.fromUah(0.29)) // 0.29 * 100 у Double = 28.999…
        assertEquals(1_250L, Money.parse("12,5"))
        assertEquals(1_250L, Money.parse(" 12.50 "))
        assertEquals(100_000L, Money.parse("1 000"))
        assertNull(Money.parse("-5"))
        assertNull(Money.parse("1,234"))
        assertNull(Money.parse("абв"))
        assertNull(Money.parse(""))
        assertEquals("1 234 567,05 ₴", Money.format(123_456_705))
    }

    @Test fun `не більше 200 позицій`() {
        var c = Cart()
        repeat(Cart.MAX_ITEMS) { c = c.add(item("$it", 100)) }
        assertEquals(200, c.items.size)
        assertEquals(2, c.add(item("0", 100)).items.first().qty) // той самий — можна
        assertTrue(runCatching { c.add(item("новий", 100)) }.isFailure)
    }

    @Test fun `документ продажу — білий список полів, копійки`() {
        val cart = Cart().add(item("1", 12_345, qty = 2)).add(item("2", 500, manual = true)).withDiscount(Discount.Amount(190))
        val sale = Sale.of("s1", "22", "uid22", cart, "AB23CD")
        val doc = sale.toDoc()
        assertEquals(setOf("shopId", "by", "items", "discount", "total", "status", "code"), doc.keys)
        assertEquals("draft", doc["status"])
        assertEquals(25_000L, doc["total"])
        assertEquals(190L, doc["discount"])
        @Suppress("UNCHECKED_CAST")
        val first = (doc["items"] as List<Map<String, Any>>).first()
        assertEquals(setOf("barcode", "name", "code", "article", "qty", "price", "manualPrice"), first.keys)
        assertEquals(12_345L, first["price"])
        assertEquals(2L, first["qty"])
        assertTrue(runCatching { Sale.of("s2", "22", "u", Cart(), "AB23CD") }.isFailure)
    }

    @Test fun `короткий код — 6 символів без схожих`() {
        val r = Random(7)
        repeat(500) {
            val c = SaleCode.generate(r)
            assertTrue(c, SaleCode.PATTERN.matches(c))
            assertTrue(c, c.none { it in "01OI" })
        }
        assertEquals("chudoskan://sale/abc?c=AB23CD", SaleCode.qr("abc", "AB23CD"))
    }
}
