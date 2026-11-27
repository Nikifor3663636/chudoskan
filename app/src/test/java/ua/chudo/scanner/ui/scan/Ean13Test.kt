package ua.chudo.scanner.ui.scan

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Ean13Test {
    @Test fun checksControlDigit() {
        assertTrue(ean13Valid("5702016911114"))
        assertTrue(ean13Valid("6903317562084"))
        assertFalse(ean13Valid("5702016911115"))
        assertFalse(ean13Valid("570201691111"))
        assertFalse(ean13Valid("BOC0224460000"))
    }
}
