package ua.chudo.scanner.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

/** Підключення ПК з Godex: код із вікна «ЧудоСкан Друк» і стан ПК. */
class PcPairingTest {
    private val now = Date(1_800_000_000_000)

    @Test fun codeFormat() {
        assertNull(PcPairing.formatError("123456"))
        assertNull(PcPairing.formatError("123 456"))
        assertEquals("123456", PcPairing.normalize(" 123 456 "))
        listOf("012345", "12345", "1234567", "", "abcdef").forEach { assertEquals(it, "Код — 6 цифр із вікна програми «ЧудоСкан Друк»", PcPairing.formatError(it)) }
    }

    @Test fun wrongExpiredOrValid() {
        assertEquals("Неправильний код. Перевірте цифри у вікні програми.", PcPairing.pairingError(false, null, null, now))
        assertEquals("Неправильний код. Перевірте цифри у вікні програми.", PcPairing.pairingError(true, "", Date(now.time + 60_000), now))
        assertEquals("Код минув — у вікні програми вже новий код.", PcPairing.pairingError(true, "pc", Date(now.time - 1), now))
        assertEquals("Код минув — у вікні програми вже новий код.", PcPairing.pairingError(true, "pc", now, now))
        assertNull(PcPairing.pairingError(true, "pc", Date(now.time + 10 * 60_000), now))
    }

    @Test fun pcOnline() {
        assertTrue(PcPairing.online(Date(now.time - 60_000), true, now))
        assertTrue(PcPairing.online(Date(now.time - 60_000), null, now))
        assertFalse(PcPairing.online(Date(now.time - 4 * 60_000), true, now))
        assertFalse(PcPairing.online(Date(now.time - 60_000), false, now))
        assertFalse(PcPairing.online(null, true, now))
    }
}
