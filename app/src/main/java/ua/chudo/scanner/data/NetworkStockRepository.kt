package ua.chudo.scanner.data

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
 * - `stock/{штрихкод}` = {q: {"22": 1, …}, name, code, article, updatedAt} — лише ненульові;
 * - `stores/{код}` = {name, city, shopId?}; `shops/{shopId}.code1C`;
 * - `meta/stock` = {updatedAt, updatedBy, items, stores}.
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
     * Пише лише змінені товари, видаляє зниклі (пачками по [BATCH]), оновлює склади, `meta/stock` і журнал.
     * Нічого не змінилося — жодного запису. Знімок зберігаємо лише після успішного запису всього.
     */
    suspend fun upload(
        employee: Employee, fresh: NetworkStock, plan: StockPlan, known: Map<String, StoreDoc>, startedAt: Long,
        onProgress: (Int, Int) -> Unit,
    ): NetworkImportResult {
        val storeWrites = storesToWrite(fresh.stores, known)
        val total = plan.writes
        var done = 0
        onProgress(0, total)

        if (storeWrites.isNotEmpty()) {
            val b = db.batch()
            storeWrites.forEach { s -> b.set(db.collection(STORES).document(s.code), mapOf("name" to s.name.take(200), "city" to s.city.take(100)), SetOptions.merge()) }
            b.commit().await()
        }

        val ops: List<Pair<String, NetItem?>> = plan.changed.map { it to plan.items.getValue(it) } + plan.removed.map { it to null }
        for (chunk in ops.chunked(BATCH)) {
            val b = db.batch()
            for ((bc, item) in chunk) {
                val ref = db.collection(STOCK).document(bc)
                if (item == null) b.delete(ref) else b.set(ref, toDoc(item))
            }
            b.commit().await()
            done += chunk.size
            onProgress(done, total)
        }

        val storeCount = plan.items.values.flatMap { it.q.keys }.toSet().size
        val seconds = (System.currentTimeMillis() - startedAt) / 1000
        if (total > 0) {
            db.collection(META).document(STOCK).set(
                mapOf(
                    "updatedAt" to FieldValue.serverTimestamp(), "updatedBy" to employee.name.take(100),
                    "items" to plan.items.size, "stores" to storeCount,
                ),
            ).await()
            db.collection(ADMIN_LOG).add(
                mapOf(
                    "by" to employee.uid, "at" to FieldValue.serverTimestamp(), "action" to ACTION,
                    "details" to summary(storeCount, plan.items.size, plan.changed.size, plan.removed.size, seconds),
                ),
            ).await()
        }
        snapshot.write(plan.items)
        return NetworkImportResult(storeCount, plan.items.size, plan.changed.size, plan.removed.size, plan.keptStores, seconds)
    }

    companion object {
        const val STOCK = "stock"
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
