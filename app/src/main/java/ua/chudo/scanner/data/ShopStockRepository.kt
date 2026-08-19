package ua.chudo.scanner.data

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.WriteBatch
import kotlinx.coroutines.tasks.await
import java.util.Date

data class StockItem(
    val article: String?,
    val barcode: String?,
    val name: String?,
    val qty: Double,
)

/** Когда и кем последний раз загружены остатки магазина. */
data class ShopStockInfo(val updatedAt: Date?, val updatedBy: String?, val rows: Int)

data class ImportResult(val total: Int, val changed: Int, val removed: Int)

/**
 * Остатки одного магазина: `shops/{shopId}/stock/{key}`, где key — артикул (или `bc_<штрихкод>`).
 * Читать и писать может только подтверждённый сотрудник этого магазина — см. firestore.rules.
 */
class ShopStockRepository(private val db: FirebaseFirestore) {

    private fun shop(shopId: String) = db.collection(SHOPS).document(shopId)
    private fun stock(shopId: String) = shop(shopId).collection(STOCK)

    suspend fun info(shopId: String): ShopStockInfo? {
        val d = shop(shopId).get().await()
        if (!d.exists()) return null
        return ShopStockInfo(d.getTimestamp("updatedAt")?.toDate(), d.getString("updatedBy"), d.getLong("rows")?.toInt() ?: 0)
    }

    /** Ищем по артикулу с сайта, потом по штрихкодам. `null` — товара нет в выгрузке. */
    suspend fun find(shopId: String, article: String?, barcodes: List<String>): StockItem? {
        article?.let { a ->
            val d = stock(shopId).document(keyOf(a, null)).get().await()
            if (d.exists()) return d.toItem()
        }
        for (bc in barcodes.map { it.filter(Char::isDigit) }.filter { it.isNotEmpty() }.distinct()) {
            val q = stock(shopId).whereEqualTo("barcode", bc).limit(1).get().await()
            q.documents.firstOrNull()?.let { return it.toItem() }
            val d = stock(shopId).document(keyOf(null, bc)).get().await()
            if (d.exists()) return d.toItem()
        }
        return null
    }

    /**
     * Заменяет остатки магазина содержимым выгрузки: пишем только изменившиеся строки
     * и удаляем то, чего в выгрузке больше нет (бесплатный лимит Firestore — 20 000 записей в сутки).
     */
    suspend fun import(employee: Employee, rows: List<StockRow>, onProgress: (Int, Int) -> Unit): ImportResult {
        val old = stock(employee.shopId).get().await().documents.associate { it.id to it.toItem() }
        val fresh = rows.associateBy { keyOf(it.article, it.barcode) }

        val writes = fresh.filter { (key, r) ->
            val o = old[key]
            o == null || o.qty != r.qty || o.name != r.name || o.barcode != r.barcode || o.article != r.article
        }
        val removes = old.keys - fresh.keys
        val total = writes.size + removes.size

        var done = 0
        var batch: WriteBatch = db.batch()
        var inBatch = 0
        suspend fun flush() {
            if (inBatch == 0) return
            batch.commit().await()
            done += inBatch
            onProgress(done, total)
            batch = db.batch()
            inBatch = 0
        }
        for ((key, r) in writes) {
            batch.set(
                stock(employee.shopId).document(key),
                mapOf("article" to r.article, "barcode" to r.barcode, "name" to r.name, "qty" to r.qty),
            )
            if (++inBatch == BATCH) flush()
        }
        for (key in removes) {
            batch.delete(stock(employee.shopId).document(key))
            if (++inBatch == BATCH) flush()
        }
        flush()

        shop(employee.shopId).set(
            mapOf(
                "region" to employee.region,
                "address" to employee.shop,
                "rows" to fresh.size,
                "updatedAt" to FieldValue.serverTimestamp(),
                "updatedBy" to employee.name,
            ),
        ).await()
        return ImportResult(fresh.size, writes.size, removes.size)
    }

    private fun com.google.firebase.firestore.DocumentSnapshot.toItem() = StockItem(
        article = getString("article"),
        barcode = getString("barcode"),
        name = getString("name"),
        qty = getDouble("qty") ?: 0.0,
    )

    companion object {
        const val SHOPS = "shops"
        const val STOCK = "stock"
        private const val BATCH = 450

        /** id документа: артикул без пробелов в верхнем регистре; «/» в id Firestore запрещён. */
        fun keyOf(article: String?, barcode: String?): String {
            val a = article?.trim()?.uppercase()?.replace(Regex("\\s+"), "")?.replace('/', '_')?.ifEmpty { null }
            return a?.takeUnless { it == "." || it == ".." || it.startsWith("__") }
                ?: "bc_${barcode.orEmpty().filter(Char::isDigit)}"
        }
    }
}
