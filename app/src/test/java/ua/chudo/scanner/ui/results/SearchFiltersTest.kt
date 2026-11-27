package ua.chudo.scanner.ui.results

import org.junit.Assert.assertEquals
import org.junit.Test
import uniffi.chudo_core.Price
import uniffi.chudo_core.ProductSummary
import uniffi.chudo_core.StockStatus

class SearchFiltersTest {
    private fun item(id: Long, article: String, final: Double, regular: Double = final) =
        ProductSummary(id, article, "Товар $id", "p$id", ".html", StockStatus.IN_STOCK, Price(final, regular, 0.0, "UAH"), null, false)

    private val items = listOf(item(1, "10954", 649.0, 969.0), item(2, "SK-014D2", 339.0), item(3, "HRX56", 599.0, 799.0))

    @Test fun shopFilterUsesOwnQuantityByArticle() {
        val own = mapOf(articleKey("10954") to 3.0, articleKey("sk014d2") to 0.0)
        assertEquals(listOf(1L), applySearchFilters(items, SearchFilters(shop = true), own, emptySet()).map { it.id })
    }

    @Test fun promoAndNewFiltersCombine() {
        assertEquals(listOf(1L, 3L), applySearchFilters(items, SearchFilters(promo = true), emptyMap(), emptySet()).map { it.id })
        assertEquals(listOf(3L), applySearchFilters(items, SearchFilters(promo = true, fresh = true), emptyMap(), setOf("hrx56")).map { it.id })
    }

    @Test fun noFiltersKeepEverything() {
        assertEquals(3, applySearchFilters(items, SearchFilters(), emptyMap(), emptySet()).size)
    }
}
