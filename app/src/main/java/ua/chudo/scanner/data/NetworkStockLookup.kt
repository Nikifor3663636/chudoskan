package ua.chudo.scanner.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Кількість у магазині мережі. */
data class StoreQty(val code: String, val name: String, val qty: Double)

/** Що показати співробітнику по товару із залишків мережі. */
data class NetAvailability(
    val barcode: String,
    val item: NetItem,
    /** Код свого магазину в 1С; `null` — магазин ще не прив'язано до складу. */
    val ownCode: String?,
    /** Кількість у своєму магазині (0 — немає); `null`, якщо свій код невідомий. */
    val own: Double?,
    /** Магазини того самого міста з кількістю > 0, за спаданням кількості. */
    val city: List<StoreQty>,
    val cityName: String?,
    val updatedAt: Date?,
)

object NetworkAvailability {
    /**
     * Свій магазин — за кодом 1С; «магазини міста» — склади з тим самим city (без свого), кількість > 0,
     * за спаданням кількості, при рівній — за кодом.
     */
    fun of(barcode: String, item: NetItem, ownCode: String?, stores: Map<String, StoreDoc>, updatedAt: Date?): NetAvailability {
        val cityName = ownCode?.let { stores[it]?.city }?.trim()?.takeIf { it.isNotEmpty() }
        val city = if (cityName == null) emptyList() else item.q
            .filter { (code, qty) -> code != ownCode && qty > 0 && stores[code]?.city?.trim().equals(cityName, ignoreCase = true) }
            .map { (code, qty) -> StoreQty(code, stores[code]?.name?.ifBlank { null } ?: "ЧО $code", qty) }
            .sortedWith(compareByDescending<StoreQty> { it.qty }.thenBy { it.code })
        return NetAvailability(barcode, item, ownCode, ownCode?.let { item.q[it] ?: 0.0 }, city, cityName, updatedAt)
    }

    /** Код свого магазину: прив'язка stores/{код}.shopId, інакше shops/{shopId}.code1C. */
    fun ownCode(shopId: String, stores: Map<String, StoreDoc>, code1C: String?): String? =
        stores.entries.firstOrNull { it.value.shopId == shopId }?.key ?: code1C?.trim()?.ifEmpty { null }

    private val CODE = Regex("^[A-Za-z]{3}\\d{6}$")

    /** «BOC022446» — код 1С (BOC/BUK/DPK/ODR…). */
    fun isCode(q: String) = CODE.matches(q.trim())

    /**
     * Для адміна: товар у всіх магазинах мережі, де він є, — по містах. Своє місто першим, далі за кількістю
     * в місті; усередині міста — за спаданням кількості, потім за кодом.
     */
    fun byCity(item: NetItem, stores: Map<String, StoreDoc>, ownCode: String?): List<CityStock> {
        val ownCity = ownCode?.let { stores[it]?.city }?.trim()?.lowercase()
        return item.q.filter { (_, qty) -> qty > 0 }
            .map { (code, qty) -> Triple(stores[code]?.city?.trim()?.ifEmpty { null } ?: "Інше", code, qty) }
            // «Київ» і « київ » — одне місто; назва — з великої літери, як у більшості складів.
            .groupBy { it.first.lowercase() }
            .map { (city, list) ->
                CityStock(
                    list.map { it.first }.groupingBy { it }.eachCount().entries
                        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { !it.key.first().isUpperCase() }).first().key,
                    list.map { (_, code, qty) -> StoreQty(code, stores[code]?.name?.ifBlank { null } ?: "ЧО $code", qty) }
                        .sortedWith(compareByDescending<StoreQty> { it.qty }.thenBy { it.code }),
                    own = city.lowercase() == ownCity,
                )
            }
            .sortedWith(compareByDescending<CityStock> { it.own }.thenByDescending { it.total }.thenBy { it.city })
    }

    /** Склади для вибору «Що в магазині»: за номером ЧО. */
    fun storeChoices(stores: Map<String, StoreDoc>): List<Pair<String, StoreDoc>> =
        stores.entries.sortedWith(compareBy({ it.key.toIntOrNull() ?: Int.MAX_VALUE }, { it.key })).map { it.key to it.value }
}

/** Магазини одного міста з кількістю товару. */
data class CityStock(val city: String, val stores: List<StoreQty>, val own: Boolean = false) {
    val total: Double get() = stores.sumOf { it.qty }
}

/** Товар у вибраному магазині: штрихкод, дані 1С і кількість саме там. */
data class StoreItem(val barcode: String, val item: NetItem, val qty: Double)

