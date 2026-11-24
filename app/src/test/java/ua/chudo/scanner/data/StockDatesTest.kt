package ua.chudo.scanner.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Date
import java.util.TimeZone

class StockDatesTest {
    @Test fun `підпис дати залишків`() {
        // 2026-11-24 07:05 UTC = 09:05 у Києві (зимовий час UTC+2).
        val at = Date(1_795_503_900_000)
        assertEquals("Залишки на 24.11, 09:05", StockDates.label(at, TimeZone.getTimeZone("Europe/Kyiv")))
    }
}
