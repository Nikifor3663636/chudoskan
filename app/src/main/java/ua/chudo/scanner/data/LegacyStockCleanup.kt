package ua.chudo.scanner.data

import com.google.firebase.firestore.AggregateSource
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await
import java.time.LocalDate
import java.time.ZoneId

/** Стан видалення старих `stock/{штрихкод}`: скільки видалено сьогодні й скільки лишилося. */
data class CleanupState(val day: String, val deletedToday: Int, val remaining: Long?) {
    val leftToday: Int get() = LegacyStockCleanup.budget(day, day, deletedToday)
}

/** Що бачить Firestore: старі документи й лічильник у `meta/stockCleanup`. */
interface LegacyStockStore {
    suspend fun counter(): Pair<String, Int>?
    suspend fun saveCounter(day: String, deleted: Int, remaining: Long, by: String)
    /** Видаляє до [n] документів `stock`; повертає скільки видалено (0 — уже порожньо). */
    suspend fun deleteSome(n: Int): Int
    suspend fun remaining(): Long
}

/**
 * Старий формат `stock/{штрихкод}` (~22 000 документів) більше не читається — видаляємо пачками,
 * не більше [DAILY] на добу (безкоштовний ліміт Firestore — 20 000 видалень на добу на весь проєкт).
 */
class LegacyStockCleanup(private val store: LegacyStockStore, private val today: () -> String = ::todayKyiv) {

    suspend fun state(): CleanupState {
        val d = today()
        val deleted = store.counter()?.takeIf { it.first == d }?.second ?: 0
        return CleanupState(d, deleted, runCatching { store.remaining() }.getOrNull())
    }

    /** Видаляє, доки не вичерпано добовий ліміт або документи; [onProgress] — після кожної пачки. */
    suspend fun run(by: String, onProgress: (CleanupState) -> Unit = {}): CleanupState {
        val d = today()
        var deleted = store.counter()?.takeIf { it.first == d }?.second ?: 0
        while (true) {
            val n = minOf(BATCH, budget(d, d, deleted))
            if (n <= 0) break
            val done = store.deleteSome(n)
            if (done == 0) break
            deleted += done
            val left = store.remaining()
            store.saveCounter(d, deleted, left, by)
            onProgress(CleanupState(d, deleted, left))
            if (left == 0L) break
        }
        return CleanupState(d, deleted, store.remaining())
    }

    companion object {
        const val DAILY = 5_000
        const val BATCH = 450

        /** Скільки ще можна видалити сьогодні: лічильник іншого дня не рахується. */
        fun budget(counterDay: String, today: String, deleted: Int): Int =
            if (counterDay != today) DAILY else (DAILY - deleted).coerceAtLeast(0)

        fun todayKyiv(): String = LocalDate.now(ZoneId.of("Europe/Kyiv")).toString()
    }
}

class FirestoreLegacyStockStore(private val db: FirebaseFirestore) : LegacyStockStore {
    private val stock get() = db.collection(NetworkStockRepository.STOCK)
    private val doc get() = db.collection(NetworkStockRepository.META).document(CLEANUP)

    override suspend fun counter(): Pair<String, Int>? {
        val d = doc.get().await()
        val day = d.getString("day") ?: return null
        return day to (d.getLong("deleted")?.toInt() ?: 0)
    }

    override suspend fun saveCounter(day: String, deleted: Int, remaining: Long, by: String) {
        doc.set(
            mapOf("day" to day, "deleted" to deleted, "remaining" to remaining, "at" to FieldValue.serverTimestamp(), "by" to by.take(100)),
            SetOptions.merge(),
        ).await()
    }

    override suspend fun deleteSome(n: Int): Int {
        val docs = stock.limit(n.toLong()).get().await().documents
        if (docs.isEmpty()) return 0
        val b = db.batch()
        docs.forEach { b.delete(it.reference) }
        b.commit().await()
        return docs.size
    }

    /** Агрегатний підрахунок: ~1 читання на 1 000 документів. */
    override suspend fun remaining(): Long = stock.count().get(AggregateSource.SERVER).await().count

    private companion object { const val CLEANUP = "stockCleanup" }
}
