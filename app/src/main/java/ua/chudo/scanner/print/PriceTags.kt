package ua.chudo.scanner.print

import kotlin.math.roundToLong

/** Товар для ценника. Пустые поля на ценнике не печатаются. */
data class LabelItem(
    val barcode: String = "",
    val article: String = "",
    val code: String = "",
    val brand: String = "",
    val name: String = "",
    val price: Double? = null,
    val oldPrice: Double? = null,
) {
    /** «Акційний» возможен, только если старая цена больше новой. */
    val canPromo: Boolean get() = price != null && oldPrice != null && oldPrice > price + 0.001

    /** «ТМ {brand}, {name}» — без пустых частей. */
    fun title(withArticle: Boolean = false): String = listOfNotNull(
        brand.trim().takeIf { it.isNotEmpty() }?.let { "ТМ $it" },
        name.trim().takeIf { it.isNotEmpty() },
        article.trim().takeIf { withArticle && it.isNotEmpty() }?.let { "арт. $it" },
    ).joinToString(", ")
}

/** Шаблоны как в 1С. Размер ячейки — в мм; листовые раскладываются по A4. */
enum class Template(val title: String, val kind: LabelKind, val cellWmm: Float, val cellHmm: Float, val landscape: Boolean) {
    LABEL_40x24("Етикетка 40×24", LabelKind.STICKY, 40f, 24f, false),
    TAG_68x38("Цінник 68×38", LabelKind.PLAIN, 68f, 38f, false),

    /** 148×105 мм (A6): 2 на лист A4, лист стоя, один под другим. */
    BIG("Крупногабарит", LabelKind.PLAIN, BIG_CELL_W_MM, BIG_CELL_H_MM, false),
    ;

    val layout: SheetLayout? get() = if (kind == LabelKind.PLAIN) SheetLayout.a4(cellWmm, cellHmm, landscape) else null
}

const val BIG_CELL_W_MM = 148f
const val BIG_CELL_H_MM = 105f

/** Позиция ячейки: лист, колонка, ряд. */
data class Cell(val page: Int, val col: Int, val row: Int)

/** Сетка ячеек без промежутков, по центру листа. */
data class SheetLayout(val pageWmm: Float, val pageHmm: Float, val cellWmm: Float, val cellHmm: Float) {
    val cols: Int = (pageWmm / cellWmm + 1e-4f).toInt()
    val rows: Int = (pageHmm / cellHmm + 1e-4f).toInt()
    val perPage: Int get() = cols * rows
    val marginXmm: Float get() = (pageWmm - cols * cellWmm) / 2
    val marginYmm: Float get() = (pageHmm - rows * cellHmm) / 2

    fun pages(count: Int): Int = if (count <= 0) 0 else (count + perPage - 1) / perPage

    fun cell(index: Int): Cell {
        val onPage = index % perPage
        return Cell(index / perPage, onPage % cols, onPage / cols)
    }

    companion object {
        const val A4_W_MM = 210f
        const val A4_H_MM = 297f

        fun a4(cellW: Float, cellH: Float, landscape: Boolean = false) =
            if (landscape) SheetLayout(A4_H_MM, A4_W_MM, cellW, cellH) else SheetLayout(A4_W_MM, A4_H_MM, cellW, cellH)
    }
}

/** Каждый товар подряд столько раз, сколько заказано. */
fun <T> expand(items: List<Pair<T, Int>>): List<T> = items.flatMap { (item, qty) -> List(qty.coerceIn(0, 99)) { item } }

object PriceFormat {
    private fun kopecks(v: Double) = (v * 100).roundToLong()

    /** 2299 → «2 299»: пробел между тысячами. */
    fun grouped(whole: Long): String = whole.toString().reversed().chunked(3).joinToString(" ").reversed()

    /** Этикетка: «2 299,00». */
    fun label(v: Double): String {
        val k = kopecks(v)
        return "${grouped(k / 100)},${(k % 100).toString().padStart(2, '0')}"
    }

    /** Ценник: целая часть «15 885» и копейки «00» (индексом). */
    fun tag(v: Double): Pair<String, String> {
        val k = kopecks(v)
        return grouped(k / 100) to (k % 100).toString().padStart(2, '0')
    }

    /** «1 299,90» / «1299.9» → 1299.9; пусто или мусор → null. */
    fun parse(s: String): Double? = s.replace(" ", "").replace(' '.toString(), "").replace(',', '.').toDoubleOrNull()
}

fun PriceTag.toItem() = LabelItem(
    barcode = barcode.filter(Char::isDigit),
    article = article.trim(),
    code = code.trim(),
    brand = brand.trim(),
    name = name.trim(),
    price = PriceFormat.parse(price),
    oldPrice = PriceFormat.parse(oldPrice),
)
