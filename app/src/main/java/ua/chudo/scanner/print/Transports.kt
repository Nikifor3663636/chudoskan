package ua.chudo.scanner.print

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.firebase.Timestamp
import com.google.firebase.firestore.Blob
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.SocketFactory
import kotlin.coroutines.resume

/** Куда уходят байты задания. */
interface PrinterTransport {
    suspend fun send(bytes: ByteArray)
    suspend fun isAlive(): Boolean
}

/** Ошибка печати с текстом для сотрудника (уходит в snackbar как есть). */
open class PrintException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Сырой TCP (порт 9100): соединение — 3 с, запись — 15 с. [factory] — сокеты сети Wi-Fi принтера. */
class TcpTransport(
    val host: String,
    val port: Int = 9100,
    private val connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
    private val writeTimeoutMs: Long = WRITE_TIMEOUT_MS,
    private val factory: SocketFactory? = null,
) : PrinterTransport {

    override suspend fun send(bytes: ByteArray): Unit = withContext(Dispatchers.IO) {
        val s = factory?.createSocket() ?: Socket()
        try {
            connect(s)
            // У сокета нет таймаута записи: сторож закрывает его через 10 с, и write() падает.
            var timedOut = false
            coroutineScope {
                val watchdog = launch { delay(writeTimeoutMs); timedOut = true; runCatching { s.close() } }
                try {
                    s.getOutputStream().apply { write(bytes); flush() }
                    // Даём принтеру забрать данные до закрытия: часть принтеров обрывает задание на RST.
                    runCatching { s.shutdownOutput() }
                } catch (e: IOException) {
                    if (timedOut) throw PrintException("Таймаут: принтер $host не прийняв дані за ${writeTimeoutMs / 1000} с.", e)
                    throw PrintException("Зв'язок із принтером $host обірвався під час друку.", e)
                } finally {
                    watchdog.cancel()
                }
            }
        } finally {
            runCatching { s.close() }
        }
    }

    override suspend fun isAlive(): Boolean = withContext(Dispatchers.IO) {
        runCatching { (factory?.createSocket() ?: Socket()).use { connect(it) } }.isSuccess
    }

    private fun connect(s: Socket) {
        try {
            s.connect(InetSocketAddress(host, port), connectTimeoutMs)
        } catch (e: SocketTimeoutException) {
            throw PrintException("Таймаут: принтер $host не відповів за ${connectTimeoutMs / 1000} с. Перевір, що він увімкнений і в тій самій мережі, що й телефон.", e)
        } catch (e: ConnectException) {
            throw PrintException("Відмова з'єднання: $host:$port не приймає завдання. Перевір, що це принтер і в нього ввімкнено друк через порт $port.", e)
        } catch (e: NoRouteToHostException) {
            throw PrintException("Немає зв'язку з $host: телефон в іншій мережі. Підключись до Wi-Fi магазину.", e)
        } catch (e: UnknownHostException) {
            throw PrintException("Невідома адреса принтера: $host.", e)
        }
    }

    companion object {
        const val CONNECT_TIMEOUT_MS = 3000
        const val WRITE_TIMEOUT_MS = 15_000L
    }
}

/**
 * USB OTG (Android USB Host): bulk OUT конечная точка интерфейса принтера (класс 7), иначе любая bulk OUT.
 * Разрешение спрашиваем через [UsbManager.requestPermission] при первой печати.
 */
class UsbOtgTransport(context: Context, private val vendorId: Int, private val productId: Int) : PrinterTransport {
    private val app = context.applicationContext
    private val usb = app.getSystemService(Context.USB_SERVICE) as UsbManager

    fun device(): UsbDevice? = usb.deviceList.values.firstOrNull { it.vendorId == vendorId && it.productId == productId }

    // Permission is requested by send(); its absence must not block selecting this device.
    override suspend fun isAlive(): Boolean = device() != null

