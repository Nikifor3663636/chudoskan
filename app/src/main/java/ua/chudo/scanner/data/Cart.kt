package ua.chudo.scanner.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.roundToLong

/**
 * Рядок кошика. Гроші — у копійках (Long), щоб сума збігалася до копійки; так само їх потім віддамо в чек (ПРРО).
 * Назва й ціна — із сайту (як на цінниках), код 1С, артикул і залишок — із залишків мережі.
 */
data class CartLine(
    val barcode: String,
    val name: String,
    val article: String = "",
    val code1C: String = "",
    val image: String? = null,
    val urlKey: String? = null,
    /** Ціна з сайту з урахуванням акції; `null` — сайт не знає ціни. */
    val priceKop: Long? = null,
    /** Звичайна ціна (без акції), якщо вона більша за [priceKop]. */
    val regularKop: Long? = null,
    val qty: Int = 1,
    /** Скільки в 1С у своєму магазині; `null` — невідомо. */
    val inShop: Double? = null,
    /** Ще шукаємо товар на сайті й у залишках. */
    val loading: Boolean = false,
    /** Не вдалося знайти (немає інтернету тощо) — можна повторити. */
    val failed: Boolean = false,
)

object Cart {
    const val MAX_QTY = 999

    fun kop(uah: Double?): Long? = uah?.takeIf { it.isFinite() && it > 0 }?.let { (it * 100).roundToLong() }

    fun lineSum(l: CartLine): Long? = l.priceKop?.let { it * l.qty }

    /** Сума лише рядків із ціною; рядки без ціни показуємо окремо ([unpriced]). */
    fun total(lines: List<CartLine>): Long = lines.sumOf { lineSum(it) ?: 0L }

    fun units(lines: List<CartLine>): Int = lines.sumOf { it.qty }

    fun unpriced(lines: List<CartLine>): Int = lines.count { !it.loading && it.priceKop == null }

    /** Економія покупця на акціях. */
    fun savings(lines: List<CartLine>): Long = lines.sumOf { l ->
        val r = l.regularKop; val p = l.priceKop
        if (r != null && p != null && r > p) (r - p) * l.qty else 0L
    }

    /** Ще раз той самий штрихкод — +1 до кількості; новий — нагору списку й «шукаємо». */
    fun add(lines: List<CartLine>, barcode: String): Pair<List<CartLine>, Boolean> {
        val i = lines.indexOfFirst { it.barcode == barcode }
        if (i >= 0) return lines.toMutableList().also { it[i] = it[i].copy(qty = (it[i].qty + 1).coerceAtMost(MAX_QTY)) } to false
        return (listOf(CartLine(barcode, name = barcode, loading = true)) + lines) to true
    }

    /** 0 і менше — прибрати рядок. */
    fun setQty(lines: List<CartLine>, barcode: String, qty: Int): List<CartLine> =
        if (qty <= 0) lines.filter { it.barcode != barcode }
        else lines.map { if (it.barcode == barcode) it.copy(qty = qty.coerceAtMost(MAX_QTY)) else it }

    /** Кількість більша, ніж за 1С є в магазині. */
    fun overStock(l: CartLine): Boolean = l.inShop != null && l.qty > l.inShop

    private val MONEY = DecimalFormat("#,##0.00", DecimalFormatSymbols(Locale.forLanguageTag("uk")).apply {
        groupingSeparator = ' '; decimalSeparator = ','
    })

    /** 123450 → «1 234,50 ₴». */
    fun money(kop: Long): String = MONEY.format(kop / 100.0) + " ₴"
}

/**
 * Кошик продавця: один на телефон, переживає закриття застосунку (SharedPreferences).
 * Товар шукаємо у фоні — продавець тим часом сканує далі.
 */
