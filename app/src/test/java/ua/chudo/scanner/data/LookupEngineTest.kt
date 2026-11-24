package ua.chudo.scanner.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import uniffi.chudo_core.ProductSummary
import uniffi.chudo_core.StockStatus
import java.io.IOException

class LookupEngineTest {
    private val bc = "2000999154010"
    private val bag = NetItem("Пакет з петлевою ручкою 40×50 см", "BOC022446", "", mapOf("22" to 37.0, "09" to 5.0))

    private fun product(urlKey: String, article: String? = null, exact: Boolean = false) =
        ProductSummary(1, article, urlKey, urlKey, ".html", StockStatus.IN_STOCK, null, null, exact)

    private fun available(item: NetItem) = NetworkAvailability.of(bc, item, "22", emptyMap(), null)

    /** Сайт: відповіді за запитом; невідомий запит — порожньо; [down] — сайт недоступний. */
    private class FakeSite(val answers: Map<String, List<ProductSummary>> = emptyMap(), val down: Boolean = false) : SiteSearch {
        val asked = mutableListOf<String>()
        override suspend fun search(query: String): List<ProductSummary> {
            synchronized(asked) { asked += query }
            if (down) throw IOException("Сайт недоступний")
            return answers[query].orEmpty()
        }
    }

    private class FakeUpc(val hit: UpcHit? = null) : UpcSource {
        var calls = 0
        override suspend fun find(barcode: String): UpcHit? { calls++; return hit }
    }

    private fun stock(item: NetItem?) = Stock1C { if (item == null) null else available(item) }

    @Test fun `є в 1С, немає на сайті — картка «Товар з 1С», UPC не викликаємо`() = runBlocking {
        val site = FakeSite()
        val upc = FakeUpc(UpcHit(listOf("123"), "Bag"))
        val r = LookupEngine(site, upc).lookup(bc, bc, stock(bag))
        r as LookupResult.Only1C
        assertEquals(bc, r.barcode)
        assertTrue(r.stock.item.name.startsWith("Пакет з петлевою ручкою"))
        assertEquals("BOC022446", r.stock.item.code)
        assertEquals(37.0, r.stock.own)
        assertEquals(0, upc.calls)
    }

    @Test fun `артикул з 1С знайдено на сайті — Via Stock1C`() = runBlocking {
        val withArticle = bag.copy(article = "PK-4050")
        val site = FakeSite(mapOf("PK-4050" to listOf(product("paket-4050", "PK-4050", exact = true))))
        val upc = FakeUpc()
        val r = LookupEngine(site, upc).lookup(bc, bc, stock(withArticle))
        r as LookupResult.Single
        assertEquals("paket-4050", r.urlKey)
        assertEquals(Via.Stock1C("PK-4050"), r.via)
        assertEquals(0, upc.calls)
    }

    @Test fun `сайт знайшов за штрихкодом — Direct`() = runBlocking {
        val site = FakeSite(mapOf(bc to listOf(product("paket"))))
        val r = LookupEngine(site, FakeUpc()).lookup(bc, bc, stock(bag))
        assertEquals(Via.Direct, (r as LookupResult.Single).via)
    }

    @Test fun `помилка сайту, а 1С знає товар — Only1C`() = runBlocking {
        val upc = FakeUpc()
        val r = LookupEngine(FakeSite(down = true), upc).lookup(bc, bc, stock(bag))
        assertTrue(r is LookupResult.Only1C)
        assertEquals(0, upc.calls)
    }

    @Test fun `помилка сайту і немає в 1С — помилка`() = runBlocking {
        try {
            LookupEngine(FakeSite(down = true), FakeUpc()).lookup(bc, bc, stock(null))
            fail("мала бути помилка")
        } catch (e: IOException) {
            assertEquals("Сайт недоступний", e.message)
        }
    }

    @Test fun `помилка 1С не валить пошук — далі UPC`() = runBlocking {
        val site = FakeSite(mapOf("A-1" to listOf(product("by-upc"))))
        val upc = FakeUpc(UpcHit(listOf("A-1"), null))
        val broken = Stock1C { throw IOException("Firestore недоступний") }
        val r = LookupEngine(site, upc).lookup(bc, bc, broken)
        r as LookupResult.Single
        assertEquals(Via.UpcArticle("A-1"), r.via)
        assertEquals(1, upc.calls)
    }

    @Test fun `немає ніде — NotFound з артикулами UPC`() = runBlocking {
        val r = LookupEngine(FakeSite(), FakeUpc(UpcHit(listOf("X1", "X2"), null))).lookup(bc, bc, stock(null))
        r as LookupResult.NotFound
        assertEquals(listOf("X1", "X2"), r.triedArticles)
    }

    @Test fun `прив'язаний штрихкод — без сайту, 1С і UPC`() = runBlocking {
        val site = FakeSite()
        val upc = FakeUpc()
        var asked1C = false
        val r = LookupEngine(site, upc, remembered = { if (it == bc) "saved" else null })
            .lookup(bc, bc, Stock1C { asked1C = true; null })
        assertEquals(LookupResult.Single(bc, bc, "saved", Via.Remembered), r)
        assertTrue(site.asked.isEmpty())
        assertEquals(0, upc.calls)
        assertTrue(!asked1C)
    }

    @Test fun `текстовий запит — без 1С і UPC`() = runBlocking {
        val upc = FakeUpc()
        val r = LookupEngine(FakeSite(), upc).lookup("пакет", null, stock(bag))
        assertEquals(LookupResult.NotFound("пакет", null, emptyList()), r)
        assertEquals(0, upc.calls)
    }
}
