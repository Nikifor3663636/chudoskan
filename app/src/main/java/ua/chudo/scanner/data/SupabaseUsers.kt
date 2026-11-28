package ua.chudo.scanner.data

import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Рядок public.users. */
@Serializable
data class UserRow(
    val uid: String,
    val name: String = "",
    val phone: String = "",
    val region: String = "",
    val shop: String = "",
    @SerialName("shop_id") val shopId: String = "",
    val approved: Boolean = false,
    val admin: Boolean = false,
    @SerialName("super_admin") val superAdmin: Boolean = false,
    @SerialName("stock_admin") val stockAdmin: Boolean = false,
    @SerialName("changed_at") val changedAt: String? = null,
) {
    fun employee(fallbackPhone: String) = Employee(
        uid = uid, name = name, phone = phone.ifEmpty { fallbackPhone }, region = region, shop = shop, shopId = shopId,
        approved = approved, admin = admin,
        stockAdmin = approved && (stockAdmin || superAdmin), superAdmin = approved && superAdmin,
    )

    fun staff() = StaffMember(uid, name, phone, shopId, shop, region, approved, admin, superAdmin, parseTime(changedAt))
}

@Serializable
data class AgentRow(
    val uid: String,
    @SerialName("shop_id") val shopId: String = "",
    @SerialName("pc_name") val pcName: String = "",
    val online: Boolean = false,
    @SerialName("last_seen") val lastSeen: String? = null,
)

@Serializable
data class LogRow(
    val id: Long,
    val at: String? = null,
    @SerialName("by_uid") val byUid: String = "",
    val action: String = "",
    val target: String? = null,
    @SerialName("shop_id") val shopId: String? = null,
    val details: JsonElement? = null,
)

/** Анкети, магазини й журнал у Supabase (ті самі відповіді, що з Firestore). */
object SupabaseUsers {
    private val db get() = Supabase.client

    suspend fun profile(uid: String): UserRow? =
        db.from("users").select { filter { eq("uid", uid) } }.decodeList<UserRow>().firstOrNull()

    suspend fun deviceLoginProfile(): UserRow? =
        db.postgrest.rpc("device_login_profile").decodeList<UserRow>().firstOrNull()

    /** Анкета під час реєстрації — завжди непідтверджена (ролі ставить RLS / Dashboard). Уже є — переписати (до підтвердження). */
    suspend fun saveProfile(uid: String, name: String, phone: String, region: String, shop: String, shopId: String) {
        val fields = buildJsonObject {
            put("name", name); put("phone", phone); put("region", region); put("shop", shop); put("shop_id", shopId)
        }
        try {
            db.from("users").insert(JsonObject(fields + ("uid" to JsonPrimitive(uid))))
        } catch (e: PostgrestRestException) {
            if (e.code != "23505") throw e
            db.from("users").update(fields) { filter { eq("uid", uid) } }
        }
    }

    suspend fun updateName(uid: String, name: String) {
        db.from("users").update(buildJsonObject { put("name", name) }) { filter { eq("uid", uid) } }
    }

    /** Зміни рядків таблиці (Realtime, з тими самими RLS). [filter] — напр. «uid=eq.abc». */
    fun changes(table: String, filter: String? = null): Flow<Unit> =
        if (DeviceGateway.enabled) flow {
            // Realtime change checks don't receive the PostgREST device-session header.
            while (currentCoroutineContext().isActive) {
                delay(10_000)
                emit(Unit)
            }
        } else realtimeChanges(table, filter)

    private fun realtimeChanges(table: String, filter: String?): Flow<Unit> = callbackFlow {
        val ch = db.channel("$table-${filter ?: "all"}-${System.nanoTime()}")
        val flow = ch.postgresChangeFlow<PostgresAction>(schema = "public") {
            this.table = table
            filter?.let { f -> f.split("=", limit = 2).let { (col, rest) -> filter(col, io.github.jan.supabase.postgrest.query.filter.FilterOperator.EQ, rest.removePrefix("eq.")) } }
        }
        val job = launch { flow.collect { trySend(Unit) } }
        ch.subscribe()
        awaitClose {
            job.cancel()
            kotlinx.coroutines.runBlocking { runCatching { db.realtime.removeChannel(ch) } }
        }
    }

    // ---------- Адміністрування ----------

    suspend fun shops(): List<ShopDoc> =
        db.from("shops").select(Columns.list("id", "address", "region", "code1c")).decodeList<ShopRow>()
            .map { ShopDoc(it.id, it.address, it.region, it.code1c, null) }

    /** superAdmin — усі; адмін магазину — лише свій (так дозволяє RLS). */
    suspend fun staff(shopId: String?): List<StaffMember> =
        db.from("users").select { shopId?.let { s -> filter { eq("shop_id", s) } } }.decodeList<UserRow>().map { it.staff() }

    suspend fun agents(): List<AgentInfo> = db.from("agents").select().decodeList<AgentRow>().map { a ->
        val seen = parseTime(a.lastSeen)?.time ?: 0
        AgentInfo(a.uid, a.shopId, a.pcName, online = a.online && System.currentTimeMillis() - seen < NetworkAdminRepository.AGENT_ONLINE_MS)
    }

    /** Поля з [NetworkAdmin.update] (Firestore-імена) → колонки users; changed_at ставить сервер. */
    suspend fun apply(uid: String, data: Map<String, Any>) {
        val body = buildJsonObject {
            for ((k, v) in data) {
                val col = when (k) { "shopId" -> "shop_id"; "approvedBy" -> "approved_by"; else -> k }
                when (v) { is Boolean -> put(col, v); else -> put(col, v.toString()) }
            }
            put("changed_at", java.time.OffsetDateTime.now().toString())
        }
        db.from("users").update(body) { filter { eq("uid", uid) } }
    }

    suspend fun log(action: String, target: String, shopId: String, details: String?) {
        db.from("admin_log").insert(buildJsonObject {
            put("action", action); put("target", target.take(200)); put("shop_id", shopId.take(40))
            details?.let { put("details", buildJsonObject { put("text", it.take(1000)) }) }
        })
    }

    /** [shopId] — лише записи цього магазину («Мій магазин»); null — увесь журнал (адмін мережі). */
    suspend fun history(limit: Long, shopId: String? = null): List<AdminLogEntry> =
        db.from("admin_log").select {
            shopId?.let { s -> filter { eq("shop_id", s) } }
            order("at", Order.DESCENDING); limit(limit)
        }.decodeList<LogRow>().map { r ->
            val text = (r.details as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull ?: r.details?.toString().orEmpty()
            AdminLogEntry(r.id.toString(), r.byUid, r.action, r.target.orEmpty(), r.shopId.orEmpty(), text, parseTime(r.at))
        }
}
