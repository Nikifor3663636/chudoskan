package ua.chudo.scanner.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

/** Адміністрування мережі: каталог магазинів, пошук, фільтри співробітників, дії з анкетами, «Працювати як». */
class NetworkAdminTest {

    private val shops = listOf(
        ShopDoc("kyiv_a", "вул. Хрещатик, 1", "kyiv", null, Date(1)),
        ShopDoc("odesa_b", "вул. Дерибасівська, 2", "odesa", "62", null),
    )
    private val stores = mapOf(
        "22" to StoreDoc("ЧО 22 Київ (ТЦ Ocean Plaza)", "Київ", "kyiv_a"),
        "62" to StoreDoc("ЧО 62 Одеса (ТЦ Рів'єра)", "Одеса", null),
        "09" to StoreDoc("ЧО 09 Біла Церква", "Біла Церква", null),
    )
    private val staff = listOf(
        StaffMember("u1", "Оля", "+380501111111", "kyiv_a", "вул. Хрещатик, 1", "kyiv", approved = true, admin = true),
        StaffMember("u2", "Іра", "+380502222222", "kyiv_a", "вул. Хрещатик, 1", "kyiv", approved = false),
        StaffMember("u3", "Петро", "+380503333333", "odesa_b", "вул. Дерибасівська, 2", "odesa", approved = false),
        StaffMember("u4", "Без магазину", "+380504444444", "", "", "", approved = false),
        StaffMember("u5", "Новий магазин", "+380505555555", "lviv_c", "пл. Ринок, 5", "lviv", approved = true),
        StaffMember("boss", "Мережа", "+380509999999", "kyiv_a", "вул. Хрещатик, 1", "kyiv", approved = true, superAdmin = true),
    )
    private val agents = listOf(
        AgentInfo("pc1", "kyiv_a", "КАСА-22", online = true),
        AgentInfo("pc2", "kyiv_a", "СКЛАД", online = false),
        AgentInfo("pc3", "odesa_b", "КАСА-62", online = true),
    )
    private val regions = mapOf("kyiv" to "Київ", "odesa" to "Одеса", "lviv" to "Львів")

    private fun dir() = NetworkAdmin.directory(shops, stores, staff, agents) { regions[it] ?: it }

    @Test fun directoryJoinsShopsStoresStaffAndPcs() {
        val d = dir()
        val kyiv = d.single { it.shopId == "kyiv_a" }
        assertEquals("22", kyiv.code) // через stores.shopId
        assertEquals("Київ", kyiv.city)
        assertEquals(2, kyiv.staff) // u1 і boss
        assertEquals(1, kyiv.pending)
        assertEquals(1, kyiv.pcsOnline)
        assertEquals(Date(1), kyiv.stockUpdatedAt)

        val odesa = d.single { it.shopId == "odesa_b" }
        assertEquals("62", odesa.code) // через shops.code1C
        assertEquals("ЧО 62", odesa.title)

        // Магазин лише з анкети — теж є; місто з області.
        val lviv = d.single { it.shopId == "lviv_c" }
        assertNull(lviv.code)
        assertEquals("Львів", lviv.city)
        assertEquals("Без складу 1С", lviv.title)

        // Склад 1С без магазину — окремим рядком, у кінці коду за номером.
        val unlinked = d.single { it.shopId == null }
        assertEquals("09", unlinked.code)
        assertEquals(listOf("09", "22", "62", null), d.map { it.code })
    }

    @Test fun searchByNumberCityAddress() {
        val d = dir()
        assertEquals(listOf("odesa_b"), NetworkAdmin.search(d, "62").map { it.shopId })
        assertEquals(listOf("odesa_b"), NetworkAdmin.search(d, "ЧО 62").map { it.shopId })
        assertEquals(listOf(null), NetworkAdmin.search(d, "9").map { it.shopId }) // «09»
        assertEquals(listOf("kyiv_a"), NetworkAdmin.search(d, "київ").map { it.shopId })
        assertEquals(listOf("kyiv_a"), NetworkAdmin.search(d, "хрещатик").map { it.shopId })
        assertEquals(d, NetworkAdmin.search(d, " "))
    }

