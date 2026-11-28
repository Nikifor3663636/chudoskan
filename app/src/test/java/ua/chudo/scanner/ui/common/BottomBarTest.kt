package ua.chudo.scanner.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import androidx.compose.ui.unit.dp
import org.junit.Test

class BottomBarTest {
    @Test fun printIsNotInPhoneBar() {
        assertFalse(MainTab.PRINT in PhoneBarTabs)
        assertEquals(listOf(MainTab.HOME, MainTab.CATALOG, MainTab.STOCK, MainTab.PROFILE), PhoneBarTabs)
    }

    @Test fun labelsAreHomeFirstAndBarIsTall() {
        assertEquals(listOf("Головна", "Каталог", "Залишки", "Профіль"), PhoneBarTabs.map(::phoneTabLabel))
        assertTrue(PhoneBarHeight >= 88.dp)
    }

    @Test fun printScreenHighlightsProfile() {
        assertEquals(MainTab.PROFILE, phoneBarSelection(MainTab.PRINT))
        assertEquals(MainTab.STOCK, phoneBarSelection(MainTab.STOCK))
        assertEquals(MainTab.CATALOG, phoneBarSelection(MainTab.CATALOG))
        assertEquals(null, phoneBarSelection(null))
    }
}
