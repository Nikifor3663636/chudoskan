package ua.chudo.shared

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.http.takeFrom
import kotlinx.browser.localStorage
import kotlinx.browser.window

actual class KeyValueStore actual constructor() {
    actual fun get(key: String): String? = runCatching { localStorage.getItem(key) }.getOrNull()
    actual fun put(key: String, value: String?) {
        runCatching { if (value == null) localStorage.removeItem(key) else localStorage.setItem(key, value) }
    }
}

/** Сайт сети и UPCitemdb не отдают CORS — в браузере эти запросы идут через посредника (proxy/). */
private val PROXIED = setOf("chudo-ostriv.com.ua", "api.upcitemdb.com")

@JsFun("() => globalThis.CHUDO_PROXY || ''")
private external fun proxyBase(): String

actual fun createHttpClient(): HttpClient = HttpClient(Js).also { client ->
    client.plugin(HttpSend).intercept { request ->
        val host = request.url.host
        val proxy = proxyBase()
        if (host in PROXIED && proxy.isNotEmpty()) {
            val target = request.url.buildString().substringAfter("://")
            request.url.takeFrom("$proxy/$target")
        }
        execute(request)
    }
}

actual fun openUrl(url: String) {
    window.open(url, "_blank", "noopener")
}

actual val platformName: String = "Web"

/** В браузере iPhone нет живого распознавания штрихкодов — фото камерой + ZXing (scanner.js на странице). */
@JsFun(
    """(onCode, onError) => globalThis.chudoScan.photo()
        .then((code) => code ? onCode(code) : onError(''))
        .catch((e) => onError(String(e && e.message || e)))""",
)
private external fun scanPhoto(onCode: (String) -> Unit, onError: (String) -> Unit)

@Composable
actual fun BarcodeScannerView(modifier: Modifier, onCode: (String) -> Unit, onUnavailable: (String) -> Unit) {
    var busy by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf("Сфотографуйте штрихкод зблизька, щоб він займав більшу частину кадру") }
    Column(modifier.padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        if (busy) {
            CircularProgressIndicator(color = Color.White)
        } else {
            Button(onClick = {
                busy = true
                scanPhoto(
                    onCode = { busy = false; onCode(it) },
                    onError = { busy = false; hint = if (it.isEmpty()) "Штрихкод не розпізнано — спробуйте ще раз, ближче й рівніше" else it },
                )
            }) {
                Icon(Icons.Rounded.PhotoCamera, null, Modifier.size(28.dp))
                Text("  Сфотографувати штрихкод")
            }
        }
        Text(hint, color = Color.White, modifier = Modifier.padding(top = 16.dp))
    }
}
