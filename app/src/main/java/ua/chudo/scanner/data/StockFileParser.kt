package ua.chudo.scanner.data

import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** Одна позиция из выгрузки 1С. */
data class StockRow(
    val article: String?,
    val barcode: String?,
    val name: String?,
    val qty: Double,
)

/** Какие колонки нашли в шапке (названия как в файле) — показываем перед загрузкой. */
data class StockColumns(
    val article: String?,
    val barcode: String?,
    val name: String?,
    val qty: String,
)

data class ParsedStock(val columns: StockColumns, val rows: List<StockRow>)

class StockFileException(message: String) : Exception(message)

/**
 * Разбирает выгрузку остатков из 1С: Excel (.xlsx) или CSV/TXT (разделитель `;`, `,` или табуляция,
 * кодировка UTF-8 или Windows-1251). Шапку ищем сами в первых строках — 1С ставит над ней заголовок
 * отчёта. Нужны колонка количества и артикул или штрихкод.
 */
object StockFileParser {

    fun parse(bytes: ByteArray): ParsedStock {
        val table = when {
            Xlsx.isZip(bytes) -> readXlsx(bytes)
            isOldExcel(bytes) -> throw StockFileException(
                "Старый формат .xls не поддерживается. В 1С или Excel сохраните отчёт как .xlsx или .csv.",
            )
            else -> readCsv(decode(bytes))
        }
        return fromTable(table)
    }

    // ---------- Колонки ----------

    private fun fromTable(table: List<List<String>>): ParsedStock {
        for ((i, header) in table.take(HEADER_SCAN_ROWS).withIndex()) {
            val cols = detect(header) ?: continue
            val rows = table.drop(i + 1).mapNotNull { toRow(it, cols) }
            if (rows.isEmpty()) throw StockFileException("Шапку нашли, но под ней нет строк с количеством.")
            return ParsedStock(
                StockColumns(
                    article = cols.article?.let { header[it] },
                    barcode = cols.barcode?.let { header[it] },
                    name = cols.name?.let { header[it] },
                    qty = header[cols.qty],
                ),
                merge(rows),
            )
        }
        throw StockFileException(
            "Не нашли шапку таблицы. Нужны колонки «Артикул» (или «Штрихкод») и «Количество» / «Остаток».",
        )
    }

    private class Cols(val article: Int?, val barcode: Int?, val name: Int?, val qty: Int)

    private fun detect(header: List<String>): Cols? {
        val h = header.map { it.lowercase().replace('ё', 'е').trim() }
        fun find(pred: (String) -> Boolean) = h.indices.filter { h[it].isNotEmpty() && pred(h[it]) }

        val barcode = find { c -> BARCODE.any { it in c } }.firstOrNull()
        val article = find { c -> ARTICLE.any { it in c } }.firstOrNull { it != barcode }
            ?: find { c -> c == "код" || c.startsWith("код ") }.firstOrNull { it != barcode }
        val name = find { c -> NAME.any { it in c } }.firstOrNull { it != article && it != barcode }

        val qtyAll = find { c -> QTY.any { it in c } }.filter { it != article && it != barcode && it != name }
        // В оборотных ведомостях несколько колонок: берём конечный остаток, а не начальный/приход/расход.
        val qty = qtyAll.firstOrNull { c -> FINAL.any { it in h[c] } }
            ?: qtyAll.firstOrNull { c -> NOT_BALANCE.none { it in h[c] } }
            ?: return null
        if (article == null && barcode == null) return null
        return Cols(article, barcode, name, qty)
    }

    private fun toRow(r: List<String>, c: Cols): StockRow? {
        val qty = number(r.getOrNull(c.qty)) ?: return null
        val article = c.article?.let { r.getOrNull(it) }?.let(::cleanArticle)
        val barcode = c.barcode?.let { r.getOrNull(it) }?.let(::cleanBarcode)
        if (article == null && barcode == null) return null
        val name = c.name?.let { r.getOrNull(it) }?.trim()?.ifEmpty { null }
        return StockRow(article, barcode, name, qty)
    }

    /** Один товар на нескольких складах магазина — складываем. */
    private fun merge(rows: List<StockRow>): List<StockRow> =
        rows.groupBy { ShopStockRepository.keyOf(it.article, it.barcode) }
            .map { (_, same) -> same.first().copy(qty = same.sumOf { it.qty }) }

    internal fun number(s: String?): Double? {
        val t = s?.replace(Regex("[\\s ]"), "")?.replace(',', '.')?.ifEmpty { null } ?: return null
        return t.toDoubleOrNull()
    }

    private fun cleanArticle(s: String): String? {
        val t = s.trim().ifEmpty { return null }
        // Excel хранит числовой артикул как 64103.0 или 6.4103E4.
        return if (Regex("^[0-9.]+(E[0-9]+)?$", RegexOption.IGNORE_CASE).matches(t)) plain(t) else t
    }

    internal fun cleanBarcode(s: String): String? {
        val t = s.trim().ifEmpty { return null }
        val p = if (t.contains('E', ignoreCase = true)) plain(t) else t
        return p.filter(Char::isDigit).ifEmpty { null }
    }

    private fun plain(t: String): String =
        runCatching { BigDecimal(t).stripTrailingZeros().toPlainString() }.getOrDefault(t)

    // ---------- CSV ----------

    private fun decode(bytes: ByteArray): String {
        val utf8 = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val text = try {
            utf8.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            String(bytes, Charset.forName("windows-1251"))
        }
        return text.removePrefix("FEFF")
    }

    private fun readCsv(text: String): List<List<String>> {
        val lines = text.lineSequence().take(HEADER_SCAN_ROWS).toList()
        val sep = listOf(';', '\t', ',').maxBy { s -> lines.sumOf { l -> l.count { it == s } } }
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            when {
                quoted && ch == '"' && text.getOrNull(i + 1) == '"' -> { cell.append('"'); i++ }
                ch == '"' -> quoted = !quoted
                !quoted && ch == sep -> { row.add(cell.toString()); cell.clear() }
                !quoted && (ch == '\n' || ch == '\r') -> {
                    if (ch == '\r' && text.getOrNull(i + 1) == '\n') i++
                    row.add(cell.toString()); cell.clear()
                    rows.add(row); row = mutableListOf()
                }
                else -> cell.append(ch)
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) { row.add(cell.toString()); rows.add(row) }
        return rows
    }

    // ---------- XLSX ----------

    private fun isOldExcel(b: ByteArray) =
        b.size > 8 && b[0] == 0xD0.toByte() && b[1] == 0xCF.toByte() && b[2] == 0x11.toByte() && b[3] == 0xE0.toByte()

    private fun readXlsx(bytes: ByteArray): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        Xlsx.read({ ByteArrayInputStream(bytes) }) { _, r -> rows.add(r) }
        return rows
    }

    private const val HEADER_SCAN_ROWS = 40
    private val BARCODE = listOf("штрих", "barcode", "ean")
    private val ARTICLE = listOf("артикул", "арт.", "article", "sku")
    private val NAME = listOf("наимен", "найменув", "номенклатур", "назва", "товар", "name")
    private val QTY = listOf("колич", "кол-во", "кол.", "кільк", "к-сть", "к-во", "остат", "залиш", "qty", "quantity")
    private val FINAL = listOf("конеч", "кінцев", "на конец", "на кінець")
    private val NOT_BALANCE = listOf("началь", "початк", "приход", "расход", "надход", "витрат", "продаж")
}
