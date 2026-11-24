package ua.chudo.scanner.payment

import ua.chudo.scanner.terminal.Sale
import java.util.Date

/**
 * Оплата карткою (Tap to Phone через застосунок банку). Поки що реалізацій немає — лише фейк у тестах.
 * Жодних ключів банку в застосунку: провайдер лише викликає застосунок банку й повертає результат.
 */
interface PaymentProvider {
    /** [saleId] — номер замовлення для банку. */
    suspend fun pay(amountKop: Long, saleId: String): PaymentResult
}

sealed interface PaymentResult {
    data class Approved(val rrn: String, val authCode: String, val amountKop: Long, val at: Date) : PaymentResult
    data class Declined(val reason: String) : PaymentResult
    data object Cancelled : PaymentResult
}

/** Фіскальний чек ПРРО. Фіскалізує довірена сторона (ПК магазину або сервер), бо ключі ПРРО — лише там. */
interface FiscalProvider {
    suspend fun receipt(sale: Sale, payment: PaymentResult.Approved): Receipt
}

data class Receipt(val fiscalNumber: String, val url: String?, val at: Date)
