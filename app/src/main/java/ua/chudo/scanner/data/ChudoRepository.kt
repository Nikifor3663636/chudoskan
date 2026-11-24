package ua.chudo.scanner.data

import uniffi.chudo_core.Lang
import uniffi.chudo_core.ProductDetails
import uniffi.chudo_core.ProductSummary
import uniffi.chudo_core.ShopAvailability
import uniffi.chudo_core.buildDetailsBody
import uniffi.chudo_core.buildSearchBody
import uniffi.chudo_core.classifyQuery
import uniffi.chudo_core.isBarcode
import uniffi.chudo_core.parseDetails
import uniffi.chudo_core.parseSearch
import uniffi.chudo_core.parseShops
import uniffi.chudo_core.shopsUrl
import uniffi.chudo_core.upcCandidates
import uniffi.chudo_core.upcLookupUrl
import uniffi.chudo_core.upcTitle

/** Как нашли товар — показываем пользователю, чтобы было понятно, чему верить. */
sealed interface Via {
    data object Direct : Via
    data object Remembered : Via
    data class UpcArticle(val article: String) : Via
    data class UpcTitle(val title: String) : Via
    /** Артикул узяли із залишків 1С (stock/{штрихкод}). */
    data class Stock1C(val article: String) : Via
}

/** Залишки 1С за штрихкодом; `null` — немає або недоступно. */
fun interface Stock1C { suspend fun find(barcode: String): NetAvailability? }

sealed interface LookupResult {
    val query: String
    /** Штрихкод, если искали по нему (для «Запомнить»). */
    val barcode: String?

    data class Single(
        override val query: String,
        override val barcode: String?,
        val urlKey: String,
        val via: Via,
    ) : LookupResult

    data class Many(
        override val query: String,
        override val barcode: String?,
        val items: List<ProductSummary>,
        val via: Via,
    ) : LookupResult

    data class NotFound(
        override val query: String,
        override val barcode: String?,
        val triedArticles: List<String>,
    ) : LookupResult

    /** Є в 1С, на сайті не знайшли. */
    data class Only1C(
        override val query: String,
        override val barcode: String,
        val stock: NetAvailability,
    ) : LookupResult
}

class ChudoRepository(
    private val client: SiteClient,
    private val prefs: AppPrefs,
) {
    /** Застосунок лише українською — і дані сайту теж. */
    private val lang = Lang.UA

    suspend fun search(query: String, pageSize: Int = 20): List<ProductSummary> =
        parseSearch(client.graphql(buildSearchBody(query, pageSize.toUInt()), lang), query)

    suspend fun details(urlKey: String): ProductDetails =
        parseDetails(client.graphql(buildDetailsBody(urlKey), lang))

    suspend fun shops(productId: Long, region: String): List<ShopAvailability> =
        parseShops(client.getJson(shopsUrl(lang, productId, region)))

    private val engine = LookupEngine(
        site = SiteSearch { search(it) },
        upc = UpcSource { bc ->
            val raw = client.getExternal(upcLookupUrl(bc))
            UpcHit(upcCandidates(raw, bc, 4u), upcTitle(raw))
        },
        remembered = { prefs.rememberedProduct(it) },
    )

    /** Рішення — у [LookupEngine]; тут лише розбір запиту ядром. */
    suspend fun lookup(raw: String, stock: Stock1C? = null, onStep: (String) -> Unit = {}): LookupResult {
        val q = classifyQuery(raw)
        val code = q.normalized
        return engine.lookup(code, code.takeIf { isBarcode(q.kind) }, stock, onStep)
    }
}