    override suspend fun send(bytes: ByteArray) {
        val dev = device() ?: throw PrintException("USB-принтер не підключено. Встав кабель OTG у телефон і принтер.")
        if (!usb.hasPermission(dev) && !requestPermission(dev)) {
            throw PrintException("Немає дозволу USB. Дозволь застосунку доступ до принтера в системному запиті.")
        }
        val (intf, out) = bulkOut(dev) ?: throw PrintException("Пристрій USB ${hex(dev.vendorId)}:${hex(dev.productId)} не схожий на принтер (немає каналу для даних).")
        withContext(Dispatchers.IO) {
            val conn = usb.openDevice(dev) ?: throw PrintException("Не вдалося відкрити USB-принтер. Від'єднай і встав кабель ще раз.")
            try {
                if (!conn.claimInterface(intf, true)) throw PrintException("USB-принтер зайнятий іншим застосунком.")
                var off = 0
                while (off < bytes.size) {
                    val n = minOf(CHUNK, bytes.size - off)
                    val sent = conn.bulkTransfer(out, bytes, off, n, TcpTransport.WRITE_TIMEOUT_MS.toInt())
                    if (sent <= 0) throw PrintException("Таймаут USB: принтер не прийняв дані за ${TcpTransport.WRITE_TIMEOUT_MS / 1000} с.")
                    off += sent
                }
                conn.releaseInterface(intf)
            } finally {
                conn.close()
            }
        }
    }

    private suspend fun requestPermission(dev: UsbDevice): Boolean = withTimeoutOrNull(60_000) {
        suspendCancellableCoroutine { cont ->
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    runCatching { app.unregisterReceiver(this) }
                    if (cont.isActive) cont.resume(i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
                }
            }
            ContextCompat.registerReceiver(app, receiver, IntentFilter(ACTION_PERMISSION), ContextCompat.RECEIVER_NOT_EXPORTED)
            cont.invokeOnCancellation { runCatching { app.unregisterReceiver(receiver) } }
            // Система дописывает в intent результат — PendingIntent должен быть изменяемым и адресным.
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val pi = PendingIntent.getBroadcast(app, 0, Intent(ACTION_PERMISSION).setPackage(app.packageName), flags)
            usb.requestPermission(dev, pi)
        }
    } == true

    companion object {
        private const val ACTION_PERMISSION = "ua.chudo.scanner.USB_PERMISSION"
        private const val CHUNK = 16 * 1024

        /** Интерфейс и bulk OUT: сначала класс «принтер» (7), иначе первый с bulk OUT (у Godex бывает vendor-specific). */
        fun bulkOut(dev: UsbDevice): Pair<UsbInterface, UsbEndpoint>? {
            val interfaces = (0 until dev.interfaceCount).map(dev::getInterface)
                .sortedByDescending { it.interfaceClass == UsbConstants.USB_CLASS_PRINTER }
            for (intf in interfaces) {
                for (e in 0 until intf.endpointCount) {
                    val ep = intf.getEndpoint(e)
                    if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK && ep.direction == UsbConstants.USB_DIR_OUT) return intf to ep
                }
            }
            return null
        }
    }
}

/** USB-принтеры, подключённые к телефону. Godex узнаём по vendorId, остальные — по классу «принтер». */
class UsbPrinters(context: Context) {
    private val usb = context.applicationContext.getSystemService(Context.USB_SERVICE) as UsbManager

    fun list(): List<PrinterRecord> = usb.deviceList.values.mapNotNull { d ->
        // В лог — vendorId/productId любого устройства: так проще добавить новую модель.
        Log.i(TAG, "USB ${hex(d.vendorId)}:${hex(d.productId)} ${d.manufacturerNameSafe()} ${d.productNameSafe()} class=${d.deviceClass}")
        val godex = d.vendorId == GODEX_VENDOR_ID
        val printer = godex || (0 until d.interfaceCount).any { d.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_PRINTER }
        if (!printer) return@mapNotNull null
        val name = listOf(d.manufacturerNameSafe(), d.productNameSafe()).filter { it.isNotBlank() }.joinToString(" ")
            .ifEmpty { if (godex) "Godex" else "USB-принтер" }
        val kind = guessByModel(name)?.first ?: if (godex) LabelKind.STICKY else return@mapNotNull null
        PrinterRecord(
            id = "usb-${hex(d.vendorId)}-${hex(d.productId)}".lowercase(), model = name, kind = kind,
            transport = Link.USB, usbVendor = d.vendorId, usbProduct = d.productId,
        )
    }

    private fun UsbDevice.manufacturerNameSafe() = runCatching { manufacturerName }.getOrNull().orEmpty()
    private fun UsbDevice.productNameSafe() = runCatching { productName }.getOrNull().orEmpty()

    companion object {
        private const val TAG = "ChudoUsb"

        /** GODEX INTERNATIONAL CO., LTD. — 6495 (0x195F), реестр vendor ID usb.org. */
        const val GODEX_VENDOR_ID = 0x195F
    }
}

private fun hex(v: Int) = v.toString(16).padStart(4, '0').uppercase()

