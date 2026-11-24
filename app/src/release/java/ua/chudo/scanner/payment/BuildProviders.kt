package ua.chudo.scanner.payment

/** Release-збірка: лише справжні провайдери. Фейкового тут немає й бути не може. */
object BuildProviders {
    fun all(): Map<String, PaymentProvider> = emptyMap()
}
