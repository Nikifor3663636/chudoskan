package ua.chudo.scanner.ui.stock

import org.junit.Assert.assertEquals
import org.junit.Test
import ua.chudo.scanner.data.NetItem
import ua.chudo.scanner.data.SbStockRow
import ua.chudo.scanner.data.StoreDoc

class StockOverviewTest {
    private val stores = mapOf(
        "22" to StoreDoc("ЧО 22 · ТРЦ Gagarin Plaza", "Одеса", "s22"),
        "62" to StoreDoc("ЧО 62 · ТЦ Рів’єра", "Одеса", null),
        "41" to StoreDoc("ЧО 41 · Дніпро", "Дніпро", null),
    )

    @Test fun lowStockKeepsOnlyEndingItemsAvailableInCity() {
        val rows = listOf(
            SbStockRow("1", "Багато", q = mapOf("22" to 5.0, "62" to 3.0)),
            SbStockRow("2", "Один", q = mapOf("22" to 1.0, "62" to 2.0)),
            SbStockRow("3", "Немає", q = mapOf("62" to 4.0)),
            SbStockRow("4", "Лише в Дніпрі", q = mapOf("22" to 0.0, "41" to 9.0)),
        )
        val low = lowStock(rows, "22", stores)
        assertEquals(listOf("3", "2"), low.map { it.row.barcode })
        assertEquals(4.0, low.first().city, 0.0)
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