/** Звідки [NetworkStockLookup] бере дані: Firestore у застосунку, фейк у тестах. Старий `stock/{штрихкод}` не читаємо. */
interface StockStore {
    /** [bytes] — розмір пакета; у старих записах його немає. */
    data class Meta(val updatedAt: Date?, val pack: String?, val parts: Int, val bytes: Long? = null)
    /** Частина пакета: дані й (лише в частині 0) список змінених штрихкодів, обидва gzip. */
    class Part(val data: ByteArray?, val changed: ByteArray?)

    suspend fun meta(): Meta?
    suspend fun packPart(version: String, index: Int): Part
    suspend fun stores(): Map<String, StoreDoc>
    suspend fun shopCode1C(shopId: String): String?
}

/** Коли можна качати пакет у фоні: Wi-Fi (безлімітна мережа) або пакет менший за [LIMIT]. */
object StockPrefetch {
    const val LIMIT = 2_000_000L

    fun size(meta: StockStore.Meta): Long = meta.bytes ?: (meta.parts.toLong() * StockPack.PART)

    fun allowed(meta: StockStore.Meta, unmetered: Boolean): Boolean = unmetered || size(meta) < LIMIT
}

/**
 * Читання залишків мережі: пакет `stockPacks` качається раз на версію (~1 МБ) і зберігається у filesDir,
 * далі пошук локальний. Пакета ще немає — залишків мережі немає (показуємо, як раніше, сайт і свій магазин).
 * Склади (`stores`) і код свого магазину — раз на сесію, `meta/stock` — раз на 30 хв.
 */
