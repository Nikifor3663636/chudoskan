package ua.chudo.scanner.data

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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

    /**
     * 1. привʼязаний штрихкод; 2. паралельно: сайт як є + stock/{штрихкод} (1С) → одразу пошук за артикулом з 1С;
     * 3. є в 1С, на сайті немає → [LookupResult.Only1C] (UPCitemdb не чіпаємо); 4. немає в 1С → UPCitemdb.
     */
    suspend fun lookup(raw: String, stock: Stock1C? = null, onStep: (String) -> Unit = {}): LookupResult = coroutineScope {
        val q = classifyQuery(raw)
        val code = q.normalized
        val barcode = code.takeIf { isBarcode(q.kind) }

        barcode?.let { bc ->
            prefs.rememberedProduct(bc)?.let { return@coroutineScope LookupResult.Single(code, bc, it, Via.Remembered) }
        }

        onStep("Шукаємо «$code»…")
        val direct = async { runCatching { search(code) } }
        val net: Deferred<NetAvailability?>? = if (barcode != null && stock != null) {
            async { safe { stock.find(barcode) } }
        } else null
        val byArticle = net?.let { n ->
            async {
                n.await()?.item?.article?.trim()?.takeIf { it.isNotEmpty() && it != code }?.let { a -> safe { search(a) }?.let { a to it } }
            }
        }
        fun stop() { net?.cancel(); byArticle?.cancel() }

        val site = direct.await()
        site.getOrNull()?.let { list -> pick(code, barcode, list, Via.Direct)?.let { stop(); return@coroutineScope it } }
        // Сайт недоступний — але 1С знає товар: показуємо 1С, інакше помилка як раніше.
        val av = net?.await()
        if (av == null) site.getOrThrow()
        if (barcode == null) return@coroutineScope LookupResult.NotFound(code, null, emptyList())

        av?.let {
            byArticle?.await()?.let { (article, list) ->
                pick(code, barcode, list, Via.Stock1C(article))?.let { return@coroutineScope it }
            }
            return@coroutineScope LookupResult.Only1C(code, barcode, it)
        }

        onStep("Шукаємо у відкритій базі…")
        val upc = runCatching { client.getExternal(upcLookupUrl(barcode)) }.getOrNull()
            ?: return@coroutineScope LookupResult.NotFound(code, barcode, emptyList())

        val articles = upcCandidates(upc, barcode, 4u)
        for (article in articles) {
            onStep("Пробуємо артикул $article…")
            pick(code, barcode, search(article), Via.UpcArticle(article))?.let { return@coroutineScope it }
        }

        upcTitle(upc)?.let { title ->
            onStep("Шукаємо за назвою…")
            val short = title.split(' ').take(6).joinToString(" ")
            pick(code, barcode, search(short), Via.UpcTitle(title))?.let { return@coroutineScope it }
        }
        LookupResult.NotFound(code, barcode, articles)
    }

    /** Помилка не валить пошук; скасування — пропускаємо далі. */
    private suspend fun <T> safe(block: suspend () -> T): T? = try { block() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }

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
