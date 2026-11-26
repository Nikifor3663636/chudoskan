package ua.chudo.scanner.data

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.tasks.await
import java.util.Date

/** Анкета співробітника (users/{uid}) — для адміністрування. */
data class StaffMember(
    val uid: String,
    val name: String,
    val phone: String,
    val shopId: String,
    val shop: String,
    val region: String,
    val approved: Boolean,
    val admin: Boolean = false,
    val superAdmin: Boolean = false,
    val changedAt: Date? = null,
    /** Код 1С магазину (ЧО NN), якщо магазин прив'язано. */
    val code: String? = null,
) {
    /** Ще не підтверджений і має магазин (відхилені — з порожнім shopId). */
    val pending: Boolean get() = !approved && shopId.isNotEmpty()
}

/** Документ shops/{shopId}. */
data class ShopDoc(val id: String, val address: String, val region: String, val code1C: String?, val updatedAt: Date?)

/** Агент друку на ПК (agents/{uid}). */
data class AgentInfo(val id: String, val shopId: String, val pcName: String, val online: Boolean)

/** Рядок «Магазини»: магазин застосунку і/або склад 1С. */
data class ShopEntry(
    /** shops/{shopId}; `null` — склад 1С, ще не прив'язаний до магазину. */
    val shopId: String?,
    /** Код 1С: «62» → ЧО 62; `null` — магазин не прив'язано до складу. */
    val code: String?,
    val city: String,
    val address: String,
    val region: String,
    val staff: Int,
    val pending: Int,
    val pcsOnline: Int,
    val stockUpdatedAt: Date?,
) {
    val title: String get() = code?.let { "ЧО $it" } ?: "Без складу 1С"
}

/** Що адмін робить з анкетою. */
enum class StaffAction(val log: String, val title: String) {
    APPROVE("approve", "Підтвердити"),
    REJECT("reject", "Відхилити"),
    MAKE_ADMIN("makeAdmin", "Зробити адміном"),
    REMOVE_ADMIN("removeAdmin", "Зняти адміна"),
    TRANSFER("transfer", "Перевести в інший магазин"),
    DISABLE("disable", "Вимкнути доступ"),
}

/** Чисті правила адміністрування — без Firestore, щоб покрити тестами. */
object NetworkAdmin {
    private val UK_ORDER: Comparator<String> = java.text.Collator.getInstance(java.util.Locale.forLanguageTag("uk")).let { c -> Comparator { a, b -> c.compare(a, b) } }


    /**
     * Магазини застосунку (shops/) + склади 1С (stores/) + магазини з анкет без документа shops/.
     * Код — shops.code1C, інакше stores.shopId; місто — stores.city, інакше назва області.
     */
    fun directory(
        shops: List<ShopDoc>, stores: Map<String, StoreDoc>, staff: List<StaffMember>, agents: List<AgentInfo>,
        regionName: (String) -> String = { it },
    ): List<ShopEntry> {
        val byShop = shops.associateBy { it.id }.toMutableMap()
        // Магазин є лише в анкетах (ще нічого не завантажували) — теж показуємо.
        staff.filter { it.shopId.isNotEmpty() && it.shopId !in byShop }.groupBy { it.shopId }.forEach { (id, people) ->
            byShop[id] = ShopDoc(id, people.first().shop, people.first().region, null, null)
        }
        val linkedCodes = mutableSetOf<String>()
        val entries = byShop.values.map { s ->
            val code = s.code1C?.trim()?.ifEmpty { null } ?: stores.entries.firstOrNull { it.value.shopId == s.id }?.key
            code?.let(linkedCodes::add)
            val people = staff.filter { it.shopId == s.id }
            ShopEntry(
                shopId = s.id, code = code,
                city = code?.let { stores[it]?.city }?.ifBlank { null } ?: regionName(s.region),
                address = s.address, region = s.region,
                staff = people.count { it.approved }, pending = people.count { it.pending },
                pcsOnline = agents.count { it.shopId == s.id && it.online },
                stockUpdatedAt = s.updatedAt,
            )
        }
        val unlinked = stores.filterKeys { it !in linkedCodes }.map { (code, st) ->
            ShopEntry(null, code, st.city, st.name, "", 0, 0, 0, null)
        }
        return (entries + unlinked).sortedWith(compareBy<ShopEntry>({ it.code == null }, { it.code?.toIntOrNull() ?: Int.MAX_VALUE }, { it.city }, { it.address }))
    }

