package ua.chudo.scanner.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class PromoSheetTest {
    @Test
    fun readsCampaignsWithQuotedMultilineConditions() {
        val csv = "\uFEFFТема акції,Статус,Початок,Кінець,Умови,Артикулів\r\n" +
            "\"Акція, зима\",Діє,27.11.2026,30.11.2026,\"Діє на \"\"вибрані\"\"\nтовари\",42\r\n" +
            ",,,,,\r\n"
        val campaign = parsePromoSheet(csv).single()
        assertEquals("Акція, зима", campaign.title)
        assertEquals(listOf("Умови" to "Діє на \"вибрані\"\nтовари", "Артикулів" to "42"), campaign.details)
        assertTrue(campaign.isActive(LocalDate.of(2026, 11, 30)))
        assertFalse(campaign.isActive(LocalDate.of(2026, 12, 1)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsWrongTabHeaders() {
        parsePromoSheet("Огляд,Коли\nПідсумок,Сьогодні")
    }
}
