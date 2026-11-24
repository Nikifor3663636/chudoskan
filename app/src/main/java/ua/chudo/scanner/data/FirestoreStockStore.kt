package ua.chudo.scanner.data

import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

/** [StockStore] поверх Firestore: `meta/stock`, `stockPacks`, `stores`, `shops`. */
class FirestoreStockStore(private val db: FirebaseFirestore) : StockStore {
    private fun col(name: String) = db.collection(name)

    override suspend fun meta(): StockStore.Meta? {
        val d = col(NetworkStockRepository.META).document(NetworkStockRepository.STOCK).get().await()
        if (!d.exists()) return null
        return StockStore.Meta(d.getTimestamp("updatedAt")?.toDate(), d.getString("pack"), d.getLong("parts")?.toInt() ?: 0, d.getLong("bytes"))
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
}
