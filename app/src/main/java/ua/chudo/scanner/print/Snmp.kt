package ua.chudo.scanner.print

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Модель принтера по SNMP v1 (community `public`): hrDeviceDescr, иначе sysDescr.
 * Сетевые принтеры почти всех марок отвечают на это «из коробки».
 */
object Snmp {
    private val HR_DEVICE_DESCR = intArrayOf(1, 3, 6, 1, 2, 1, 25, 3, 2, 1, 3, 1)
    internal val SYS_DESCR = intArrayOf(1, 3, 6, 1, 2, 1, 1, 1, 0)
    private val IF_PHYS_ADDRESS = intArrayOf(1, 3, 6, 1, 2, 1, 2, 2, 1, 6)

    /**
     * Модель: GET sysDescr (1.3.6.1.2.1.1.1.0) и hrDeviceDescr параллельно, по 1 с.
     * Для показа лучше hrDeviceDescr («Brother HL-L2360D series»): у Brother sysDescr — это сетевая плата («Brother NC-340h…»).
     */
    suspend fun model(host: String, timeoutMs: Int = 1000): String? = coroutineScope {
        val sys = async { get(host, SYS_DESCR, timeoutMs) }
        val hr = async { get(host, HR_DEVICE_DESCR, timeoutMs) }
        pick(hr.await(), sys.await())
    }

    /** Чистим ответ (управляющие символы, длина) и выбираем: hrDeviceDescr, иначе sysDescr. */
    internal fun pick(hr: String?, sys: String?): String? =
        listOf(hr, sys).firstNotNullOfOrNull { s -> s?.filter { it in ' '..'~' || it.code > 0x400 }?.trim()?.take(80)?.ifEmpty { null } }

    /** Значение из ответа GET (последняя OCTET STRING; первая — community). */
    internal fun value(response: ByteArray): String? = octets(response).drop(1).lastOrNull()?.let { String(it, Charsets.UTF_8) }

    /**
     * MAC интерфейса — постоянный id принтера в реестре: ifPhysAddress (1.3.6.1.2.1.2.2.1.6.N) для ifIndex 1, затем 2.
     * Формат «00:00:5E:00:53:AA»; `null` — нет ответа или нулевой адрес.
     */
    suspend fun mac(host: String, timeoutMs: Int = 1000): String? {
        for (index in 1..2) formatMac(getRaw(host, IF_PHYS_ADDRESS + index, timeoutMs))?.let { return it }
        return null
    }

    internal fun formatMac(b: ByteArray?): String? =
        b?.takeIf { it.size == 6 && it.any { x -> x.toInt() != 0 } }?.joinToString(":") { "%02X".format(it.toInt() and 0xFF) }

    suspend fun get(host: String, oid: IntArray, timeoutMs: Int): String? = getRaw(host, oid, timeoutMs)?.let { String(it, Charsets.UTF_8) }

    /** Значение GET как байты (MAC приходит двоичным OCTET STRING). */
    suspend fun getRaw(host: String, oid: IntArray, timeoutMs: Int): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            DatagramSocket().use { s ->
                s.soTimeout = timeoutMs
                val req = request(oid)
                s.send(DatagramPacket(req, req.size, InetAddress.getByName(host), 161))
                val buf = ByteArray(1500)
                val p = DatagramPacket(buf, buf.size)
                s.receive(p)
                // Последняя OCTET STRING в ответе — значение (первая — community).
                octets(buf.copyOf(p.length)).drop(1).lastOrNull()
            }
        }.getOrNull()
    }

    internal fun request(oid: IntArray): ByteArray {
        val varbind = tlv(0x30, tlv(0x06, oidBytes(oid)) + byteArrayOf(0x05, 0x00))
        val pdu = tlv(0xA0, int(0x2C1) + int(0) + int(0) + tlv(0x30, varbind))
        return tlv(0x30, int(0) + tlv(0x04, "public".toByteArray()) + pdu)
    }

    private fun int(v: Int): ByteArray {
        val bytes = mutableListOf<Byte>()
        var x = v
        do { bytes.add(0, (x and 0xFF).toByte()); x = x shr 8 } while (x != 0)
        if (bytes[0].toInt() and 0x80 != 0) bytes.add(0, 0)
        return tlv(0x02, bytes.toByteArray())
    }

    private fun oidBytes(oid: IntArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(oid[0] * 40 + oid[1])
        for (i in 2 until oid.size) {
            var v = oid[i]
            val chunk = mutableListOf(v and 0x7F)
            v = v shr 7
            while (v > 0) { chunk.add(0, (v and 0x7F) or 0x80); v = v shr 7 }
            chunk.forEach(out::write)
        }
        return out.toByteArray()
    }

    private fun tlv(tag: Int, value: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag)
        val n = value.size
        when {
            n < 0x80 -> out.write(n)
            n < 0x100 -> { out.write(0x81); out.write(n) }
            else -> { out.write(0x82); out.write(n shr 8); out.write(n and 0xFF) }
        }
        out.write(value)
        return out.toByteArray()
    }

    /** Все OCTET STRING из BER-дерева по порядку (строками). */
    internal fun octetStrings(b: ByteArray): List<String> = octets(b).map { String(it, Charsets.UTF_8) }

    /** Все OCTET STRING из BER-дерева по порядку (байтами). */
    internal fun octets(b: ByteArray): List<ByteArray> {
        val found = mutableListOf<ByteArray>()
        fun walk(from: Int, to: Int) {
            var i = from
            while (i + 1 < to) {
                val tag = b[i].toInt() and 0xFF
                var len = b[i + 1].toInt() and 0xFF
                var start = i + 2
                if (len and 0x80 != 0) {
                    val k = len and 0x7F
                    len = 0
                    repeat(k) { j -> len = (len shl 8) or (b[start + j].toInt() and 0xFF) }
                    start += k
                }
                val end = start + len
                if (end > to) return
                when {
                    tag == 0x04 -> found += b.copyOfRange(start, end)
                    tag and 0x20 != 0 -> walk(start, end) // составной тип (SEQUENCE, PDU)
                }
                i = end
            }
        }
        walk(0, b.size)
        return found
    }
}

/** Что за принтер по названию модели: вид и язык. `null` — не знаем (показываем только администратору). */
fun guessByModel(model: String): Pair<LabelKind, PrintLang>? {
    val m = model.lowercase()
    fun has(vararg w: String) = w.any { it in m }
    return when {
        has("godex", "g500", "g530", "rt200", "rt230", "ez-") -> LabelKind.STICKY to PrintLang.EZPL
        has("zebra", "zd220", "zd230", "zd4", "zd6", "zt2", "zt4", "zt6", "gk420", "gx420", "honeywell", "intermec", "datamax", "citizen") ->
            LabelKind.STICKY to PrintLang.ZPL
        has("tsc", "ttp-", "tdp-", "te2", "te3", "da2", "xprinter", "xp-", "gprinter", "gp-", "hprt", "rongta", "idprt", "munbyn", "bixolon", "sato") ->
            LabelKind.STICKY to PrintLang.TSPL
        // Brother: и HL-L2360DN, и MFC-L2700DW магазина (в его отчёте конфигурации включён PCL).
        has("brother", "hp ", "hewlett", "laserjet", "kyocera", "xerox", "ricoh", "lexmark", "samsung", "pantum", "konica", "mfc-", "dcp-", "hl-") ->
            LabelKind.PLAIN to PrintLang.PCL
        else -> null
    }
}
