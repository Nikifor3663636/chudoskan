package ua.chudo.scanner.ui.common

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import java.text.NumberFormat
import java.util.Locale
import ua.chudo.scanner.i18n.tr
import ua.chudo.scanner.chudoApp

private val priceFormat = NumberFormat.getIntegerInstance(Locale.forLanguageTag("uk-UA"))

fun formatPrice(value: Double): String = "${priceFormat.format(value)} грн"

fun Context.openUrl(url: String) {
    runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        .onFailure { Toast.makeText(this, chudoApp.prefs.lang.value.tr("Немає застосунку, щоб відкрити посилання", "Нет приложения, чтобы открыть ссылку"), Toast.LENGTH_SHORT).show() }
}

fun Context.openMap(address: String) {
    val q = Uri.encode("Чудо Острів, $address")
    runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$q"))) }
        .onFailure { openUrl("https://www.google.com/maps/search/?api=1&query=$q") }
}

fun Context.shareText(text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    startActivity(Intent.createChooser(send, null))
}
