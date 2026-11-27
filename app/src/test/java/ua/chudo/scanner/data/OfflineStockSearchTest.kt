package ua.chudo.scanner.data

import org.junit.Assert.assertEquals
import org.junit.Test

class OfflineStockSearchTest {
    private val desk = OfflineStockItem("6903317293087", "Парта М 4428-8 рожева", "4428-8", "BOC229105", 2.0)
    private val accessory = OfflineStockItem("6903317293088", "Килимок для парти 4428-8", "", "", 1.0)
    private val catalog = OfflineStockSnapshot("22", 0, 0, listOf(accessory, desk))

    @Test
    fun findsGoodsByNameArticleCodeOrBarcode() {
        assertEquals(listOf(desk), catalog.search("РОЖЕВА  парта"))
        assertEquals(listOf(desk, accessory), catalog.search("4428 8"))
        assertEquals(listOf(desk), catalog.search("boc229105"))
        assertEquals(listOf(desk), catalog.search("6903317293087"))
        assertEquals(listOf(desk, accessory), catalog.search("4428-8"))
        assertEquals(emptyList<OfflineStockItem>(), catalog.search("  "))
    }
}
