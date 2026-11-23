package ua.chudo.scanner.print

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Состояние принтера: [ok] = null — ещё проверяем. */
data class PrinterStatus(val ok: Boolean?, val text: String)

class PreferredPrinterUnavailable(val pick: Pick) : PrintException(
    if (pick.missing) PrintService.MISSING else "${pick.record.displayName} не відповідає",
)

/** Ни реестр, ни сеть, ни компьютер магазина не дали принтер нужного вида. */
class PrinterNotFoundException(val kind: LabelKind) : PrintException(
    if (kind == LabelKind.STICKY) "Принтер етикеток не знайдено. Перевір, що він увімкнений і підключений до мережі"
    else "Принтер A4 не знайдено. Перевір, що він увімкнений і підключений до мережі",
)

/** Принтер для печати = способ доставки + язык команд + вид бумаги. */
class Printer(val record: PrinterRecord, val transport: PrinterTransport, val language: PrinterLanguage, val kind: LabelKind)

/**
 * Печать без участия сотрудника: вид бумаги по шаблону → [PrinterResolver] выбирает принтер магазина →
 * [LabelRenderer] рисует → язык (EZPL / PCL) → транспорт (TCP / USB / ПК-агент) → реестр запоминает, кто и где напечатал.
 */
class PrintService(context: Context) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val store = PrinterStore(app)
    val discovery = PrinterDiscovery(app)
    val usb = UsbPrinters(app)
    val renderer = LabelRenderer(app)
    private val firestore: FirebaseFirestore? get() = runCatching { if (FirebaseApp.getApps(app).isEmpty()) null else FirebaseFirestore.getInstance() }.getOrNull()
    private val agents = AgentDirectory { firestore }
    private val uid: String get() = runCatching { FirebaseAuth.getInstance().currentUser?.uid }.getOrNull().orEmpty()

    private var registry: PrinterRegistry? = null
    private var listening: Job? = null
    private val _records = MutableStateFlow<List<PrinterRecord>?>(null)

    /** Принтеры магазина из реестра; `null` — ещё не загрузили. */
    val records: StateFlow<List<PrinterRecord>?> = _records.asStateFlow()
    private val _subnets = MutableStateFlow<List<String>>(emptyList())
    val subnets: StateFlow<List<String>> = _subnets.asStateFlow()

    @Volatile var shopId: String = ""
        private set

    val resolver = PrinterResolver(object : PrinterSource {
        override suspend fun preferred(kind: LabelKind) = store.preferred(kind).first()
        override suspend fun clearPreferred(kind: LabelKind) { store.setPreferred(kind, "auto") }
        override suspend fun candidates(kind: LabelKind) = availablePrinters(kind, includeOfflineAgents = true)
        override fun registry() = _records.value.orEmpty()
        override suspend fun alive(r: PrinterRecord) = transport(r, aliveTimeoutMs = 1000).isAlive()
        override suspend fun usb(kind: LabelKind) = usb.list().firstOrNull { it.kind == kind }
            ?.takeIf { UsbOtgTransport(app, it.usbVendor, it.usbProduct).device() != null }

        override suspend fun discover(kind: LabelKind): PrinterRecord? {
            val d = discovery.findFirst(kind, _subnets.value) ?: return null
            return record(d)?.also { r -> runCatching { registry?.upsert(r, uid) } }
        }

        override suspend fun agent(kind: LabelKind) = agents.printers(shopId).firstOrNull { it.kind == kind }
    })

    /** После входа: слушаем реестр и подсети своего магазина (другие телефоны не сканируют — берут отсюда). */
    fun attach(shopId: String) {
        if (shopId.isEmpty() || shopId == this.shopId && listening != null) return
        val db = firestore ?: return
        this.shopId = shopId
        _records.value = null
        LabelKind.entries.forEach { resolver.forget(it) }
        val reg = PrinterRegistry(db, shopId).also { registry = it }
        listening?.cancel()
        listening = scope.launch {
            launch { reg.listen().collect { _records.value = it } }
            launch { reg.subnets().collect { _subnets.value = it } }
        }
    }

    /** Запись реестра для найденного устройства; не узнали вид — `null` (в реестр не пишем). */
    fun record(d: Discovered): PrinterRecord? {
        val kind = d.kind ?: return null
        val model = d.model ?: d.name ?: return null
        val existing = _records.value.orEmpty()
        val id = PrinterIds.of(d.mac, model).let { id ->
            // Раньше записан без MAC (другой путь опознания) — не плодим дубль, обновляем тот же.
            existing.firstOrNull { it.id == id }?.id ?: existing.firstOrNull { it.transport == Link.TCP && it.model == model && it.kind == kind }?.id ?: id
        }
        return PrinterRecord(id = id, model = model, kind = kind, ip = d.ip, port = d.port, transport = Link.TCP)
    }

    fun transport(r: PrinterRecord, aliveTimeoutMs: Int = TcpTransport.CONNECT_TIMEOUT_MS): PrinterTransport = when (r.transport) {
        Link.TCP -> TcpTransport(r.ip, r.port, connectTimeoutMs = aliveTimeoutMs)
        Link.USB -> UsbOtgTransport(app, r.usbVendor, r.usbProduct)
        Link.AGENT -> AgentTransport(
            firestore ?: throw PrintException("Друк через комп'ютер потребує підключеного Firebase."),
            r.agentId, r.printerName, shopId, uid, r.lang,
        )
    }

    fun printer(r: PrinterRecord): Printer {
        val lang: PrinterLanguage = when (r.lang) {
            PrintLang.PCL -> PclRaster()
            PrintLang.EZPL -> EzplGraphic(EzplSettings(darkness = r.density, speed = r.speed))
            else -> LegacyLabelLanguage(r.lang)
        }
        return Printer(r, transport(r), lang, r.kind)
    }

    /**
     * Байты задания: этикетки 40×24 — по картинке на товар с копиями; листы — товары × количество подряд,
     * один документ на скільки завгодно аркушів. Книжна / альбомна — з налаштування шаблону.
     */
    suspend fun render(p: Printer, items: List<QueueItem>, t: Template, promo: Boolean): ByteArray {
        val landscape = store.landscape(t).first()
        return withContext(Dispatchers.Default) {
            if (t.kind == LabelKind.STICKY) {
                p.language.render(PrintJob(items.map { PrintPage(renderer.mono(it.tag.toItem(), LabelFormat.Label40x24, LabelRenderer.GODEX_DPI), it.copies) }))
            } else {
                val cells = expand(items.map { it.tag.toItem() to it.copies })
                p.language.render(PrintJob(renderer.sheetPages(cells, t, promo, landscape).map { PrintPage(it) }))
            }
        }
    }

    /**
     * «Друк»: сам выбирает принтер вида шаблона и печатает. Не нашли — [PrinterNotFoundException];
     * ошибка печати — [PrintException] с понятным текстом, а выбор сессии сбрасывается, чтобы «Спробувати ще» искал заново.
     */
    suspend fun print(items: List<QueueItem>, t: Template, promo: Boolean): PrinterRecord {
        requirePrices(items.map { it.tag.toItem() })
        val pick = resolver.resolve(t.kind) ?: throw PrinterNotFoundException(t.kind)
        if (pick.confirmationRequired) throw PreferredPrinterUnavailable(pick)
        try {
            printOn(pick.record, items, t, promo)
        } catch (e: Exception) {
            resolver.forget(t.kind)
            throw e
        }
        return pick.record
    }

    /** Печать на конкретный принтер (и тест администратора). Удалось — upsert в реестр: ip, lastSeen, lastOkBy. */
    suspend fun printOn(r: PrinterRecord, items: List<QueueItem>, t: Template, promo: Boolean) {
        if (r.kind != t.kind) throw PrintException("«${r.model}» друкує ${if (r.kind == LabelKind.STICKY) "етикетки 40×24" else "аркуші A4"}.")
        requirePrices(items.map { it.tag.toItem() })
        val p = printer(r)
        p.transport.send(render(p, items, t, promo))
        runCatching { registry?.upsert(r, uid, ok = true) }
    }

    /**
     * «Тест друку» (администратор): Godex — этикетка 40×24, Brother — страница A4
     * («ЧудоСкан — тест друку», дата и время, магазин, модель, IP, EAN-13).
     */
    suspend fun test(r: PrinterRecord, shop: String): String {
        val p = printer(r)
        val code = Ean13.normalize(TEST_EAN12)!!
        val bytes = withContext(Dispatchers.Default) {
            if (r.kind == LabelKind.STICKY) {
                val item = LabelItem(barcode = code, article = "TEST", brand = "ЧудоСкан", name = "Тест друку · ${r.model}", price = 199.0)
                p.language.render(PrintJob(listOf(PrintPage(renderer.mono(item, LabelFormat.Label40x24, LabelRenderer.GODEX_DPI)))))
            } else {
                val now = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.forLanguageTag("uk")).format(Date())
                val lines = listOf(
                    "ЧудоСкан — тест друку",
                    "Дата й час: $now",
                    "Магазин: ${shop.ifEmpty { "—" }}",
                    "Принтер: ${r.model}",
                    "IP: ${r.address}",
                    "Тестовий EAN-13: $code",
                )
                p.language.render(PrintJob(listOf(PrintPage(renderer.testPage(lines, code)))))
            }
        }
        p.transport.send(bytes)
        runCatching { registry?.upsert(r, uid, ok = true) }
        return "Надруковано"
    }

    /** Статус для списка администратора. */
    suspend fun status(r: PrinterRecord): PrinterStatus = when (r.transport) {
        Link.TCP -> if (transport(r, 1000).isAlive()) PrinterStatus(true, ONLINE) else PrinterStatus(false, OFFLINE)
        Link.USB -> {
            val t = UsbOtgTransport(app, r.usbVendor, r.usbProduct)
            when {
                t.device() == null -> PrinterStatus(false, "Недоступний — кабель USB не підключено")
                !t.isAlive() -> PrinterStatus(true, "Підключено по USB · дозвіл спитаємо під час друку")
                else -> PrinterStatus(true, ONLINE)
            }
        }
        Link.AGENT -> if (runCatching { transport(r).isAlive() }.getOrDefault(false)) PrinterStatus(true, "Онлайн через комп'ютер")
        else PrinterStatus(false, "Недоступний — комп'ютер магазину не на зв'язку")
    }

    /**
     * Для листа печати: имя принтера, который возьмём, и отвечает ли он («Godex G500 · онлайн»). Без поиска в сети.
     * Выбранного нет — «Обраний принтер не підключено», выбор не сбрасываем (решает вопрос при «Друк»).
     */
    suspend fun quickStatus(kind: LabelKind): Pair<String, Boolean>? {
        val preferred = store.preferred(kind).first()
        if (preferred != "auto") {
            val selected = availablePrinters(kind, includeOfflineAgents = true).firstOrNull { it.id == preferred }
                ?: return MISSING to false
            return selected.displayName to isAlive(selected)
        }
        withTimeoutOrNull(5_000) { records.filterNotNull().first() }
        val list = _records.value.orEmpty().filter { it.kind == kind }
        val chosen = list.firstOrNull { it.id == resolver.chosen(kind) }
        val ordered = listOfNotNull(chosen) + list.filter { it != chosen && it.transport == Link.TCP }
        for (r in ordered) if (transport(r, 1000).isAlive()) return r.displayName to true
        usb.list().firstOrNull { it.kind == kind }?.let { return it.displayName to true }
        agents.printers(shopId).firstOrNull { it.kind == kind }?.let { return it.displayName to true }
        return list.firstOrNull()?.let { it.displayName to false }
    }

    /**
     * При запуске, не чаще раза в 10 минут: тихо проверяем принтеры реестра (lastSeen),
     * пропавшие ищем по подсетям магазина — новый IP попадёт в реестр под тем же id.
     */
    fun syncOnStart() {
        if (registry == null) return
        scope.launch {
            val now = System.currentTimeMillis()
            if (now - store.lastSync() < SYNC_EVERY_MS) return@launch
            store.setLastSync(now)
            val list = withTimeoutOrNull(15_000) { records.filterNotNull().first() } ?: return@launch
            val tcp = list.filter { it.transport == Link.TCP && it.ip.isNotEmpty() }
            val dead = coroutineScope {
                tcp.map { r -> async { r to transport(r, 1000).isAlive() } }.awaitAll()
            }.mapNotNull { (r, ok) -> if (ok) { runCatching { registry?.touch(r) }; null } else r }
            if (dead.isEmpty() && tcp.isNotEmpty()) return@launch
            // Кого-то нет на месте (или реестр пуст) — один поиск по всем подсетям.
            runCatching { adminScan() }
        }
    }

    /** Полный поиск: узнанные пишем в реестр, неопознанные возвращаем (их видит только администратор). */
    suspend fun adminScan(): List<Discovered> {
        val found = discovery.discoverAll(_subnets.value)
        found.forEach { d -> record(d)?.let { r -> runCatching { registry?.upsert(r, uid) } } }
        return found.filter { it.kind == null }
    }

    /** «Додати вручну»: IP + вид; модель и MAC пробуем узнать. */
    suspend fun addManual(ip: String, kind: LabelKind) {
        val d = discovery.identify(ip)
        val r = record(d.copy(kind = kind, model = d.model ?: if (kind == LabelKind.STICKY) "Принтер етикеток $ip" else "Принтер A4 $ip"))!!
        (registry ?: throw PrintException("Реєстр принтерів недоступний: увійди як співробітник магазину.")).upsert(r, uid)
    }

    suspend fun setTuning(r: PrinterRecord, density: Int?, speed: Int?) = registry?.setTuning(r.id, density, speed)
    suspend fun rename(r: PrinterRecord, name: String) =
        (registry ?: throw PrintException("Реєстр принтерів недоступний")).rename(r.id, name)

    /** Do not clear a preference until the registry has actually loaded. */
    suspend fun availablePrinters(kind: LabelKind, includeOfflineAgents: Boolean = false): List<PrinterRecord> {
        val registered = withTimeoutOrNull(5_000) { records.filterNotNull().first() }
            ?: throw PrintException("Не вдалося завантажити принтери. Спробуй ще.")
        val remote = withTimeoutOrNull(5_000) { agents.printers(shopId, includeOfflineAgents, strict = true) }
            ?: throw PrintException("Не вдалося завантажити принтери комп'ютера. Спробуй ще.")
        return (registered.map { r -> remote.firstOrNull { it.id == r.id }?.let { r.copy(computerName = it.computerName) } ?: r } + usb.list() + remote)
            .filter { it.kind == kind }.distinctBy { it.id }
    }

    suspend fun isAlive(r: PrinterRecord): Boolean = try {
        withTimeoutOrNull(1_000) { transport(r, 1000).isAlive() } == true
    } catch (e: CancellationException) { throw e } catch (_: Exception) { false }
    suspend fun delete(r: PrinterRecord) = registry?.delete(r.id)
    suspend fun agentPrinters() = agents.printers(shopId)

    companion object {
        const val ONLINE = "Онлайн — готовий до друку"
        const val MISSING = "Обраний принтер не підключено"
        const val OFFLINE ="Не відповідає. Перевір, що принтер увімкнений, і натисни «Знайти знову»."
        const val SYNC_EVERY_MS = 10 * 60 * 1000L

        /** Тестовый код: префикс Украины 482 + 9 цифр; контрольную допишет [Ean13.normalize]. */
        const val TEST_EAN12 = "482307760123"
    }
}
