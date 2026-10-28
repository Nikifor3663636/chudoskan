package ua.chudo.scanner.print

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.util.UUID
import kotlin.coroutines.resume

/**
 * Собственная Wi-Fi сеть принтера (Brother «Wireless Direct» DIRECT-xx…, Wi-Fi Direct, режим точки доступа).
 * Телефон подключается к ней только на время печати (Android 10+ показывает системный запрос),
 * интернет при этом остаётся на мобильных данных.
 */
class WifiDirectLink(context: Context) {
    private val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /**
     * Подключиться к [ssid], выполнить [block] с сетью и адресом принтера, отключиться.
     * Адрес — [host], если задан, иначе шлюз сети принтера (у Brother это 192.168.118.1).
     */
    suspend fun <T> use(ssid: String, pass: String, host: String, port: Int, block: suspend (Network, String) -> T): T {
        check(supported) { "Підключення до Wi-Fi принтера потребує Android 10 або новіше" }
        val spec = WifiNetworkSpecifier.Builder().setSsid(ssid).apply {
            if (pass.isNotEmpty()) setWpa2Passphrase(pass)
        }.build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(spec)
            .build()
        var callback: ConnectivityManager.NetworkCallback? = null
        try {
            val network = withTimeoutOrNull(60_000) {
                suspendCancellableCoroutine<Network?> { cont ->
                    val cb = object : ConnectivityManager.NetworkCallback() {
                        override fun onAvailable(network: Network) { if (cont.isActive) cont.resume(network) }
                        override fun onUnavailable() { if (cont.isActive) cont.resume(null) }
                    }
                    callback = cb
                    cm.requestNetwork(request, cb)
                }
            } ?: throw IllegalStateException("Не вдалося підключитися до Wi-Fi «$ssid». Перевір, що принтер увімкнений поруч і пароль правильний.")
            val target = host.ifEmpty { printerAddress(network, port) }
                ?: throw IllegalStateException("Підключилися до «$ssid», але принтер не відповідає на порту $port.")
            return block(network, target)
        } finally {
            callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        }
    }

    /** Шлюз сети принтера, затем типовые адреса: принтер в режиме точки доступа обычно сам себе шлюз. */
    private suspend fun printerAddress(network: Network, port: Int): String? {
        val lp = cm.getLinkProperties(network)
        val gateways = lp?.routes.orEmpty().mapNotNull { r -> r.gateway?.takeIf { it is Inet4Address && !it.isAnyLocalAddress }?.hostAddress }
        val own = lp?.linkAddresses.orEmpty().map { it.address }.filterIsInstance<Inet4Address>().firstOrNull()?.hostAddress
        val sameNet = own?.substringBeforeLast('.')?.let { listOf("$it.1", "$it.254") }.orEmpty()
        val candidates = (gateways + sameNet + KNOWN).distinct().filter { it != own }
        // DHCP в сети принтера иногда отдаёт адрес чуть позже — даём пару попыток.
        repeat(3) {
            candidates.firstOrNull { RawTcp.reachable(it, port, 700, network.socketFactory) }?.let { return it }
            delay(1000)
        }
        return null
    }

    private companion object {
        /** Brother Wireless Direct, типичные адреса Wi-Fi Direct / AP-режима. */
        val KNOWN = listOf("192.168.118.1", "192.168.49.1", "192.168.4.1", "192.168.1.1", "192.168.0.1", "10.10.100.254")
    }
}

/** Bluetooth Classic, профиль SPP — так работают мобильные Zebra, Godex, Xprinter и т.п. */
class BluetoothLink(context: Context) {
    private val app = context.applicationContext
    private val adapter get() = (app.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    val available: Boolean get() = adapter != null
    val enabled: Boolean get() = adapter?.isEnabled == true

    fun permissionGranted(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(app, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    /** Спаренные устройства (принтеры спаривают в налаштуваннях Bluetooth телефона). */
    @SuppressLint("MissingPermission")
    fun bonded(): List<Pair<String, String>> {
        if (!permissionGranted()) return emptyList()
        return adapter?.bondedDevices.orEmpty()
            .sortedByDescending { it.bluetoothClass?.majorDeviceClass == android.bluetooth.BluetoothClass.Device.Major.IMAGING }
            .map { (it.name ?: it.address) to it.address }
    }

    @SuppressLint("MissingPermission")
    suspend fun send(mac: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
        val a = adapter ?: error("На телефоні немає Bluetooth")
        check(permissionGranted()) { "Дозволь застосунку доступ до Bluetooth" }
        check(a.isEnabled) { "Увімкни Bluetooth" }
        val device = a.getRemoteDevice(mac)
        runCatching { a.cancelDiscovery() }
        val socket = connect(device)
        try {
            socket.outputStream.apply {
                // Частями: у дешёвых принтеров маленький буфер.
                var i = 0
                while (i < bytes.size) {
                    val n = minOf(4096, bytes.size - i)
                    write(bytes, i, n); flush()
                    i += n
                }
            }
            delay(800 + bytes.size / 50L) // закрытие сокета сразу после записи обрывает передачу
        } finally {
            runCatching { socket.close() }
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun reachable(mac: String): Boolean = runCatching {
        withContext(Dispatchers.IO) { connect(adapter!!.getRemoteDevice(mac)).close() }
    }.isSuccess

    @SuppressLint("MissingPermission")
    private fun connect(device: BluetoothDevice): BluetoothSocket {
        val attempts = listOf<() -> BluetoothSocket>(
            { device.createRfcommSocketToServiceRecord(SPP) },
            { device.createInsecureRfcommSocketToServiceRecord(SPP) },
            // Старые прошивки без SDP-записи: канал 1 напрямую.
            { device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType).invoke(device, 1) as BluetoothSocket },
        )
        var last: Exception? = null
        for (make in attempts) {
            val s = runCatching(make).getOrNull() ?: continue
            try {
                s.connect(); return s
            } catch (e: Exception) {
                last = e; runCatching { s.close() }
            }
        }
        throw IllegalStateException("Не вдалося підключитися по Bluetooth. Принтер увімкнений і спарений з телефоном?", last)
    }

    private companion object {
        val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}
