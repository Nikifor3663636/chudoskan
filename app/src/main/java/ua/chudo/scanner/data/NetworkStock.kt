package ua.chudo.scanner.data

import java.io.File
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Склад зі звіту 1С: «ЧО 22 Київ (ТЦ …)» → code "22", city «Київ». */
data class NetStore(val code: String, val name: String, val city: String)

/** Товар мережі: кількість за кодом магазину, лише ненульові. */
data class NetItem(val name: String, val code: String, val article: String, val q: Map<String, Double>)

data class NetworkStock(
    val stores: List<NetStore>,
    /** Штрихкод → товар. */
    val items: Map<String, NetItem>,
    /** Рядки товарів без штрихкоду (пропущені). */
    val noBarcode: Int,
)

/**
 * Звіт 1С «Ведомость по партиям товаров на складах» — залишки всієї мережі:
 * - над шапкою — заголовок звіту (рядки 1–7);
 * - шапка: колонка «Номенклатура, Код, Артикул», далі через одну — склади «ЧО NN Місто (ТЦ …)»;
 * - під кожним складом: «Штрих код | Конечный остаток», нижче «Количество»;
 * - далі рядок на товар; колонка A — службовий id; рядки «Итого» пропускаємо.
 * Шапку й склади шукаємо за текстом, а не за номерами рядків.
 */
object NetworkStockParser {

