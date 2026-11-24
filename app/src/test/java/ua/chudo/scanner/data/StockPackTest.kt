package ua.chudo.scanner.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import kotlin.random.Random

class StockPackTest {
    @Test fun `пакет ділиться на частини й склеюється без втрат`() {
        val bytes = Random(1).nextBytes(StockPack.PART * 2 + 123)
        val parts = StockPack.split(bytes)
        assertEquals(3, parts.size)
        assert(parts.all { it.size <= StockPack.PART })
        assertArrayEquals(bytes, StockPack.join(parts))
    }

    @Test fun `знімок через пакет читається тим самим`() {
        val items = (1..2000).associate { "200099915${"%04d".format(it)}" to NetItem("Пакет $it", "BOC$it", "", mapOf("22" to it.toDouble(), "09" to 1.0)) }
        val a = File.createTempFile("pack", ".gz").apply { deleteOnExit() }
        StockSnapshot(a).write(items)
        val b = File.createTempFile("pack2", ".gz").apply { deleteOnExit() }
        b.writeBytes(StockPack.join(StockPack.split(a.readBytes())))
        assertEquals(items, StockSnapshot(b).read())
    }

    @Test fun `список змінених`() {
        assertEquals(setOf("1", "2"), StockPack.gunzipLines(StockPack.gzipLines(listOf("1", "2", "1"))))
        assertEquals(emptySet<String>(), StockPack.gunzipLines(ByteArray(0)))
    }
}
