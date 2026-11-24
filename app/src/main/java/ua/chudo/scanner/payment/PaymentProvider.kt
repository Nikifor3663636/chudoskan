package ua.chudo.scanner.payment

import java.util.Date

/**
 * Оплата карткою (Tap to Phone через застосунок банку). Новий банк = новий клас + запис у [PaymentRegistry]
 * + `shops/{shopId}.payment.provider`. Жодних ключів банку в застосунку: провайдер лише викликає застосунок банку.
 */
interface PaymentProvider {
    /** Ідентифікатор у `shops/{shopId}.payment.provider`. */
    val id: String

    /** [saleId] — номер замовлення для банку. Для того самого saleId повторно не викликається — спершу [status]. */
    suspend fun pay(saleId: String, amountKop: Long): PaymentResult

    /** Чим закінчилася оплата [saleId] — для відновлення після збою чи перезапуску. */
    suspend fun status(saleId: String): PaymentResult

    suspend fun refund(saleId: String, rrn: String, amountKop: Long): PaymentResult
}

sealed interface PaymentResult {
    data class Approved(val rrn: String, val authCode: String, val maskedPan: String, val amountKop: Long, val at: Date) : PaymentResult
    data class Declined(val reason: String) : PaymentResult
    data object Cancelled : PaymentResult
    /** Банк не відповів або відповідь втрачено — гроші могли списатися. Повторно не платимо. */
    data class Unknown(val reason: String) : PaymentResult
}

/**
 * Фіскальний чек ПРРО. Фіскалізує НЕ телефон, а довірена сторона (програма на ПК магазину або сервер):
 * ключі ПРРО лише там. Телефон лише чекає, поки в `sales/{id}.fiscal` з'явиться чек.
 */
interface FiscalProvider {
    suspend fun receipt(saleId: String): Receipt
}

data class Receipt(val fiscalNumber: String, val url: String?, val at: Date)

/** `shops/{shopId}.payment` — лише ідентифікатори, ніяких секретів. */
data class ShopPayment(val provider: String = NONE, val terminalId: String? = null) {
    companion object { const val NONE = "none" }
}

/** Провайдери оплати за id. "none" — оплати карткою немає; "fake" — лише в debug-збірці ([BuildProviders]). */
class PaymentRegistry(private val providers: Map<String, PaymentProvider>) {
    fun forShop(config: ShopPayment?): PaymentProvider? =
        config?.provider?.takeIf { it != ShopPayment.NONE }?.let { providers[it] }

    val ids: Set<String> get() = providers.keys

    companion object {
        /** Провайдери цієї збірки: у release — лише справжні (поки жодного). */
        fun forBuild() = PaymentRegistry(BuildProviders.all())
    }
}
