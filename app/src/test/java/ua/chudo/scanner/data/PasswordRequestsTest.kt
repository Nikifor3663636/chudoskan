package ua.chudo.scanner.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ua.chudo.scanner.ui.admin.resetTime
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date

class PasswordRequestsTest {
    @Test fun phoneNeedsNineDigitsAfterCountryCode() {
        assertEquals("+380671234567", resetPhone("671234567"))
        assertEquals("+380671234567", resetPhone("67 123 45 67"))
        assertNull(resetPhone("67123456"))
        assertNull(resetPhone("0671234567"))
        assertNull(resetPhone(""))
    }

    @Test fun serverAnswersAreParsed() {
        assertEquals(ResetRequestResult.OK, ResetRequestResult.parse("\"ok\""))
        assertEquals(ResetRequestResult.TOO_SOON, ResetRequestResult.parse("too_soon"))
        assertEquals(ResetRequestResult.TOO_MANY, ResetRequestResult.parse("\"too_many\"\n"))
        assertEquals(ResetRequestResult.BAD_PHONE, ResetRequestResult.parse("\"bad_phone\""))
        assertEquals(ResetRequestResult.BAD_SHOP, ResetRequestResult.parse("\"bad_shop\""))
        assertTrue(runCatching { ResetRequestResult.parse("\"boom\"") }.isFailure)
    }

    @Test fun repeatedRequestExplainsTheWait() {
        assertNull(ResetRequestResult.OK.problem())
        assertTrue(ResetRequestResult.TOO_SOON.problem()!!.contains("10 хвилин"))
        ResetRequestResult.entries.filter { it != ResetRequestResult.OK }.forEach { assertTrue(it.problem()!!.isNotBlank()) }
    }

    @Test fun resetErrorsForAdmin() {
        assertTrue(resetError(404, "no_employee").startsWith("Співробітника"))
        assertEquals("Запит уже закрито.", resetError(409, "closed"))
        assertEquals("Немає прав скинути цей пароль.", resetError(403, "forbidden"))
        assertEquals("Немає прав скинути цей пароль.", resetError(401, null))
        assertTrue(resetError(502, "update failed").contains("502"))
    }

    @Test fun requestTimeIsHuman() {
        val zone = ZoneId.of("Europe/Kyiv")
        val today = LocalDate.of(2026, 11, 29)
        fun at(d: LocalDate, h: Int, m: Int) = Date.from(d.atTime(h, m).atZone(zone).toInstant())
        assertEquals("сьогодні о 10:42", resetTime(at(today, 10, 42), zone, today))
        assertEquals("учора о 18:20", resetTime(at(today.minusDays(1), 18, 20), zone, today))
        assertEquals("26.11 о 09:05", resetTime(at(LocalDate.of(2026, 11, 26), 9, 5), zone, today))
    }
}
