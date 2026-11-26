package ua.chudo.scanner.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TerminalPaymentTest {
    private val line = CartLine("001", "Гра", priceKop = 19950, qty = 2)

    @Test fun totalUsesExactMinorUnits() {
        val other = CartLine("002", "Книга", priceKop = 101, qty = 1)
        assertEquals(TerminalPaymentDraft(40001), TerminalPaymentDraft.fromCart(listOf(line, other)))
    }

    @Test fun incompleteOrOverflowingCartCannotPreparePayment() {
        assertNull(TerminalPaymentDraft.fromCart(listOf(line, line.copy(priceKop = null))))
        assertNull(TerminalPaymentDraft.fromCart(listOf(line.copy(loading = true))))
        assertNull(TerminalPaymentDraft.fromCart(listOf(line.copy(priceKop = Long.MAX_VALUE))))
        assertNull(TerminalPaymentDraft.fromCart(listOf(line.copy(priceKop = 0))))
    }
}
