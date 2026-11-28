package ua.chudo.scanner.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test

class GreetingTest {
    @Test fun timeOfDay() {
        assertEquals("Доброго ранку", greetingFor(5))
        assertEquals("Доброго ранку", greetingFor(10))
        assertEquals("Доброго дня", greetingFor(11))
        assertEquals("Доброго дня", greetingFor(16))
        assertEquals("Доброго вечора", greetingFor(17))
        assertEquals("Доброго вечора", greetingFor(22))
        assertEquals("Доброї ночі", greetingFor(23))
        assertEquals("Доброї ночі", greetingFor(3))
    }

    @Test fun vocativeForCommonNames() {
        assertEquals("Олено", vocativeName("Олена Коваль"))
        assertEquals("Микито", vocativeName("Микита"))
        assertEquals("Маріє", vocativeName("Марія Д."))
        assertEquals("Таню", vocativeName("Таня"))
        assertEquals("Андрію", vocativeName("Андрій Мельник"))
        assertEquals("Сергію", vocativeName("  Сергій  "))
        assertEquals("Денис", vocativeName("Денис"))
        assertEquals("", vocativeName(""))
    }

    @Test fun greetingWithAndWithoutName() {
        assertEquals("Доброго ранку, Олено", greeting("Олена Коваль", 9))
        assertEquals("Доброго вечора", greeting(" ", 19))
    }
}
