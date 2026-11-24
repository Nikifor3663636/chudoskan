package ua.chudo.scanner.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.kxml2.io.KXmlParser
import java.io.ByteArrayInputStream

/** Залишки мережі зі звіту 1С: розбір, різниця з попереднім файлом, знімок на телефоні. */
class NetworkStockTest {

    @get:Rule val tmp = TemporaryFolder()

    @Before fun jvmParser() {
        Xlsx.newParser = { KXmlParser() }
    }

    private val stores = listOf("ЧО 09 Біла Церква (ТЦ Гермес)", "ЧО 11 Київ (ТЦ Gulliver)", "ЧО 22 Київ (ТЦ Ocean Plaza)")

    /** Зменшена копія реального звіту: 3 склади, 20 товарів, «Итого», група без штрихкоду. */
    private val products: List<Triple<String, List<Any?>, List<Any?>>> = buildList {
        add(Triple("\"Multi truck\" вантажівка, BOC022446, 39217", listOf(4823077601234L, 4823077601234L, 4823077601234L), listOf(1, null, 3)))
        // Коми в назві й порожній артикул.
        add(Triple("Лялька, з аксесуарами, рожева, BUK100200, ", listOf("4823077600008", "4823077600008", "4823077600008"), listOf(null, 2, null)))
        add(Triple("Пазл 1000 елементів, ODR000123, 5-0023", listOf(null, null, "4823077600015"), listOf(null, null, 7)))
        add(Triple("Конструктор «Місто», DPK999001, 10698", listOf("5702017417912", null, null), listOf(4.0, null, 1)))
        // Штрихкод числом у форматі Excel.
        add(Triple("М'яч, BOC000777, ", listOf(4.823077601357E12, null, null), listOf(2, 2, 2)))
        // Група без штрихкоду — пропуск.
        add(Triple("Іграшки для малюків", listOf(null, null, null), listOf(10, 20, 30)))
        // Є штрихкод, але ніде немає — у Firestore не пишемо.
        add(Triple("Розпродано, BOC000001, 1", listOf("4820000000999", null, null), listOf(null, null, null)))
        for (i in 1..15) {
            val bc = "48200000001%02d".format(i)
            add(Triple("Товар $i, тест, BOC1000%02d, A-$i".format(i), listOf(bc, bc, bc), listOf(i % 3, i, null)))
        }
    }

    private fun parse(rows: List<List<Any?>>): NetworkStock {
        val bytes = Report1C.xlsx(rows)
        return NetworkStockParser.parse({ ByteArrayInputStream(bytes) }, bytes.size.toLong())
    }

    @Test fun reducedRealReport() {
        val progress = mutableListOf<Float>()
        val bytes = Report1C.xlsx(Report1C.report(stores, products))
        val s = NetworkStockParser.parse({ ByteArrayInputStream(bytes) }, bytes.size.toLong()) { progress += it }

        assertEquals(listOf("09", "11", "22"), s.stores.map { it.code })
        assertEquals(listOf("Біла Церква", "Київ", "Київ"), s.stores.map { it.city })
        assertEquals("ЧО 22 Київ (ТЦ Ocean Plaza)", s.stores[2].name)
        assertEquals(1, s.noBarcode)
        // 21 рядок зі штрихкодом; «Розпродано» ніде немає — 20 товарів.
        assertEquals(20, s.items.size)

        assertEquals(NetItem("\"Multi truck\" вантажівка", "BOC022446", "39217", mapOf("09" to 1.0, "22" to 3.0)), s.items["4823077601234"])
        assertEquals(NetItem("Лялька, з аксесуарами, рожева", "BUK100200", "", mapOf("11" to 2.0)), s.items["4823077600008"])
        assertEquals(NetItem("Пазл 1000 елементів", "ODR000123", "5-0023", mapOf("22" to 7.0)), s.items["4823077600015"])
        assertEquals(NetItem("Конструктор «Місто»", "DPK999001", "10698", mapOf("09" to 4.0, "22" to 1.0)), s.items["5702017417912"])
        assertEquals(mapOf("09" to 2.0, "11" to 2.0, "22" to 2.0), s.items["4823077601357"]?.q)
        assertEquals("М'яч", s.items["4823077601357"]?.name)
        assertNull(s.items["4820000000999"])
        // «Товар 3, тест»: 3 % 3 = 0 у ЧО 09 — немає; ЧО 11 — 3; ЧО 22 — порожньо.
        assertEquals(NetItem("Товар 3, тест", "BOC100003", "A-3", mapOf("11" to 3.0)), s.items["4820000000103"])
        assertTrue(s.items.values.none { it.q.values.any { v -> v <= 0 } })
        // «Итого» не став товаром.
        assertTrue(s.items.values.none { it.name.startsWith("Итого") })

        assertEquals(1f, progress.last())
        assertTrue(progress.zipWithNext().all { (a, b) -> b >= a })
    }

