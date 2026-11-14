package ua.chudo.scanner.print

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PriceTagsTest {
    @Test fun labelPrice() {
        assertEquals("2 299,00", PriceFormat.label(2299.0))
        assertEquals("129,90", PriceFormat.label(129.9))
        assertEquals("0,99", PriceFormat.label(0.99))
        assertEquals("1 234 567,05", PriceFormat.label(1234567.05))
    }

    @Test fun tagPrice() {
        assertEquals("15 885" to "00", PriceFormat.tag(15885.0))
        assertEquals("999" to "50", PriceFormat.tag(999.5))
        assertEquals("99 999" to "99", PriceFormat.tag(99999.99))
    }

    @Test fun parse() {
        assertEquals(1299.9, PriceFormat.parse("1 299,90")!!, 1e-9)
        assertEquals(15.0, PriceFormat.parse("15")!!, 1e-9)
        assertEquals(null, PriceFormat.parse(""))
    }

    @Test fun promoOnlyWhenOldPriceHigher() {
        assertTrue(LabelItem(price = 100.0, oldPrice = 150.0).canPromo)
        assertFalse(LabelItem(price = 100.0, oldPrice = 100.0).canPromo)
        assertFalse(LabelItem(price = 100.0).canPromo)
    }

    @Test fun titleSkipsEmpty() {
        assertEquals("ТМ LEGO, Поїзд, арт. 60337", LabelItem(brand = "LEGO", name = "Поїзд", article = "60337").title(withArticle = true))
        assertEquals("Поїзд", LabelItem(name = "Поїзд", article = "60337").title())
    }

    @Test fun tag68x38Grid() {
        val l = Template.TAG_68x38.layout!!
        assertEquals(3, l.cols)
        assertEquals(7, l.rows)
        assertEquals(21, l.perPage)
        assertEquals(1, l.pages(21))
        assertEquals(2, l.pages(22))
        assertEquals(Cell(1, 0, 0), l.cell(21))
        assertEquals(Cell(0, 2, 6), l.cell(20))
        assertEquals(3f, l.marginXmm, 1e-4f)
    }

    @Test fun bigFourPerLandscapeSheet() {
        val l = Template.BIG.layout!!
        assertEquals(2, l.cols)
        assertEquals(2, l.rows)
        assertEquals(4, l.perPage)
        assertEquals(2, l.pages(5))
        assertEquals(Cell(1, 0, 0), l.cell(4))
        assertEquals(null, Template.LABEL_40x25.layout)
    }

    @Test fun itemsTimesQtyInOrder() {
        val cells = expand(listOf("a" to 2, "b" to 1, "c" to 3))
        assertEquals(listOf("a", "a", "b", "c", "c", "c"), cells)
        val l = Template.TAG_68x38.layout!!
        assertEquals(2, l.pages(expand(listOf("x" to 20, "y" to 5)).size))
    }
}
