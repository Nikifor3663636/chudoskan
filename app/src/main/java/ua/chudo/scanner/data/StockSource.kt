package ua.chudo.scanner.data

/**
 * Точные остатки (штуки) по магазинам.
 *
 * Публичный сайт отдаёт только «є / немає» по магазину: в `multiwarehouse/shops` нет чисел,
 * а `only_x_left_in_stock` в GraphQL пустое. Количество есть только в учётной системе сети (1С),
 * поэтому источник подключаемый: реализуйте этот интерфейс поверх внутреннего API и передайте
 * его в [ua.chudo.scanner.ui.stock.StockViewModel].
 */
fun interface StockSource {
    /** Адрес магазина (как на сайте) → штук на остатке; `null`, если источник не подключён. */
    suspend fun quantities(productId: Long, article: String?, region: String): Map<String, Int>?
}

/** Пока внутреннего API нет — количества неизвестны. */
val NoStockSource = StockSource { _, _, _ -> null }