/**
 * ПК-агент магазина (запасной путь): задание кладём в Firestore `printJobs`, агент на компьютере
 * печатает его на своём принтере и ставит `status` = `done` / `error` (+ `error` с текстом).
 *
 * `agents/{agentId}`: `shopId`, `name` (компьютер), `lastSeen` (Timestamp), `printers`: [{`name`, `model`}].
 * `printJobs/{id}`: `agentId`, `printer`, `shopId`, `by`, `at`, `status`, `lang`, `data` (байты), `size`.
 */
class AgentTransport(
    private val db: FirebaseFirestore,
    private val agentId: String,
    private val printer: String,
    private val shopId: String,
    private val uid: String,
    private val lang: PrintLang,
) : PrinterTransport {
    override suspend fun isAlive(): Boolean = runCatching {
        val doc = db.collection("agents").document(agentId).get().await()
        AgentDirectory.online(doc.getTimestamp("lastSeen")) && doc.getBoolean("online") != false
    }.getOrDefault(false)

    override suspend fun send(bytes: ByteArray) {
        if (shopId.isEmpty() || uid.isEmpty()) throw PrintException("Друк через комп'ютер доступний після входу співробітника.")
        if (bytes.size > MAX_BYTES) throw PrintException("Завдання завелике для друку через комп'ютер (${bytes.size / 1024} КБ). Друкуй меншими частинами.")
        if (!isAlive()) throw PrintException("Комп'ютер магазину не на зв'язку понад 3 хв. Перевір, що він увімкнений і агент друку запущений.")
        val ref = db.collection("printJobs").add(
            mapOf(
                "agentId" to agentId, "printer" to printer, "shopId" to shopId, "by" to uid,
                "at" to FieldValue.serverTimestamp(), "status" to "queued", "lang" to lang.name,
                "data" to Blob.fromBytes(bytes), "size" to bytes.size,
            ),
        ).await()
        // Ждём, пока агент заберёт задание: так «Тест друку» честно говорит, напечаталось ли.
        var status: String? = null
        var error: String? = null
        withTimeoutOrNull(AGENT_WAIT_MS) {
            while (status != "done" && status != "error") {
                delay(1500)
                val d = ref.get().await()
                status = d.getString("status")
                error = d.getString("error")
            }
        }
        when (status) {
            "done" -> Unit
            "error" -> throw PrintException(error ?: "Комп'ютер не зміг надрукувати завдання.")
            else -> throw PrintException("Комп'ютер не взяв завдання за ${AGENT_WAIT_MS / 1000} с. Перевір агент друку на ПК.")
        }
    }

    companion object {
        /** Документ Firestore — до 1 МиБ; оставляем запас на поля. */
        const val MAX_BYTES = 900_000
        const val AGENT_WAIT_MS = 30_000L
    }
}

/**
 * Принтеры компьютеров магазина, которые были на связи последние 3 минуты.
 * `agents/{agentId}`: `shopId`, `pcName`, `online`, `lastSeen`, `printers`: [{`name`, `isDefault`, `kind`}] — агент пишет раз в 60 с.
 */
class AgentDirectory(private val db: () -> FirebaseFirestore?) {
    suspend fun printers(shopId: String, includeOffline: Boolean = false, strict: Boolean = false): List<PrinterRecord> {
        val fs = db() ?: return emptyList()
        if (shopId.isEmpty()) return emptyList()
        val docs = try { fs.collection("agents").whereEqualTo("shopId", shopId).get().await().documents }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { if (strict) throw e else emptyList() }
        return docs.filter { includeOffline || (online(it.getTimestamp("lastSeen")) && it.getBoolean("online") != false) }.flatMap { d ->
            @Suppress("UNCHECKED_CAST")
            val list = d.get("printers") as? List<Map<String, Any?>> ?: emptyList()
            list.mapNotNull { m ->
                val name = m["name"] as? String ?: return@mapNotNull null
                val kind = LabelKind.ofWire(m["kind"] as? String) ?: guessByModel(name)?.first ?: return@mapNotNull null
                PrinterRecord(
                    id = PrinterIds.agent(d.id, name), model = name, kind = kind, transport = Link.AGENT,
                    agentId = d.id, printerName = name,
                    computerName = d.getString("pcName").orEmpty(),
                    // Принтер по умолчанию на ПК — первым.
                    lastSeen = if (m["isDefault"] == true) 1 else 0,
                )
            }
        }.sortedByDescending { it.lastSeen }
    }

    companion object {
        const val ONLINE_MS = 3 * 60 * 1000L

        fun online(lastSeen: Timestamp?, now: Long = System.currentTimeMillis()): Boolean =
            lastSeen != null && now - lastSeen.toDate().time <= ONLINE_MS
    }
}
