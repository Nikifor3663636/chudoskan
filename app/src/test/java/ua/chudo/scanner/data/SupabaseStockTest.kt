package ua.chudo.scanner.data

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SupabaseStockTest {
    @Test fun commitProblems() {
        assertEquals(CommitProblem.Confirm(100, 24000), CommitProblem.of("confirm_required", "100,24000"))
        assertEquals(CommitProblem.Unknown(listOf("09", "99")), CommitProblem.of("unknown_stores", "09,99"))
        assertNull(CommitProblem.of("stock admin only", null))
    }

    @Test fun rowsInChunksOf2000() {
        val items = (0 until 4500).associate { "$it" to NetItem("Товар, з комою", "BOC000001", "1", mapOf("22" to 1.0)) }
        val chunks = SupabaseStock.rows(items)
        assertEquals(listOf(2000, 2000, 500), chunks.map { it.size })
        val first = chunks[0][0].jsonObject
        assertEquals("BOC000001", first["code"]!!.jsonPrimitive.content)
        assertEquals("1.0", first["q"]!!.jsonObject["22"]!!.jsonPrimitive.content)
    }

    @Test fun timestampFromPostgrest() {
        assertEquals(1795996800000L, parseTime("2026-11-30T00:00:00+00:00")!!.time)
        assertNull(parseTime("вчора"))
    }
}
