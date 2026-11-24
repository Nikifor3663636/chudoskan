package ua.chudo.scanner.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException

/** Зрозуміла людині помилка оновлення. */
class UpdateException(message: String) : IOException(message)

/**
 * Завантаження APK: GitHub — напряму; Google Диск — через uc?export=download з переходом на
 * drive.usercontent.google.com і сторінкою підтвердження, якщо Диск її покаже.
 */
class ApkDownloader(private val http: OkHttpClient) {

    /** [maxBytes] — межа для мобільної мережі; більший файл — [UpdateException], щоб дочекатися Wi-Fi. */
    suspend fun download(url: String, target: File, maxBytes: Long? = null, onProgress: (Long, Long) -> Unit = { _, _ -> }): File =
        withContext(Dispatchers.IO) {
            val first = DriveLinks.fileId(url)?.takeIf { "google.com" in url || "/" !in url }?.let(DriveLinks::directUrl) ?: url
            var next: String? = first
            var hops = 0
            while (next != null && hops++ < 3) {
                val r = http.newCall(Request.Builder().url(next).header("User-Agent", "ChudoSkan").build()).execute()
                r.use {
                    if (r.code == 403 || r.code == 401) throw UpdateException(DriveLinks.NO_ACCESS)
                    if (r.code == 429) throw UpdateException(DriveLinks.QUOTA)
                    if (!r.isSuccessful) throw UpdateException("Не вдалося завантажити оновлення (код ${r.code}).")
                    if (isHtml(r)) {
                        next = when (val p = DriveLinks.classify(r.body?.string().orEmpty().take(512_000))) {
                            is DriveLinks.Page.Confirm -> p.url
                            DriveLinks.Page.NoAccess -> throw UpdateException(DriveLinks.NO_ACCESS)
                            DriveLinks.Page.Quota -> throw UpdateException(DriveLinks.QUOTA)
                            DriveLinks.Page.Unknown -> throw UpdateException("За посиланням не APK. ${DriveLinks.NO_ACCESS}")
                        }
                        return@use
                    }
                    save(r, target, maxBytes, onProgress)
                    return@withContext target
                }
            }
            throw UpdateException("Google Диск не віддав файл. ${DriveLinks.NO_ACCESS}")
        }

    private fun isHtml(r: Response) = r.header("Content-Type").orEmpty().startsWith("text/html", ignoreCase = true)

    private fun save(r: Response, target: File, maxBytes: Long?, onProgress: (Long, Long) -> Unit) {
        val body = r.body ?: throw UpdateException("Порожня відповідь сервера.")
        val total = body.contentLength()
        if (maxBytes != null && (total <= 0 || total > maxBytes)) throw UpdateException(WAIT_WIFI)
        val tmp = File(target.parentFile, target.name + ".part")
        var done = 0L
        body.byteStream().use { input ->
            tmp.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (maxBytes != null && done > maxBytes) throw UpdateException(WAIT_WIFI)
                    onProgress(done, total)
                }
            }
        }
        target.delete()
        if (!tmp.renameTo(target)) throw UpdateException("Не вдалося зберегти оновлення.")
    }

    companion object { const val WAIT_WIFI = "Оновлення велике — завантажимо через Wi-Fi." }
}
