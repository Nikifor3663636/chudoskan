package ua.chudo.scanner.data

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.QuerySnapshot
import kotlinx.coroutines.tasks.await
import java.util.Date

/** [StockStore] поверх Firestore: `meta/stock`, `stockPacks`, `stores`, `shops`, старий `stock/{bc}`. */
class FirestoreStockStore(private val db: FirebaseFirestore) : StockStore {
    private fun col(name: String) = db.collection(name)

    override suspend fun meta(): StockStore.Meta? {
        val d = col(NetworkStockRepository.META).document(NetworkStockRepository.STOCK).get().await()
        if (!d.exists()) return null
        return StockStore.Meta(
            d.getTimestamp("updatedAt")?.toDate(), d.getTimestamp("changedAfter")?.toDate(),
            d.getString("pack"), d.getLong("parts")?.toInt() ?: 0,
        )
    }

    override suspend fun packPart(version: String, index: Int): StockStore.Part {
        val d = col(NetworkStockRepository.PACKS).document("${version}_$index").get().await()
        return StockStore.Part(d.getBlob("data")?.toBytes(), d.getBlob("changed")?.toBytes())
    }

    override suspend fun stores(): Map<String, StoreDoc> =
        col(NetworkStockRepository.STORES).get().await().documents.associate {
            it.id to StoreDoc(it.getString("name").orEmpty(), it.getString("city").orEmpty(), it.getString("shopId")?.ifEmpty { null })
        }

    override suspend fun shopCode1C(shopId: String): String? =
        col(NetworkStockRepository.SHOPS).document(shopId).get().await().getString("code1C")

    override suspend fun legacyItem(barcode: String): NetItem? =
        col(NetworkStockRepository.STOCK).document(barcode).get().await().toNetItem()

    override suspend fun legacyChanged(after: Date, limit: Long) =
        col(NetworkStockRepository.STOCK).whereGreaterThan("updatedAt", Timestamp(after)).limit(limit).get().await().items()

    override suspend fun legacyWhere(field: String, value: String, limit: Long) =
        col(NetworkStockRepository.STOCK).whereEqualTo(field, value).limit(limit).get().await().items()

    override suspend fun legacyStoreItems(code: String, limit: Long): List<Pair<String, NetItem>> {
        val field = FieldPath.of("q", code)
        return col(NetworkStockRepository.STOCK).whereGreaterThan(field, 0)
            .orderBy(field, Query.Direction.DESCENDING).limit(limit).get().await().items()
    }

    private fun QuerySnapshot.items() = documents.mapNotNull { d -> d.toNetItem()?.let { d.id to it } }

    private fun DocumentSnapshot.toNetItem(): NetItem? {
        if (!exists()) return null
        @Suppress("UNCHECKED_CAST")
        val q = (get("q") as? Map<String, Any?>).orEmpty().mapNotNull { (k, v) -> (v as? Number)?.toDouble()?.let { k to it } }.toMap()
        return NetItem(getString("name").orEmpty(), getString("code").orEmpty(), getString("article").orEmpty(), q)
    }
}