    /** Пошук за містом, номером («62», «ЧО 62») або адресою. */
    fun search(entries: List<ShopEntry>, query: String): List<ShopEntry> {
        val q = query.trim().lowercase().removePrefix("чо").trim()
        if (q.isEmpty()) return entries
        return entries.filter { e ->
            e.code == q || e.code?.trimStart('0') == q.trimStart('0') && q.all(Char::isDigit) ||
                e.city.lowercase().contains(q) || e.address.lowercase().contains(q)
        }
    }

    /** Фільтри «Співробітників»: «Очікують», магазин, місто (за кодом магазину → місто складу). */
    fun filterStaff(
        staff: List<StaffMember>, pendingOnly: Boolean, shopId: String?, city: String?, cityOf: (StaffMember) -> String,
        query: String = "",
    ): List<StaffMember> {
        val q = query.trim().lowercase()
        return staff.asSequence()
            .filter { !pendingOnly || it.pending }
            .filter { shopId == null || it.shopId == shopId }
            .filter { city == null || cityOf(it).equals(city, ignoreCase = true) }
            .filter { q.isEmpty() || it.name.lowercase().contains(q) || it.phone.contains(q) || it.shop.lowercase().contains(q) || it.code == q }
            // За українською абеткою: «Іра» перед «Петро» (у Unicode «і» стоїть після «п»).
            .sortedWith(compareBy<StaffMember> { !it.pending }.thenBy(UK_ORDER) { it.name })
            .toList()
    }

    /** Які дії доступні: адмін мережі — усі доречні; адмін магазину — у своєму магазині, без переведення й відхилення. */
    fun actions(m: StaffMember, actorUid: String, superAdmin: Boolean): List<StaffAction> {
        if (m.uid == actorUid || m.superAdmin) return emptyList() // себе й адмінів мережі не змінюємо з телефону
        return buildList {
            if (!m.approved && m.shopId.isNotEmpty()) add(StaffAction.APPROVE)
            if (!m.approved && m.shopId.isNotEmpty() && superAdmin) add(StaffAction.REJECT)
            if (m.approved) add(if (m.admin) StaffAction.REMOVE_ADMIN else StaffAction.MAKE_ADMIN)
            if (superAdmin) add(StaffAction.TRANSFER)
            if (m.approved) add(StaffAction.DISABLE)
        }
    }

    /**
     * Поля users/{uid} для дії (лише дозволені правилами: approved, admin, shopId, region, shop, approvedBy;
     * changedAt = serverTimestamp додає репозиторій). Відхилити — непідтверджений без магазину.
     */
    fun update(action: StaffAction, actorUid: String, target: ShopDoc? = null): Map<String, Any> = when (action) {
        StaffAction.APPROVE -> mapOf("approved" to true, "approvedBy" to actorUid)
        StaffAction.REJECT -> mapOf("approved" to false, "admin" to false, "shopId" to "", "approvedBy" to actorUid)
        StaffAction.MAKE_ADMIN -> mapOf("admin" to true, "approvedBy" to actorUid)
        StaffAction.REMOVE_ADMIN -> mapOf("admin" to false, "approvedBy" to actorUid)
        StaffAction.DISABLE -> mapOf("approved" to false, "admin" to false, "approvedBy" to actorUid)
        StaffAction.TRANSFER -> requireNotNull(target) { "Оберіть магазин" }.let { s ->
            // Новий магазин — без прав адміна старого.
            mapOf("shopId" to s.id, "shop" to s.address, "region" to s.region, "admin" to false, "approvedBy" to actorUid)
        }
    }

    /** Запитання перед дією. */
    fun confirmText(action: StaffAction, m: StaffMember, target: ShopDoc? = null): String = when (action) {
        StaffAction.APPROVE -> "Підтвердити ${m.name}? Співробітник отримає доступ до магазину «${m.shop}»."
        StaffAction.REJECT -> "Відхилити анкету ${m.name}? Доступу не буде; щоб увійти, треба буде обрати магазин знову."
        StaffAction.MAKE_ADMIN -> "Зробити ${m.name} адміном магазину «${m.shop}»?"
        StaffAction.REMOVE_ADMIN -> "Зняти з ${m.name} права адміна магазину?"
        StaffAction.TRANSFER -> "Перевести ${m.name} у магазин «${target?.address.orEmpty()}»? Права адміна буде знято."
        StaffAction.DISABLE -> "Вимкнути доступ ${m.name}? Застосунок перестане показувати дані магазину."
    }
}

