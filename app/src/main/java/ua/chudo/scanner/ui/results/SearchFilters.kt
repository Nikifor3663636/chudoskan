package ua.chudo.scanner.ui.results

import uniffi.chudo_core.ProductSummary
import java.util.Locale

/** Артикул для зіставлення сайту з 1С: лише літери й цифри, без регістру. */
fun articleKey(article: String?): String = article.orEmpty().filter(Char::isLetterOrDigit).lowercase(Locale.ROOT)

/** Фільтри пошуку як у дизайні: «Є в магазині», «Акції», «Новинки». */
data class SearchFilters(val shop: Boolean = false, val promo: Boolean = false, val fresh: Boolean = false) {
    val any: Boolean get() = shop || promo || fresh
}

/**
 * [own] — кількість у своєму магазині за [articleKey]; товар без даних про залишок
 * під фільтром «Є в магазині» не показуємо. [newArticles] — артикули новинок.
 */
fun applySearchFilters(
    items: List<ProductSummary>,
    filters: SearchFilters,
    own: Map<String, Double>,
    newArticles: Set<String>,
): List<ProductSummary> = items.filter { item ->
    val key = articleKey(item.article)
    (!filters.shop || (own[key] ?: 0.0) > 0.0) &&
        (!filters.promo || item.price?.let { it.regularPrice > it.finalPrice + 0.001 } == true) &&
        (!filters.fresh || key in newArticles)
}