    /** «назва, КОД, артикул» — ділимо з кінця: у назві бувають коми, артикул може бути порожнім. */
    private val NAME_CODE_ARTICLE = Regex("^(.*), ([A-Z]{3}\\d{6})(?:, ?(.*))?$", RegexOption.DOT_MATCHES_ALL)
    private val STORE = Regex("^\\s*Ч[ОO]\\s*(\\d{1,4})(?!\\d)\\s*(.*)$", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

    private class StoreCols(val store: NetStore, val from: Int, var barcode: Int, var qty: Int)

    /**
     * [open] викликається двічі (див. [Xlsx.read]). [totalBytes] — розмір файлу для [onProgress] (0..1);
     * невідомий — прогрес не повідомляємо.
     */
    fun parse(open: () -> InputStream, totalBytes: Long = -1, onProgress: (Float) -> Unit = {}): NetworkStock {
        var passBase = 0L
        var lastReported = -1
        val counted = {
            val pass = passBase
            passBase += totalBytes.coerceAtLeast(0)
            CountingInputStream(open()) { n ->
                if (totalBytes > 0) {
                    val pct = ((pass + n) * 100 / (2 * totalBytes)).toInt().coerceIn(0, 100)
                    if (pct != lastReported) { lastReported = pct; onProgress(pct / 100f) }
                }
            }
        }

        var nameCol = -1
        var codeCol: Int? = null
        var articleCol: Int? = null
        var stores: List<StoreCols> = emptyList()
        var productsStarted = false
        var noBarcode = 0
        val items = HashMap<String, NetItem>()

        Xlsx.read(counted) { _, row ->
            if (nameCol < 0) {
                // Шапка — рядок з «Номенклатура» і складами «ЧО NN» (у заголовку звіту теж буває «Номенклатура»).
                val i = row.indexOfFirst { it.contains("номенклатур", ignoreCase = true) }
                if (i < 0) return@read
                val found = row.withIndex().mapNotNull { (c, text) -> store(text)?.let { StoreCols(it, c, c, c + 1) } }
                if (found.isEmpty()) return@read
                nameCol = i
                stores = found
                codeCol = row.indexOfFirst { it.trim().equals("код", ignoreCase = true) }.takeIf { it >= 0 }
                articleCol = row.indexOfFirst { it.trim().equals("артикул", ignoreCase = true) }.takeIf { it >= 0 }
                return@read
            }
            // Підшапка: «Штрих код | Конечный остаток», «Количество» — уточнюємо колонки складів.
            if (!productsStarted && subHeader(row, stores)) return@read
            val name = row.getOrNull(nameCol)?.trim().orEmpty()
            if (name.isEmpty() || isTotal(row, nameCol)) return@read
            productsStarted = true

            val barcode = stores.firstNotNullOfOrNull { s -> row.getOrNull(s.barcode)?.let(StockFileParser::cleanBarcode) }
            if (barcode == null) { noBarcode++; return@read }

            val (title, code, article) = if (codeCol != null || articleCol != null) {
                Triple(name, cell(row, codeCol), cell(row, articleCol))
            } else {
                NAME_CODE_ARTICLE.find(name)?.destructured?.let { (n, c, a) -> Triple(n.trim(), c, a.trim()) } ?: Triple(name, "", "")
            }
            val q = HashMap<String, Double>()
            for (s in stores) {
                val v = StockFileParser.number(row.getOrNull(s.qty)) ?: continue
                if (v > 0 && v.isFinite()) q[s.store.code] = (q[s.store.code] ?: 0.0) + v
            }
            val prev = items[barcode]
            when {
                prev != null -> items[barcode] = prev.copy(q = sum(prev.q, q))
                // Порожня кількість у всіх складах — товару немає ніде: не пишемо.
                q.isNotEmpty() -> items[barcode] = NetItem(title, code, article, q)
            }
        }

        if (nameCol < 0) throw StockFileException("Не знайшли шапку звіту: потрібна колонка «Номенклатура, Код, Артикул».")
        if (stores.isEmpty()) throw StockFileException("У шапці звіту немає складів «ЧО NN …».")
        onProgress(1f)
        return NetworkStock(stores.map { it.store }.distinctBy { it.code }, items, noBarcode)
    }

    /** «ЧО 22 Київ (ТЦ Ocean Plaza)» → склад; місто — текст після коду до дужки. */
    fun store(text: String): NetStore? {
        val m = STORE.find(text.trim()) ?: return null
        val rest = m.groupValues[2].trim()
        // «Біла Церква (ТЦ …)» → «Біла Церква»; без дужок: «Малехів ТЦ Обрій» → «Малехів», «Умань LOMO» → «Умань».
        val words = rest.substringBefore('(').trim().split(Regex("\\s+"))
            .takeWhile { w -> w.uppercase() !in MALL && !w.all { it in 'A'..'Z' || it in 'a'..'z' || it.isDigit() } }
        val city = words.joinToString(" ").trimEnd(',', '.', '-', '–').trim()
        return NetStore(m.groupValues[1], text.trim().replace(Regex("\\s+"), " "), city)
    }

    /** Рядок підшапки — якщо в колонках складів є «штрих» або «остаток / количество». */
    private fun subHeader(row: List<String>, stores: List<StoreCols>): Boolean {
        var hit = false
        stores.forEachIndexed { k, s ->
            val until = stores.getOrNull(k + 1)?.from ?: (s.from + 2)
            for (c in s.from until minOf(until, row.size)) {
                val t = row[c].lowercase()
                when {
                    "штрих" in t -> { s.barcode = c; hit = true }
                    QTY.any { it in t } -> { s.qty = c; hit = true }
                }
            }
        }
        return hit
    }

    private fun cell(row: List<String>, col: Int?) = col?.let { row.getOrNull(it) }?.trim().orEmpty()

    private fun isTotal(row: List<String>, nameCol: Int) =
        (0..nameCol).any { row.getOrNull(it)?.trim()?.startsWith("итог", ignoreCase = true) == true }

    private fun sum(a: Map<String, Double>, b: Map<String, Double>): Map<String, Double> =
        (a.keys + b.keys).associateWith { (a[it] ?: 0.0) + (b[it] ?: 0.0) }

    private val QTY = listOf("остат", "колич", "кільк", "залиш")

    /** Слова, з яких починається назва торгового центру, а не місто. */
    private val MALL = setOf("ТЦ", "ТРЦ", "ТД", "ТРК", "ТК", "ТРЦ.", "ТЦ.")
}

/** Що й навіщо писати у Firestore після порівняння з попереднім файлом. */
data class StockPlan(
    /** Підсумкові залишки (з урахуванням захищених магазинів) — новий знімок. */
    val items: Map<String, NetItem>,
    val changed: List<String>,
    val removed: List<String>,
    /** Магазини, у яких у файлі 0 товарів, а раніше були: їхні залишки лишаємо як були. */
    val keptStores: List<String>,
    /** Знімка на телефоні ще немає — буде повне завантаження. */
    val first: Boolean,
    /** Товарів менше ніж половина від попереднього файлу. */
    val shrunk: Boolean,
    val previousItems: Int,
) {
    val writes get() = changed.size + removed.size
}

object StockDiff {
    fun plan(old: Map<String, NetItem>?, fresh: NetworkStock): StockPlan {
        if (old == null) {
            return StockPlan(fresh.items, fresh.items.keys.sorted(), emptyList(), emptyList(), first = true, shrunk = false, previousItems = 0)
        }
        val before = storesWithItems(old.values)
        val now = storesWithItems(fresh.items.values)
        val kept = before.keys.filter { (now[it] ?: 0) == 0 }.sorted()

        val items = HashMap(fresh.items)
        if (kept.isNotEmpty()) {
            for ((bc, o) in old) {
                val keep = o.q.filterKeys { it in kept }
                if (keep.isEmpty()) continue
                val cur = items[bc]
                items[bc] = if (cur == null) o.copy(q = keep) else cur.copy(q = cur.q + keep)
            }
        }
        val changed = items.filter { (bc, it) -> old[bc] != it }.keys.sorted()
        val removed = old.keys.filter { it !in items }.sorted()
        return StockPlan(items, changed, removed, kept, first = false, shrunk = items.size < old.size / 2.0, previousItems = old.size)
    }

    private fun storesWithItems(items: Collection<NetItem>): Map<String, Int> {
        val n = HashMap<String, Int>()
        for (it in items) for ((code, v) in it.q) if (v > 0) n[code] = (n[code] ?: 0) + 1
        return n
    }
}

/** Знімок останнього завантаженого файлу на телефоні (`filesDir/last-stock.json.gz`) — щоб не читати Firestore. */
class StockSnapshot(private val file: File) {