/** Історія завантажень залишків мережі. */
data class AdminLogEntry(val id: String, val by: String, val action: String, val target: String, val shopId: String, val details: String, val at: Date?)

/**
 * Адміністрування мережі (superAdmin) і «Мій магазин» (адмін магазину): читання shops/, stores/, users/, agents/,
 * зміни анкет із approvedBy/changedAt і запис кожної дії в adminLog/{id} = {by, action, target, shopId, at}.
 */
class NetworkAdminRepository(private val db: FirebaseFirestore) {

    suspend fun shops(): List<ShopDoc> = if (Backend.isSupabase) SupabaseUsers.shops() else db.collection("shops").get().await().documents.map {
        ShopDoc(it.id, it.getString("address").orEmpty(), it.getString("region").orEmpty(), it.getString("code1C"), it.getTimestamp("updatedAt")?.toDate())
    }

    /** superAdmin — усі; адмін магазину — лише свій магазин (так дозволяють правила). */
    suspend fun staff(shopId: String? = null): List<StaffMember> {
        if (Backend.isSupabase) return SupabaseUsers.staff(shopId)
        val q: Query = if (shopId == null) db.collection("users") else db.collection("users").whereEqualTo("shopId", shopId)
        return q.get().await().documents.map { it.toStaff() }
    }

    suspend fun agents(): List<AgentInfo> = if (Backend.isSupabase) SupabaseUsers.agents() else db.collection("agents").get().await().documents.map { d ->
        val seen = d.getTimestamp("lastSeen")?.toDate()?.time ?: 0
        AgentInfo(d.id, d.getString("shopId").orEmpty(), d.getString("pcName").orEmpty(),
            online = d.getBoolean("online") != false && System.currentTimeMillis() - seen < AGENT_ONLINE_MS)
    }

    suspend fun apply(actorUid: String, m: StaffMember, action: StaffAction, target: ShopDoc? = null) {
        if (Backend.isSupabase) SupabaseUsers.apply(m.uid, NetworkAdmin.update(action, actorUid, target))
        else db.collection("users").document(m.uid).update(NetworkAdmin.update(action, actorUid, target) + ("changedAt" to FieldValue.serverTimestamp())).await()
        log(actorUid, action.log, m.uid, target?.id ?: m.shopId)
    }

    suspend fun log(actorUid: String, action: String, target: String, shopId: String, details: String? = null) {
        if (Backend.isSupabase) { SupabaseUsers.log(action, target, shopId, details); return }
        val data = mutableMapOf<String, Any>(
            "by" to actorUid, "action" to action, "target" to target.take(200), "shopId" to shopId, "at" to FieldValue.serverTimestamp(),
        )
        details?.let { data["details"] = it.take(1000) }
        db.collection("adminLog").add(data).await()
    }

    /** Останні дії (лише superAdmin читає журнал). */
    suspend fun history(limit: Long = 100): List<AdminLogEntry> =
        if (Backend.isSupabase) SupabaseUsers.history(limit) else db.collection("adminLog").orderBy("at", Query.Direction.DESCENDING).limit(limit).get().await().documents.map {
            AdminLogEntry(it.id, it.getString("by").orEmpty(), it.getString("action").orEmpty(), it.getString("target").orEmpty(),
                it.getString("shopId").orEmpty(), it.getString("details").orEmpty(), (it.get("at") as? Timestamp)?.toDate())
        }

    /** Нові й змінені анкети (Realtime) — адмін бачить їх без «Оновити». Firestore — без підписки. */
    fun staffChanges(): kotlinx.coroutines.flow.Flow<Unit> =
        if (Backend.isSupabase) SupabaseUsers.changes("users") else kotlinx.coroutines.flow.emptyFlow()

    private fun DocumentSnapshot.toStaff() = StaffMember(
        uid = id, name = getString("name").orEmpty(), phone = getString("phone").orEmpty(),
        shopId = getString("shopId").orEmpty(), shop = getString("shop").orEmpty(), region = getString("region").orEmpty(),
        approved = getBoolean("approved") == true, admin = getBoolean("admin") == true, superAdmin = getBoolean("superAdmin") == true,
        changedAt = getTimestamp("changedAt")?.toDate(),
    )

    companion object {
        /** Агент на зв'язку, якщо писав пульс не пізніше 3 хв тому (як AgentDirectory). */
        const val AGENT_ONLINE_MS = 3 * 60 * 1000L
    }
}
