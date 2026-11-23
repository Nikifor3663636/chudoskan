package ua.chudo.scanner.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

/** Показ залишків мережі: свій магазин, магазини міста й сортування. */
class NetworkAvailabilityTest {

    private val stores = mapOf(
        "22" to StoreDoc("ЧО 22 Київ (ТЦ Ocean Plaza)", "Київ", "kyiv_mine"),
        "11" to StoreDoc("ЧО 11 Київ (ТЦ Gulliver)", "Київ", null),
        "14" to StoreDoc("ЧО 14 Київ (ТЦ Республіка)", " київ ", null),
        "30" to StoreDoc("ЧО 30 Київ (ТЦ Дрім)", "Київ", null),
        "05" to StoreDoc("ЧО 05 Київ (ТЦ Блокбастер)", "Київ", null),
        "09" to StoreDoc("ЧО 09 Біла Церква (ТЦ Гермес)", "Біла Церква", null),
        "62" to StoreDoc("ЧО 62 Одеса (ТЦ Рів'єра)", "Одеса", "odesa_x"),
    )
    private val at = Date(1_700_000_000_000)

    private fun item(vararg q: Pair<String, Double>) = NetItem("\"Multi truck\" вантажівка", "BOC022446", "39217", mapOf(*q))

    @Test fun ownStoreAndCityStoresSortedByQuantity() {
        val av = NetworkAvailability.of(
            "4823077601234",
            item("22" to 2.0, "11" to 1.0, "14" to 5.0, "30" to 5.0, "05" to 0.0, "09" to 9.0, "62" to 7.0),
            "22", stores, at,
        )
        assertEquals(2.0, av.own)
        assertEquals("Київ", av.cityName)
        // Лише Київ, без свого (22), без нуля (05), без Білої Церкви й Одеси; 5 і 5 — за кодом.
        assertEquals(listOf("14" to 5.0, "30" to 5.0, "11" to 1.0), av.city.map { it.code to it.qty })
        assertEquals("ЧО 14 Київ (ТЦ Республіка)", av.city.first().name)
        assertEquals(at, av.updatedAt)
    }

    @Test fun notInOwnStoreIsZero() {
        val av = NetworkAvailability.of("1", item("11" to 3.0), "22", stores, null)
        assertEquals(0.0, av.own)
        assertEquals(listOf("11"), av.city.map { it.code })
    }

    @Test fun nowhereInCity() {
        val av = NetworkAvailability.of("1", item("62" to 3.0), "22", stores, null)
        assertEquals(0.0, av.own)
        assertTrue(av.city.isEmpty())
        assertEquals("Київ", av.cityName)
    }

    @Test fun unknownOwnStore() {
        val av = NetworkAvailability.of("1", item("22" to 3.0, "11" to 1.0), null, stores, null)
        assertNull(av.own)
        assertNull(av.cityName)
        assertTrue(av.city.isEmpty())
    }

    @Test fun storeWithoutNameShowsCode() {
        val av = NetworkAvailability.of("1", item("77" to 1.0), "22", stores + ("77" to StoreDoc("", "Київ", null)), null)
        assertEquals("ЧО 77", av.city.single().name)
    }

    @Test fun ownCodeFromStoresLinkThenShopCode1C() {
        assertEquals("22", NetworkAvailability.ownCode("kyiv_mine", stores, "99"))
        assertEquals("99", NetworkAvailability.ownCode("other", stores, " 99 "))
        assertNull(NetworkAvailability.ownCode("other", stores, ""))
        assertNull(NetworkAvailability.ownCode("other", stores, null))
    }

    @Test fun code1CQuery() {
        assertTrue(NetworkAvailability.isCode("BOC022446"))
        assertTrue(NetworkAvailability.isCode(" boc022446 "))
        assertTrue(NetworkAvailability.isCode("ODR000123"))
        assertTrue(NetworkAvailability.isCode("DPK999001"))
        assertFalse(NetworkAvailability.isCode("39217"))
        assertFalse(NetworkAvailability.isCode("4823077601234"))
        assertFalse(NetworkAvailability.isCode("BOC02244"))
    }
}
