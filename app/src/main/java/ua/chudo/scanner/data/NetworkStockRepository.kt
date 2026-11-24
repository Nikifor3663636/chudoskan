package ua.chudo.scanner.data

import com.google.firebase.firestore.Blob
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await
import java.io.File
import java.util.Date

/** Магазин застосунку (shops/{shopId}) — для вибору «Який це магазин?». */
data class ShopRef(val id: String, val address: String, val region: String)

/** Запис stores/{код}: що вже відомо про склад 1С. */
data class StoreDoc(val name: String, val city: String, val shopId: String?)

data class NetworkStockInfo(val updatedAt: Date?, val updatedBy: String?, val items: Int, val stores: Int)

data class NetworkImportResult(val stores: Int, val items: Int, val changed: Int, val removed: Int, val keptStores: List<String>, val seconds: Long)

/**
 * Залишки мережі (тільки stockAdmin — див. firestore.rules):
 * - `stockPacks/{версія}_{i}` — увесь знімок пакетом ([StockPack]); `stock/{штрихкод}` — старий формат, лише видалення ([LegacyStockCleanup]);
 * - `stores/{код}` = {name, city, shopId?}; `shops/{shopId}.code1C`;
 * - `meta/stock` = {updatedAt, updatedBy, items, stores, pack, parts, bytes}.
 * Різниця рахується зі знімком попереднього файлу на телефоні — без читань `stock`.
 */
class NetworkStockRepository(private val db: FirebaseFirestore, filesDir: File) {
    val snapshot = StockSnapshot(File(filesDir, StockSnapshot.NAME))

    suspend fun info(): NetworkStockInfo? {
        val d = db.collection(META).document(STOCK).get().await()
        if (!d.exists()) return null
        return NetworkStockInfo(d.getTimestamp("updatedAt")?.toDate(), d.getString("updatedBy"),
            d.getLong("items")?.toInt() ?: 0, d.getLong("stores")?.toInt() ?: 0)
    }

    /** Відомі склади: ~50 читань. */
    suspend fun stores(): Map<String, StoreDoc> =
        db.collection(STORES).get().await().documents.associate {
            it.id to StoreDoc(it.getString("name").orEmpty(), it.getString("city").orEmpty(), it.getString("shopId")?.ifEmpty { null })
        }

    suspend fun shops(): List<ShopRef> =
        db.collection(SHOPS).get().await().documents
            .map { ShopRef(it.id, it.getString("address").orEmpty(), it.getString("region").orEmpty()) }
            .sortedWith(compareBy({ it.region }, { it.address }))

    /** «Який це магазин?»: склад 1С ↔ магазин застосунку. */
    suspend fun link(code: String, shopId: String) {
        db.batch()
            .set(db.collection(STORES).document(code), mapOf("shopId" to shopId), SetOptions.merge())
            .set(db.collection(SHOPS).document(shopId), mapOf("code1C" to code), SetOptions.merge())
            .commit().await()
    }

    /**
     * Пише пакет залишків ([StockPack]): кілька документів `stockPacks`, потім `meta/stock` (нова версія),
     * потім видаляє старий пакет. Нічого не змінилося і пакет уже є — жодного запису.
     * Знімок зберігаємо лише після успішного запису всього.
     */
    suspend fun upload(
        employee: Employee, fresh: NetworkStock, plan: StockPlan, known: Map<String, StoreDoc>, startedAt: Long,
        onProgress: (Int, Int) -> Unit,
    ): NetworkImportResult {
        val storeWrites = storesToWrite(fresh.stores, known)
        val old = runCatching { db.collection(META).document(STOCK).get().await() }.getOrNull()
        val oldPack = old?.getString("pack")
        val oldParts = old?.getLong("parts")?.toInt() ?: 0
        val storeCount = plan.items.values.flatMap { it.q.keys }.toSet().size
        val needed = plan.writes > 0 || oldPack == null

        if (storeWrites.isNotEmpty()) {
            val b = db.batch()
            storeWrites.forEach { s -> b.set(db.collection(STORES).document(s.code), mapOf("name" to s.name.take(200), "city" to s.city.take(100)), SetOptions.merge()) }
            b.commit().await()
        }

        if (needed) {
            val tmp = File(snapshot.file.parentFile, "upload.pack")
            StockSnapshot(tmp).write(plan.items)
            val parts = StockPack.split(tmp.readBytes())
            tmp.delete()
            val version = System.currentTimeMillis().toString()
            onProgress(0, parts.size)
            parts.forEachIndexed { i, bytes ->
                val doc = mutableMapOf<String, Any>("v" to version, "i" to i, "n" to parts.size, "data" to Blob.fromBytes(bytes))
                if (i == 0) doc["changed"] = Blob.fromBytes(StockPack.gzipLines(plan.changed))
                db.collection(PACKS).document("${version}_$i").set(doc).await()
                onProgress(i + 1, parts.size)
            }
            db.collection(META).document(STOCK).set(
                mapOf(
                    "updatedAt" to FieldValue.serverTimestamp(), "updatedBy" to employee.name.take(100),
                    "items" to plan.items.size, "stores" to storeCount, "pack" to version, "parts" to parts.size, "bytes" to parts.sumOf { it.size },
                ),
            ).await()
            if (oldPack != null) runCatching {
                val b = db.batch()
                (0 until oldParts).forEach { b.delete(db.collection(PACKS).document("${oldPack}_$it")) }
                b.commit().await()
            }
            db.collection(ADMIN_LOG).add(
                mapOf(
                    "by" to employee.uid, "at" to FieldValue.serverTimestamp(), "action" to ACTION,
                    "details" to summary(storeCount, plan.items.size, plan.changed.size, plan.removed.size, (System.currentTimeMillis() - startedAt) / 1000),
                ),
            ).await()
        }
        snapshot.write(plan.items)
        val seconds = (System.currentTimeMillis() - startedAt) / 1000
        return NetworkImportResult(storeCount, plan.items.size, plan.changed.size, plan.removed.size, plan.keptStores, seconds)
    }

    companion object {
        const val STOCK = "stock"
        const val PACKS = "stockPacks"
        const val STORES = "stores"
        const val SHOPS = "shops"
        const val META = "meta"
        const val ADMIN_LOG = "adminLog"
        const val ACTION = "stockImport"

        /** Firestore — до 500 операцій у пачці; лишаємо запас. */
        const val BATCH = 450

        fun toDoc(item: NetItem): Map<String, Any> = mapOf(
            "q" to item.q.mapValues { (_, v) -> if (v == Math.floor(v) && v < 1e15) v.toLong() else v },
            "name" to item.name.take(300), "code" to item.code.take(40), "article" to item.article.take(100),
            "updatedAt" to FieldValue.serverTimestamp(),
        )

        /** Склади, яких ще немає в stores або в яких змінилися назва чи місто. */
        fun storesToWrite(stores: List<NetStore>, known: Map<String, StoreDoc>): List<NetStore> =
            stores.filter { s -> known[s.code].let { it == null || it.name != s.name.take(200) || it.city != s.city.take(100) } }

        fun summary(stores: Int, items: Int, changed: Int, removed: Int, seconds: Long) =
            "магазинів $stores · товарів $items · змінено $changed · видалено $removed · ${seconds} с"
    }
}
