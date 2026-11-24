package ua.chudo.scanner.payment

import ua.chudo.scanner.terminal.SaleStatus

/** Що про продаж потрібно для оплати. */
data class SaleRecord(val id: String, val shopId: String, val by: String, val totalKop: Long, val status: SaleStatus)

/** Сховище продажів (Firestore або фейк у тестах). */
interface SaleStore {
    suspend fun get(id: String): SaleRecord?

    /**
     * Атомарно: статус [from] → [to] (і [payment] для "paid") + запис у журнал продажу (хто, коли, з якого стану в який).
     * Поточний статус не [from] або перехід не дозволено — [IllegalStateException], нічого не змінено.
     */
    suspend fun move(id: String, from: SaleStatus, to: SaleStatus, payment: PaymentRecord? = null)

    /** Продажі цього співробітника, що зависли в "paying" (збій, перезапуск). */
    suspend fun paying(shopId: String, by: String): List<SaleRecord>

    /** Повернення: окремий документ `refunds/{id}` з посиланням на продаж + статус "refunded". */
    suspend fun refunded(id: String, from: SaleStatus, refund: PaymentRecord)
}

/** Поля `payment`, які пише телефон після схваленої оплати. */
data class PaymentRecord(val provider: String, val approved: PaymentResult.Approved)

sealed interface PayOutcome {
    data class Paid(val payment: PaymentResult.Approved) : PayOutcome
    /** Відхилено — кошик знову доступний, можна повторити. */
    data class Declined(val reason: String) : PayOutcome
    data object Cancelled : PayOutcome
    /** Невідомо, чи списано гроші: повторно не платимо, лишаємо "paying". */
    data class CheckBank(val message: String = CHECK_BANK) : PayOutcome

    companion object { const val CHECK_BANK = "Перевірте оплату в застосунку банку" }
}

/**
 * Оплата продажу через [provider]:
 * - перед pay() — status "paying": склад кошика й сума (total) з цієї миті зафіксовані;
 * - saleId — номер замовлення для банку; перед кожним pay() спершу status(): якщо банк уже списав гроші,
 *   другого списання не буде;
 * - Unknown — "paying" лишається, після перезапуску [recover] питає банк через status().
 */
class PaymentFlow(private val store: SaleStore, private val provider: PaymentProvider) {

    suspend fun pay(saleId: String): PayOutcome {
        var sale = store.get(saleId) ?: error("Продаж не знайдено")
        when (sale.status) {
            SaleStatus.DECLINED, SaleStatus.CANCELLED -> { store.move(saleId, sale.status, SaleStatus.DRAFT); sale = sale.copy(status = SaleStatus.DRAFT) }
            SaleStatus.PAYING -> return settle(sale, provider.status(saleId))
            else -> Unit
        }
        if (sale.status == SaleStatus.DRAFT) { store.move(saleId, SaleStatus.DRAFT, SaleStatus.SENT); sale = sale.copy(status = SaleStatus.SENT) }
        check(sale.status == SaleStatus.SENT) { "Продаж уже оплачено або закрито" }

        // Банк міг уже провести цей saleId (попередня спроба, збій зв'язку) — спершу питаємо.
        val before = provider.status(saleId)
        if (before is PaymentResult.Unknown) return PayOutcome.CheckBank()
        store.move(saleId, SaleStatus.SENT, SaleStatus.PAYING)
        if (before is PaymentResult.Approved) return settle(sale.copy(status = SaleStatus.PAYING), before)
        return settle(sale.copy(status = SaleStatus.PAYING), provider.pay(saleId, sale.totalKop))
    }

    /** Повернення оплаченого продажу (з "paid" або "fiscalized"). */
    suspend fun refund(saleId: String, rrn: String, amountKop: Long): PayOutcome {
        val sale = store.get(saleId) ?: error("Продаж не знайдено")
        check(sale.status == SaleStatus.PAID || sale.status == SaleStatus.FISCALIZED) { "Повернути можна лише оплачений продаж" }
        require(amountKop in 1..sale.totalKop) { "Сума повернення — від 0,01 до суми продажу" }
        return when (val r = provider.refund(saleId, rrn, amountKop)) {
            is PaymentResult.Approved -> { store.refunded(saleId, sale.status, PaymentRecord(provider.id, r)); PayOutcome.Paid(r) }
            is PaymentResult.Declined -> PayOutcome.Declined(r.reason)
            PaymentResult.Cancelled -> PayOutcome.Cancelled
            is PaymentResult.Unknown -> PayOutcome.CheckBank()
        }
    }

    /** Після перезапуску: продажі в "paying" — питаємо банк, без повторного списання. */
    suspend fun recover(shopId: String, by: String): Map<String, PayOutcome> =
        store.paying(shopId, by).associate { s -> s.id to settle(s, provider.status(s.id)) }

    private suspend fun settle(sale: SaleRecord, r: PaymentResult): PayOutcome = when (r) {
        is PaymentResult.Approved ->
            if (r.amountKop != sale.totalKop) PayOutcome.CheckBank("Банк провів іншу суму — ${PayOutcome.CHECK_BANK.lowercase()}")
            else { store.move(sale.id, SaleStatus.PAYING, SaleStatus.PAID, PaymentRecord(provider.id, r)); PayOutcome.Paid(r) }
        is PaymentResult.Declined -> { store.move(sale.id, SaleStatus.PAYING, SaleStatus.DECLINED); PayOutcome.Declined(r.reason) }
        PaymentResult.Cancelled -> { store.move(sale.id, SaleStatus.PAYING, SaleStatus.CANCELLED); PayOutcome.Cancelled }
        is PaymentResult.Unknown -> PayOutcome.CheckBank()
    }
}
