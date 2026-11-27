package ua.chudo.scanner.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {
    @Test fun maskPhoneHidesMiddle() {
        assertEquals("+380 67 ••• •• 67", maskPhone(formatPhone("+380671234567")))
        assertEquals("12345", maskPhone("12345"))
    }

    @Test fun shopsOfCityUsesGenitive() {
        assertEquals("Магазини Одеси", shopsOfCity("Одеса"))
        assertEquals("Магазини Києва", shopsOfCity("Київ"))
        assertEquals("Магазини Львова", shopsOfCity("Львів"))
        assertEquals("Магазини Дніпра", shopsOfCity("Дніпро"))
        assertEquals("Магазини Вінниці", shopsOfCity("Вінниця"))
        assertEquals("Магазини Ужгорода", shopsOfCity("Ужгород"))
        assertEquals("Магазини Білої Церкви", shopsOfCity("Біла Церква"))
        assertEquals("Магазини · Кам'янець-Подільський", shopsOfCity("Кам'янець-Подільський"))
    }
}
