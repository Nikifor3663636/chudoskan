package ua.chudo.shared.data

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import ua.chudo.shared.KeyValueStore
import ua.chudo.shared.core.BlockKind
import ua.chudo.shared.core.Core
import ua.chudo.shared.core.ProductDetails
import ua.chudo.shared.core.ProductSummary
import ua.chudo.shared.core.Region
import ua.chudo.shared.core.ShopAvailability

class HttpException(val code: Int) : Exception("HTTP $code")
class BlockedException(val kind: BlockKind) : Exception("Сайт заблокував запит: $kind")

/** HTTP к сайту chudo-ostriv.com.ua (GraphQL + наличие по магазинам) и к UPCitemdb. */
class SiteClient(private val http: HttpClient) {
    suspend fun graphql(body: String): String {
        val r = http.post(Core.graphqlUrl()) {
            header("Content-Type", "application/json")
            header("Accept", "application/json")
            header("Store", Core.storeHeader())
            header("User-Agent", UA)
            setBody(body)
        }
        return checked(r.status, r.bodyAsText())
    }

    suspend fun getJson(url: String): String {
        val r = http.get(url) {
            header("Accept", "application/json")
            header("X-Requested-With", "XMLHttpRequest")
            header("User-Agent", UA)
        }
        return checked(r.status, r.bodyAsText())
    }

    suspend fun getExternal(url: String): String {
        val r = http.get(url) { header("Accept", "application/json"); header("User-Agent", UA) }
        if (r.status.value == 429) throw BlockedException(BlockKind.RATE_LIMITED)
        if (r.status.value >= 400 && r.status != HttpStatusCode.NotFound) throw HttpException(r.status.value)
        return r.bodyAsText()
    }

    private fun checked(status: HttpStatusCode, text: String): String {
        Core.detectBlock(status.value, text)?.let { throw BlockedException(it) }
        if (status.value >= 400) throw HttpException(status.value)
        return text
    }

    private companion object {
        const val UA = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"
    }
}

@Serializable data class HistoryItem(val urlKey: String, val name: String, val article: String? = null, val image: String? = null)

/** Город, недавние товары, привязанные штрихкоды. */
class Prefs(private val kv: KeyValueStore) {
    private val json = Json { ignoreUnknownKeys = true }
    val allRegions: List<Region> = Core.regions()

    private val _region = MutableStateFlow(regionByCode(kv.get("region")))
    val region: StateFlow<Region> = _region.asStateFlow()

    private val _history = MutableStateFlow(
        runCatching { json.decodeFromString(ListSerializer(HistoryItem.serializer()), kv.get("history") ?: "[]") }.getOrDefault(emptyList()),
    )
    val history: StateFlow<List<HistoryItem>> = _history.asStateFlow()

    fun setRegion(code: String) { kv.put("region", code); _region.value = regionByCode(code) }

    fun rememberedProduct(barcode: String): String? = kv.get("bc:$barcode")
    fun rememberBarcode(barcode: String, urlKey: String) = kv.put("bc:$barcode", urlKey)

    fun addToHistory(item: HistoryItem) {
        _history.value = (listOf(item) + _history.value.filterNot { it.urlKey == item.urlKey }).take(20)
        kv.put("history", json.encodeToString(ListSerializer(HistoryItem.serializer()), _history.value))
    }

    fun clearHistory() { _history.value = emptyList(); kv.put("history", null) }

    private fun regionByCode(code: String?): Region =
        allRegions.firstOrNull { it.code == code } ?: allRegions.first { it.code == Core.defaultRegion() }
}

/** Как нашли товар — показываем пользователю, чтобы было понятно, чему верить. */
sealed interface Via {
    data object Direct : Via
    data object Remembered : Via
    data class UpcArticle(val article: String) : Via
    data class UpcTitle(val title: String) : Via
}

sealed interface LookupResult {
    val query: String
    val barcode: String?

    data class Single(override val query: String, override val barcode: String?, val urlKey: String, val via: Via) : LookupResult
    data class Many(override val query: String, override val barcode: String?, val items: List<ProductSummary>, val via: Via) : LookupResult
    data class NotFound(override val query: String, override val barcode: String?, val triedArticles: List<String>) : LookupResult
}

/** Поиск товара — та же цепочка, что в Android-версии. */
class ChudoRepository(private val client: SiteClient, private val prefs: Prefs) {
    suspend fun search(query: String, pageSize: Int = 20): List<ProductSummary> =
        Core.parseSearch(client.graphql(Core.buildSearchBody(query, pageSize)), query)

    suspend fun details(urlKey: String): ProductDetails = Core.parseDetails(client.graphql(Core.buildDetailsBody(urlKey)))

    suspend fun shops(productId: Long, region: String): List<ShopAvailability> =
        Core.parseShops(client.getJson(Core.shopsUrl(productId, region)))

    /**
     * 1. штрихкод, который уже привязали; 2. поиск по сайту как есть;
     * 3. для штрихкода: UPCitemdb → вероятные артикулы; 4. название из UPCitemdb.
     */
    suspend fun lookup(raw: String, onStep: (String) -> Unit = {}): LookupResult {
        val q = Core.classifyQuery(raw)
        val code = q.normalized
        val barcode = code.takeIf { Core.isBarcode(q.kind) }

        barcode?.let { bc -> prefs.rememberedProduct(bc)?.let { return LookupResult.Single(code, bc, it, Via.Remembered) } }

        onStep("Шукаємо «$code» на сайті…")
        pick(code, barcode, search(code), Via.Direct)?.let { return it }
        if (barcode == null) return LookupResult.NotFound(code, null, emptyList())

        onStep("Сайт не знає штрихкодів — шукаємо у відкритій базі…")
        val upc = runCatching { client.getExternal(Core.upcLookupUrl(barcode)) }.getOrNull()
            ?: return LookupResult.NotFound(code, barcode, emptyList())

        val articles = Core.upcCandidates(upc, barcode, 4)
        for (article in articles) {
            onStep("Пробуємо артикул $article…")
            pick(code, barcode, search(article), Via.UpcArticle(article))?.let { return it }
        }
        Core.upcTitle(upc)?.let { title ->
            onStep("Шукаємо за назвою…")
            pick(code, barcode, search(title.split(' ').take(6).joinToString(" ")), Via.UpcTitle(title))?.let { return it }
        }
        return LookupResult.NotFound(code, barcode, articles)
    }

    private fun pick(query: String, barcode: String?, list: List<ProductSummary>, via: Via): LookupResult? {
        if (list.isEmpty()) return null
        val exact = list.filter { it.exactMatch }
        return when {
            exact.size == 1 -> LookupResult.Single(query, barcode, exact.first().urlKey, via)
            list.size == 1 -> LookupResult.Single(query, barcode, list.first().urlKey, via)
            else -> LookupResult.Many(query, barcode, list, via)
        }
    }
}
