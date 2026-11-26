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

/** Supabase: commit або застосовано, або сервер просить підтвердження (сеанс [id] лишається відкритим). */
sealed interface SupabaseUpload {
    data class Done(val result: NetworkImportResult) : SupabaseUpload
    data class NeedConfirm(val id: Long, val file: Int, val previous: Int) : SupabaseUpload
}

/**
 * Залишки мережі (тільки stockAdmin — див. firestore.rules):
 * - `stock/{штрихкод}` = {q: {"22": 1, …}, name, code, article, updatedAt} — лише ненульові;
 * - `stores/{код}` = {name, city, shopId?}; `shops/{shopId}.code1C`;
 * - `meta/stock` = {updatedAt, updatedBy, items, stores}.
 * Різниця рахується зі знімком попереднього файлу на телефоні — без читань `stock`.
 */
class NetworkStockRepository(private val db: FirebaseFirestore, filesDir: File) {
    val snapshot = StockSnapshot(File(filesDir, StockSnapshot.NAME))

    /** `meta/stock.updatedAt` (мс) на момент запису знімка — щоб помітити завантаження з іншого місця (сторінка на ПК). */
    private val snapshotMark = File(filesDir, SNAPSHOT_MARK_NAME)

    /**
     * З чим порівнювати новий файл: знімок на телефоні, якщо після нього ніхто інший не завантажував
     * (сторінка «Залишки мережі» на ПК, інший телефон); інакше — те, що зараз у `stock/` (~24 000 читань).
     */
    suspend fun baseline(): Map<String, NetItem>? {
        val snap = snapshot.read()
        val metaAt = runCatching { db.collection(META).document(STOCK).get().await().getTimestamp("updatedAt") }
            .getOrElse { return snap } // без мережі — як раніше
            ?.toDate()?.time
        val mark = runCatching { snapshotMark.readText().trim().toLong() }.getOrNull()
        if (useSnapshot(snap != null, mark, metaAt)) return snap
        return readAll().ifEmpty { null }
    }

    /** Усі `stock/` сторінками по [PAGE], щоб не тримати тисячі знімків Firestore одночасно. */
    private suspend fun readAll(): Map<String, NetItem> {
        val out = HashMap<String, NetItem>()
        var last: com.google.firebase.firestore.DocumentSnapshot? = null
        while (true) {
            var q = db.collection(STOCK).orderBy(com.google.firebase.firestore.FieldPath.documentId()).limit(PAGE.toLong())
            last?.let { q = q.startAfter(it) }
            val page = q.get().await()
            for (d in page.documents) out[d.id] = fromDoc(d.getString("name"), d.getString("code"), d.getString("article"), d.get("q"))
            if (page.size() < PAGE) break
            last = page.documents.last()
        }
        return out
    }

    suspend fun info(): NetworkStockInfo? {
        if (Backend.isSupabase) return SupabaseStock.info()
        val d = db.collection(META).document(STOCK).get().await()
        if (!d.exists()) return null
        return NetworkStockInfo(d.getTimestamp("updatedAt")?.toDate(), d.getString("updatedBy"),
            d.getLong("items")?.toInt() ?: 0, d.getLong("stores")?.toInt() ?: 0)
    }

    /** Відомі склади: ~50 читань. */
    suspend fun stores(): Map<String, StoreDoc> =
        if (Backend.isSupabase) SupabaseStock.stores() else db.collection(STORES).get().await().documents.associate {
            it.id to StoreDoc(it.getString("name").orEmpty(), it.getString("city").orEmpty(), it.getString("shopId")?.ifEmpty { null })
        }

    suspend fun shops(): List<ShopRef> =
        if (Backend.isSupabase) SupabaseStock.shops() else db.collection(SHOPS).get().await().documents
            .map { ShopRef(it.id, it.getString("address").orEmpty(), it.getString("region").orEmpty()) }
            .sortedWith(compareBy({ it.region }, { it.address }))

