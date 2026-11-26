package ua.chudo.scanner.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InventoryDraftTest {
    @Test fun repeatsPreserveTheBaselineAndIncreaseTheCount() {
        val first = InventoryDraft.add(emptyList(), "0012345678901", "Гра", 4.0)
        val second = InventoryDraft.add(first, "0012345678901", "Інша назва", 99.0)
        assertEquals(listOf(InventoryLine("0012345678901", "Гра", 4.0, 2)), second)
    }

    @Test fun movementIncludesItsDestinationAndOmitsZeroQuantities() {
        val input = listOf(InventoryLine("001", "Гра", 9.0, 3), InventoryLine("002", "Інша", 5.0, 0))
        val csv = InventoryDraft.movementCsv(input, "ЧО 22", "ЧО 62", StockOperation.TRANSFER)
        assertEquals(listOf("Магазин;Магазин призначення;Штрихкод;Товар;Кількість", "\"ЧО 22\";\"ЧО 62\";\"001\";\"Гра\";3"), csv.trimEnd().lines())
    }

    @Test fun csvEscapesTextAndLeavesUnknownQuantitiesBlank() {
        val report = InventoryDraft.csv(listOf(InventoryLine("0012345678901", "=формула; \"Назва\"", null, 1)), "ЧО 22")
        assertTrue(report.contains("\"ЧО 22\";\"0012345678901\";\"'=формула; \"\"Назва\"\"\";;1;"))
    }
}
