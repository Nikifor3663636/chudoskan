package ua.chudo.scanner.data

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.kxml2.io.KXmlParser
import java.io.File

/**
 * Файл розміру реального звіту (46 складів, 23 780 товарів): час розбору й пам'ять.
 * Запуск: CHUDO_BENCH=1 ./gradlew :app:testDebugUnitTest --tests '*NetworkStockBenchmark*'
 */
class NetworkStockBenchmark {
    @Test fun fullSizeReport() {
        assumeTrue(System.getenv("CHUDO_BENCH") != null)
        Xlsx.newParser = { KXmlParser() }
        val stores = (1..46).map { "ЧО %02d Місто$it (ТЦ «Центр $it»)".format(it) }
        val file = File.createTempFile("network-stock", ".xlsx")
        try {
            val products = (1..23_780).asSequence().map { i ->
                val bc = (4_820_000_000_000L + i).toString()
                Triple("Товар $i, з комою, \"лапки\", BOC%06d, %d".format(i, 10_000 + i), List(46) { bc }, List(46) { k -> if ((i + k) % 3 == 0) null else (i + k) % 7 })
            }
            val rows = sequence {
                yieldAll(Report1C.report(stores, emptyList(), total = false))
                products.forEachIndexed { n, (b, bcs, qs) -> yield(listOf<Any?>(n, b) + (0 until 46).flatMap { k -> listOf(bcs[k], qs[k]) }) }
            }
            file.outputStream().buffered().use { Report1C.write(rows, it) }

            System.gc()
            val rt = Runtime.getRuntime()
            val before = rt.totalMemory() - rt.freeMemory()
            var peak = 0L
            val t0 = System.nanoTime()
            val s = NetworkStockParser.parse({ file.inputStream() }, file.length()) {
                peak = maxOf(peak, rt.totalMemory() - rt.freeMemory())
            }
            val ms = (System.nanoTime() - t0) / 1_000_000
            println("BENCH file=${file.length() / 1024 / 1024} MB items=${s.items.size} stores=${s.stores.size} time=$ms ms heapDelta≈${(peak - before) / 1024 / 1024} MB max=${rt.maxMemory() / 1024 / 1024} MB")
            assertEquals(46, s.stores.size)
            assertEquals(23_780, s.items.size)

            // Знімок на телефоні: запис, читання, повторне порівняння.
            val snapFile = File(file.parentFile, "bench-" + StockSnapshot.NAME)
            try {
                val snap = StockSnapshot(snapFile)
                val t1 = System.nanoTime()
                snap.write(StockDiff.plan(null, s).items)
                val t2 = System.nanoTime()
                System.gc()
                val base = rt.totalMemory() - rt.freeMemory()
                val old = snap.read()!!
                System.gc()
                val afterRead = rt.totalMemory() - rt.freeMemory()
                val again = StockDiff.plan(old, s)
                val t3 = System.nanoTime()
                println("BENCH snapshot=${snapFile.length() / 1024} KB write=${(t2 - t1) / 1_000_000} ms read+diff=${(t3 - t2) / 1_000_000} ms retained≈${(afterRead - base) / 1024 / 1024} MB writes=${again.writes}")
                assertEquals(0, again.writes)
            } finally {
                snapFile.delete()
            }
        } finally {
            file.delete()
        }
    }
}
