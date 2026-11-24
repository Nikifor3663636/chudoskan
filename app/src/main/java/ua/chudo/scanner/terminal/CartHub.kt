package ua.chudo.scanner.terminal

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Один кошик «Термінал» на весь застосунок: його бачать і вкладка «Термінал», і картка товару
 * («Додати в кошик»). Зберігається у файлі ([CartStore]). Поки йде оплата або невідомо, чи списано
 * гроші ([locked]), склад кошика не змінюється — інакше каса отримала б не той продаж.
 */
class CartHub(private val store: CartStore?) {
    private val _cart = MutableStateFlow(Cart())
    val cart: StateFlow<Cart> = _cart.asStateFlow()

    /** Продаж для поточного складу кошика; будь-яка зміна складу — новий продаж. */
    var saleId: String? = null
        private set

    /** Ставить «Термінал»: оплата триває або чекаємо відповіді банку. */
    val locked = MutableStateFlow(false)

    /** Кошик передано на касу чи оплачено й чекає «Новий кошик» — додавати ззовні не можна, щоб товар не зник. */
    val closed = MutableStateFlow(false)

    init {
        store?.load()?.let { (c, id) -> _cart.value = c; saleId = id }
    }

    /** Змінити склад; `false` — кошик заблоковано оплатою, нічого не змінено. */
    fun edit(f: (Cart) -> Cart): Boolean {
        if (locked.value) return false
        _cart.value = f(_cart.value)
        saleId = null
        persist()
        return true
    }

    sealed interface AddResult {
        /** Додано; [qty] — скільки тепер цього товару в кошику. */
        data class Added(val qty: Int, val count: Int) : AddResult
        data object Locked : AddResult
        data class Failed(val message: String) : AddResult
    }

    fun add(item: CartItem): AddResult {
        if (locked.value || closed.value) return AddResult.Locked
        return runCatching { edit { it.add(item) } }.fold(
            { val c = _cart.value; AddResult.Added(c.items.first { it.key == item.key }.qty, c.count) },
            { AddResult.Failed(it.message ?: "Не вдалося додати товар") },
        )
    }

    /** Продаж створено для поточного складу — зберігаємо, щоб повтор оплати не створив другий. */
    fun bindSale(id: String) { saleId = id; persist() }

    /** Оплачено: файл більше не потрібен, але кошик лишається на екрані до «Новий кошик». */
    fun forgetSaved() { store?.clear() }

    fun clear() {
        _cart.value = Cart()
        saleId = null
        locked.value = false
        closed.value = false
        store?.clear()
    }

    private fun persist() { runCatching { store?.save(_cart.value, saleId) } }
}

/** Рядок кошика з картки товару: штрихкод і код — з 1С, ціна — з сайту (немає — «ціна вручну»). */
fun cartItemOf(name: String, article: String?, priceKop: Long?, barcode: String?, code: String?, urlKey: String): CartItem =
    CartItem(
        key = barcode?.ifBlank { null } ?: code?.ifBlank { null } ?: "site:$urlKey",
        barcode = barcode.orEmpty(),
        name = name,
        code = code.orEmpty(),
        article = article.orEmpty(),
        qty = 1,
        priceKop = priceKop ?: 0,
        manualPrice = priceKop == null,
    )
