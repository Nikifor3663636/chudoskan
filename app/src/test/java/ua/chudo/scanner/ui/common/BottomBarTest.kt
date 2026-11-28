package ua.chudo.scanner.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class BottomBarTest {
    @Test fun printIsNotInPhoneBar() {
        assertFalse(MainTab.PRINT in PhoneBarTabs)
        assertEquals(listOf(MainTab.HOME, MainTab.STOCK, MainTab.PROFILE), PhoneBarTabs)
    }

    @Test fun printScreenHighlightsProfile() {
        assertEquals(MainTab.PROFILE, phoneBarSelection(MainTab.PRINT))
        assertEquals(MainTab.STOCK, phoneBarSelection(MainTab.STOCK))
        assertEquals(null, phoneBarSelection(null))
    }
}
