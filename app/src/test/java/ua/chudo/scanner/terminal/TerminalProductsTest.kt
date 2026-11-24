package ua.chudo.scanner.terminal

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ua.chudo.scanner.data.LookupResult
import ua.chudo.scanner.data.NetItem
import ua.chudo.scanner.data.NetworkAvailability
import ua.chudo.scanner.data.Via

class TerminalProductsTest {
    private val bc = "2000999154010"
    private val bag = NetItem("Пакет з петлевою ручкою 40×50 см", "BOC022446", "PK-4050", mapOf("22" to 37.0))
    private val av = NetworkAvailability.of(bc, bag, "22", emptyMap(), null)

    private fun products(result: LookupResult, price: Long? = 2_490) = TerminalProducts(
        lookup = { result },
        site = { SiteItem("Пакет із сайту", "SITE-1", price) },
        stock = { if (it == bc) av else null },
    )

    @Test fun `товар з сайту — ціна з сайту, код і артикул з 1С`() = runBlocking {
        val f = products(LookupResult.Single(bc, bc, "paket", Via.Direct)).find(bc)
        val item = (f as Found.Ready).item
        assertEquals(2_490, item.priceKop)
        assertFalse(item.manualPrice)
        assertEquals("BOC022446", item.code)
        assertEquals("PK-4050", item.article)
        assertEquals(bc, item.key)
    }

    @Test fun `лише в 1С — ціна вручну`() = runBlocking {
        val f = products(LookupResult.Only1C(bc, bc, av)).find(bc)
        val item = (f as Found.NeedPrice).item
        assertTrue(item.manualPrice)
        assertEquals("Пакет з петлевою ручкою 40×50 см", item.name)
        assertEquals(0, item.priceKop)
    }

    @Test fun `на сайті без ціни — теж вручну`() = runBlocking {
        val f = products(LookupResult.Single("A-1", null, "x", Via.Direct), price = null).find("A-1")
        assertTrue(f is Found.NeedPrice)
        assertEquals("site:x", (f as Found.NeedPrice).item.key)
    }

    @Test fun `не знайшли`() = runBlocking {
        assertEquals(Found.NotFound("x"), products(LookupResult.NotFound("x", null, emptyList())).find("x"))
    }
}
