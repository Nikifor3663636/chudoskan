package ua.chudo.scanner.print

import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import ua.chudo.scanner.data.AgentRow
import ua.chudo.scanner.data.Supabase
import ua.chudo.scanner.data.SupabaseUsers
import ua.chudo.scanner.data.parseTime

@Serializable
data class PrinterRow(
    @SerialName("shop_id") val shopId: String,
    val id: String,
    val model: String = "",
    val kind: String = "",
    val name: String? = null,
    val ip: String = "",
    val port: Int = 9100,
    val transport: String = "tcp",
    @SerialName("agent_id") val agentId: String? = null,
    @SerialName("printer_name") val printerName: String? = null,
    @SerialName("last_seen") val lastSeen: String? = null,
    @SerialName("last_ok_by") val lastOkBy: String? = null,
    val density: Int? = null,
    val speed: Int? = null,
) {
    fun record(): PrinterRecord? = LabelKind.ofWire(kind)?.let { k ->
        PrinterRecord(
            id = id, model = model, name = name.orEmpty(), kind = k, ip = ip, port = port, transport = Link.ofWire(transport),
            agentId = agentId.orEmpty(), printerName = printerName.orEmpty(), lastSeen = parseTime(lastSeen)?.time ?: 0,
            lastOkBy = lastOkBy.orEmpty(), density = density, speed = speed,
        )
    }
}

@Serializable
private data class SubnetsRow(@SerialName("printer_subnets") val subnets: List<String> = emptyList())

@Serializable
data class AgentFull(
    val uid: String,
    @SerialName("shop_id") val shopId: String = "",
    @SerialName("pc_name") val pcName: String = "",
    val online: Boolean = false,
    @SerialName("last_seen") val lastSeen: String? = null,
    val version: String? = null,
    val queue: Int = 0,
    @SerialName("last_error") val lastError: String? = null,
    val printers: JsonElement = JsonArray(emptyList()),
) {
    /** [{name, isDefault, kind, text, state}] → карти, як у Firestore. */
    fun printerMaps(): List<Map<String, Any?>> = (printers as? JsonArray).orEmpty().mapNotNull { e ->
        (e as? JsonObject)?.mapValues { (_, v) ->
            when (v) { is JsonNull -> null; is JsonPrimitive -> v.booleanOrNull ?: v.contentOrNull; else -> v.toString() }
        }
    }
}

@Serializable
private data class JobRow(val id: String, val status: String = "", val error: String? = null)

/** Друк у Supabase: реєстр принтерів, підмережі, ПК-агенти, завдання. Права — RLS (supabase/migrations). */
object SupabasePrint {
    private val db get() = Supabase.client

    /** Початковий стан + перечитування на кожну зміну (Realtime). */
    private fun <T> live(table: String, filter: String, load: suspend () -> T): Flow<T> = flow {
        merge(flowOf(Unit), SupabaseUsers.changes(table, filter)).collect { runCatching { load() }.onSuccess { emit(it) } }
    }

    fun printers(shopId: String): Flow<List<PrinterRecord>> = live("printers", "shop_id=eq.$shopId") {
        db.from("printers").select { filter { eq("shop_id", shopId) } }.decodeList<PrinterRow>().mapNotNull { it.record() }
    }

    fun subnets(shopId: String): Flow<List<String>> = live("shops", "id=eq.$shopId") {
        db.from("shops").select(Columns.list("printer_subnets")) { filter { eq("id", shopId) } }
            .decodeList<SubnetsRow>().firstOrNull()?.subnets.orEmpty().mapNotNull(Cidr::normalize).distinct()
    }

    /** Знайдено або надруковано. density / speed / name не передаємо — їх змінює лише адмін (тригер). */
    suspend fun upsert(shopId: String, r: PrinterRecord, uid: String, ok: Boolean) {
        db.from("printers").upsert(buildJsonObject {
            put("shop_id", shopId); put("id", r.id); put("model", r.model.take(120)); put("kind", r.kind.wire)
            put("ip", r.ip); put("port", r.port); put("transport", r.transport.wire)
            if (r.agentId.isNotEmpty()) put("agent_id", r.agentId)
            if (r.printerName.isNotEmpty()) put("printer_name", r.printerName)
            if (ok) put("last_ok_by", uid)
        }) { onConflict = "shop_id,id" }
    }

    suspend fun touch(shopId: String, r: PrinterRecord) {
        db.from("printers").update(buildJsonObject { put("ip", r.ip) }) { filter { eq("shop_id", shopId); eq("id", r.id) } }
    }