    @Test fun separateCodeAndArticleColumns() {
        val rows = listOf(
            listOf(null, "Номенклатура", "Код", "Артикул", "ЧО 22 Київ", null),
            listOf<Any?>(null, null, null, null, "Штрих код", "Конечный остаток"),
            listOf<Any?>(7, "Машинка, червона", "BOC000123", "", "4820000000017", 5),
        )
        val s = parse(rows)
        assertEquals(NetItem("Машинка, червона", "BOC000123", "", mapOf("22" to 5.0)), s.items["4820000000017"])
    }

    @Test fun nameWithoutTrailingArticle() {
        val s = parse(Report1C.report(listOf("ЧО 22 Київ"), listOf(Triple("Кубик, BOC123456", listOf("4820000000024"), listOf(1)))))
        assertEquals(NetItem("Кубик", "BOC123456", "", mapOf("22" to 1.0)), s.items["4820000000024"])
    }

    @Test fun sameBarcodeInTwoRowsIsSummed() {
        val s = parse(Report1C.report(listOf("ЧО 22 Київ"), listOf(
            Triple("Кубик, BOC123456, 1", listOf("4820000000024"), listOf(1)),
            Triple("Кубик, BOC123456, 1", listOf("4820000000024"), listOf(2)),
        )))
        assertEquals(mapOf("22" to 3.0), s.items["4820000000024"]?.q)
    }

    @Test(expected = StockFileException::class) fun notANetworkReport() {
        parse(listOf(listOf("Артикул", "Кількість"), listOf("1", "2")))
    }

    @Test fun storeHeader() {
        assertEquals(NetStore("62", "ЧО 62 Одеса (ТЦ Рів'єра)", "Одеса"), NetworkStockParser.store("ЧО 62 Одеса (ТЦ Рів'єра)"))
        assertEquals("05", NetworkStockParser.store("чо05 Львів")?.code)
        assertNull(NetworkStockParser.store("Чорноморськ"))
        assertNull(NetworkStockParser.store("Номенклатура"))
        // Назви складів зі справжнього звіту.
        assertEquals("Малехів", NetworkStockParser.store("ЧО 80 Малехів ТЦ Обрій")?.city)
        assertEquals("Умань", NetworkStockParser.store("ЧО 70 Умань LOMO")?.city)
        assertEquals("Кам'янець-Подільський", NetworkStockParser.store("ЧО 33 Кам'янець-Подільський")?.city)
        assertEquals("Біла Церква", NetworkStockParser.store("ЧО 09 Біла Церква (ТЦ Гермес)")?.city)
        assertEquals("Вінниця", NetworkStockParser.store("ЧО 63 Вінниця (ТРЦ S)mall)")?.city)
    }

    @Test fun byteOrderMarkSkipped() {
        val withBom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "<a/>".toByteArray()
        assertEquals("<a/>", Xlsx.skipBom(java.io.ByteArrayInputStream(withBom)).readBytes().decodeToString())
        assertEquals("<a/>", Xlsx.skipBom(java.io.ByteArrayInputStream("<a/>".toByteArray())).readBytes().decodeToString())
        assertEquals("", Xlsx.skipBom(java.io.ByteArrayInputStream(ByteArray(0))).readBytes().decodeToString())
    }

    // ---------- Різниця з попереднім файлом ----------

    private fun item(vararg q: Pair<String, Double>) = NetItem("Товар", "BOC000001", "1", mapOf(*q))

    private fun stock(vararg items: Pair<String, NetItem>) =
        NetworkStock(listOf(NetStore("09", "ЧО 09", ""), NetStore("22", "ЧО 22", "")), mapOf(*items), 0)

