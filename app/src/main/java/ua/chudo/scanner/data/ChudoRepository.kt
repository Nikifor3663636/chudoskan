package ua.chudo.scanner.data

import android.util.Base64
import uniffi.chudo_core.PhotoGuess
import uniffi.chudo_core.ProductDetails
import uniffi.chudo_core.ProductSummary
import uniffi.chudo_core.ShopAvailability
import uniffi.chudo_core.buildDetailsBody
import uniffi.chudo_core.buildVisionBody
import uniffi.chudo_core.openaiUrl
import uniffi.chudo_core.parseVisionResponse
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
import ua.chudo.scanner.i18n.tr

/** Как нашли товар — показываем пользователю, чтобы было понятно, чему верить. */
sealed interface Via {
    data object Direct : Via
    data object Remembered : Via
    data class UpcArticle(val article: String) : Via
    data class UpcTitle(val title: String) : Via
    /** ChatGPT распознал товар на фото. */
    data class Photo(val guess: String) : Via
}

class NoAiKeyException : Exception("Немає ключа OpenAI")

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
    private val lang get() = prefs.lang.value

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

        onStep(lang.tr("Шукаємо «$code» на сайті…", "Ищем «$code» на сайте…"))
        pick(code, barcode, search(code), Via.Direct)?.let { return it }
        if (barcode == null) return LookupResult.NotFound(code, null, emptyList())

        onStep(lang.tr("Сайт не знає штрихкодів — шукаємо у відкритій базі…", "Сайт не знает штрихкодов — ищем в открытой базе…"))
        val upc = runCatching { client.getExternal(upcLookupUrl(barcode)) }.getOrNull()
            ?: return LookupResult.NotFound(code, barcode, emptyList())

        val articles = upcCandidates(upc, barcode, 4u)
        for (article in articles) {
            onStep(lang.tr("Пробуємо артикул $article…", "Пробуем артикул $article…"))
            pick(code, barcode, search(article), Via.UpcArticle(article))?.let { return it }
        }

        upcTitle(upc)?.let { title ->
            onStep(lang.tr("Шукаємо за назвою…", "Ищем по названию…"))
            val short = title.split(' ').take(6).joinToString(" ")
            pick(code, barcode, search(short), Via.UpcTitle(title))?.let { return it }
        }
        return LookupResult.NotFound(code, barcode, articles)
    }

    /** Фото → ChatGPT → поисковый запрос. */
    suspend fun identify(jpeg: ByteArray): PhotoGuess {
        val key = prefs.aiKey.value ?: throw NoAiKeyException()
        val b64 = Base64.encodeToString(jpeg, Base64.NO_WRAP)
        val json = client.postExternal(
            openaiUrl(),
            buildVisionBody(prefs.aiModel.value, b64),
            mapOf("Authorization" to "Bearer $key"),
        )
        return parseVisionResponse(json)
    }

    /**
     * Фото → распознавание → обычный поиск. Если по артикулу с упаковки
     * ничего нет, пробуем «бренд + назва».
     */
    suspend fun lookupPhoto(jpeg: ByteArray, onStep: (String) -> Unit): LookupResult {
        onStep(lang.tr("ChatGPT визначає товар…", "ChatGPT определяет товар…"))
        val g = identify(jpeg)
        val label = listOfNotNull(g.brand, g.name.ifBlank { null }, g.article?.let { lang.tr("арт. $it", "арт. $it") })
            .distinct().joinToString(" · ")
        val via = Via.Photo(label.ifBlank { g.searchQuery })

        onStep(lang.tr("ChatGPT: «${g.searchQuery}» — шукаємо…", "ChatGPT: «${g.searchQuery}» — ищем…"))
        val first = withVia(lookup(g.searchQuery), via)
        if (first !is LookupResult.NotFound || g.article == null) return first

        val byName = listOfNotNull(g.brand, g.name).distinct().joinToString(" ").trim()
        if (byName.isEmpty() || byName == g.searchQuery) return first
        onStep(lang.tr("Шукаємо за назвою «$byName»…", "Ищем по названию «$byName»…"))
        return withVia(lookup(byName), via)
    }

    private fun withVia(r: LookupResult, via: Via): LookupResult = when (r) {
        is LookupResult.Single -> r.copy(via = via)
        is LookupResult.Many -> r.copy(via = via)
        is LookupResult.NotFound -> r
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
