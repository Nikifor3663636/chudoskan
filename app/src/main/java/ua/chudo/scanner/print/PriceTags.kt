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

/**
 * Шаблоны как в 1С. Размер ячейки — в мм; листовые раскладываются по A4.
 * Книжна чи альбомна — налаштування для кожного A4-шаблону ([PrinterStore.landscape]), за замовчуванням альбомна.
 */
enum class Template(val title: String, val kind: LabelKind, val cellWmm: Float, val cellHmm: Float) {
    LABEL_40x24("Етикетка 40×24", LabelKind.STICKY, 40f, 24f),
    TAG_68x38("Цінник 68×38", LabelKind.PLAIN, 68f, 38f),

    /** 148×105 мм (A6). */
    BIG("Крупногабарит 148×105", LabelKind.PLAIN, BIG_CELL_W_MM, BIG_CELL_H_MM),

    /** 140×100 мм — як у 1С: альбомно 2×2 на аркуш. */
    BIG_140x100("Крупногабарит 140×100", LabelKind.PLAIN, 140f, 100f),
    ;

    /**
     * За замовчуванням альбомна. 148×105 лишається книжним, як було: альбомно вміщається лише 1 на аркуш (книжно — 2).
     */
    val defaultLandscape: Boolean get() = this != BIG

    /** Лише для A4-шаблонів; етикетка 40×24 — `null`. */
    fun layout(landscape: Boolean = defaultLandscape): SheetLayout? =
        if (kind == LabelKind.PLAIN) SheetLayout.a4(cellWmm, cellHmm, landscape) else null
}

const val BIG_CELL_W_MM = 148f
const val BIG_CELL_H_MM = 105f

/** Позиция ячейки: лист, колонка, ряд. */
data class Cell(val page: Int, val col: Int, val row: Int)

/**
 * Сетка ячеек без промежутков, по центру листа. Скільки вміщається — рахуємо від друкованої області
 * ([printableMarginMm] з кожного боку), щоб нічого не обрізалось по краях.
 */
data class SheetLayout(
    val pageWmm: Float, val pageHmm: Float, val cellWmm: Float, val cellHmm: Float,
    val printableMarginMm: Float = 0f,
    /** Аркуш альбомний: малюємо 297×210 і повертаємо на 90° перед друком. */
    val landscape: Boolean = false,
) {
    val cols: Int = ((pageWmm - 2 * printableMarginMm) / cellWmm + 1e-4f).toInt()
    val rows: Int = ((pageHmm - 2 * printableMarginMm) / cellHmm + 1e-4f).toInt()
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

        /** Поле Brother, куди принтер не друкує, — з кожного боку. */
        const val BROTHER_MARGIN_MM = 4.2f

        /**
         * Альбомно — від друкованої області Brother (68×38: 4×5 = 20, 140×100: 2×2 = 4).
         * Книжно — як раніше, від усього аркуша (68×38: 3×7 = 21, 148×105: 1×2 = 2): так задано для
         * цінників 68×38, хоча крайні колонки тоді заходять у поле принтера на 1,2 мм.
         */
        fun a4(cellW: Float, cellH: Float, landscape: Boolean) =
            if (landscape) SheetLayout(A4_H_MM, A4_W_MM, cellW, cellH, BROTHER_MARGIN_MM, landscape = true)
            else SheetLayout(A4_W_MM, A4_H_MM, cellW, cellH)
    }
}

/** Ціна обов'язкова: без неї цінник не друкуємо. */
class MissingPriceException(val names: List<String>) : PrintException(
    "Немає ціни — перевірте товар на сайті" + names.take(3).joinToString(prefix = ": ", separator = "; ").takeIf { names.isNotEmpty() }.orEmpty(),
)

/** Перед друком: у кожного товару є ціна. */
fun requirePrices(items: List<LabelItem>) {
    val missing = items.filter { it.price == null }
    if (missing.isNotEmpty()) throw MissingPriceException(missing.map { it.name.ifBlank { it.barcode } }.distinct())
}

/**
 * Дані цінника з двох джерел. 1С має пріоритет для штрихкоду, коду й артикула (як у 1С);
 * назва — з сайту, а якщо товару на сайті немає — з 1С; ціна й стара ціна — лише з сайту.
 */
object TagData {
    data class Site(val name: String, val article: String?, val price: Double?, val oldPrice: Double?)
    data class OneC(val barcode: String, val name: String, val code: String, val article: String)

    fun merge(site: Site?, oneC: OneC?, scannedBarcode: String? = null): PriceTag = PriceTag(
        name = site?.name?.trim()?.ifEmpty { null } ?: oneC?.name?.trim().orEmpty(),
        price = site?.price?.let(::formatPrice).orEmpty(),
        oldPrice = site?.takeIf { s -> s.price != null && s.oldPrice != null && s.oldPrice > s.price + 0.001 }?.oldPrice?.let(::formatPrice).orEmpty(),
        article = oneC?.article?.trim()?.ifEmpty { null } ?: site?.article?.trim().orEmpty(),
        barcode = oneC?.barcode?.ifEmpty { null } ?: scannedBarcode.orEmpty(),
        code = oneC?.code?.trim().orEmpty(),
    )
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
