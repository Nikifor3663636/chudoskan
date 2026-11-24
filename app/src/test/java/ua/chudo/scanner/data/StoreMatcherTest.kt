package ua.chudo.scanner.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Автоприв'язка складів 1С до магазинів сайту: назви складів — зі справжнього звіту, адреси Києва — з сайту. */
class StoreMatcherTest {

    private fun store(header: String) = NetworkStockParser.store(header)!!
    private fun shop(region: String, regionName: String, address: String) = SiteShop("$region:$address", region, regionName, address)

    private val stores = listOf(
        "ЧО 72 Крюківщина (ТЦ Наше Небо)", "ЧО 76 Київ (ТЦ Щасливий Mall)", "ЧО 73 Київ (ТРЦ Retroville)",
        "ЧО 22 Одеса (ТРЦ Gagarin Plaza)", "ЧО 62 Одеса (ТРЦ Острів)", "ЧО 69 Одеса (ТРЦ Рівь'єра)", "ЧО 77 Одеса (ТЦ А1 Акварель)",
        "ЧО 60 Дніпро (ТРЦ Караван)", "ЧО 41 Дніпро (Победа)", "ЧО 78 Дніпро", "ЧО 81 Ужгород",
    ).map(::store)

    private val kyiv = listOf(
        // Справжні адреси з сайту (core/tests/fixtures/shops_kyiv_1233.json).
        shop("kyiv", "Київ", "с. Крюківщина, вул. Балукова, 1е, ТЦ \"Наше НЕБО\", 2 поверх"),
        shop("kyiv", "Київ", "с. Софіївська Борщагівка,ТЦ \"Щасливий Mall\""),
        shop("kyiv", "Київ", "просп. Європейського Союзу (Правди), 47, ТРЦ \"Retroville\", 2 поверх"),
    )
    private val others = listOf(
        shop("odesa", "Одеса", "вул. Генуезька, 5/2, ТРЦ \"Gagarinn Plaza\", друга черга, 3 поверх"),
        shop("odesa", "Одеса", "вул. Семафорна, 4, ТРЦ \"Острів\""),
        shop("odesa", "Одеса", "Фонтанська дорога, 4, ТРЦ \"Рів'єра\""),
        shop("odesa", "Одеса", "вул. Академіка Глушка, 11Ж, ТЦ \"Акварель\""),
        shop("dnipro", "Дніпро", "просп. Олександра Поля, 2, ТРЦ \"Караван\""),
        shop("dnipro", "Дніпро", "вул. Перемоги, 1"),
        shop("dnipro", "Дніпро", "вул. Робоча, 76"),
        shop("uzhgorod", "Ужгород", "вул. Минайська, 16"),
    )

    @Test fun confidentStoresLinkedAutomatically() {
        val m = StoreMatcher.match(stores, kyiv + others).associateBy { it.code }
        fun linked(code: String) = (kyiv + others).firstOrNull { it.shopId == m.getValue(code).shopId }?.address
        assertTrue(linked("72")!!.contains("Наше НЕБО"))
        assertTrue(linked("76")!!.contains("Щасливий Mall")) // місто на сайті інше (Софіївська Борщагівка)
        assertTrue(linked("73")!!.contains("Retroville"))
        assertTrue(linked("22")!!.contains("Gagarinn Plaza")) // опечатка на сайті
        assertTrue(linked("62")!!.contains("Острів"))
        assertTrue(linked("69")!!.contains("Рів'єра"))
        assertTrue(linked("77")!!.contains("Акварель"))
        assertTrue(linked("60")!!.contains("Караван"))
        assertTrue(linked("81")!!.contains("Минайська")) // єдиний магазин міста
    }

    @Test fun doubtfulStoresAskWithBestCandidatesFirst() {
        val m = StoreMatcher.match(stores, kyiv + others).associateBy { it.code }
        // «Победа» / «Перемоги» і «ЧО 78 Дніпро» без назви ТЦ — не вгадуємо, питаємо.
        assertNull(m.getValue("41").shopId)
        assertNull(m.getValue("78").shopId)
        // Кандидати — магазини Дніпра, без уже прив'язаного «Каравану».
        val cands = m.getValue("78").candidates.take(2).map { it.regionName }
        assertEquals(listOf("Дніпро", "Дніпро"), cands)
        assertTrue(m.getValue("78").candidates.none { it.address.contains("Караван") })
    }

    @Test fun alreadyLinkedShopsAreNotReused() {
        val gagarin = others.first()
        val m = StoreMatcher.match(listOf(store("ЧО 22 Одеса (ТРЦ Gagarin Plaza)")), others, alreadyLinked = setOf(gagarin.shopId))
        assertTrue(m.single().shopId != gagarin.shopId)
    }

    @Test fun normalisation() {
        // Латиниця й кирилиця зводяться до одного вигляду.
        assertEquals(StoreMatcher.norm("Gagarin"), StoreMatcher.norm("ГАГАРІН"))
        assertTrue(StoreMatcher.sim(StoreMatcher.norm("Gagarin"), StoreMatcher.norm("Gagarinn")) > 0.85)
        assertEquals(listOf("nashe", "nebo"), StoreMatcher.mallTokens(store("ЧО 72 Крюківщина (ТЦ Наше Небо)")))
        assertEquals(listOf("lomo"), StoreMatcher.mallTokens(store("ЧО 70 Умань LOMO")))
    }
}
