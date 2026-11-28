package ua.chudo.scanner.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ShopLogTest {
    private val names = mapOf("adm22" to "Олена Коваль", "new22" to "Денис Т.")
    private fun entry(action: String, by: String = "adm22", target: String = "new22", details: String = "") =
        AdminLogEntry("1", by, action, target, "22", details, null)

    @Test fun approvalReadsLikeTheMockup() {
        val line = shopLogLine(entry("approve"), names)
        assertEquals("Підтверджено анкету", line.title)
        assertEquals("Олена Коваль → Денис Т.", line.detail)
    }

    @Test fun networkAdminAndUnknownTargets() {
        assertEquals("Адмін мережі → Денис Т.", shopLogLine(entry("disable", by = "sup"), names).detail)
        assertEquals("Олена Коваль → КАСА-22", shopLogLine(entry("pairPc", target = "КАСА-22"), names).detail)
        assertEquals("Скинуто пароль", shopLogLine(entry("passwordReset"), names).title)
    }

    @Test fun unknownActionAndDetails() {
        val line = shopLogLine(entry("somethingNew", target = "", details = "x"), names)
        assertEquals("somethingNew", line.title)
        assertEquals("Олена Коваль · x", line.detail)
    }
}
