package ua.chudo.scanner.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import uniffi.chudo_core.BlockKind
import uniffi.chudo_core.Lang
import uniffi.chudo_core.detectBlock
import uniffi.chudo_core.graphqlUrl
import uniffi.chudo_core.storeHeader
import java.io.IOException

class HttpException(val code: Int) : IOException("HTTP $code")
class BlockedException(val kind: BlockKind) : IOException("Сайт заблокировал запрос: $kind")

/**
 * HTTP к сайту. Сначала обычный OkHttp (быстро). Если Cloudflare вернул
 * проверку браузера — повторяем тот же запрос из невидимого WebView,
 * который проходит проверку как обычный Chrome.
 */
class SiteClient(
    private val ok: OkHttpClient,
    private val web: WebViewFetcher,
) {
    @Volatile private var preferWebView = false

    suspend fun graphql(body: String, lang: Lang): String {
        val headers = mapOf(
            "Content-Type" to "application/json",
            "Accept" to "application/json",
            "Store" to storeHeader(lang),
        )
        return site("POST", graphqlUrl(), headers, body)
    }

    suspend fun getJson(url: String): String =
        site("GET", url, mapOf("Accept" to "application/json", "X-Requested-With" to "XMLHttpRequest"), null)

    /** Сторонние API (UPCitemdb) — без WebView-обхода. */
    suspend fun getExternal(url: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).header("Accept", "application/json").header("User-Agent", UA).build()
        ok.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (r.code == 429) throw BlockedException(BlockKind.RATE_LIMITED)
            if (!r.isSuccessful && r.code != 404) throw HttpException(r.code)
            text
        }
    }

    private suspend fun site(method: String, url: String, headers: Map<String, String>, body: String?): String {
        if (preferWebView) return viaWebView(method, url, headers, body)

        val (code, text) = withContext(Dispatchers.IO) {
            val rb = body?.toRequestBody(JSON)
            val req = Request.Builder().url(url).method(method, rb).apply {
                headers.forEach { (k, v) -> header(k, v) }
                header("User-Agent", UA)
                header("Accept-Language", "uk-UA,uk;q=0.9,ru;q=0.8")
            }.build()
            ok.newCall(req).execute().use { it.code to it.body?.string().orEmpty() }
        }

        return when (detectBlock(code.toUShort(), text)) {
            BlockKind.CLOUDFLARE_CHALLENGE, BlockKind.FORBIDDEN -> {
                preferWebView = true // дальше в этой сессии сразу через WebView
                viaWebView(method, url, headers, body)
            }
            BlockKind.RATE_LIMITED -> throw BlockedException(BlockKind.RATE_LIMITED)
            null -> if (code in 200..299) text else throw HttpException(code)
        }
    }

    private suspend fun viaWebView(method: String, url: String, headers: Map<String, String>, body: String?): String {
        val (code, text) = web.fetch(method, url, headers, body)
        detectBlock(code.toUShort(), text)?.let { throw BlockedException(it) }
        if (code !in 200..299) throw HttpException(code)
        return text
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
        const val UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/141.0.0.0 Mobile Safari/537.36"
    }
}
