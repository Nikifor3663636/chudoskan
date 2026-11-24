package ua.chudo.scanner.payment

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ua.chudo.scanner.terminal.SalesRepository

/**
 * Оплата для магазину: провайдер із `shops/{shopId}.payment` (одне читання за сесію) і [PaymentFlow].
 * Після запуску застосунку — [recover]: продажі в "paying" перевіряються в банку без повторного списання.
 */
class PaymentService(private val sales: SalesRepository, private val registry: PaymentRegistry) {
    private val lock = Mutex()
    private val configs = HashMap<String, ShopPayment?>()

    private val _notices = MutableStateFlow<List<String>>(emptyList())
    /** Що сказати продавцю після відновлення («Перевірте оплату в застосунку банку»). */
    val notices = _notices.asStateFlow()

    suspend fun provider(shopId: String): PaymentProvider? {
        val cfg = lock.withLock {
            if (shopId !in configs) configs[shopId] = runCatching { sales.shopPayment(shopId) }.getOrNull()
            configs[shopId]
        }
        return registry.forShop(cfg)
    }

    suspend fun flow(shopId: String): PaymentFlow? = provider(shopId)?.let { PaymentFlow(sales, it) }

    suspend fun recover(shopId: String, uid: String) {
        val f = flow(shopId) ?: return
        val out = runCatching { f.recover(shopId, uid) }.getOrNull().orEmpty()
        val msgs = out.values.mapNotNull {
            when (it) {
                is PayOutcome.Paid -> "Оплату, що не завершилася, підтвердив банк — продаж оплачено."
                is PayOutcome.CheckBank -> it.message
                else -> null
            }
        }.distinct()
        if (msgs.isNotEmpty()) _notices.value = _notices.value + msgs
    }

    fun dismissNotices() { _notices.value = emptyList() }
}
