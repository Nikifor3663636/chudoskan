package ua.chudo.scanner.data

data class TerminalPaymentDraft(val amountKop: Long, val currency: String = "UAH") {
    companion object {
        fun fromCart(lines: List<CartLine>): TerminalPaymentDraft? {
            if (lines.isEmpty() || lines.any { it.loading || it.failed || it.priceKop == null || it.priceKop < 0 || it.qty <= 0 }) return null
            val total = try {
                lines.fold(0L) { sum, line ->
                    Math.addExact(sum, Math.multiplyExact(line.priceKop!!, line.qty.toLong()))
                }
            } catch (_: ArithmeticException) {
                return null
            }
            return total.takeIf { it > 0 }?.let { TerminalPaymentDraft(it) }
        }
    }
}
