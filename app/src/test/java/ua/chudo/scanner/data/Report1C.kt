package ua.chudo.scanner.data

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Збирає xlsx так, як його пише 1С: рядки — у `xl/SharedStrings.xml` (з великої S), числа — у `<v>`,
 * аркуш іде в zip раніше за рядки. Клітинка: String → спільний рядок, Number → число, null — немає.
 */
object Report1C {
    fun xlsx(rows: List<List<Any?>>): ByteArray = ByteArrayOutputStream().also { write(rows.asSequence(), it) }.toByteArray()

    fun write(rows: Sequence<List<Any?>>, out: OutputStream) {
        val strings = LinkedHashMap<String, Int>()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("[Content_Types].xml"))
            zip.write("<?xml version=\"1.0\"?><Types/>".toByteArray())
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
            val w = zip.bufferedWriter()
            w.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>")
            rows.forEachIndexed { i, row ->
                val r = i + 1
                w.write("<row r=\"$r\">")
                row.forEachIndexed { c, v ->
                    val ref = col(c) + r
                    when (v) {
                        null -> Unit
                        is Number -> w.write("<c r=\"$ref\"><v>$v</v></c>")
                        else -> {
                            val idx = strings.getOrPut(v.toString()) { strings.size }
                            w.write("<c r=\"$ref\" t=\"s\"><v>$idx</v></c>")
                        }
                    }
                }
                w.write("</row>")
            }
            w.write("</sheetData></worksheet>")
            w.flush()
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("xl/SharedStrings.xml"))
            val s = zip.bufferedWriter()
            s.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?><sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
            for (str in strings.keys) s.write("<si><t xml:space=\"preserve\">${esc(str)}</t></si>")
            s.write("</sst>")
            s.flush()
            zip.closeEntry()
        }
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    fun col(i: Int): String {
        var n = i + 1
        val sb = StringBuilder()
        while (n > 0) { val m = (n - 1) % 26; sb.insert(0, 'A' + m); n = (n - 1) / 26 }
        return sb.toString()
    }

    /**
     * Звіт «Ведомость по партиям товаров на складах»: 7 рядків заголовка, шапка, дві підшапки, товари, «Итого».
     * [stores] — заголовки складів; [products] — (B, штрихкод на склад, кількість на склад).
     */
    fun report(stores: List<String>, products: List<Triple<String, List<Any?>, List<Any?>>>, total: Boolean = true): List<List<Any?>> {
        val rows = mutableListOf<List<Any?>>()
        rows.add(listOf(null, "Ведомость по партиям товаров на складах"))
        rows.add(listOf(null, "Период: 01.11.2026 - 23.11.2026"))
        rows.add(listOf(null, "Показатели: Количество"))
        rows.add(listOf(null, "Группировки строк: Номенклатура"))
        rows.add(listOf(null, "Отбор: Склад В списке"))
        rows.add(emptyList())
        rows.add(emptyList())
        rows.add(listOf(null, "Номенклатура, Код, Артикул") + stores.flatMap { listOf(it, null) })
        rows.add(listOf<Any?>(null, null) + stores.flatMap { listOf("Штрих код", "Конечный остаток") })
        rows.add(listOf<Any?>(null, null) + stores.flatMap { listOf(null, "Количество") })
        products.forEachIndexed { i, (b, bcs, qs) ->
            rows.add(listOf<Any?>(1000 + i, b) + stores.indices.flatMap { k -> listOf(bcs.getOrNull(k), qs.getOrNull(k)) })
        }
        if (total) rows.add(listOf<Any?>(null, "Итого") + stores.flatMap { listOf(null, 999) })
        return rows
    }
}
