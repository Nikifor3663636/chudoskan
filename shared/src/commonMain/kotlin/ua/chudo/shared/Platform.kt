package ua.chudo.shared

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.ktor.client.HttpClient

/** Небольшое хранилище ключ-значение: SharedPreferences на Android, NSUserDefaults на iOS. */
expect class KeyValueStore() {
    fun get(key: String): String?
    fun put(key: String, value: String?)
}

/** HTTP-клиент с движком платформы (OkHttp / Darwin). */
expect fun createHttpClient(): HttpClient

/** Открыть ссылку в браузере. */
expect fun openUrl(url: String)

/** Название платформы для User-Agent и экрана «Про застосунок». */
expect val platformName: String

/**
 * Живой сканер штрихкодов камерой (EAN-13/EAN-8/UPC/Code128).
 * [onCode] вызывается один раз на найденный код. [onUnavailable] — нет камеры или доступа.
 */
@Composable
expect fun BarcodeScannerView(modifier: Modifier, onCode: (String) -> Unit, onUnavailable: (String) -> Unit)
