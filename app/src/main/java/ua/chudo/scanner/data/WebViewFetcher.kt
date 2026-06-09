package ua.chudo.scanner.data

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Невидимый WebView на домене сайта: проходит проверку Cloudflare и
 * выполняет `fetch()` уже «изнутри» страницы, с её cookie.
 */
class WebViewFetcher(private val context: Context) {
    private var webView: WebView? = null
    private val initLock = Mutex()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Pair<Int, String>>>()

    suspend fun fetch(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String?,
    ): Pair<Int, String> {
        val wv = ensureReady()
        val id = UUID.randomUUID().toString()
        val result = CompletableDeferred<Pair<Int, String>>()
        pending[id] = result

        val opts = JSONObject()
            .put("method", method)
            .put("headers", JSONObject(headers))
            .put("credentials", "include")
        if (body != null) opts.put("body", body)

        val js = """
            fetch(${JSONObject.quote(url)}, $opts)
              .then(r => r.text().then(t => ChudoBridge.done(${JSONObject.quote(id)}, r.status, t)))
              .catch(e => ChudoBridge.fail(${JSONObject.quote(id)}, String(e)));
        """.trimIndent()

        withContext(Dispatchers.Main) { wv.evaluateJavascript(js, null) }
        return try {
            withTimeout(TIMEOUT_MS) { result.await() }
        } finally {
            pending.remove(id)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun ensureReady(): WebView = initLock.withLock {
        webView ?: withContext(Dispatchers.Main) {
            val loaded = CompletableDeferred<Unit>()
            val wv = WebView(context.applicationContext).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.blockNetworkImage = true
                addJavascriptInterface(Bridge(), "ChudoBridge")
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        // Страница проверки тоже «загружается» — ждём настоящую.
                        view.evaluateJavascript("document.title") { title ->
                            if (!title.contains("moment", ignoreCase = true)) loaded.complete(Unit)
                        }
                    }
                }
                loadUrl("https://chudo-ostriv.com.ua/ua/customer/section/load/")
            }
            withTimeout(TIMEOUT_MS) { loaded.await() }
            wv.also { webView = it }
        }
    }

    private inner class Bridge {
        @JavascriptInterface
        fun done(id: String, status: Int, body: String) {
            pending[id]?.complete(status to body)
        }

        @JavascriptInterface
        fun fail(id: String, error: String) {
            pending[id]?.completeExceptionally(java.io.IOException(error))
        }
    }

    private companion object {
        const val TIMEOUT_MS = 25_000L
    }
}