    /** «Який це магазин?»: склад 1С ↔ магазин застосунку. */
    suspend fun link(code: String, shopId: String) {
        if (Backend.isSupabase) { SupabaseStock.link(code, shopId); return }
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
        // Серверний час кінця попереднього завантаження: усе, що запишемо зараз, матиме updatedAt пізніше.
        // Так «змінені з минулого завантаження» не залежать від годинника телефону.
        val previousEnd = if (total > 0) runCatching { db.collection(META).document(STOCK).get().await().getTimestamp("updatedAt") }.getOrNull() else null

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
            val meta = mutableMapOf<String, Any>(
                "updatedAt" to FieldValue.serverTimestamp(), "updatedBy" to employee.name.take(100),
                "items" to plan.items.size, "stores" to storeCount,
            )
            // Товари з updatedAt > changedAfter — «змінені з минулого завантаження» (пакетний друк цінників).
            previousEnd?.let { meta["changedAfter"] = it }
            db.collection(META).document(STOCK).set(meta).await()
            db.collection(ADMIN_LOG).add(
                mapOf(
                    "by" to employee.uid, "at" to FieldValue.serverTimestamp(), "action" to ACTION,
                    "details" to summary(storeCount, plan.items.size, plan.changed.size, plan.removed.size, seconds),
                ),
            ).await()
        }
        snapshot.write(plan.items)
        // Позначка «знімок = ось це завантаження»; не вийшло — наступного разу просто прочитаємо Firestore.
        runCatching {
            db.collection(META).document(STOCK).get().await().getTimestamp("updatedAt")?.toDate()?.time
        }.getOrNull()?.let { at -> runCatching { snapshotMark.writeText(at.toString()) } } ?: snapshotMark.delete()
        return NetworkImportResult(storeCount, plan.items.size, plan.changed.size, plan.removed.size, plan.keptStores, seconds)
    }

    /** Supabase: нові склади — у stores до прив'язки «Який це магазин?» (Firestore пише їх під час upload). */
    suspend fun prepareStores(fresh: NetworkStock, known: Map<String, StoreDoc>): Map<String, StoreDoc> {
        if (!Backend.isSupabase) return known
        val sw = storesToWrite(fresh.stores, known)
        SupabaseStock.storesUpsert(sw)
        return known + sw.associate { it.code to StoreDoc(it.name.take(200), it.city.take(100), known[it.code]?.shopId) }
    }

    /**
     * Supabase: begin → пачки по 2 000 → commit (одна транзакція на сервері). Різницю рахує сервер —
     * знімок на телефоні не потрібен. [uploadId] — сеанс, відкритий до запиту підтвердження: тоді лише commit.
     */
    suspend fun uploadSupabase(
        fresh: NetworkStock, uploadId: Long?, confirm: Boolean, fileName: String, startedAt: Long,
        onProgress: (Int, Int) -> Unit,
    ): SupabaseUpload {
        val total = fresh.items.size
        val id = uploadId ?: SupabaseStock.begin(fileName).also { id ->
            var done = 0
            onProgress(0, total)
            try {
                for (rows in SupabaseStock.rows(fresh.items)) {
                    done += SupabaseStock.chunk(id, rows)
                    onProgress(done.coerceAtMost(total), total)
                }
            } catch (e: Exception) { SupabaseStock.abort(id); throw e }
        }
        val report = try {
            SupabaseStock.commit(id, fresh.stores.map { it.code }, confirm)
        } catch (e: io.github.jan.supabase.postgrest.exception.PostgrestRestException) {
            when (val p = CommitProblem.of(e.message, e.details?.toString()?.trim('"'))) {
                is CommitProblem.Confirm -> return SupabaseUpload.NeedConfirm(id, p.file, p.previous)
                is CommitProblem.Unknown -> { SupabaseStock.abort(id); throw StockFileException("Невідомі склади: ЧО ${p.codes.joinToString(", ЧО ")}. Спробуйте ще раз.") }
                null -> { SupabaseStock.abort(id); throw e }
            }
        }
        snapshot.delete(); snapshotMark.delete()
        val seconds = (System.currentTimeMillis() - startedAt) / 1000
        return SupabaseUpload.Done(NetworkImportResult(report.stores, report.items, report.changed, report.removed, report.kept, seconds))
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
        private const val PAGE = 2000
        const val SNAPSHOT_MARK_NAME = "last-stock.mark"

        /**
         * Знімок годиться, якщо він є і записаний саме після останнього завантаження ([mark] == [metaAt]).
         * Завантажень ще не було ([metaAt] == null) — теж знімок (або перше завантаження).
         */
        fun useSnapshot(hasSnapshot: Boolean, mark: Long?, metaAt: Long?): Boolean =
            hasSnapshot && (metaAt == null || mark == metaAt)

        /** Документ `stock/{штрихкод}` → [NetItem]; кількості з Firestore бувають Long — переводимо в Double. */
        fun fromDoc(name: String?, code: String?, article: String?, q: Any?): NetItem = NetItem(
            name.orEmpty(), code.orEmpty(), article.orEmpty(),
            (q as? Map<*, *>)?.entries?.mapNotNull { (k, v) ->
                val key = k as? String ?: return@mapNotNull null
                val n = (v as? Number)?.toDouble() ?: return@mapNotNull null
                key to n
            }?.toMap() ?: emptyMap(),
        )

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
