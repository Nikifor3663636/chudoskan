package ua.chudo.scanner.print

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Collections
import kotlin.coroutines.resume

/** Сервис из NSD (mDNS): имя, адрес и модель из TXT-записи. */
data class NsdHit(val name: String, val host: String, val model: String? = null)

/** TXT-запись mDNS принтера (RFC 6763, Bonjour Printing Specification): ищем модель. */
object MdnsTxt {
    /**
     * Модель по ключам: `ty` («Brother MFC-L2700DW series»), `product` («(Brother …)»),
     * иначе `usb_MFG` + `usb_MDL`. Ключи без учёта регистра, пустые значения пропускаем.
     */
    fun model(attrs: Map<String, ByteArray?>): String? {
        val m = attrs.entries.associate { (k, v) -> k.lowercase() to v?.toString(Charsets.UTF_8)?.trim().orEmpty() }
        m["ty"]?.takeIf { it.isNotEmpty() }?.let { return it }
        m["product"]?.removeSurrounding("(", ")")?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        val usb = listOfNotNull(m["usb_mfg"], m["usb_mdl"]).filter { it.isNotEmpty() }
        if (usb.isNotEmpty()) {
            // usb_MDL у Brother уже с маркой: «Brother HL-L2360D» — не дублируем.
            return if (usb.size == 2 && usb[1].startsWith(usb[0], ignoreCase = true)) usb[1] else usb.joinToString(" ")
        }
        return null
    }
}

/**
 * Фоновый поиск принтеров, без UI. Параллельно:
 * а) NsdManager `_pdl-datastream._tcp`, `_ipp._tcp`, `_printer._tcp` (4 с) — только своя подсеть;
 * б) TCP 9100 по `printerSubnets` + /24 Wi-Fi телефона (300 мс, до 64 сразу);
 * в) для открытых адресов SNMP sysDescr + hrDeviceDescr + ifPhysAddress (public, 1 с);
 * г) SNMP молчит — страница http://IP/ (1 с): «GODEX» / «G500» → этикетки 40×24.
 * Вид — по модели: Brother → A4, Godex → 40×24; не узнали — kind = null (только администратору).
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

    fun ownSubnet(): String? = localIp()?.let(Cidr::slash24)

    /** Что сканировать: подсети магазина + своя /24, без повторов. */
    fun targets(subnets: List<String>): List<String> {
        val me = localIp()
        return (subnets + listOfNotNull(ownSubnet())).mapNotNull(Cidr::normalize).distinct()
            .flatMap(Cidr::hosts).distinct().filter { it != me }
    }

    /** Находки по мере опознания: принтер нужного вида можно взять, не дожидаясь конца поиска. */
    fun discover(subnets: List<String>, port: Int = 9100): Flow<Discovered> = channelFlow {
        val hits = async { runCatching { nsdHits() }.getOrDefault(emptyList()) }
        val gate = Semaphore(64)
        val seen = Collections.synchronizedSet(mutableSetOf<String>())
        val jobs = targets(subnets).map { host ->
            launch(Dispatchers.IO) {
                val open = gate.withPermit { RawTcp.reachable(host, port, 300) }
                if (open && seen.add(host)) send(identify(host, port, null))
            }
        }
        jobs.forEach { it.join() }
        // Принтеры из mDNS, у которых 9100 закрыт или не ответил за 300 мс.
        hits.await().filter { seen.add(it.host) }.forEach { h ->
            if (RawTcp.reachable(h.host, port, 1000)) send(identify(h.host, port, h))
        }
    }

    suspend fun discoverAll(subnets: List<String>): List<Discovered> = discover(subnets).toList()

    /** Первый найденный принтер вида [kind] за [timeoutMs] (по умолчанию 6 с) или `null`. */
    suspend fun findFirst(kind: LabelKind, subnets: List<String>, timeoutMs: Long = 6000): Discovered? =
        withTimeoutOrNull(timeoutMs) { discover(subnets).firstOrNull { it.kind == kind } }

    /** Модель и MAC по SNMP; молчит — страница Godex; модель → вид. */
    suspend fun identify(ip: String, port: Int = 9100, hit: NsdHit? = null): Discovered = coroutineScope {
        val model = async { Snmp.model(ip) }
        val mac = async { Snmp.mac(ip) }
        var name = model.await() ?: hit?.model
        if (name == null) name = HttpProbe.godexModel(HttpProbe.page(ip))
        val kind = name?.let(::guessByModel)?.first
        Log.i(TAG, "$ip: ${name ?: "?"} → ${kind?.wire ?: "невідомо"}")
        Discovered(ip, port, name, mac.await(), kind, hit?.name)
    }

    /** NSD — только адреса своей подсети (другие подсети покрывает скан по `printerSubnets`). */
    suspend fun nsdHits(windowMs: Long = 4000): List<NsdHit> = coroutineScope {
        val own = ownSubnet() ?: return@coroutineScope emptyList()
        val services = listOf("_pdl-datastream._tcp", "_ipp._tcp", "_printer._tcp")
            .map { async { browse(it, windowMs) } }.awaitAll().flatten()
            .distinctBy { it.serviceName }
        services.mapNotNull { s ->
            resolve(s)?.let { r -> hostOf(r)?.let { NsdHit(s.serviceName, it, runCatching { MdnsTxt.model(r.attributes) }.getOrNull()) } }
        }.filter { Cidr.contains(own, it.host) }.distinctBy { it.host }
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

    private companion object {
        const val TAG = "ChudoPrint"
    }
}
