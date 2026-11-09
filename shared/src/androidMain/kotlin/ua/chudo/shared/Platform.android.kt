package ua.chudo.shared

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp

/** Android-контекст для общего кода; задаётся из Application. */
@SuppressLint("StaticFieldLeak")
object SharedAndroid {
    lateinit var context: Context
}

actual class KeyValueStore actual constructor() {
    private val sp = SharedAndroid.context.getSharedPreferences("chudo_shared", Context.MODE_PRIVATE)
    actual fun get(key: String): String? = sp.getString(key, null)
    actual fun put(key: String, value: String?) {
        sp.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
    }
}

actual fun createHttpClient(): HttpClient = HttpClient(OkHttp)

actual fun openUrl(url: String) {
    SharedAndroid.context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

actual val platformName: String = "Android"

/** В Android-приложении свой сканер (CameraX + ML Kit); общий экран сюда пока не подключён. */
@Composable
actual fun BarcodeScannerView(modifier: Modifier, onCode: (String) -> Unit, onUnavailable: (String) -> Unit) {
    LaunchedEffect(Unit) { onUnavailable("Сканер у цій збірці недоступний") }
    Box(modifier, contentAlignment = Alignment.Center) { Text("Сканер недоступний") }
}
