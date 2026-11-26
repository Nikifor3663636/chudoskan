package ua.chudo.scanner.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.kxml2.io.KXmlParser
import java.io.ByteArrayInputStream

class HighlightReportTest {
    @Before fun jvmParser() {
        Xlsx.newParser = { KXmlParser() }
    }

    @Test fun readsPromoWithLeadingZeroAndTwoDateFormats() {
        val file = Report1C.xlsx(listOf(
            listOf("Назва", "Штрихкод", "Артикул", "Початок", "Кінець", "Акційна ціна"),
            listOf("Конструктор", "0012345678901", "A-1", "26.11.2026", "2026-11-30", "199,50"),
        ))
        val products = HighlightReport.parse(HighlightKind.PROMO) { ByteArrayInputStream(file) }

        assertEquals(listOf(ProductHighlight(
            barcode = "0012345678901",
            article = "A-1",
            name = "Конструктор",
            startsOn = "2026-11-26",
            endsOn = "2026-11-30",
            promoPrice = 199.5,
        )), products)
    }

    @Test fun rejectsInvalidCalendarDate() {
        val file = Report1C.xlsx(listOf(
            listOf("Назва", "Артикул", "Початок"),
            listOf("Гра", "A-1", "31.02.2026"),
        ))
        val failure = runCatching { HighlightReport.parse(HighlightKind.NEW) { ByteArrayInputStream(file) } }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertEquals("Рядок 2: некоректна дата.", failure?.message)
    }
}