class CartRepository(
    context: Context,
    private val repo: ChudoRepository,
    private val net: NetworkStockLookup,
) {
    private val sp = context.getSharedPreferences("cart", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _lines = MutableStateFlow(read())
    val lines: StateFlow<List<CartLine>> = _lines.asStateFlow()

    /** Останній відсканований (для підказки в сканері). */
    private val _last = MutableStateFlow<String?>(null)
    val last: StateFlow<String?> = _last.asStateFlow()

    private var shopId: String = ""

    /** Рядки, які шукали, коли застосунок закрили, — шукаємо знову. */
    fun attach(shopId: String) {
        this.shopId = shopId
        _lines.value.filter { it.loading }.forEach { resolve(it.barcode) }
    }

    fun add(raw: String) {
        val bc = raw.trim().ifEmpty { return }
        var isNew = false
        _lines.update { cur -> Cart.add(cur, bc).also { isNew = it.second }.first }
        save()
        if (isNew) resolve(bc) else describeLast(bc)
    }

    fun setQty(barcode: String, qty: Int) { _lines.update { Cart.setQty(it, barcode, qty) }; save() }

    fun remove(barcode: String) = setQty(barcode, 0)

    fun clear() { _lines.value = emptyList(); _last.value = null; save() }

    fun retry(barcode: String) {
        _lines.update { cur -> cur.map { if (it.barcode == barcode) it.copy(loading = true, failed = false) else it } }
        resolve(barcode)
    }

    private fun describeLast(bc: String) {
        _lines.value.firstOrNull { it.barcode == bc }?.let { l ->
            _last.value = (if (l.loading) bc else l.name) + if (l.qty > 1) " · ${l.qty} шт" else ""
        }
    }

    private fun resolve(bc: String) {
        _last.value = "Шукаю $bc…"
        scope.launch {
            // Залишки мережі — одне читання; сайт — ціна, назва, фото (як для цінника).
            val av = runCatching { net.byBarcode(shopId, bc) }.getOrNull()
            var siteFailed = false
            val site = try {
                (repo.lookup(bc) as? LookupResult.Single)?.let { repo.details(it.urlKey) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                siteFailed = true; null
            }
            _lines.update { cur ->
                cur.map { l ->
                    if (l.barcode != bc) l else {
                        val price = Cart.kop(site?.price?.finalPrice)
                        val regular = Cart.kop(site?.price?.regularPrice)?.takeIf { price != null && it > price }
                        l.copy(
                            name = site?.name ?: av?.item?.name?.ifBlank { null } ?: "Невідомий товар $bc",
                            article = site?.article ?: av?.item?.article.orEmpty(),
                            code1C = av?.item?.code.orEmpty(),
                            image = site?.images?.firstOrNull(),
                            urlKey = site?.urlKey,
                            priceKop = price, regularKop = regular,
                            inShop = av?.own,
                            loading = false,
                            // Ні сайт, ні залишки не відповіли — швидше за все, немає інтернету.
                            failed = site == null && av == null && siteFailed,
                        )
                    }
                }
            }
            save()
            describeLast(bc)
        }
    }

    private fun save() {
        val arr = JSONArray()
        for (l in _lines.value) arr.put(JSONObject().apply {
            put("bc", l.barcode); put("n", l.name); put("a", l.article); put("c", l.code1C)
            put("img", l.image ?: ""); put("u", l.urlKey ?: "")
            l.priceKop?.let { put("p", it) }; l.regularKop?.let { put("r", it) }
            put("q", l.qty); l.inShop?.let { put("s", it) }
            put("ld", l.loading); put("f", l.failed)
        })
        sp.edit { putString(KEY, arr.toString()) }
    }

    private fun read(): List<CartLine> = runCatching {
        val arr = JSONArray(sp.getString(KEY, "[]"))
        List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            CartLine(
                barcode = o.getString("bc"), name = o.optString("n"), article = o.optString("a"), code1C = o.optString("c"),
                image = o.optString("img").ifEmpty { null }, urlKey = o.optString("u").ifEmpty { null },
                priceKop = if (o.has("p")) o.getLong("p") else null, regularKop = if (o.has("r")) o.getLong("r") else null,
                qty = o.optInt("q", 1).coerceIn(1, Cart.MAX_QTY), inShop = if (o.has("s")) o.getDouble("s") else null,
                loading = o.optBoolean("ld"), failed = o.optBoolean("f"),
            )
        }
    }.getOrDefault(emptyList())

    private companion object { const val KEY = "lines" }
}
