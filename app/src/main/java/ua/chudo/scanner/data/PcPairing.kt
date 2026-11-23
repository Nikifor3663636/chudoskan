package ua.chudo.scanner.data

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.tasks.await
import java.util.Date

/** Комп'ютер магазину з «ЧудоСкан Друк» (devices/{uid} + пульс agents/{uid}). */
data class PcCard(
    val uid: String,
    val pcName: String,
    val online: Boolean,
    /** Godex на ПК і його стан («Готовий», «Немає паперу»…); `null` — Godex не знайдено. */
    val printer: String?,
    val printerText: String?,
    val printerOk: Boolean,
    val queue: Int,
    val lastError: String,
    val version: String,
    val lastSeen: Date?,
)

class PcPairingException(message: String) : Exception(message)

/** Код з вікна програми → devices/{uid}. Чисті перевірки — для тестів. */
object PcPairing {
    /** «123 456» → «123456». */
    fun normalize(code: String) = code.filter(Char::isDigit)

    fun formatError(code: String): String? =
        if (Regex("^[1-9]\\d{5}$").matches(normalize(code))) null else "Код — 6 цифр із вікна програми «ЧудоСкан Друк»"

    /** Перевірка документа devicePairing/{code}: є, діє, має uid. */
    fun pairingError(exists: Boolean, uid: String?, expires: Date?, now: Date): String? = when {
        !exists || uid.isNullOrBlank() -> "Неправильний код. Перевірте цифри у вікні програми."
        expires == null || !expires.after(now) -> "Код минув — у вікні програми вже новий код."
        else -> null
    }

    /** Агент на зв'язку: пульс не старший за 3 хв і не вимкнений. */
    fun online(lastSeen: Date?, onlineFlag: Boolean?, now: Date): Boolean =
        onlineFlag != false && lastSeen != null && now.time - lastSeen.time < NetworkAdminRepository.AGENT_ONLINE_MS
}

class PcRepository(private val db: FirebaseFirestore) {

    /**
     * «Підключити комп'ютер»: get devicePairing/{code} → перевірити expires → devices/{uid} = {shopId, pcName, code,
     * approvedBy, approvedAt} → видалити devicePairing/{code} → adminLog. Повертає ім'я ПК.
     */
    suspend fun pair(employee: Employee, rawCode: String): String {
        PcPairing.formatError(rawCode)?.let { throw PcPairingException(it) }
        val code = PcPairing.normalize(rawCode)
        try {
            val p = db.collection("devicePairing").document(code).get().await()
            val uid = p.getString("uid")
            PcPairing.pairingError(p.exists(), uid, p.getTimestamp("expires")?.toDate(), Date())?.let { throw PcPairingException(it) }
            val pcName = p.getString("pcName").orEmpty().ifBlank { "Комп'ютер" }
            db.collection("devices").document(uid!!).set(
                mapOf(
                    "shopId" to employee.shopId, "pcName" to pcName.take(100), "code" to code,
                    "approvedBy" to employee.uid, "approvedAt" to FieldValue.serverTimestamp(),
                ),
            ).await()
            runCatching { db.collection("devicePairing").document(code).delete().await() }
            runCatching { NetworkAdminRepository(db).log(employee.uid, "pairPc", pcName, employee.shopId) }
            return pcName
        } catch (e: FirebaseFirestoreException) {
            if (e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                throw PcPairingException("Немає прав підключати комп'ютер до цього магазину (потрібен адмін магазину або мережі).")
            }
            throw e
        }
    }

    /** Комп'ютери магазину: devices where shopId == … + пульс agents/{uid}. */
    suspend fun pcs(shopId: String): List<PcCard> {
        val devices = db.collection("devices").whereEqualTo("shopId", shopId).get().await().documents
        val now = Date()
        return devices.map { d ->
            val a = runCatching { db.collection("agents").document(d.id).get().await() }.getOrNull()
            @Suppress("UNCHECKED_CAST")
            val printers = (a?.get("printers") as? List<Map<String, Any?>>).orEmpty()
            val main = printers.firstOrNull { it["isDefault"] == true } ?: printers.firstOrNull()
            PcCard(
                uid = d.id,
                pcName = d.getString("pcName").orEmpty(),
                online = PcPairing.online(a?.getTimestamp("lastSeen")?.toDate(), a?.getBoolean("online"), now),
                printer = main?.get("name") as? String,
                printerText = main?.get("text") as? String,
                printerOk = (main?.get("state") as? String ?: "ok") == "ok",
                queue = a?.getLong("queue")?.toInt() ?: 0,
                lastError = a?.getString("lastError").orEmpty(),
                version = a?.getString("version").orEmpty(),
                lastSeen = a?.getTimestamp("lastSeen")?.toDate(),
            )
        }.sortedBy { it.pcName }
    }

    /** «Відключити»: видалити devices/{uid} — ПК знову покаже код, друк через нього зупиниться. */
    suspend fun unpair(employee: Employee, pc: PcCard) {
        db.collection("devices").document(pc.uid).delete().await()
        runCatching { NetworkAdminRepository(db).log(employee.uid, "unpairPc", pc.pcName, employee.shopId) }
    }
}
