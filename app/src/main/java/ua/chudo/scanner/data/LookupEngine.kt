package ua.chudo.scanner.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import uniffi.chudo_core.ProductSummary

/** Пошук на сайті за текстом / артикулом. */
fun interface SiteSearch { suspend fun search(query: String): List<ProductSummary> }

/** Що знає відкрита база штрихкодів про товар: кандидати в артикули й назва. */
data class UpcHit(val articles: List<String>, val title: String?)

/** Відкрита база штрихкодів (UPCitemdb); `null` — нічого або недоступна. */
fun interface UpcSource { suspend fun find(barcode: String): UpcHit? }

/**
 * Рішення, як шукати товар, без мережі й ядра — джерела підставляються ззовні (і фейками в тестах).
 *
 * 1. прив'язаний штрихкод; 2. паралельно: сайт як є + залишки 1С → одразу пошук за артикулом з 1С;
 * 3. є в 1С, на сайті немає → [LookupResult.Only1C] (відкриту базу не чіпаємо); 4. немає в 1С → відкрита база.
 * Сайт недоступний, а 1С знає товар → [LookupResult.Only1C]; не знає — помилка сайту.
 */
class LookupEngine(
    private val site: SiteSearch,
    private val upc: UpcSource,
    private val remembered: suspend (String) -> String? = { null },
) {
    /** [code] — нормалізований запит, [barcode] — він же, якщо це штрихкод. */
    suspend fun lookup(code: String, barcode: String?, stock: Stock1C?, onStep: (String) -> Unit = {}): LookupResult = coroutineScope {
        barcode?.let { bc ->
            remembered(bc)?.let { return@coroutineScope LookupResult.Single(code, bc, it, Via.Remembered) }
        }

        onStep("Шукаємо «$code»…")
        val direct = async { runCatching { site.search(code) }.onFailure { if (it is CancellationException) throw it } }
        val net: Deferred<NetAvailability?>? = if (barcode != null && stock != null) {
            async { safe { stock.find(barcode) } }
        } else null
        val byArticle = net?.let { n ->
            async {
                n.await()?.item?.article?.trim()?.takeIf { it.isNotEmpty() && it != code }
                    ?.let { a -> safe { site.search(a) }?.let { a to it } }
            }
        }
        fun stop() { net?.cancel(); byArticle?.cancel() }

        val found = direct.await()
        found.getOrNull()?.let { list -> pick(code, barcode, list, Via.Direct)?.let { stop(); return@coroutineScope it } }
        val av = net?.await()
        if (av == null) found.getOrThrow()
        if (barcode == null) return@coroutineScope LookupResult.NotFound(code, null, emptyList())

        av?.let {
            byArticle?.await()?.let { (article, list) ->
                pick(code, barcode, list, Via.Stock1C(article))?.let { r -> return@coroutineScope r }
            }
            return@coroutineScope LookupResult.Only1C(code, barcode, it)
        }

        onStep("Шукаємо у відкритій базі…")
        val hit = safe { upc.find(barcode) } ?: return@coroutineScope LookupResult.NotFound(code, barcode, emptyList())

        for (article in hit.articles) {
            onStep("Пробуємо артикул $article…")
            pick(code, barcode, site.search(article), Via.UpcArticle(article))?.let { return@coroutineScope it }
        }
        hit.title?.let { title ->
            onStep("Шукаємо за назвою…")
            val short = title.split(' ').take(6).joinToString(" ")
            pick(code, barcode, site.search(short), Via.UpcTitle(title))?.let { return@coroutineScope it }
        }
        LookupResult.NotFound(code, barcode, hit.articles)
    }

    /** Помилка не валить пошук; скасування — пропускаємо далі. */
    private suspend fun <T> safe(block: suspend () -> T): T? =
        try { block() } catch (e: CancellationException) { throw e } catch (_: Exception) { null }

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
