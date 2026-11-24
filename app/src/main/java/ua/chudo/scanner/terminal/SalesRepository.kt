package ua.chudo.scanner.terminal

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

/** `sales/{id}`: одне записування на «Передати на касу». */
class SalesRepository(private val db: FirebaseFirestore) {
    suspend fun send(shopId: String, by: String, cart: Cart): Sale {
        val ref = db.collection(SALES).document()
        val sale = Sale.of(ref.id, shopId, by, cart, SaleCode.generate())
        ref.set(sale.toDoc() + ("at" to FieldValue.serverTimestamp())).await()
        return sale
    }

    /** Скасувати свій кошик (або адмін магазину — будь-який у своєму магазині). */
    suspend fun cancel(id: String) {
        db.collection(SALES).document(id).update("status", SaleStatus.CANCELLED.id).await()
    }

    companion object { const val SALES = "sales" }
}