    /** Підмережа знайденого принтера (до 32, без дублів). */
    suspend fun addSubnet(shopId: String, cidr: String) {
        val cur = db.from("shops").select(Columns.list("printer_subnets")) { filter { eq("id", shopId) } }.decodeList<SubnetsRow>().firstOrNull()
        val list = (cur?.subnets.orEmpty() + cidr).distinct().takeLast(32)
        if (cur != null && cur.subnets.contains(cidr)) return
        val body = buildJsonObject { put("printer_subnets", JsonArray(list.map(::JsonPrimitive))) }
        if (cur == null) db.from("shops").insert(JsonObject(body + ("id" to JsonPrimitive(shopId))))
        else db.from("shops").update(body) { filter { eq("id", shopId) } }
    }

    suspend fun setTuning(shopId: String, id: String, density: Int?, speed: Int?) {
        db.from("printers").update(buildJsonObject { put("density", density); put("speed", speed) }) { filter { eq("shop_id", shopId); eq("id", id) } }
    }

    suspend fun rename(shopId: String, id: String, name: String) {
        db.from("printers").update(buildJsonObject { put("name", name) }) { filter { eq("shop_id", shopId); eq("id", id) } }
    }

    suspend fun delete(shopId: String, id: String) {
        db.from("printers").delete { filter { eq("shop_id", shopId); eq("id", id) } }
    }

    // ---------- ПК-агенти й завдання ----------

    suspend fun agent(agentId: String): AgentFull? =
        db.from("agents").select { filter { eq("uid", agentId) } }.decodeList<AgentFull>().firstOrNull()

    suspend fun agents(shopId: String): List<AgentFull> =
        db.from("agents").select { filter { eq("shop_id", shopId) } }.decodeList<AgentFull>()

    suspend fun pairedIds(shopId: String): Set<String> =
        db.from("devices").select(Columns.list("uid")) { filter { eq("shop_id", shopId) } }.decodeList<AgentRow>().map { it.uid }.toSet()

    /** Завдання в print_jobs і очікування done / error (до [waitMs]). */
    suspend fun printJob(agentId: String, printer: String, shopId: String, lang: String, bytes: ByteArray, waitMs: Long): Pair<String?, String?> {
        val hex = buildString(bytes.size * 2 + 2) { append("\\x"); bytes.forEach { append("%02x".format(it)) } }
        val id = db.from("print_jobs").insert(buildJsonObject {
            put("agent_id", agentId); put("printer", printer.take(200)); put("shop_id", shopId); put("lang", lang)
            put("data", hex); put("size", bytes.size)
        }) { select(Columns.list("id")) }.decodeSingle<JobRow>().id
        var status: String? = null
        var error: String? = null
        withTimeoutOrNull(waitMs) {
            while (status != "done" && status != "error") {
                delay(1500)
                db.from("print_jobs").select(Columns.list("id", "status", "error")) { filter { eq("id", id) } }
                    .decodeList<JobRow>().firstOrNull()?.let { status = it.status; error = it.error }
            }
        }
        return status to error
    }

    // ---------- Підключення ПК ----------

    @Serializable
    data class PairingRow(val code: String, @SerialName("pc_name") val pcName: String = "", @SerialName("expires_at") val expiresAt: String? = null)

    suspend fun pairing(code: String): PairingRow? =
        db.postgrest.rpc("device_pairing_get", buildJsonObject { put("p_code", code) }).decodeList<PairingRow>().firstOrNull()

    suspend fun pair(code: String, shopId: String) {
        db.postgrest.rpc("device_pair", buildJsonObject { put("p_code", code); put("p_shop", shopId) })
    }

    @Serializable
    data class DeviceRow(val uid: String, val name: String = "")

    suspend fun devices(shopId: String): List<DeviceRow> =
        db.from("devices").select(Columns.list("uid", "name")) { filter { eq("shop_id", shopId) } }.decodeList()

    /** «Відключити» = delete devices: доступ ПК закривається одразу (RLS). */
    suspend fun unpair(uid: String) {
        db.from("devices").delete { filter { eq("uid", uid) } }
    }

    fun online(a: AgentFull?, now: Long = System.currentTimeMillis()): Boolean =
        a != null && a.online && (parseTime(a.lastSeen)?.time?.let { now - it <= AgentDirectory.ONLINE_MS } ?: false)
}