class NetworkStockLookup(
    private val store: StockStore,
    private val dir: File,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val lock = Mutex()
    private val packLock = Mutex()
    private var meta: StockStore.Meta? = null
    private var metaAt = 0L
    private var metaLoaded = false
    private var loaded: Pack? = null

    /** Пакет залишків у пам'яті; індекси за кодом і артикулом будуються при першому запиті. */
    private class Pack(val version: String, val items: Map<String, NetItem>, val changed: Set<String>) {
        val byCode by lazy { items.entries.groupBy({ it.value.code.uppercase() }, { it.key }) }
        val byArticle by lazy { items.entries.groupBy({ it.value.article }, { it.key }) }
    }

    /**
     * `null` — у meta/stock немає пакета. Скачуємо раз на версію, далі — з файлу.
     * Частин немає — їх щойно замінило нове завантаження, а meta/stock у нас застарів: перечитуємо й пробуємо ще раз.
     */
    private suspend fun pack(): Pack? = try {
        packOnce()
    } catch (_: PackGone) {
        lock.withLock { metaLoaded = false }
        packOnce()
    }

    private class PackGone : java.io.IOException("Пакет залишків неповний")

    private suspend fun packOnce(): Pack? {
        val m = loadMeta() ?: return null
        val v = m.pack ?: return null
        return packLock.withLock {
            loaded?.takeIf { it.version == v } ?: withContext(Dispatchers.IO) {
                val f = File(dir, "stockpack_$v.gz")
                val ch = File(dir, "stockpack_$v.changed")
                if (!f.exists() || !ch.exists()) {
                    val docs = (0 until m.parts).map { store.packPart(v, it) }
                    val parts = docs.map { it.data ?: throw PackGone() }
                    ch.writeBytes(docs.firstOrNull()?.changed ?: ByteArray(0))
                    File(dir, "stockpack_$v.tmp").apply { writeBytes(StockPack.join(parts)); renameTo(f) }
                }
                val items = StockSnapshot(f).read() ?: run { f.delete(); throw java.io.IOException("Пакет залишків пошкоджено") }
                val changed = runCatching { StockPack.gunzipLines(ch.readBytes()) }.getOrDefault(emptySet())
                dir.listFiles { x -> x.name.startsWith("stockpack_") && !x.name.startsWith("stockpack_$v.") }?.forEach { it.delete() }
                Pack(v, items, changed).also { loaded = it }
            }
        }
    }

    /**
     * Фонове завантаження пакета після входу, щоб перший скан не чекав. [unmetered] — Wi-Fi;
     * на мобільній мережі — лише якщо пакет менший за [StockPrefetch.LIMIT].
     * `false` — пакет великий, а мережа платна: спробуємо на Wi-Fi.
     */
    suspend fun prefetch(unmetered: Boolean): Boolean {
        val m = loadMeta() ?: return true
        if (m.pack == null) return true
        if (!StockPrefetch.allowed(m, unmetered) && !File(dir, "stockpack_${m.pack}.gz").exists()) return false
        pack()
        return true
    }

    private var stores: Map<String, StoreDoc>? = null
    private val code1C = HashMap<String, String?>()

    /** Один запит на всі склади за сесію. */
    suspend fun stores(): Map<String, StoreDoc> = lock.withLock {
        stores ?: store.stores().also { stores = it }
    }

    /** meta/stock — раз на [META_TTL]: так новий пакет доходить до магазинів без перезапуску. */
    private suspend fun loadMeta(): StockStore.Meta? = lock.withLock {
        if (!metaLoaded || now() - metaAt > META_TTL) {
            runCatching { store.meta() }.getOrNull()?.let { meta = it; metaAt = now() }
            metaLoaded = true
        }
        meta
    }

    /** Коли востаннє завантажували залишки мережі (meta/stock.updatedAt); `null` — ще ні. */
    suspend fun lastUpdated(): Date? = loadMeta()?.updatedAt

    /** «Змінені з минулого завантаження», які є у своєму магазині. */
    /** У базі є пакет залишків 1С (інакше «змін немає» було б неправдою — даних просто немає). */
    suspend fun hasPack(): Boolean = loadMeta()?.pack != null

    suspend fun changedSinceLastImport(shopId: String, limit: Long = 500): List<NetAvailability> {
        val p = pack() ?: return emptyList()
        val own = ownCode(shopId) ?: return emptyList()
        return p.changed.mapNotNull { bc -> p.items[bc]?.takeIf { (it.q[own] ?: 0.0) > 0 }?.let { availability(shopId, bc, it) } }
            .sortedBy { it.item.name }.take(limit.toInt())
    }

    private suspend fun ownCode(shopId: String): String? {
        val all = stores()
        all.entries.firstOrNull { it.value.shopId == shopId }?.let { return it.key }
        if (shopId.isEmpty()) return null
        val c = lock.withLock {
            if (shopId !in code1C) code1C[shopId] = runCatching { store.shopCode1C(shopId) }.getOrNull()
            code1C[shopId]
        }
        return NetworkAvailability.ownCode(shopId, all, c)
    }

    /** Після завантаження залишків — перечитати склади й дату. */
    suspend fun invalidate() = lock.withLock { stores = null; metaLoaded = false; metaAt = 0; code1C.clear() }

    /** `null` — даних про штрихкод немає (тоді показуємо як раніше: сайт і shops/{shopId}/stock). */
    suspend fun byBarcode(shopId: String, barcode: String): NetAvailability? {
        val bc = barcode.filter(Char::isDigit).ifEmpty { return null }
        val item = pack()?.items?.get(bc) ?: return null
        return availability(shopId, bc, item)
    }

    /** Пошук за кодом 1С (BOC…) або артикулом. */
    suspend fun byCode(shopId: String, code: String): List<NetAvailability> =
        query(shopId, code.trim().uppercase()) { it.byCode }

    suspend fun byArticle(shopId: String, article: String): List<NetAvailability> =
        query(shopId, article.trim()) { it.byArticle }

    private suspend fun query(shopId: String, value: String, index: (Pack) -> Map<String, List<String>>): List<NetAvailability> {
        if (value.isEmpty()) return emptyList()
        val p = pack() ?: return emptyList()
        return index(p)[value].orEmpty().take(10).mapNotNull { bc -> p.items[bc]?.let { availability(shopId, bc, it) } }
    }

    /** Склади мережі й код свого магазину — для «Залишків» адміна. */
    suspend fun storesWithOwn(shopId: String): Pair<Map<String, StoreDoc>, String?> = stores() to ownCode(shopId)

    /** «Що в магазині ЧО NN» (адмін): товари з кількістю > 0 саме там, найбільші залишки першими. */
    suspend fun storeItems(code: String, limit: Long = 100): List<StoreItem> {
        val p = pack() ?: return emptyList()
        return p.items.mapNotNull { (bc, item) -> item.q[code]?.takeIf { q -> q > 0 }?.let { q -> StoreItem(bc, item, q) } }
            .sortedByDescending { it.qty }.take(limit.toInt())
    }

    private suspend fun availability(shopId: String, bc: String, item: NetItem) =
        NetworkAvailability.of(bc, item, ownCode(shopId), stores(), lastUpdated())

    private companion object { const val META_TTL = 30 * 60 * 1000L }
}
