package ua.chudo.scanner.ui.results

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ua.chudo.scanner.data.LookupResult
import uniffi.chudo_core.Category
import uniffi.chudo_core.ProductSummary
import uniffi.chudo_core.StockStatus
import java.net.UnknownHostException

class CatalogTest {
    private val lego = Category("Mjc=", "Конструктори", "konstruktory", 2234u, emptyList())

    @Test fun digitsGroupedLikeMockup() {
        assertEquals("1 284", groupDigits(1284))
        assertEquals("25 675", groupDigits(25675))
        assertEquals("604", groupDigits(604))
        assertEquals("1 000 000", groupDigits(1_000_000))
    }

    @Test fun loadedCategoriesKeepSiteOrder() = runBlocking {
        val second = lego.copy(uid = "MjQw", name = "Для малюків")
        val state = loadCatalog { listOf(lego, second) }
        assertEquals(CatalogState.Done(listOf(lego, second)), state)
    }

    @Test fun offlineShowsClearErrorWithRetry() = runBlocking {
        val state = loadCatalog { throw UnknownHostException("chudo-ostriv.com.ua") }
        assertTrue(state is CatalogState.Failed)
        assertEquals("Немає інтернету", (state as CatalogState.Failed).error.title)
    }

    @Test fun emptyCatalogIsDoneNotError() = runBlocking {
        assertEquals(CatalogState.Done(emptyList()), loadCatalog { emptyList() })
    }

    @Test fun categoryWithOneProductStaysAList() {
        val one = ProductSummary(1, "60510", "LEGO City", "lego-city", ".html", StockStatus.IN_STOCK, null, null, false)
        val r = (categoryResult("Конструктори", listOf(one)) as ResultsState.Done).result
        assertTrue(r is LookupResult.Many)
        assertEquals("Конструктори", r.query)
        assertTrue((categoryResult("Порожня", emptyList()) as ResultsState.Done).result is LookupResult.NotFound)
    }
}
