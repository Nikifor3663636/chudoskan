package ua.chudo.scanner.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TeamTest {
    private fun p(uid: String, name: String, job: JobTitle?, admin: Boolean = false) = TeamMember(uid, name, job, admin)

    @Test fun jobCodesRoundTrip() {
        JobTitle.entries.forEach { assertEquals(it, JobTitle.of(it.code)) }
        assertNull(JobTitle.of(""))
        assertNull(JobTitle.of("boss"))
        assertNull(JobTitle.of(null))
    }

    @Test fun groupsByJobInOrderAndAlphabet() {
        val groups = Team.groups(listOf(
            p("1", "Яна", JobTitle.SELLER),
            p("2", "Іван", null),
            p("3", "Олена", JobTitle.MANAGER, admin = true),
            p("4", "Євген", JobTitle.SELLER),
            p("5", "Дмитро", JobTitle.SENIOR),
        ))
        assertEquals(listOf("Керуючий магазином", "Старші продавці", "Продавці", "Посаду не вказано"), groups.map { it.title })
        assertEquals(listOf("Євген", "Яна"), groups[2].members.map { it.name })
    }

    @Test fun emptyGroupsAreHidden() {
        assertEquals(listOf("Продавці"), Team.groups(listOf(p("1", "А", JobTitle.SELLER))).map { it.title })
        assertTrue(Team.groups(emptyList()).isEmpty())
    }

    @Test fun subtitleShowsJobAndAdmin() {
        assertEquals("Старший продавець · Адмін магазину", Team.subtitle(JobTitle.SENIOR, admin = true))
        assertEquals("Керуючий", Team.subtitle(JobTitle.MANAGER, admin = false))
        assertEquals("Адмін магазину", Team.subtitle(null, admin = true))
        assertEquals("Співробітник", Team.subtitle(null, admin = false))
    }

    @Test fun jobIsSetOnlyForApprovedNonNetworkAdmins() {
        fun s(approved: Boolean, superAdmin: Boolean = false) = StaffMember("u", "Н", "", "22", "", "", approved, superAdmin = superAdmin)
        assertTrue(Team.canSetJob(s(approved = true)))
        assertFalse(Team.canSetJob(s(approved = false)))
        assertFalse(Team.canSetJob(s(approved = true, superAdmin = true)))
    }

    @Test fun userRowCarriesJob() {
        val row = UserRow(uid = "u", approved = true, jobTitle = "senior")
        assertEquals(JobTitle.SENIOR, row.staff().job)
        assertEquals(JobTitle.SENIOR, row.employee("").jobTitle)
        assertNull(UserRow(uid = "u").staff().job)
    }
}
