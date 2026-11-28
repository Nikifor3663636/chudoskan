package ua.chudo.scanner.data

import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.OffsetDateTime
import java.util.Date

/** Рядок public.stock. */
@Serializable
data class SbStockRow(
    val barcode: String,
    val name: String = "",
    val code: String = "",
    val article: String = "",
    val q: Map<String, Double> = emptyMap(),
) {
    fun item() = NetItem(name, code, article, q)
}

@Serializable
data class StoreRow(
    val code: String,
    val name: String = "",
    val city: String = "",
    @SerialName("shop_id") val shopId: String? = null,
) {
    fun doc() = StoreDoc(name, city, shopId?.ifEmpty { null })
}

@Serializable
data class MetaRow(
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("updated_by") val updatedBy: String? = null,
    val items: Int = 0,
    val stores: Int = 0,
) {
    fun info() = NetworkStockInfo(parseTime(updatedAt), updatedBy, items, stores)
}

@Serializable
data class ShopRow(val id: String, val address: String = "", val region: String = "", val code1c: String? = null)

/** timestamptz з PostgREST («2026-11-26T10:00:00.123+00:00») → Date. */
fun parseTime(s: String?): Date? = s?.let { runCatching { Date.from(OffsetDateTime.parse(it).toInstant()) }.getOrNull() }

/** Залишки мережі з Supabase: ті самі відповіді, що з Firestore (NetworkStockLookup / NetworkStockRepository). */
object SupabaseStock {
    private val db get() = Supabase.client

    suspend fun byBarcode(bc: String): NetItem? =
        db.from("stock").select { filter { eq("barcode", bc) }; limit(1) }.decodeList<SbStockRow>().firstOrNull()?.item()

    /** `field` — code або article (індекси stock_code / stock_article). */
    /** Рядки залишків за кількома артикулами одним запитом (список результатів пошуку). */
    suspend fun byArticles(articles: List<String>, limit: Long = 500): List<NetItem> =
        if (articles.isEmpty()) emptyList()
        else db.from("stock").select { filter { isIn("article", articles) }; limit(limit) }.decodeList<SbStockRow>().map { it.item() }

    suspend fun byField(field: String, value: String, limit: Long = 10): List<Pair<String, NetItem>> =
        db.from("stock").select { filter { eq(field, value) }; limit(limit) }.decodeList<SbStockRow>().map { it.barcode to it.item() }

    suspend fun stores(): Map<String, StoreDoc> =
        db.from("stores").select(Columns.list("code", "name", "city", "shop_id")).decodeList<StoreRow>().associate { it.code to it.doc() }

    suspend fun info(): NetworkStockInfo? =
        db.from("meta_stock").select(Columns.list("updated_at", "updated_by", "items", "stores")).decodeList<MetaRow>().firstOrNull()?.info()

    suspend fun shops(): List<ShopRef> =
        db.from("shops").select(Columns.list("id", "address", "region")).decodeList<ShopRow>()
            .map { ShopRef(it.id, it.address, it.region) }.sortedWith(compareBy({ it.region }, { it.address }))

    suspend fun code1C(shopId: String): String? =
        db.from("shops").select(Columns.list("id", "code1c")) { filter { eq("id", shopId) } }.decodeList<ShopRow>().firstOrNull()?.code1c

    suspend fun catalogPage(offset: Int, size: Int): List<SbStockRow> =
        db.from("stock").select(Columns.list("barcode", "name", "article", "code", "q")) {
            order("name", Order.ASCENDING)
            order("barcode", Order.ASCENDING)
            range(offset.toLong(), (offset + size - 1).toLong())
        }.decodeList()

    internal suspend fun offlinePage(code: String, after: String): List<SbStockRow> =
        db.from("stock").select(Columns.list("barcode", "name", "article", "code", "q")) {
            filter {
                gt("q->>$code", "0")
                if (after.isNotEmpty()) gt("barcode", after)
            }
            order("barcode", Order.ASCENDING)
            limit(500)
        }.decodeList()

    suspend fun storeItems(code: String, limit: Long): List<SbStockRow> =
        db.postgrest.rpc("stock_in_store", buildJsonObject { put("p_code", code); put("p_limit", limit) }).decodeList()

    suspend fun changedLastImport(code: String, limit: Long): List<SbStockRow> =
        db.postgrest.rpc("stock_changed_last_import", buildJsonObject { put("p_code", code); put("p_limit", limit) }).decodeList()

    // ---------- Імпорт: begin → chunk × N → commit ----------

    /** Пачка для stock_upload_chunk. */
    const val CHUNK = 2000

    fun rows(items: Map<String, NetItem>): List<JsonArray> =
        items.entries.chunked(CHUNK).map { chunk ->
            JsonArray(chunk.map { (bc, it) ->
                Supabase.json.encodeToJsonElement(SbStockRow.serializer(), SbStockRow(bc, it.name.take(300), it.code.take(40), it.article.take(100), it.q))
            })
        }

    suspend fun begin(fileName: String): Long =
        db.postgrest.rpc("stock_upload_begin", buildJsonObject { put("p_file_name", fileName.take(300)); put("p_source", "xlsx") }).decodeAs()

    suspend fun chunk(id: Long, rows: JsonArray): Int =
        db.postgrest.rpc("stock_upload_chunk", buildJsonObject { put("p_id", id); put("p_rows", rows) }).decodeAs()

    suspend fun commit(id: Long, shops: List<String>, confirm: Boolean): CommitReport =
        db.postgrest.rpc("stock_upload_commit", buildJsonObject {
            put("p_id", id); put("p_shops", JsonArray(shops.map { kotlinx.serialization.json.JsonPrimitive(it) })); put("p_confirm", confirm)
        }).decodeAs()

    suspend fun abort(id: Long) { runCatching { db.postgrest.rpc("stock_upload_abort", buildJsonObject { put("p_id", id) }) } }

    suspend fun storesUpsert(stores: List<NetStore>) {
        if (stores.isEmpty()) return
        db.postgrest.rpc("stores_upsert", buildJsonObject {
            put("p_rows", JsonArray(stores.map { s -> buildJsonObject { put("code", s.code); put("name", s.name.take(200)); put("city", s.city.take(100)) } }))
        })
    }

    suspend fun link(code: String, shopId: String) {
        db.postgrest.rpc("store_link", buildJsonObject { put("p_code", code); put("p_shop", shopId) })
    }
}

@Serializable
data class CommitReport(
    val stores: Int = 0,
    val items: Int = 0,
    val changed: Int = 0,
    val removed: Int = 0,
    val kept: List<String> = emptyList(),
    val seconds: Double = 0.0,
)

/** Що сервер відповів на commit замість звіту. */
sealed interface CommitProblem {
    data class Confirm(val file: Int, val previous: Int) : CommitProblem
    data class Unknown(val codes: List<String>) : CommitProblem

    companion object {
        /** Помилка RPC (message/details PostgREST) → що просить сервер; null — інша помилка. */
        fun of(message: String?, details: String?): CommitProblem? = when {
            message?.contains("confirm_required") == true -> details.orEmpty().split(',').mapNotNull { it.trim().toIntOrNull() }
                .let { Confirm(it.getOrElse(0) { 0 }, it.getOrElse(1) { 0 }) }
            message?.contains("unknown_stores") == true -> Unknown(details.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() })
            else -> null
        }
    }
}
