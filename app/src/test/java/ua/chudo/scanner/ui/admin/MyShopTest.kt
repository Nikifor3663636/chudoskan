package ua.chudo.scanner.ui.admin

import org.junit.Assert.assertEquals
import org.junit.Test
import ua.chudo.scanner.data.ActivityKind
import ua.chudo.scanner.data.ShopActivity
import ua.chudo.scanner.data.StaffMember
import ua.chudo.scanner.ui.profile.notificationEvents

class MyShopTest {
    private fun member(uid: String, name: String, approved: Boolean, admin: Boolean = false, shopId: String = "s22") =
        StaffMember(uid, name, "+380671234567", shopId, "ЧО 22", "odesa", approved, admin)

    @Test fun groupsPendingAndTeam() {
        val staff = listOf(
            member("1", "Ірина Савчук", approved = false),
            member("2", "Андрій Мельник", approved = true),
            member("3", "Олена Коваль", approved = true, admin = true),
            member("4", "Вимкнений", approved = false, shopId = ""),
        )
        val (pending, team) = myShopGroups(staff)
        assertEquals(listOf("1"), pending.map { it.uid })
        assertEquals(listOf("3", "2", "4"), team.map { it.uid })
        assertEquals("Адмін магазину", staffRole(team[0]))
        assertEquals("Доступ вимкнено", staffRole(team[2]))
    }

    @Test fun teamIsOrderedByJob() {
        val staff = listOf(
            member("1", "Андрій", approved = true).copy(job = ua.chudo.scanner.data.JobTitle.SELLER),
            member("2", "Борис", approved = true),
            member("3", "Віра", approved = true).copy(job = ua.chudo.scanner.data.JobTitle.MANAGER),
            member("4", "Галина", approved = true, admin = true).copy(job = ua.chudo.scanner.data.JobTitle.SENIOR),
        )
        val (_, team) = myShopGroups(staff)
        assertEquals(listOf("3", "4", "1", "2"), team.map { it.uid })
        assertEquals("Старший продавець · Адмін магазину", staffRole(team[1]))
        assertEquals("Співробітник", staffRole(team[3]))
    }

    @Test fun notificationsShowStockAndPrinters() {
        val events = ActivityKind.entries.mapIndexed { i, k -> ShopActivity(k, k.title, "", i.toLong()) }
        assertEquals(setOf(ActivityKind.STOCK, ActivityKind.PRINTER), notificationEvents(events).map { it.kind }.toSet())
    }
}
