package ua.chudo.scanner.data

import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Count
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.InputStream
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

enum class HighlightKind(val key: String, val title: String, val badge: String) {
    NEW("new", "Новинки", "Новинка"),
    PROMO("promo", "Акції", "Акція"),
}

@Serializable
data class ProductHighlight(
    val barcode: String = "",
    val article: String = "",
    val name: String,
    @SerialName("starts_on") val startsOn: String? = null,
    @SerialName("ends_on") val endsOn: String? = null,
    @SerialName("promo_price") val promoPrice: Double? = null,
    val kind: String = "",
)

object ProductHighlights {
    private val db get() = Supabase.client

    suspend fun forProduct(barcode: String?, article: String?): List<ProductHighlight> =
        db.postgrest.rpc("product_highlights_for", buildJsonObject {
            put("p_barcode", barcode.orEmpty())
            put("p_article", article.orEmpty())
        }).decodeList()

    suspend fun count(kind: HighlightKind): Long? =
        db.from("product_highlights").select(Columns.list("kind")) {
            filter { eq("kind", kind.key) }
            count(Count.EXACT)
            limit(1)
        }.countOrNull()

    const val PAGE_SIZE = 50

    suspend fun page(kind: HighlightKind, query: String, offset: Int): List<ProductHighlight> =
        db.postgrest.rpc("list_product_highlights", buildJsonObject {
            put("p_kind", kind.key)
            put("p_query", query)
            put("p_offset", offset)
        }).decodeList()

    suspend fun publish(kind: HighlightKind, entries: List<ProductHighlight>): Int =
        db.postgrest.rpc("publish_product_highlights", buildJsonObject {
            put("p_kind", kind.key)
            put("p_rows", JsonArray(entries.map { Supabase.json.encodeToJsonElement(ProductHighlight.serializer(), it) }))
        }).decodeAs()
}

object HighlightReport {
    private val columns = listOf(
        listOf("штрихкод", "штрих-код", "barcode", "ean"),
        listOf("артикул", "article", "sku"),
        listOf("назва", "товар", "найменування", "наименование", "номенклатура", "name"),
        listOf("початок", "дата початку", "дата начала", "starts_on"),
        listOf("кінець", "дата завершення", "дата окончания", "ends_on"),
        listOf("акційна ціна", "акционная цена", "promo_price"),
    )
    private val barcodePattern = Regex("[0-9]{8,14}")
    private val pricePattern = Regex("[0-9]{1,10}([.,][0-9]{1,2})?")
    private val dotDate = DateTimeFormatter.ofPattern("dd.MM.uuuu").withResolverStyle(ResolverStyle.STRICT)

    fun parse(kind: HighlightKind, open: () -> InputStream): List<ProductHighlight> {
        var header: List<Int>? = null
        val entries = mutableListOf<ProductHighlight>()
        val keys = mutableSetOf<Pair<String, String>>()
        Xlsx.read(open) { index, cells ->
            val values = cells.map(String::trim)
            val positions = header
            if (positions == null) {
                require(index <= 40) { "Не знайдено заголовки «Назва» та «Штрихкод» або «Артикул»." }
                val normalized = values.map(String::lowercase)
                val found = columns.map { aliases -> normalized.indexOfFirst { it in aliases } }
                if (found[2] >= 0 && (found[0] >= 0 || found[1] >= 0)) header = found
            } else if (values.any(String::isNotEmpty)) {
                fun cell(column: Int) = values.getOrNull(positions[column]).orEmpty()
                val barcode = cell(0)
                val article = cell(1)
                val name = cell(2)
                require((barcode.isNotEmpty() || article.isNotEmpty()) && (barcode.isEmpty() || barcode.matches(barcodePattern))
                    && article.length <= 100 && name.length in 1..300) { "Рядок $index: перевірте назву, штрихкод та артикул." }
                val start = parseDate(cell(3), index)
                val end = parseDate(cell(4), index)
                require(start == null || end == null || start <= end) { "Рядок $index: кінець раніше початку." }
                val price = cell(5).takeIf { kind == HighlightKind.PROMO && it.isNotEmpty() }?.let {
                    require(it.matches(pricePattern)) { "Рядок $index: некоректна акційна ціна." }
                    it.replace(',', '.').toDouble()
                }
                require(keys.add(barcode to article)) { "Рядок $index: товар дублюється." }
                require(entries.size < 10000) { "Звіт має містити до 10 000 товарів." }
                entries += ProductHighlight(barcode, article, name, start?.toString(), end?.toString(), price)
            }
        }
        require(entries.isNotEmpty()) { "У звіті немає товарів. Потрібні «Назва» та «Штрихкод» або «Артикул»." }
        return entries
    }

    private fun parseDate(value: String, row: Int): LocalDate? {
        if (value.isEmpty()) return null
        return runCatching {
            val date = when {
                value.matches(Regex("[0-9]{1,5}")) && value.toLong() >= 61 ->
                    LocalDate.of(1899, 12, 30).plusDays(value.toLong())
                '.' in value -> LocalDate.parse(value, dotDate)
                value.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")) -> LocalDate.parse(value)
                else -> error("date")
            }
            require(date.year >= 1)
            date
        }.getOrElse { throw IllegalArgumentException("Рядок $row: некоректна дата.") }
    }
}
