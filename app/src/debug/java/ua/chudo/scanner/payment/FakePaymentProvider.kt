package ua.chudo.scanner.payment

import kotlinx.coroutines.delay
import java.util.Date

/**
 * Лише debug-збірка: імітує застосунок банку. [mode] вибирають у «Профіль → Оплата (тест)» або в тестах.
 * Пам'ятає результат кожного saleId — як справжній банк, щоб [status] після збою відповідав правдиво.
 */
class FakePaymentProvider(var mode: Mode = Mode.APPROVE, private val wait: Long = 0) : PaymentProvider {
    enum class Mode(val label: String) {
        APPROVE("Схвалено"), DECLINE("Відхилено"), TIMEOUT("Таймаут"), UNKNOWN("Невідомо (списано)"),
    }

    override val id = "fake"

    private val done = HashMap<String, PaymentResult>()
    val payCalls = mutableListOf<String>()

    override suspend fun pay(saleId: String, amountKop: Long): PaymentResult {
        synchronized(payCalls) { payCalls += saleId }
        if (wait > 0) delay(wait)
        val approved = PaymentResult.Approved("RRN${saleId.takeLast(9).padStart(9, '0')}", "A1B2C3", "4149 49** **** 1234", amountKop, Date())
        return when (mode) {
            Mode.APPROVE -> approved.also { done[saleId] = it }
            Mode.DECLINE -> PaymentResult.Declined("Недостатньо коштів").also { done[saleId] = it }
            // Таймаут: банк нічого не відповів і нічого не списав.
            Mode.TIMEOUT -> PaymentResult.Unknown("Банк не відповів").also { done[saleId] = PaymentResult.Cancelled }
            // Відповідь втрачено, але гроші списано — status() покаже Approved.
            Mode.UNKNOWN -> PaymentResult.Unknown("Відповідь банку втрачено").also { done[saleId] = approved }
        }
    }

    override suspend fun status(saleId: String): PaymentResult = done[saleId] ?: PaymentResult.Cancelled

    override suspend fun refund(saleId: String, rrn: String, amountKop: Long): PaymentResult {
        val paid = done[saleId] as? PaymentResult.Approved ?: return PaymentResult.Declined("Оплату не знайдено")
        if (paid.rrn != rrn || amountKop > paid.amountKop) return PaymentResult.Declined("Сума або RRN не збігаються")
        return PaymentResult.Approved("R$rrn", "R1", paid.maskedPan, amountKop, Date())
    }
}
