package ua.chudo.scanner.print

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Реестр (CIDR, id без дублей) и автовыбор принтера (п. 6): все шаги цепочки на подделках. */
class RegistryAndPickTest {

    // ---------- CIDR ----------

    @Test fun cidrHostsSkipNetworkAndBroadcast() {
        val h = Cidr.hosts("192.0.2.0/24")
        assertEquals(254, h.size)
        assertEquals("192.0.2.1", h.first())
        assertEquals("192.0.2.254", h.last())
        assertEquals(listOf("198.51.100.1", "198.51.100.2"), Cidr.hosts("198.51.100.0/30"))
        assertEquals(listOf("10.0.0.7"), Cidr.hosts("10.0.0.7/32"))
    }

    @Test fun cidrNormalizesAndRejects() {
        assertEquals("192.0.2.0/24", Cidr.normalize("192.0.2.250/24"))
        assertEquals("198.51.100.0/24", Cidr.slash24("198.51.100.31"))
        assertTrue(Cidr.contains("192.0.2.0/24", "192.0.2.250"))
        assertTrue(!Cidr.contains("192.0.2.0/24", "198.51.100.31"))
        assertEquals(emptyList<String>(), Cidr.hosts("10.0.0.0/8")) // слишком большая сеть — не сканируем
        assertEquals(emptyList<String>(), Cidr.hosts("300.1.1.0/24"))
        assertEquals(emptyList<String>(), Cidr.hosts("мусор"))
        assertNull(Cidr.normalize("192.0.2.0"))
    }

    // ---------- Id в реестре ----------

    @Test fun printerIdPrefersMacAndIgnoresIp() {
        assertEquals("mac-00005e0053aa", PrinterIds.of("00:00:5E:00:53:AA", "Godex G500"))
        // Без MAC — модель и имя: «series» и регистр не плодят дубли.
        assertEquals(PrinterIds.of(null, "Brother MFC-L2700DW series"), PrinterIds.of(null, "brother mfc-l2700dw"))
        assertNotEquals(PrinterIds.of(null, "Brother MFC-L2700DW"), PrinterIds.of(null, "Brother HL-L2360D series"))
        // Нулевой MAC — не MAC.
        assertTrue(PrinterIds.of("00:00:00:00:00:00", "Godex G500").startsWith("m-"))
    }

    // ---------- Автовыбор и цепочка запасных путей ----------

    private val godex = PrinterRecord("g", "Godex G500", LabelKind.STICKY, ip = "192.0.2.10")
    private val mfc = PrinterRecord("mfc", "Brother MFC-L2700DW", LabelKind.PLAIN, ip = "192.0.2.250")
    private val hl = PrinterRecord("hl", "Brother HL-L2360DN", LabelKind.PLAIN, ip = "198.51.100.31")
    private val pc = PrinterRecord("pc", "Godex G500", LabelKind.STICKY, transport = Link.AGENT, agentId = "pc-kasa", printerName = "Godex G500")

    private class Fake(
        var reg: List<PrinterRecord> = emptyList(),
        /** id → задержка ответа, мс; нет в карте — не отвечает. */
        var alive: Map<String, Long> = emptyMap(),
        var usb: PrinterRecord? = null,
        var found: PrinterRecord? = null,
        var agent: PrinterRecord? = null,
        var preference: String = "auto",
        var extra: List<PrinterRecord> = emptyList(),
    ) : PrinterSource {
        override suspend fun preferred(kind: LabelKind) = preference
        override suspend fun clearPreferred(kind: LabelKind) { preference = "auto" }
        override suspend fun candidates(kind: LabelKind) = (reg + extra + listOfNotNull(usb, agent)).filter { it.kind == kind }
        val calls = mutableListOf<String>()
        override fun registry() = reg
        override suspend fun alive(r: PrinterRecord): Boolean {
            val d = alive[r.id] ?: return false
            delay(d)
            return true
        }
        override suspend fun usb(kind: LabelKind) = usb?.takeIf { it.kind == kind }
        override suspend fun discover(kind: LabelKind): PrinterRecord? { calls += "discover"; return found?.takeIf { it.kind == kind } }
        override suspend fun agent(kind: LabelKind): PrinterRecord? { calls += "agent"; return agent?.takeIf { it.kind == kind } }
    }

    @Test fun picksByKind() = runBlocking {
        val r = PrinterResolver(Fake(reg = listOf(godex, mfc), alive = mapOf("g" to 0, "mfc" to 0)))
        assertEquals("g", r.resolve(LabelKind.STICKY)!!.record.id)
        assertEquals("mfc", r.resolve(LabelKind.PLAIN)!!.record.id)
    }

    @Test fun preferredWinsOverFasterPrinterAndCache() = runBlocking {
        val src = Fake(reg = listOf(mfc, hl), alive = mapOf("mfc" to 30, "hl" to 0))
        val resolver = PrinterResolver(src)
        assertEquals(hl, resolver.resolve(LabelKind.PLAIN)!!.record)
        src.preference = mfc.id
        assertEquals(Pick(mfc, Via.PREFERRED), resolver.resolve(LabelKind.PLAIN))
    }

