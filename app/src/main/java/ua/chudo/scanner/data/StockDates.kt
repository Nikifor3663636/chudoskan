package ua.chudo.scanner.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Підписи дат залишків. */
object StockDates {
    /** «Залишки на 24.11, 09:05» — з meta/stock.updatedAt. */
    fun label(at: Date, zone: TimeZone = TimeZone.getDefault()): String =
        "Залишки на " + SimpleDateFormat("dd.MM, HH:mm", Locale.forLanguageTag("uk")).apply { timeZone = zone }.format(at)
}