    @Test fun staffFilters() {
        val cityOf: (StaffMember) -> String = { regions[it.region] ?: it.region }
        assertEquals(listOf("u2", "u3"), NetworkAdmin.filterStaff(staff, pendingOnly = true, shopId = null, city = null, cityOf = cityOf).map { it.uid })
        assertEquals(listOf("u2", "boss", "u1"), NetworkAdmin.filterStaff(staff, false, "kyiv_a", null, cityOf).map { it.uid })
        assertEquals(listOf("u3"), NetworkAdmin.filterStaff(staff, false, null, "одеса", cityOf).map { it.uid })
        assertEquals(listOf("u5"), NetworkAdmin.filterStaff(staff, false, null, null, cityOf, query = "ринок").map { it.uid })
        // Відхилений (без магазину) — не «очікує».
        assertFalse(staff.single { it.uid == "u4" }.pending)
    }

    @Test fun actionsForSuperAdminAndShopAdmin() {
        val pending = staff.single { it.uid == "u3" }
        val admin = staff.single { it.uid == "u1" }
        assertEquals(listOf(StaffAction.APPROVE, StaffAction.REJECT, StaffAction.TRANSFER), NetworkAdmin.actions(pending, "boss", superAdmin = true))
        assertEquals(listOf(StaffAction.REMOVE_ADMIN, StaffAction.TRANSFER, StaffAction.DISABLE), NetworkAdmin.actions(admin, "boss", superAdmin = true))
        // Адмін магазину: без переведення й відхилення.
        assertEquals(listOf(StaffAction.APPROVE), NetworkAdmin.actions(staff.single { it.uid == "u2" }, "u1", superAdmin = false))
        // Себе й адміна мережі — не змінюємо.
        assertTrue(NetworkAdmin.actions(admin, "u1", superAdmin = false).isEmpty())
        assertTrue(NetworkAdmin.actions(staff.single { it.uid == "boss" }, "u1", superAdmin = true).isEmpty())
    }

    @Test fun updatesOnlyAllowedFieldsWithApprovedBy() {
        val allowed = setOf("approved", "admin", "shopId", "region", "shop", "approvedBy")
        StaffAction.entries.forEach { a ->
            val u = NetworkAdmin.update(a, "boss", shops[1])
            assertTrue("$a: ${u.keys}", allowed.containsAll(u.keys))
            assertEquals("boss", u["approvedBy"])
        }
        assertEquals(true, NetworkAdmin.update(StaffAction.APPROVE, "boss")["approved"])
        assertEquals("", NetworkAdmin.update(StaffAction.REJECT, "boss")["shopId"])
        assertEquals(mapOf("approved" to false, "admin" to false, "approvedBy" to "boss"), NetworkAdmin.update(StaffAction.DISABLE, "boss"))
        val t = NetworkAdmin.update(StaffAction.TRANSFER, "boss", shops[1])
        assertEquals("odesa_b", t["shopId"])
        assertEquals("вул. Дерибасівська, 2", t["shop"])
        assertEquals("odesa", t["region"])
        assertEquals(false, t["admin"])
    }

    @Test fun actingInAnotherShopKeepsProfile() {
        val me = Employee("boss", "Мережа", "+380509999999", "kyiv", "вул. Хрещатик, 1", "kyiv_a", approved = true, superAdmin = true)
        val acting = me.actingIn("odesa_b", "вул. Дерибасівська, 2", "odesa", "62")
        assertEquals("odesa_b", acting.shopId)
        assertEquals("62", acting.actingCode)
        assertEquals("kyiv_a", acting.homeShopId)
        assertEquals(me.uid, acting.uid)
        assertTrue(acting.managesShop)
        assertNull(me.actingCode)
        assertFalse(me.copy(superAdmin = false).managesShop)
    }
}