    @Test fun deadPreferredRequiresConfirmationWithoutChangingPreference() = runBlocking {
        val src = Fake(reg = listOf(mfc, hl), alive = mapOf("hl" to 0), preference = mfc.id)
        val resolver = PrinterResolver(src)
        repeat(2) {
            assertEquals(Pick(mfc, Via.PREFERRED, true, hl), resolver.resolve(LabelKind.PLAIN))
            assertEquals(mfc.id, src.preference)
            assertNull(resolver.chosen(LabelKind.PLAIN))
        }
    }

    @Test fun removedPreferredResetsToAuto() = runBlocking {
        val src = Fake(reg = listOf(hl), alive = mapOf("hl" to 0), preference = mfc.id)
        assertEquals(Pick(hl, Via.REGISTRY), PrinterResolver(src).resolve(LabelKind.PLAIN))
        assertEquals("auto", src.preference)
    }

    @Test fun preferredAgentOutsideRegistryAndWithoutAlternative() = runBlocking {
        val src = Fake(extra = listOf(pc), preference = pc.id)
        assertEquals(Pick(pc, Via.PREFERRED, true), PrinterResolver(src).resolve(LabelKind.STICKY))
        src.alive = mapOf(pc.id to 0)
        assertEquals(Pick(pc, Via.PREFERRED), PrinterResolver(src).resolve(LabelKind.STICKY))
    }

    @Test fun preferredProbeTimesOut() = runBlocking {
        val src = Fake(reg = listOf(mfc, hl), alive = mapOf("mfc" to 60_000, "hl" to 0), preference = mfc.id)
        assertEquals(Pick(mfc, Via.PREFERRED, true, hl), PrinterResolver(src).resolve(LabelKind.PLAIN))
    }

    @Test fun firstToAnswerWinsAndIsCached() = runBlocking {
        val src = Fake(reg = listOf(mfc, hl), alive = mapOf("mfc" to 300, "hl" to 10))
        val r = PrinterResolver(src)
        assertEquals(Pick(hl, Via.REGISTRY), r.resolve(LabelKind.PLAIN))
        // Повтор в той же сессии — из кеша, даже если другой стал быстрее.
        src.alive = mapOf("mfc" to 0, "hl" to 50)
        assertEquals(Pick(hl, Via.CACHE), r.resolve(LabelKind.PLAIN))
    }

    @Test fun deadCachedFallsBackToOther() = runBlocking {
        val src = Fake(reg = listOf(mfc, hl), alive = mapOf("hl" to 0))
        val r = PrinterResolver(src)
        assertEquals("hl", r.resolve(LabelKind.PLAIN)!!.record.id)
        src.alive = mapOf("mfc" to 0) // HL выключили
        assertEquals(Pick(mfc, Via.REGISTRY), r.resolve(LabelKind.PLAIN))
    }

    @Test fun nobodyAnswersThenDiscovery() = runBlocking {
        val moved = mfc.copy(ip = "192.0.2.77") // тот же id, новый IP
        val src = Fake(reg = listOf(mfc), found = moved)
        assertEquals(Pick(moved, Via.DISCOVERY), PrinterResolver(src).resolve(LabelKind.PLAIN))
        assertEquals(listOf("discover"), src.calls)
    }

    @Test fun notFoundOnNetworkThenAgent() = runBlocking {
        val src = Fake(reg = listOf(godex), agent = pc)
        assertEquals(Pick(pc, Via.AGENT), PrinterResolver(src).resolve(LabelKind.STICKY))
        assertEquals(listOf("discover", "agent"), src.calls)
    }

    @Test fun usbBeforeNetworkSearch() = runBlocking {
        val usb = PrinterRecord("usb-195f-0001", "Godex G500", LabelKind.STICKY, transport = Link.USB, usbVendor = 0x195F, usbProduct = 1)
        val src = Fake(usb = usb, agent = pc)
        assertEquals(Pick(usb, Via.USB), PrinterResolver(src).resolve(LabelKind.STICKY))
        assertEquals(emptyList<String>(), src.calls)
    }

    @Test fun nothingAnywhereIsNull() = runBlocking {
        val r = PrinterResolver(Fake(reg = listOf(mfc), agent = pc))
        assertNull(r.resolve(LabelKind.PLAIN)) // агент есть, но у него только принтер этикеток
    }

    @Test fun forgetAfterFailedPrint() = runBlocking {
        val src = Fake(reg = listOf(mfc, hl), alive = mapOf("mfc" to 0, "hl" to 100))
        val r = PrinterResolver(src)
        assertEquals("mfc", r.resolve(LabelKind.PLAIN)!!.record.id)
        r.forget(LabelKind.PLAIN)
        assertNull(r.chosen(LabelKind.PLAIN))
    }
}
