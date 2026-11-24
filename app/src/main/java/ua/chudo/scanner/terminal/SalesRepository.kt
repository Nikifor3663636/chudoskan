package ua.chudo.scanner.terminal

import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import ua.chudo.scanner.payment.PaymentRecord
import ua.chudo.scanner.payment.SaleRecord
import ua.chudo.scanner.payment.SaleStates
import ua.chudo.scanner.payment.SaleStore
import ua.chudo.scanner.payment.ShopPayment

/**
 * `sales/{id}` і журнал `sales/{id}/log/{auto}` = {by, at, from, to}; повернення — `refunds/{id}`.
 * Поля `fiscal`, `exported1C`, `verified` пише лише довірена сторона (ПК магазину) — правила забороняють їх телефону.
 */
class SalesRepository(private val db: FirebaseFirestore, private val uid: () -> String?) : SaleStore {
    private fun sales() = db.collection(SALES)

    /** «Передати на касу» / перед оплатою: одне записування. */
    suspend fun send(shopId: String, by: String, cart: Cart, status: SaleStatus = SaleStatus.DRAFT): Sale {
        val ref = sales().document()
        val sale = Sale.of(ref.id, shopId, by, cart, SaleCode.generate(), status)
        ref.set(sale.toDoc() + ("at" to FieldValue.serverTimestamp())).await()
        return sale
    }

    /** Скасувати свій кошик (або адмін магазину — будь-який у своєму магазині). */
    suspend fun cancel(id: String) {
        val s = get(id) ?: return
        move(id, s.status, SaleStatus.CANCELLED)
    }

    /** `shops/{shopId}.payment` — одне читання за сесію. */
    suspend fun shopPayment(shopId: String): ShopPayment? {
        val d = db.collection("shops").document(shopId).get().await()
        @Suppress("UNCHECKED_CAST")
        val p = d.get("payment") as? Map<String, Any?> ?: return null
        return ShopPayment((p["provider"] as? String) ?: ShopPayment.NONE, p["terminalId"] as? String)
    }

    override suspend fun get(id: String): SaleRecord? {
        val d = sales().document(id).get().await()
        if (!d.exists()) return null
        return SaleRecord(
            d.id, d.getString("shopId").orEmpty(), d.getString("by").orEmpty(), d.getLong("total") ?: 0,
            SaleStatus.of(d.getString("status")) ?: SaleStatus.DRAFT,
        )
    }

    override suspend fun move(id: String, from: SaleStatus, to: SaleStatus, payment: PaymentRecord?) {
        check(SaleStates.clientCan(from, to)) { "Перехід ${from.id} → ${to.id} заборонено" }
        val ref = sales().document(id)
        db.runTransaction { tx ->
            val cur = SaleStatus.of(tx.get(ref).getString("status"))
            check(cur == from) { "Стан продажу змінився: ${cur?.id}" }
            val upd = mutableMapOf<String, Any>("status" to to.id)
            payment?.let { upd["payment"] = it.toMap() }
            tx.update(ref, upd)
            tx.set(ref.collection(LOG).document(), log(from, to))
        }.await()
    }

    override suspend fun refunded(id: String, from: SaleStatus, refund: PaymentRecord) {
        val ref = sales().document(id)
        val sale = get(id) ?: error("Продаж не знайдено")
        db.batch()
            .set(db.collection(REFUNDS).document(), refund.toMap() + mapOf("saleId" to id, "shopId" to sale.shopId, "by" to uid().orEmpty(), "createdAt" to FieldValue.serverTimestamp()))
            .update(ref, "status", SaleStatus.REFUNDED.id)
            .set(ref.collection(LOG).document(), log(from, SaleStatus.REFUNDED))
            .commit().await()
    }

    override suspend fun paying(shopId: String, by: String): List<SaleRecord> =
        sales().whereEqualTo("shopId", shopId).whereEqualTo("by", by).whereEqualTo("status", SaleStatus.PAYING.id)
            .limit(20).get().await().documents.map {
                SaleRecord(it.id, shopId, by, it.getLong("total") ?: 0, SaleStatus.PAYING)
            }

    private fun log(from: SaleStatus, to: SaleStatus) =
        mapOf("by" to uid().orEmpty(), "at" to FieldValue.serverTimestamp(), "from" to from.id, "to" to to.id)

    private fun PaymentRecord.toMap(): Map<String, Any> = mapOf(
        "provider" to provider, "rrn" to approved.rrn, "authCode" to approved.authCode, "maskedPan" to approved.maskedPan,
        "amountKop" to approved.amountKop, "at" to Timestamp(approved.at),
    )

    companion object {
        const val SALES = "sales"
        const val REFUNDS = "refunds"
        const val LOG = "log"
    }
}
