package ua.chudo.scanner.terminal

import ua.chudo.scanner.data.LookupResult
import ua.chudo.scanner.data.NetAvailability
import uniffi.chudo_core.ProductSummary

/** Товар із сайту для кошика: назва, артикул і ціна (finalPrice) у копійках; ціни немає — `null`. */
data class SiteItem(val name: String, val article: String?, val priceKop: Long?)

/** Що знайшли за сканом чи введенням. */
sealed interface Found {
    /** Готово до кошика. */
    data class Ready(val item: CartItem) : Found
    /** Ціни на сайті немає (або товар лише в 1С) — продавець вводить її сам. */
    data class NeedPrice(val item: CartItem) : Found
    data class Choose(val query: String, val barcode: String?, val options: List<ProductSummary>) : Found
    data class NotFound(val query: String) : Found
}

/**
 * Скан → товар через той самий lookup, що й «Пошук»; ціна — з сайту, код і артикул — з 1С, якщо є.
 * Товар лише в 1С ([LookupResult.Only1C]) — ціна вручну з позначкою «ціна вручну».
 */
class TerminalProducts(
    private val lookup: suspend (String) -> LookupResult,
    private val site: suspend (urlKey: String) -> SiteItem,
    private val stock: suspend (barcode: String) -> NetAvailability?,
) {
    suspend fun find(query: String): Found = when (val r = lookup(query)) {
        is LookupResult.Single -> fromSite(r.urlKey, r.barcode)
        is LookupResult.Many -> Found.Choose(r.query, r.barcode, r.items)
        is LookupResult.Only1C -> Found.NeedPrice(
            CartItem(r.barcode, r.barcode, r.stock.item.name.ifBlank { r.barcode }, r.stock.item.code, r.stock.item.article, 1, 0, manualPrice = true),
        )
        is LookupResult.NotFound -> Found.NotFound(r.query)
    }

    suspend fun pick(urlKey: String, barcode: String?): Found = fromSite(urlKey, barcode)

    private suspend fun fromSite(urlKey: String, barcode: String?): Found {
        val p = site(urlKey)
        val net = barcode?.let { runCatching { stock(it) }.getOrNull() }
        val article = net?.item?.article?.ifBlank { null } ?: p.article.orEmpty()
        val item = CartItem(
            key = barcode ?: net?.item?.code?.ifBlank { null } ?: "site:$urlKey",
            barcode = barcode.orEmpty(),
            name = p.name,
            code = net?.item?.code.orEmpty(),
            article = article,
            qty = 1,
            priceKop = p.priceKop ?: 0,
            manualPrice = p.priceKop == null,
        )
        return if (p.priceKop == null) Found.NeedPrice(item) else Found.Ready(item)
    }
}