    fun read(): Map<String, NetItem>? {
        if (!file.exists()) return null
        return try {
            GZIPInputStream(file.inputStream()).bufferedReader().use { SnapshotReader(it).read() }
        } catch (_: Exception) {
            null // зіпсований знімок — як перше завантаження
        }
    }

    /**
     * Потоковий розбір саме того JSON, що пише [write]: без дерева в пам'яті (org.json на 24 000 товарів
     * займає ~150 МБ). Коди магазинів інтернуються — їх лише кілька десятків.
     */
    private class SnapshotReader(private val r: java.io.Reader) {
        private var peeked = -2
        private val codes = HashMap<String, String>()

        fun read(): Map<String, NetItem> {
            val out = HashMap<String, NetItem>()
            obj { key ->
                if (key == "items") obj { bc -> out[bc] = item() } else skipScalar()
            }
            return out
        }

        private fun item(): NetItem {
            var n = ""; var c = ""; var a = ""
            val q = HashMap<String, Double>()
            obj { key ->
                when (key) {
                    "n" -> n = string()
                    "c" -> c = string()
                    "a" -> a = string()
                    "q" -> obj { k -> q[codes.getOrPut(k) { k }] = number() }
                    else -> skipScalar()
                }
            }
            return NetItem(n, c, a, q)
        }

        private inline fun obj(onKey: (String) -> Unit) {
            expect('{')
            if (peek() == '}'.code) { next(); return }
            while (true) {
                val key = string()
                expect(':')
                onKey(key)
                when (val ch = nextNonWs()) {
                    ','.code -> continue
                    '}'.code -> return
                    else -> error("Очікував , або } а не ${ch.toChar()}")
                }
            }
        }

        private fun string(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                val ch = next()
                when {
                    ch < 0 -> error("Обірваний рядок")
                    ch == '"'.code -> return sb.toString()
                    ch == '\\'.code -> when (val e = next()) {
                        'n'.code -> sb.append('\n'); 't'.code -> sb.append('\t'); 'r'.code -> sb.append('\r')
                        'b'.code -> sb.append('\b'); 'f'.code -> sb.append('\u000C')
                        'u'.code -> sb.append(CharArray(4) { next().toChar() }.concatToString().toInt(16).toChar())
                        else -> sb.append(e.toChar())
                    }
                    else -> sb.append(ch.toChar())
                }
            }
        }

        private fun number(): Double {
            skipWs()
            val sb = StringBuilder()
            while (peek().let { it >= 0 && (it.toChar().isDigit() || it.toChar() in "+-.eE") }) sb.append(next().toChar())
            return sb.toString().toDouble()
        }

        private fun skipScalar() {
            skipWs()
            if (peek() == '"'.code) string() else number()
        }

        private fun expect(c: Char) {
            val ch = nextNonWs()
            if (ch != c.code) error("Очікував $c")
        }

        private fun nextNonWs(): Int { skipWs(); return next() }

        private fun skipWs() { while (peek().let { it == ' '.code || it == '\n'.code || it == '\r'.code || it == '\t'.code }) next() }

        private fun peek(): Int { if (peeked == -2) peeked = r.read(); return peeked }

        private fun next(): Int { val c = peek(); peeked = -2; return c }
    }

    /** Пишемо поруч і перейменовуємо: обірваний запис не зіпсує попередній знімок. */
    fun write(items: Map<String, NetItem>) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        GZIPOutputStream(tmp.outputStream()).bufferedWriter().use { w ->
            w.write("{\"v\":1,\"items\":{")
            var first = true
            for ((bc, it) in items) {
                if (!first) w.write(",")
                first = false
                w.write(quote(bc)); w.write(":{\"n\":"); w.write(quote(it.name))
                w.write(",\"c\":"); w.write(quote(it.code))
                w.write(",\"a\":"); w.write(quote(it.article))
                w.write(",\"q\":{")
                it.q.entries.joinTo(w, ",") { (k, v) -> quote(k) + ":" + num(v) }
                w.write("}}")
            }
            w.write("}}")
        }
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) throw java.io.IOException("Не вдалося зберегти знімок залишків")
        }
    }

    fun delete() { file.delete() }

    companion object {
        const val NAME = "last-stock.json.gz"

        fun num(v: Double): String = if (v == Math.floor(v) && !v.isInfinite() && Math.abs(v) < 1e15) v.toLong().toString() else v.toString()

        fun quote(s: String): String {
            val sb = StringBuilder(s.length + 2).append('"')
            for (ch in s) when {
                ch == '"' -> sb.append("\\\"")
                ch == '\\' -> sb.append("\\\\")
                ch < ' ' -> sb.append(String.format("\\u%04x", ch.code))
                else -> sb.append(ch)
            }
            return sb.append('"').toString()
        }
    }
}
