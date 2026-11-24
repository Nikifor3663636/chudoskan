package ua.chudo.scanner.payment

/** Debug-збірка: є фейковий провайдер для перевірки кошика й станів без банку. */
object BuildProviders {
    val fake by lazy { FakePaymentProvider() }

    fun all(): Map<String, PaymentProvider> = mapOf(fake.id to fake)
}
