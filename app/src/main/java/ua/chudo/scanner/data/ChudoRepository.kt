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
}

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

    /**
     * Цепочка поиска:
     * 1. штрихкод, который пользователь уже привязал;
     * 2. поиск по сайту как есть (артикул, название или EAN, если он в названии);
     * 3. для штрихкода: UPCitemdb → вероятные артикулы → поиск каждого;
     * 4. для штрихкода: название из UPCitemdb → поиск по тексту.
     */
    suspend fun lookup(raw: String, onStep: (String) -> Unit = {}): LookupResult {
        val q = classifyQuery(raw)
        val code = q.normalized
        val barcode = code.takeIf { isBarcode(q.kind) }

        barcode?.let { bc ->
            prefs.rememberedProduct(bc)?.let { return LookupResult.Single(code, bc, it, Via.Remembered) }
        }

        onStep("Шукаємо «$code» на сайті…")
        pick(code, barcode, search(code), Via.Direct)?.let { return it }
        if (barcode == null) return LookupResult.NotFound(code, null, emptyList())

        onStep("Сайт не знає штрихкодів — шукаємо у відкритій базі…")
        val upc = runCatching { client.getExternal(upcLookupUrl(barcode)) }.getOrNull()
            ?: return LookupResult.NotFound(code, barcode, emptyList())

        val articles = upcCandidates(upc, barcode, 4u)
        for (article in articles) {
            onStep("Пробуємо артикул $article…")
            pick(code, barcode, search(article), Via.UpcArticle(article))?.let { return it }
        }

        upcTitle(upc)?.let { title ->
            onStep("Шукаємо за назвою…")
            val short = title.split(' ').take(6).joinToString(" ")
            pick(code, barcode, search(short), Via.UpcTitle(title))?.let { return it }
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
