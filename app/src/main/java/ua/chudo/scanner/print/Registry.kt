package ua.chudo.scanner.print

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

/** Id принтера в реестре: MAC, иначе модель+имя. Смена IP не меняет id — дублей нет. */
object PrinterIds {
    fun of(mac: String?, model: String?, name: String? = null): String {
        mac?.filter { it.isLetterOrDigit() }?.lowercase()?.takeIf { it.length == 12 && it.any { c -> c != '0' } }?.let { return "mac-$it" }
        val base = listOfNotNull(model, name).joinToString(" ").lowercase()
            // «Brother MFC-L2700DW series» и «brother mfc-l2700dw» — один принтер.
            .replace("series", " ")
            .replace(Regex("[^a-z0-9а-яіїєґ]+"), "-").trim('-')
        return "m-" + base.ifEmpty { "printer" }.take(80)
    }

    /** Принтер компьютера: агент + имя принтера в Windows. */
    fun agent(agentId: String, printerName: String) = of(null, "agent $agentId", printerName)
}

/**
 * Общий реестр принтеров магазина (Firestore):
 * - `shops/{shopId}/printers/{printerId}`: model, kind, ip, port, transport, agentId?, printerName?, lastSeen, lastOkBy, density?, speed?;
 * - `shops/{shopId}.printerSubnets`: подсети, где искать принтеры (пополняются подсетью каждого найденного).
 * Любой телефон, нашедший принтер или напечатавший, делает upsert; остальные получают список слушателем и не сканируют.
 */
class PrinterRegistry(private val db: FirebaseFirestore, val shopId: String) {
    private val shop get() = db.collection("shops").document(shopId)
    private val printers get() = shop.collection("printers")

    fun listen(): Flow<List<PrinterRecord>> = callbackFlow {
        val reg = printers.addSnapshotListener { snap, _ ->
            if (snap != null) trySend(snap.documents.mapNotNull { it.toRecord() })
        }
        awaitClose { reg.remove() }
    }

    fun subnets(): Flow<List<String>> = callbackFlow {
        val reg = shop.addSnapshotListener { snap, _ ->
            @Suppress("UNCHECKED_CAST")
            val list = (snap?.get("printerSubnets") as? List<Any?>).orEmpty().filterIsInstance<String>()
            trySend(list.mapNotNull(Cidr::normalize).distinct())
        }
        awaitClose { reg.remove() }
    }

    /** Найден или напечатал: адрес, время, кто. Поля администратора (density/speed) не трогаем. */
    suspend fun upsert(r: PrinterRecord, uid: String, ok: Boolean = false) {
        if (r.transport == Link.USB) return // USB — только этот телефон
        val data = mutableMapOf<String, Any>(
            "model" to r.model, "kind" to r.kind.wire, "ip" to r.ip, "port" to r.port,
            "transport" to r.transport.wire, "lastSeen" to FieldValue.serverTimestamp(),
        )
        if (r.agentId.isNotEmpty()) data["agentId"] = r.agentId
        if (r.printerName.isNotEmpty()) data["printerName"] = r.printerName
        if (ok) data["lastOkBy"] = uid
        printers.document(r.id).set(data, SetOptions.merge()).await()
        if (r.transport == Link.TCP) Cidr.slash24(r.ip)?.let { addSubnet(it) }
    }

    /** Принтер жив: обновляем только lastSeen (и IP, если сменился). */
    suspend fun touch(r: PrinterRecord) {
        if (r.transport == Link.USB) return
        printers.document(r.id).set(mapOf("ip" to r.ip, "lastSeen" to FieldValue.serverTimestamp()), SetOptions.merge()).await()
    }

    suspend fun addSubnet(cidr: String) {
        val norm = Cidr.normalize(cidr) ?: return
        shop.set(mapOf("printerSubnets" to FieldValue.arrayUnion(norm)), SetOptions.merge()).await()
    }

    /** Только администратор (правила Firestore). */
    suspend fun setTuning(id: String, density: Int?, speed: Int?) {
        printers.document(id).set(
            mapOf(
                "density" to (density ?: FieldValue.delete()), "speed" to (speed ?: FieldValue.delete()),
                // Правила требуют свежий lastSeen в каждой записи реестра.
                "lastSeen" to FieldValue.serverTimestamp(),
            ),
            SetOptions.merge(),
        ).await()
    }

    suspend fun delete(id: String) {
        printers.document(id).delete().await()
    }

    companion object {
        fun DocumentSnapshot.toRecord(): PrinterRecord? {
            val kind = LabelKind.ofWire(getString("kind")) ?: return null
            return PrinterRecord(
                id = id,
                model = getString("model").orEmpty(),
                kind = kind,
                ip = getString("ip").orEmpty(),
                port = getLong("port")?.toInt() ?: 9100,
                transport = Link.ofWire(getString("transport")),
                agentId = getString("agentId").orEmpty(),
                printerName = getString("printerName").orEmpty(),
                lastSeen = (get("lastSeen") as? Timestamp)?.toDate()?.time ?: 0,
                lastOkBy = getString("lastOkBy").orEmpty(),
                density = getLong("density")?.toInt(),
                speed = getLong("speed")?.toInt(),
            )
        }
    }
}
