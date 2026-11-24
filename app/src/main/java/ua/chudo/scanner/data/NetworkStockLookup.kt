package ua.chudo.scanner.data

import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import java.util.Date

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

/**
 * Читання залишків мережі: товар — ОДНЕ читання `stock/{штрихкод}`; склади (`stores`), `meta/stock`
 * і код свого магазину читаються раз на сесію й тримаються в пам'яті.
 */
class NetworkStockLookup(private val db: FirebaseFirestore) {
    private val lock = Mutex()
    private var stores: Map<String, StoreDoc>? = null
    private var updatedAt: Date? = null
    private var metaLoaded = false
    private val code1C = HashMap<String, String?>()

    /** Один запит на всі склади за сесію. */
    suspend fun stores(): Map<String, StoreDoc> = lock.withLock {
        stores ?: db.collection(NetworkStockRepository.STORES).get().await().documents.associate {
            it.id to StoreDoc(it.getString("name").orEmpty(), it.getString("city").orEmpty(), it.getString("shopId")?.ifEmpty { null })
        }.also { stores = it }
    }

    private var changedAfter: com.google.firebase.Timestamp? = null

    private suspend fun loadMeta() = lock.withLock {
        if (!metaLoaded) {
            runCatching { db.collection(NetworkStockRepository.META).document(NetworkStockRepository.STOCK).get().await() }.getOrNull()?.let { d ->
                updatedAt = d.getTimestamp("updatedAt")?.toDate()
                changedAfter = d.getTimestamp("changedAfter")
            }
            metaLoaded = true
        }
    }

    private suspend fun updatedAt(): Date? { loadMeta(); return updatedAt }

    /** Коли востаннє завантажували залишки мережі (meta/stock.updatedAt); `null` — ще ні. */
    suspend fun lastUpdated(): Date? = updatedAt()

    /**
     * «Змінені з минулого завантаження», які є у своєму магазині: `stock where updatedAt > changedAfter`
     * (до [limit] читань). Першого завантаження ще не порівнювали — порожньо.
     */
    suspend fun changedSinceLastImport(shopId: String, limit: Long = 500): List<NetAvailability> {
        loadMeta()
        val after = changedAfter ?: return emptyList()
        val own = ownCode(shopId) ?: return emptyList()
        val docs = db.collection(NetworkStockRepository.STOCK).whereGreaterThan("updatedAt", after).limit(limit).get().await().documents
        return docs.mapNotNull { d -> d.toNetItem()?.takeIf { (it.q[own] ?: 0.0) > 0 }?.let { availability(shopId, d.id, it) } }
            .sortedBy { it.item.name }
    }

    private suspend fun ownCode(shopId: String): String? {
        val all = stores()
        all.entries.firstOrNull { it.value.shopId == shopId }?.let { return it.key }
        if (shopId.isEmpty()) return null
        val c = lock.withLock {
            if (shopId !in code1C) {
                code1C[shopId] = runCatching {
                    db.collection(NetworkStockRepository.SHOPS).document(shopId).get().await().getString("code1C")
                }.getOrNull()
            }
            code1C[shopId]
        }
        return NetworkAvailability.ownCode(shopId, all, c)
    }

    /** Після завантаження залишків — перечитати склади й дату. */
    suspend fun invalidate() = lock.withLock { stores = null; metaLoaded = false; code1C.clear() }

    /** `null` — у stock/{bc} немає даних (тоді показуємо як раніше: сайт і shops/{shopId}/stock). */
    suspend fun byBarcode(shopId: String, barcode: String): NetAvailability? {
        val bc = barcode.filter(Char::isDigit).ifEmpty { return null }
        val d = db.collection(NetworkStockRepository.STOCK).document(bc).get().await()
        val item = d.toNetItem() ?: return null
        return availability(shopId, bc, item)
    }

    /** Пошук за кодом 1С (BOC…) або артикулом: `stock where code ==` / `article ==`. */
    suspend fun byCode(shopId: String, code: String): List<NetAvailability> =
        query(shopId, "code", code.trim().uppercase())

    suspend fun byArticle(shopId: String, article: String): List<NetAvailability> =
        query(shopId, "article", article.trim())

    private suspend fun query(shopId: String, field: String, value: String): List<NetAvailability> {
        if (value.isEmpty()) return emptyList()
        val docs = db.collection(NetworkStockRepository.STOCK).whereEqualTo(field, value).limit(10).get().await().documents
        return docs.mapNotNull { d -> d.toNetItem()?.let { availability(shopId, d.id, it) } }
    }

    /** Склади мережі й код свого магазину — для «Залишків» адміна. */
    suspend fun storesWithOwn(shopId: String): Pair<Map<String, StoreDoc>, String?> = stores() to ownCode(shopId)

    /**
     * «Що в магазині ЧО NN» (адмін): товари з кількістю > 0 саме там, найбільші залишки першими.
     * Запит `stock where q.NN > 0 orderBy q.NN desc` — до [limit] читань.
     */
    suspend fun storeItems(code: String, limit: Long = 100): List<StoreItem> {
        val field = com.google.firebase.firestore.FieldPath.of("q", code)
        return db.collection(NetworkStockRepository.STOCK).whereGreaterThan(field, 0)
            .orderBy(field, com.google.firebase.firestore.Query.Direction.DESCENDING).limit(limit).get().await().documents
            .mapNotNull { d -> d.toNetItem()?.let { StoreItem(d.id, it, it.q[code] ?: 0.0) } }
    }

    private suspend fun availability(shopId: String, bc: String, item: NetItem) =
        NetworkAvailability.of(bc, item, ownCode(shopId), stores(), updatedAt())

    private fun DocumentSnapshot.toNetItem(): NetItem? {
        if (!exists()) return null
        @Suppress("UNCHECKED_CAST")
        val q = (get("q") as? Map<String, Any?>).orEmpty().mapNotNull { (k, v) -> (v as? Number)?.toDouble()?.let { k to it } }.toMap()
        return NetItem(getString("name").orEmpty(), getString("code").orEmpty(), getString("article").orEmpty(), q)
    }
}
