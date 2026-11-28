package ua.chudo.scanner.ui.stock

import org.junit.Assert.assertEquals
import org.junit.Test
import ua.chudo.scanner.data.NetItem
import ua.chudo.scanner.data.StoreDoc

class StockOverviewTest {
    private val stores = mapOf(
        "22" to StoreDoc("ЧО 22 · ТРЦ Gagarin Plaza", "Одеса", "s22"),
        "62" to StoreDoc("ЧО 62 · ТЦ Рів’єра", "Одеса", null),
        "41" to StoreDoc("ЧО 41 · Дніпро", "Дніпро", null),
    )

    @Test fun storeOrderPutsOwnThenCityThenRest() {
        val all = stores + mapOf("7" to StoreDoc("ЧО 7", "Одеса", null), "3" to StoreDoc("ЧО 3", "Київ", null))
        assertEquals(listOf("22", "7", "62", "41", "3"), storeOrder(all, "22").map { it.first })
    }

    @Test fun storeOrderWithoutOwnSortsByCityAndNumber() {
        assertEquals(listOf("41", "22", "62"), storeOrder(stores, null).map { it.first })
    }

    @Test fun networkCitiesPutOwnStoreAndCityFirst() {
        val item = NetItem("LEGO", "BOC1", "10954", mapOf("62" to 5.0, "41" to 9.0))
        val cities = networkCities(item, stores, "22", "ЧО 22 · ТРЦ Gagarin Plaza")
        assertEquals(listOf("Одеса", "Дніпро"), cities.map { it.name })
        assertEquals(listOf("22", "62"), cities.first().rows.map { it.code })
        assertEquals(true, cities.first().rows.first().own)
        assertEquals(5.0, cities.first().total, 0.0)
    }

    @Test fun thousandsUseNonBreakingSpace() {
        assertEquals("21 870", groupThousands(21870))
    }
}
