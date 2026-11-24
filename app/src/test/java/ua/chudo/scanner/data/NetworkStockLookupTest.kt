package ua.chudo.scanner.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Date

class NetworkStockLookupTest {
    private val bag = NetItem("Пакет з петлевою ручкою 40×50 см", "BOC022446", "PK-4050", mapOf("22" to 37.0, "23" to 4.0, "09" to 5.0))
    private val ball = NetItem("М'яч гумовий 22 см", "BUK000123", "MB-22", mapOf("22" to 2.0))
    private val kite = NetItem("Повітряний змій", "DPK000777", "", mapOf("23" to 8.0))
    private val items = mapOf("2000999154010" to bag, "4820000000017" to ball, "4820000000024" to kite)

    private val stores = mapOf(
        "22" to StoreDoc("ЧО 22 Одеса (ТРЦ Gagarinn Plaza)", "Одеса", "shop-22"),
        "23" to StoreDoc("ЧО 23 Одеса (ТРЦ Острів)", "Одеса", null),
        "09" to StoreDoc("ЧО 09 Київ", "Київ", null),
    )

    /** Пакет у «Firestore»: дві частини (щоб перевірити склейку), змінені — у частині 0. Стара гілка заборонена. */
    private inner class FakeStore(version: String = "v1", changed: List<String> = listOf("2000999154010", "4820000000024")) : StockStore {
        var metaReads = 0
        var partReads = 0
        var storeReads = 0
        val updated = Date(1_700_000_000_000)
        var meta = StockStore.Meta(updated, null, version, 2)
        val parts: List<ByteArray>
        val changedBytes = StockPack.gzipLines(changed)

        init {
            val f = File.createTempFile("pack", ".gz").apply { deleteOnExit() }
            StockSnapshot(f).write(items)
            val all = f.readBytes()
            parts = listOf(all.copyOfRange(0, all.size / 2), all.copyOfRange(all.size / 2, all.size))
        }

        override suspend fun meta(): StockStore.Meta { metaReads++; return meta }
        override suspend fun packPart(version: String, index: Int): StockStore.Part {
            partReads++
            return StockStore.Part(parts[index], if (index == 0) changedBytes else null)
        }
        override suspend fun stores(): Map<String, StoreDoc> { storeReads++; return this@NetworkStockLookupTest.stores }
        override suspend fun shopCode1C(shopId: String): String? = null

        override suspend fun legacyItem(barcode: String) = error("stock/{bc} не читаємо, коли є пакет")
        override suspend fun legacyChanged(after: Date, limit: Long) = error("stock/{bc} не читаємо, коли є пакет")
        override suspend fun legacyWhere(field: String, value: String, limit: Long) = error("stock/{bc} не читаємо, коли є пакет")
        override suspend fun legacyStoreItems(code: String, limit: Long) = error("stock/{bc} не читаємо, коли є пакет")
    }

    private fun dir(): File = Files.createTempDirectory("stockpack").toFile().apply { deleteOnExit() }

    @Test fun `штрихкод — з пакета, свій магазин і магазини міста`() = runBlocking {
        val store = FakeStore()
        val net = NetworkStockLookup(store, dir())
        val a = net.byBarcode("shop-22", "2000999154010")!!
        assertEquals("Пакет з петлевою ручкою 40×50 см", a.item.name)
        assertEquals("22", a.ownCode)
        assertEquals(37.0, a.own)
        assertEquals(listOf("23"), a.city.map { it.code })
        assertEquals(store.updated, a.updatedAt)
        assertNull(net.byBarcode("shop-22", "4800000000000"))
    }

    @Test fun `повторні скани — без читань Firestore`() = runBlocking {
        val store = FakeStore()
        val net = NetworkStockLookup(store, dir())
        net.byBarcode("shop-22", "2000999154010")
        val reads = Triple(store.metaReads, store.partReads, store.storeReads)
        assertEquals(Triple(1, 2, 1), reads)
        repeat(20) { net.byBarcode("shop-22", "4820000000017") }
        assertEquals(reads, Triple(store.metaReads, store.partReads, store.storeReads))
    }

    @Test fun `пакет з файлу — після перезапуску частини не качаємо`() = runBlocking {
        val d = dir()
        NetworkStockLookup(FakeStore(), d).byBarcode("shop-22", "2000999154010")
        val again = FakeStore()
        assertEquals(37.0, NetworkStockLookup(again, d).byBarcode("shop-22", "2000999154010")!!.own)
        assertEquals(0, again.partReads)
    }

    @Test fun `код 1С BOC і артикул`() = runBlocking {
        val net = NetworkStockLookup(FakeStore(), dir())
        assertEquals(listOf("2000999154010"), net.byCode("shop-22", "boc022446").map { it.barcode })
        assertEquals(listOf("4820000000017"), net.byArticle("shop-22", " MB-22 ").map { it.barcode })
        assertTrue(net.byArticle("shop-22", "немає").isEmpty())
    }

    @Test fun `що в магазині ЧО NN — за спаданням кількості`() = runBlocking {
        val net = NetworkStockLookup(FakeStore(), dir())
        assertEquals(listOf("4820000000024" to 8.0, "2000999154010" to 4.0), net.storeItems("23").map { it.barcode to it.qty })
        assertEquals(listOf("2000999154010"), net.storeItems("22", limit = 1).map { it.barcode })
    }

    @Test fun `змінені — лише ті, що є у своєму магазині`() = runBlocking {
        val net = NetworkStockLookup(FakeStore(), dir())
        assertEquals(listOf("2000999154010"), net.changedSinceLastImport("shop-22").map { it.barcode })
    }

    @Test fun `старий пакет уже видалено — одразу перечитуємо meta і беремо новий`() = runBlocking {
        val store = object : StockStore by FakeStore("v2") {
            var meta = StockStore.Meta(null, null, "v1", 2)
            override suspend fun meta() = meta.also { meta = it.copy(pack = "v2") }
            override suspend fun packPart(version: String, index: Int) =
                if (version == "v1") StockStore.Part(null, null) else FakeStore("v2").packPart(version, index)
        }
        assertEquals(37.0, NetworkStockLookup(store, dir()).byBarcode("shop-22", "2000999154010")!!.own)
    }

    @Test fun `нова версія пакета — через 30 хв, старі файли видалено`() = runBlocking {
        var t = 0L
        val d = dir()
        val store = FakeStore()
        val net = NetworkStockLookup(store, d) { t }
        net.byBarcode("shop-22", "2000999154010")
        store.meta = store.meta.copy(pack = "v2")
        t = 29 * 60_000L
        net.byBarcode("shop-22", "2000999154010")
        assertEquals(2, store.partReads)
        t = 31 * 60_000L
        net.byBarcode("shop-22", "2000999154010")
        assertEquals(4, store.partReads)
        assertTrue(d.list()!!.all { it.startsWith("stockpack_v2.") })
    }
}
