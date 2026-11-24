package ua.chudo.scanner.payment

import ua.chudo.scanner.terminal.SaleStatus
import ua.chudo.scanner.terminal.SaleStatus.CANCELLED
import ua.chudo.scanner.terminal.SaleStatus.DECLINED
import ua.chudo.scanner.terminal.SaleStatus.DRAFT
import ua.chudo.scanner.terminal.SaleStatus.EXPORTED_1C
import ua.chudo.scanner.terminal.SaleStatus.FISCALIZED
import ua.chudo.scanner.terminal.SaleStatus.PAID
import ua.chudo.scanner.terminal.SaleStatus.PAYING
import ua.chudo.scanner.terminal.SaleStatus.REFUNDED
import ua.chudo.scanner.terminal.SaleStatus.SENT

/**
 * Стан продажу — лише вперед: draft → sent → paying → paid → fiscalized → exported1C;
 * з paying — declined / cancelled, звідти назад у draft; з paid / fiscalized — refunded.
 * Те саме перевіряє firestore.rules (clientStep / trustedStep).
 */
object SaleStates {
    /** Телефон (автор продажу або адмін магазину). */
    private val client: Map<SaleStatus, Set<SaleStatus>> = mapOf(
        DRAFT to setOf(SENT, CANCELLED),
        SENT to setOf(PAYING, CANCELLED),
        PAYING to setOf(PAID, DECLINED, CANCELLED),
        DECLINED to setOf(DRAFT),
        CANCELLED to setOf(DRAFT),
        PAID to setOf(REFUNDED),
        FISCALIZED to setOf(REFUNDED),
    )

    /** Довірена сторона (ПК магазину / сервер): фіскалізація й передача в 1С. */
    private val trusted: Map<SaleStatus, Set<SaleStatus>> = mapOf(
        PAID to setOf(FISCALIZED),
        FISCALIZED to setOf(EXPORTED_1C),
    )

    fun clientCan(from: SaleStatus, to: SaleStatus) = to in client[from].orEmpty()

    fun trustedCan(from: SaleStatus, to: SaleStatus) = to in trusted[from].orEmpty()

    /** Кошик редагується лише в чернетці; з "paying" і далі склад і сума зафіксовані. */
    fun editable(s: SaleStatus) = s == DRAFT

    /** Оплату ще можна почати. */
    fun payable(s: SaleStatus) = s == DRAFT || s == SENT
}
