package ua.chudo.scanner.print

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Етап 5: A4 альбомно від друкованої області Brother, поворот растру, дані цінника з 1С і сайту. */
class SheetsAndTagDataTest {

    // ---------- Розкладка ----------

    @Test fun tag68x38Landscape20() {
        val l = Template.TAG_68x38.layout(landscape = true)!!
        assertTrue(l.landscape)
        assertEquals(4, l.cols)
        assertEquals(5, l.rows)
        assertEquals(20, l.perPage)
        assertEquals(12.5f, l.marginXmm, 1e-4f)
        assertEquals(10f, l.marginYmm, 1e-4f)
        assertInsidePrintable(l)
    }

    @Test fun tag68x38Portrait21AsBefore() {
        val l = Template.TAG_68x38.layout(landscape = false)!!
        assertFalse(l.landscape)
        assertEquals(21, l.perPage)
    }

    @Test fun big140x100Landscape4() {
        val l = Template.BIG_140x100.layout(landscape = true)!!
        assertEquals(2, l.cols)
        assertEquals(2, l.rows)
        assertEquals(4, l.perPage)
        assertEquals(8.5f, l.marginXmm, 1e-4f)
        assertEquals(5f, l.marginYmm, 1e-4f)
        assertInsidePrintable(l)
        assertEquals(2, l.pages(5))
        assertEquals(Cell(1, 0, 0), l.cell(4))
        assertEquals(Cell(0, 1, 1), l.cell(3))
    }

    @Test fun big148x105KeptPortraitByDefault() {
        assertFalse(Template.BIG.defaultLandscape)
        assertEquals(2, Template.BIG.layout()!!.perPage)
        assertEquals(1, Template.BIG.layout(landscape = true)!!.perPage)
    }

    @Test fun landscapeIsDefaultForA4() {
        assertTrue(Template.TAG_68x38.defaultLandscape)
        assertTrue(Template.BIG_140x100.defaultLandscape)
        assertEquals(20, Template.TAG_68x38.layout()!!.perPage)
        assertEquals(4, Template.BIG_140x100.layout()!!.perPage)
    }

    private fun assertInsidePrintable(l: SheetLayout) {
        val m = SheetLayout.BROTHER_MARGIN_MM
        assertTrue("ліве/праве поле ${l.marginXmm}", l.marginXmm >= m)
        assertTrue("верхнє/нижнє поле ${l.marginYmm}", l.marginYmm >= m)
    }

    // ---------- Поворот растру ----------

    @Test fun rotateClockwiseMapsPixels() {
        // 10×3: чорні (0,0), (9,0), (2,2).
        val src = MonoImage.of(10, 3) { x, y -> (x == 0 && y == 0) || (x == 9 && y == 0) || (x == 2 && y == 2) }
        val r = src.rotateClockwise()
        assertEquals(3, r.width)
        assertEquals(10, r.height)
        // (x, y) → (H-1-y, x): верх стає правим краєм.
        val black = (0 until r.height).flatMap { y -> (0 until r.width).filter { x -> r.isBlack(x, y) }.map { it to y } }.toSet()
        assertEquals(setOf(2 to 0, 2 to 9, 0 to 2), black)
    }

    @Test fun fourRotationsGiveSameImage() {
        val src = MonoImage.of(13, 7) { x, y -> (x * 7 + y * 3) % 5 == 0 }
        val back = src.rotateClockwise().rotateClockwise().rotateClockwise().rotateClockwise()
        assertEquals(src.width, back.width)
        assertEquals(src.height, back.height)
        assertTrue(src.data.contentEquals(back.data))
    }

    @Test fun a4LandscapeBecomesPortraitForBrother() {
        val w = 3508
        val h = 2480
        val img = MonoImage(w, h, ByteArray((w + 7) / 8 * h)).let { blank ->
            // Рамка по краю, щоб перевірити кути.
            MonoImage.of(w, h) { x, y -> x == 0 || y == 0 || x == w - 1 || y == h - 1 }.also { assertEquals(blank.width, it.width) }
        }
        val r = img.rotateClockwise()
        assertEquals(2480, r.width)
        assertEquals(3508, r.height)
        assertTrue(r.isBlack(0, 0) && r.isBlack(2479, 0) && r.isBlack(0, 3507) && r.isBlack(2479, 3507))
        assertFalse(r.isBlack(1240, 1754))
    }

    // ---------- Дані цінника: 1С і сайт ----------

    private val site = TagData.Site("LEGO Duplo Потяг із цифрами", null, 649.0, 969.0)
    private val oneC = TagData.OneC("5702016911114", "Конструктор Duplo потяг", "BOC022446", "10954")

    @Test fun oneCWinsForCodesSiteForNameAndPrice() {
        val t = TagData.merge(site, oneC, "4823077601234")
        assertEquals("LEGO Duplo Потяг із цифрами", t.name)
        assertEquals("649", t.price)
        assertEquals("969", t.oldPrice)
        assertEquals("5702016911114", t.barcode)
        assertEquals("BOC022446", t.code)
        assertEquals("10954", t.article)
    }

    @Test fun noArticleOnSiteTakesArticleAndCodeFrom1C() {
        val item = TagData.merge(site.copy(article = ""), oneC).toItem()
        assertEquals("10954", item.article)
        assertEquals("BOC022446", item.code)
    }

    @Test fun siteArticleWhen1CHasNone() {
        val t = TagData.merge(site.copy(article = "10954-S"), oneC.copy(article = ""), null)
        assertEquals("10954-S", t.article)
    }

    @Test fun notOnSiteNameFrom1CWithoutPrice() {
        val t = TagData.merge(null, oneC)
        assertEquals("Конструктор Duplo потяг", t.name)
        assertEquals("", t.price)
        assertEquals("BOC022446", t.code)
    }

    @Test fun no1CDataKeepsSiteAndScannedBarcode() {
        val t = TagData.merge(site.copy(article = "10954"), null, "4823077601234")
        assertEquals("4823077601234", t.barcode)
        assertEquals("10954", t.article)
        assertEquals("", t.code)
    }

    @Test fun oldPriceOnlyWhenHigher() {
        assertEquals("", TagData.merge(site.copy(oldPrice = 649.0), oneC).oldPrice)
        assertEquals("", TagData.merge(site.copy(oldPrice = null), oneC).oldPrice)
    }

    @Test fun noPriceNoPrint() {
        val e = assertThrows(MissingPriceException::class.java) {
            requirePrices(listOf(LabelItem(name = "Є ціна", price = 10.0), LabelItem(name = "Конструктор Duplo потяг")))
        }
        assertEquals("Немає ціни — перевірте товар на сайті: Конструктор Duplo потяг", e.message)
        requirePrices(listOf(LabelItem(name = "Є ціна", price = 10.0)))
    }
}
