package ua.chudo.scanner.terminal

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.random.Random

/** Гроші — лише в копійках (Long). Double з'являється тільки на межі з сайтом і одразу переводиться сюди. */
object Money {
    /** Ціна сайту в гривнях → копійки (через десятковий запис, без похибки двійкового Double). */
    fun fromUah(uah: Double): Long =
        BigDecimal(uah.toString()).movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact()

    private val INPUT = Regex("^\\d{1,7}([.,]\\d{0,2})?$")

    /** «12», «12,5», «12.50» → копійки; порожньо, від'ємне, більше двох знаків після коми — `null`. */
    fun parse(text: String): Long? {
        val t = text.trim().replace(" ", "").replace(" ", "")
        if (!INPUT.matches(t)) return null
        return BigDecimal(t.replace(',', '.')).movePointRight(2).longValueExact()
    }

    /** 123450 → «1 234,50 ₴». */
    fun format(kop: Long): String {
        val sign = if (kop < 0) "−" else ""
        val a = Math.abs(kop)
        val uah = (a / 100).toString().reversed().chunked(3).joinToString(" ").reversed()
        return "$sign$uah,${"%02d".format(a % 100)} ₴"
    }
}

/** Рядок кошика. [key] — штрихкод, а без нього — код/артикул: однаковий товар додається кількістю. */
data class CartItem(
    val key: String,
    val barcode: String,
    val name: String,
    val code: String,
    val article: String,
    val qty: Int,
    val priceKop: Long,
    /** Ціни на сайті немає — продавець увів її сам. */
    val manualPrice: Boolean,
) {
    val sumKop: Long get() = priceKop * qty
}

sealed interface Discount {
    data object None : Discount
    /** Відсоток 0..100 від суми. */
    data class Percent(val percent: Int) : Discount
    /** Сума в копійках, не більша за суму кошика. */
    data class Amount(val kop: Long) : Discount
}

data class Cart(val items: List<CartItem> = emptyList(), val discount: Discount = Discount.None) {
    val subtotalKop: Long get() = items.sumOf { it.sumKop }

    val discountKop: Long get() = when (val d = discount) {
        Discount.None -> 0
        // Округлення до копійки «половина — вгору».
        is Discount.Percent -> (subtotalKop * d.percent.coerceIn(0, 100) + 50) / 100
        is Discount.Amount -> d.kop.coerceIn(0, subtotalKop)
    }

    val totalKop: Long get() = (subtotalKop - discountKop).coerceAtLeast(0)

    val count: Int get() = items.sumOf { it.qty }

    /** Решта з готівки; `null` — отримано менше, ніж до сплати. */
    fun changeKop(receivedKop: Long): Long? = (receivedKop - totalKop).takeIf { it >= 0 }

    fun add(item: CartItem): Cart {
        val i = items.indexOfFirst { it.key == item.key }
        if (i >= 0) return copy(items = items.toMutableList().also { it[i] = it[i].copy(qty = it[i].qty + item.qty) })
        require(items.size < MAX_ITEMS) { "У кошику вже $MAX_ITEMS позицій" }
        return copy(items = items + item)
    }

    fun inc(key: String) = map(key) { it.copy(qty = it.qty + 1) }

    /** Не менше 1 — щоб прибрати, є [remove]. */
    fun dec(key: String) = map(key) { it.copy(qty = (it.qty - 1).coerceAtLeast(1)) }

    fun remove(key: String) = copy(items = items.filterNot { it.key == key })

    fun setPrice(key: String, kop: Long) = map(key) { it.copy(priceKop = kop.coerceAtLeast(0), manualPrice = true) }

    fun withDiscount(d: Discount) = copy(discount = d)

    private fun map(key: String, f: (CartItem) -> CartItem) = copy(items = items.map { if (it.key == key) f(it) else it })

    companion object { const val MAX_ITEMS = 200 }
}

/** Короткий код кошика для каси: 6 символів без схожих (0/O, 1/I). */
object SaleCode {
    private const val ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
    val PATTERN = Regex("^[0-9A-Z]{6}$")

    fun generate(random: Random = Random.Default): String = String(CharArray(6) { ALPHABET[random.nextInt(ALPHABET.length)] })

    /** Вміст QR: каса відкриває кошик за id, а код — для перевірки очима. */
    fun qr(saleId: String, code: String) = "chudoskan://sale/$saleId?c=$code"
}

enum class SaleStatus(val id: String) { DRAFT("draft"), SENT("sent"), PAID("paid"), CANCELLED("cancelled") }

/** Продаж `sales/{id}`. */
data class Sale(
    val id: String,
    val shopId: String,
    val by: String,
    val items: List<CartItem>,
    val discountKop: Long,
    val totalKop: Long,
    val status: SaleStatus,
    val code: String,
) {
    /** Поля документа без `at` (його ставить сервер). Білий список — як у firestore.rules. */
    fun toDoc(): Map<String, Any> = mapOf(
        "shopId" to shopId,
        "by" to by,
        "items" to items.map {
            mapOf(
                "barcode" to it.barcode, "name" to it.name.take(300), "code" to it.code, "article" to it.article,
                "qty" to it.qty.toLong(), "price" to it.priceKop, "manualPrice" to it.manualPrice,
            )
        },
        "discount" to discountKop,
        "total" to totalKop,
        "status" to status.id,
        "code" to code,
    )

    companion object {
        fun of(id: String, shopId: String, by: String, cart: Cart, code: String, status: SaleStatus = SaleStatus.DRAFT): Sale {
            require(cart.items.isNotEmpty()) { "Кошик порожній" }
            require(cart.items.size <= Cart.MAX_ITEMS)
            return Sale(id, shopId, by, cart.items, cart.discountKop, cart.totalKop, status, code)
        }
    }
}