    @Test fun sameFileTwiceChangesNothing() {
        val s = parse(Report1C.report(stores, products))
        val first = StockDiff.plan(null, s)
        assertTrue(first.first)
        assertEquals(20, first.writes)

        val again = StockDiff.plan(first.items, parse(Report1C.report(stores, products)))
        assertFalse(again.first)
        assertEquals(0, again.changed.size)
        assertEquals(0, again.removed.size)
        assertEquals(0, again.writes)
    }

    @Test fun onlyChangedAndRemoved() {
        val old = mapOf("1" to item("22" to 1.0), "2" to item("22" to 2.0), "3" to item("09" to 3.0))
        val plan = StockDiff.plan(old, stock("1" to item("22" to 1.0), "2" to item("22" to 5.0), "4" to item("09" to 1.0)))
        assertEquals(listOf("2", "4"), plan.changed)
        assertEquals(listOf("3"), plan.removed)
        assertEquals(emptyList<String>(), plan.keptStores)
    }

    @Test fun storeWithZeroItemsIsNotWipedOut() {
        // У ЧО 09 раніше були товари, у новому файлі — жодного: лишаємо як було.
        val old = mapOf("1" to item("09" to 2.0, "22" to 1.0), "2" to item("09" to 4.0))
        val plan = StockDiff.plan(old, stock("1" to item("22" to 3.0)))
        assertEquals(listOf("09"), plan.keptStores)
        assertEquals(mapOf("09" to 2.0, "22" to 3.0), plan.items["1"]?.q)
        assertEquals(mapOf("09" to 4.0), plan.items["2"]?.q)
        assertEquals(listOf("1"), plan.changed)
        assertEquals(emptyList<String>(), plan.removed)
    }

    @Test fun lessThanHalfNeedsConfirmation() {
        val old = (1..10).associate { "$it" to item("22" to 1.0) }
        assertTrue(StockDiff.plan(old, stock(*(1..4).map { "$it" to item("22" to 1.0) }.toTypedArray())).shrunk)
        assertFalse(StockDiff.plan(old, stock(*(1..5).map { "$it" to item("22" to 1.0) }.toTypedArray())).shrunk)
    }

    // ---------- Знімок на телефоні ----------

    @Test fun snapshotRoundTrip() {
        val snap = StockSnapshot(tmp.newFile(StockSnapshot.NAME).also { it.delete() })
        assertNull(snap.read())
        val items = mapOf(
            "4823077601234" to NetItem("\"Multi truck\" \\ вантажівка\n", "BOC022446", "39217", mapOf("09" to 1.0, "22" to 2.5)),
            "1" to NetItem("Без залишків", "", "", emptyMap()),
        )
        snap.write(items)
        assertEquals(items, snap.read())
        // Повтор того самого файлу після знімка — нічого писати.
        assertEquals(0, StockDiff.plan(snap.read(), NetworkStock(emptyList(), items, 0)).writes)
    }

    @Test fun brokenSnapshotIsFirstUpload() {
        val f = tmp.newFile(StockSnapshot.NAME)
        f.writeText("не gzip")
        assertNull(StockSnapshot(f).read())
    }

    @Test fun storesWrittenOnlyWhenNewOrChanged() {
        val s = listOf(NetStore("09", "ЧО 09 Біла Церква", "Біла Церква"), NetStore("22", "ЧО 22 Київ", "Київ"))
        val known = mapOf("09" to StoreDoc("ЧО 09 Біла Церква", "Біла Церква", "kyiv_1"), "22" to StoreDoc("ЧО 22 Київ (старий)", "Київ", null))
        assertEquals(listOf("22"), NetworkStockRepository.storesToWrite(s, known).map { it.code })
        assertEquals(emptyList<NetStore>(), NetworkStockRepository.storesToWrite(s.take(1), known))
    }

    @Test fun `звіт без коду 1С і артикулу — попередження, але завантажити можна`() {
        val noCodes = products.map { (name, bc, q) -> Triple(name.substringBefore(", BOC").substringBefore(", BUK").substringBefore(", ODR").substringBefore(", DPK"), bc, q) }
        val s = parse(Report1C.report(stores, noCodes))
        assertTrue(s.items.isNotEmpty())
        assertTrue(s.missingCodes)
        val plan = StockDiff.plan(null, s)
        assertEquals(listOf(StockImportWarnings.NO_CODES), StockImportWarnings.of(s, plan))

        val full = parse(Report1C.report(stores, products))
        assertFalse(full.missingCodes)
        assertTrue(StockImportWarnings.of(full, StockDiff.plan(null, full)).isEmpty())
    }
}
