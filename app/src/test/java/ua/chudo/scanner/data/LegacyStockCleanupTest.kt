package ua.chudo.scanner.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyStockCleanupTest {
    private class FakeStore(var docs: Long, var counter: Pair<String, Int>? = null) : LegacyStockStore {
        var deletes = 0L
        override suspend fun counter() = counter
        override suspend fun saveCounter(day: String, deleted: Int, remaining: Long, by: String) { counter = day to deleted }
        override suspend fun deleteSome(n: Int): Int {
            val k = minOf(n.toLong(), docs).toInt()
            docs -= k; deletes += k
            return k
        }
        override suspend fun remaining() = docs
    }

    @Test fun `не більше 5 000 на добу, наступного дня — продовжує`() = runBlocking {
        val store = FakeStore(22_000)
        var day = "2026-11-24"
        val c = LegacyStockCleanup(store) { day }
        val progress = mutableListOf<CleanupState>()
        val s = c.run("Залишки") { progress += it }
        assertEquals(5_000, s.deletedToday)
        assertEquals(17_000L, s.remaining)
        assertEquals(0, s.leftToday)
        assertTrue(progress.all { it.deletedToday <= 5_000 } && progress.size == 12)
        assertEquals(17_000L, progress.last().remaining)

        // Ще раз того ж дня — нічого не видаляє.
        assertEquals(5_000, c.run("Залишки").deletedToday)
        assertEquals(5_000L, store.deletes)

        day = "2026-11-25"
        assertEquals(5_000, c.state().leftToday)
        c.run("Залишки")
        assertEquals(12_000L, store.docs)
    }

    @Test fun `зупиняється, коли все видалено`() = runBlocking {
        val store = FakeStore(700)
        val s = LegacyStockCleanup(store) { "2026-11-24" }.run("Залишки")
        assertEquals(700, s.deletedToday)
        assertEquals(0L, s.remaining)
    }

    @Test fun `ліміт рахується від уже видаленого сьогодні`() {
        assertEquals(5_000, LegacyStockCleanup.budget("2026-11-23", "2026-11-24", 5_000))
        assertEquals(1_200, LegacyStockCleanup.budget("2026-11-24", "2026-11-24", 3_800))
        assertEquals(0, LegacyStockCleanup.budget("2026-11-24", "2026-11-24", 6_000))
    }
}
