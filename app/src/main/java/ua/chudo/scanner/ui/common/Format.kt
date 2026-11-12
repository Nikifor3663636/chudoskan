package ua.chudo.scanner.ui.common

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.net.toUri
import java.text.NumberFormat
import java.util.Locale

/** +380671234567 → +380 67 123 45 67 */
fun formatPhone(p: String): String =
    if (p.length == 13 && p.startsWith("+380")) "+380 ${p.substring(4, 6)} ${p.substring(6, 9)} ${p.substring(9, 11)} ${p.substring(11)}" else p

private val priceFormat = NumberFormat.getIntegerInstance(Locale.forLanguageTag("uk-UA"))

fun formatPrice(value: Double): String = "${priceFormat.format(value)} грн"

fun Context.openUrl(url: String) {
    runCatching { startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
        .onFailure { Toast.makeText(this, "Немає застосунку, щоб відкрити посилання", Toast.LENGTH_SHORT).show() }
}

fun Context.openMap(address: String) {
    val q = Uri.encode("Чудо Острів, $address")
    runCatching { startActivity(Intent(Intent.ACTION_VIEW, "geo:0,0?q=$q".toUri())) }
        .onFailure { openUrl("https://www.google.com/maps/search/?api=1&query=$q") }
}

fun Context.shareText(text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    startActivity(Intent.createChooser(send, null))
}
