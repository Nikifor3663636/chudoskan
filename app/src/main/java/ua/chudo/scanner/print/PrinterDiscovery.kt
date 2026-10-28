package ua.chudo.scanner.print

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Collections
import kotlin.coroutines.resume

/** Имя и адрес сервиса из NSD (mDNS). */
data class NsdHit(val name: String, val host: String)

/**
 * Поиск принтеров в сети магазина: TCP-connect к :9100 по своей /24 (300 мс) + NsdManager
 * (`_pdl-datastream._tcp`, `_ipp._tcp`, `_printer._tcp`) + SNMP для модели.
 */
class PrinterDiscovery(context: Context) {
    private val app = context.applicationContext
    private val nsd = app.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val resolveLock = Mutex()

    /** Свой IPv4 в Wi-Fi (ACCESS_WIFI_STATE). */
    @Suppress("DEPRECATION")
    fun localIp(): String? {
        val wm = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val ip = wm.connectionInfo?.ipAddress ?: 0
        if (ip == 0) return null
        return "${ip and 255}.${ip shr 8 and 255}.${ip shr 16 and 255}.${ip shr 24 and 255}"
    }

    /** Адреса /24 с открытым [port]. */
    suspend fun scanSubnet(port: Int = 9100): List<String> = coroutineScope {
        val me = localIp() ?: return@coroutineScope emptyList()
        val base = me.substringBeforeLast('.')
        val gate = Semaphore(64)
        (1..254).map { "$base.$it" }.filter { it != me }
            .map { host -> async(Dispatchers.IO) { gate.withPermit { host.takeIf { RawTcp.reachable(it, port, 300) } } } }
            .awaitAll().filterNotNull()
    }

    suspend fun nsdHits(windowMs: Long = 2500): List<NsdHit> = coroutineScope {
        val services = listOf("_pdl-datastream._tcp", "_ipp._tcp", "_printer._tcp")
            .map { async { browse(it, windowMs) } }.awaitAll().flatten()
            .distinctBy { it.serviceName }
        services.mapNotNull { s -> resolve(s)?.let { r -> hostOf(r)?.let { NsdHit(s.serviceName, it) } } }
    }

    /** Ответ Godex на ~HI (только для не-Brother: иначе Brother может напечатать мусор). */
    suspend fun godexId(host: String, port: Int): String? =
        RawTcp.query(host, port, "~HI\r\n".toByteArray(Charsets.US_ASCII))
            ?.filter { it in ' '..'~' }?.trim()?.take(60)?.ifEmpty { null }

    /**
     * Все принтеры в сети магазина: открытый :9100 (сырая печать) + mDNS (`_pdl-datastream`, `_ipp`).
     * Модель — по SNMP, затем по имени mDNS, у Godex — по ~HI; по модели угадываем роль и язык.
     */
    suspend fun discover(port: Int = 9100): List<FoundPrinter> = coroutineScope {
        val hitsJob = async { nsdHits() }
        val open = scanSubnet(port)
        val hits = hitsJob.await().associateBy { it.host }
        val found = open.map { host -> async { identify(host, port, hits[host]?.name) } }.awaitAll()
        // Офисные принтеры без :9100 (только IPP) — печать через системний друк Android.
        found + hits.values.filter { h -> found.none { it.host == h.host } }
            .map { FoundPrinter(it.host, port, "nsd:${it.name}", it.name, LabelKind.PLAIN, PrintLang.PDF) }
    }

    /** Кто отвечает на [host]:[port]. ~HI шлём только не-офисным: офисный A4 напечатал бы её текстом. */
    suspend fun identify(host: String, port: Int, nsdName: String? = null): FoundPrinter {
        val model = Snmp.model(host)
        val name = model ?: nsdName
        val guess = name?.let(::guessByModel)
        if (nsdName != null && nsdName.contains("brother", true)) {
            return FoundPrinter(host, port, "brother:$nsdName", nsdName, guess?.first ?: LabelKind.PLAIN, guess?.second ?: PrintLang.PDF)
        }
        if (guess?.first == LabelKind.PLAIN) return FoundPrinter(host, port, "snmp:$name", name, LabelKind.PLAIN, PrintLang.PDF)
        // Godex узнаём по ~HI (отпечаток совместим с принтерами, сохранёнными раньше).
        if (model == null || model.contains("godex", true)) {
            godexId(host, port)?.let { return FoundPrinter(host, port, "godex:$it", model ?: "Godex · $it", LabelKind.STICKY, PrintLang.ZPL) }
        }
        if (name != null) return FoundPrinter(host, port, "snmp:$name", name, guess?.first ?: LabelKind.STICKY, guess?.second ?: PrintLang.ZPL)
        return FoundPrinter(host, port, ONLY_9100, "Принтер $host", LabelKind.STICKY, PrintLang.ZPL)
    }

    /** Отпечаток для принтера, добавленного вручную. */
    suspend fun fingerprintOf(host: String, port: Int, role: LabelKind): Pair<String, String?> {
        val nsdName = nsdHits().firstOrNull { it.host == host }?.name
        val f = if (role == LabelKind.PLAIN && Snmp.model(host) == null && nsdName == null) null else identify(host, port, nsdName)
        return when {
            f == null -> "ip:$host" to null
            f.fingerprint == ONLY_9100 -> ONLY_9100 to null
            else -> f.fingerprint to f.name
        }
    }

    /** Новый адрес принтера по отпечатку (тихий пересканирование /24). */
    suspend fun locate(p: SavedPrinter): String? {
        if (p.fingerprint.startsWith("ip:")) return null
        val found = discover(p.port)
        found.filter { it.fingerprint == p.fingerprint }.let { same ->
            // Две одинаковые модели — не угадываем, какая наша.
            if (same.size == 1) return same.first().host
            if (same.size > 1) return same.firstOrNull { it.host == p.host }?.host
        }
        if (p.fingerprint == ONLY_9100) {
            return found.filter { it.role == LabelKind.STICKY && it.fingerprint == ONLY_9100 }.singleOrNull()?.host
        }
        return null
    }

    private suspend fun browse(type: String, windowMs: Long): List<NsdServiceInfo> {
        val found = Collections.synchronizedList(mutableListOf<NsdServiceInfo>())
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(s: NsdServiceInfo) { found += s }
            override fun onServiceLost(s: NsdServiceInfo) {}
            override fun onDiscoveryStarted(t: String) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onStartDiscoveryFailed(t: String, code: Int) {}
            override fun onStopDiscoveryFailed(t: String, code: Int) {}
        }
        val started = runCatching { nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener) }.isSuccess
        if (!started) return emptyList()
        try {
            delay(windowMs)
        } finally {
            runCatching { nsd.stopServiceDiscovery(listener) }
        }
        return found.toList()
    }

    @Suppress("DEPRECATION")
    private suspend fun resolve(s: NsdServiceInfo): NsdServiceInfo? = resolveLock.withLock {
        withTimeoutOrNull(2000) {
            suspendCancellableCoroutine { cont ->
                runCatching {
                    nsd.resolveService(s, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo, code: Int) { if (cont.isActive) cont.resume(null) }
                        override fun onServiceResolved(info: NsdServiceInfo) { if (cont.isActive) cont.resume(info) }
                    })
                }.onFailure { if (cont.isActive) cont.resume(null) }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun hostOf(info: NsdServiceInfo): String? = info.host?.hostAddress
}
