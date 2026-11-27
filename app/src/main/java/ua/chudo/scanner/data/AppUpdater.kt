package ua.chudo.scanner.data

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** Нова версія з GitHub Releases. */
data class UpdateInfo(val version: String, val apkUrl: String, val notes: String)

sealed interface UpdateState {
    data object None : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data class Downloading(val info: UpdateInfo, val progress: Float) : UpdateState
    /** Android ще не дозволив ЧудоСкан встановлювати застосунки — відкриваємо налаштування. */
    data class NeedPermission(val info: UpdateInfo, val apk: File) : UpdateState
    data class Ready(val info: UpdateInfo, val apk: File) : UpdateState
    data class Failed(val info: UpdateInfo, val message: String) : UpdateState
}

object Versions {
    /** «v3.2.0» → [3, 2, 0]; хвости («-beta») відкидаємо. */
    fun parse(v: String): List<Int> =
        v.trim().removePrefix("v").removePrefix("V").substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }

    /** [remote] новіша за [current]? «3.10.0» > «3.9.1». */
    fun newer(remote: String, current: String): Boolean {
        val a = parse(remote); val b = parse(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}

/**
 * Оновлення застосунку з GitHub Releases (там само, звідки його ставлять): перевірка не частіше ніж раз на
 * [CHECK_EVERY_MS], завантаження APK у кеш і системне вікно встановлення. Тихо встановити Android не дає —
 * продавець натискає «Встановити»; поверх старої версії ставиться, бо всі релізи підписані одним ключем.
 */
class AppUpdater(private val context: Context, private val http: OkHttpClient) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.None)
    val state: StateFlow<UpdateState> = _state.asStateFlow()
    private val sp = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
    private val dir get() = File(context.cacheDir, "updates")

    val currentVersion: String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull().orEmpty()

    suspend fun check(force: Boolean = false): Boolean {
        if (_state.value is UpdateState.Downloading) return true
        val now = System.currentTimeMillis()
        if (!force && now - sp.getLong(KEY_CHECKED, 0) < CHECK_EVERY_MS) {
            // Раніше вже знайшли нову версію — показуємо без запиту в мережу.
            cached()?.let { if (Versions.newer(it.version, currentVersion)) _state.value = UpdateState.Available(it) }
            return true
        }
        val info = try {
            latest()
        } catch (e: CancellationException) {
            throw e
        } catch (_: IOException) {
            null
        } ?: return false
        sp.edit {
            putLong(KEY_CHECKED, now)
            putString(KEY_VERSION, info.version); putString(KEY_URL, info.apkUrl); putString(KEY_NOTES, info.notes)
        }
        if (Versions.newer(info.version, currentVersion)) {
            if (_state.value !is UpdateState.Ready) _state.value = UpdateState.Available(info)
        } else {
            _state.value = UpdateState.None
            dir.deleteRecursively() // старі завантаження вже не потрібні
        }
        return true
    }

    private fun cached(): UpdateInfo? {
        val v = sp.getString(KEY_VERSION, null) ?: return null
        val u = sp.getString(KEY_URL, null) ?: return null
        return UpdateInfo(v, u, sp.getString(KEY_NOTES, "").orEmpty())
    }

    private suspend fun latest(): UpdateInfo? = withContext(Dispatchers.IO) {
        val api = try {
            latestFromApi()
        } catch (_: IOException) {
            null
        } catch (_: org.json.JSONException) {
            null
        }
        api ?: latestFromReleasePage(http, currentVersion)
    }

    private fun latestFromApi(): UpdateInfo? {
        val req = Request.Builder().url(LATEST_URL)
            .header("Accept", "application/vnd.github+json").header("User-Agent", "ChudoScan-Android").build()
        return http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return null
            val o = JSONObject(r.body?.string() ?: return null)
            val tag = o.optString("tag_name").ifEmpty { return null }
            val assets = o.optJSONArray("assets") ?: return null
            val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
                .firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) }
                ?.optString("browser_download_url")?.ifEmpty { null } ?: return null
            UpdateInfo(tag.removePrefix("v"), apk, o.optString("body"))
        }
    }

    /** «Оновити»: завантажити APK (з прогресом) і відкрити встановлення. */
    suspend fun download() {
        val info = when (val s = _state.value) {
            is UpdateState.Available -> s.info
            is UpdateState.Failed -> s.info
            is UpdateState.Ready -> { install(s.info, s.apk); return }
            is UpdateState.NeedPermission -> { install(s.info, s.apk); return }
            else -> return
        }
        _state.value = UpdateState.Downloading(info, 0f)
        try {
            val file = withContext(Dispatchers.IO) {
                dir.mkdirs()
                val out = File(dir, "ChudoSkan-${info.version}.apk")
                val tmp = File(dir, out.name + ".part")
                http.newCall(Request.Builder().url(info.apkUrl).header("User-Agent", "ChudoScan-Android").build()).execute().use { r ->
                    if (!r.isSuccessful) error("Сервер відповів ${r.code}")
                    val body = r.body ?: error("Порожня відповідь")
                    val total = body.contentLength().takeIf { it > 0 }
                    body.byteStream().use { input ->
                        tmp.outputStream().use { output ->
                            val buf = ByteArray(64 * 1024)
                            var done = 0L
                            var lastPct = -1
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                output.write(buf, 0, n)
                                done += n
                                if (total != null) {
                                    val pct = (done * 100 / total).toInt()
                                    if (pct != lastPct) { lastPct = pct; _state.value = UpdateState.Downloading(info, pct / 100f) }
                                }
                            }
                        }
                    }
                }
                if (!tmp.renameTo(out)) { out.delete(); tmp.renameTo(out) }
                out
            }
            install(info, file)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value = UpdateState.Failed(info, "Не вдалося завантажити оновлення: ${e.message ?: "немає інтернету?"}")
        }
    }

    private fun install(info: UpdateInfo, apk: File) {
        if (!context.packageManager.canRequestPackageInstalls()) {
            _state.value = UpdateState.NeedPermission(info, apk)
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri())
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            return
        }
        _state.value = UpdateState.Ready(info, apk)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            _state.value = UpdateState.Failed(info, "На телефоні немає встановлювача застосунків.")
        }
    }

    private companion object {
        const val LATEST_URL = "https://api.github.com/repos/Nikifor3663636/chudoskan/releases/latest"
        /** Автоматично — не частіше ніж раз на 30 хв (вручну — «Профіль → Перевірити оновлення»). */
        const val CHECK_EVERY_MS = 30 * 60 * 1000L
        const val KEY_CHECKED = "checkedAt"
        const val KEY_VERSION = "version"
        const val KEY_URL = "url"
        const val KEY_NOTES = "notes"
    }
}

private const val RELEASES = "https://github.com/Nikifor3663636/chudoskan/releases"
private val stableTag = Regex("v[0-9]+(?:\\.[0-9]+){1,2}")

internal fun latestFromReleasePage(http: OkHttpClient, currentVersion: String): UpdateInfo? {
    val tag = http.newCall(Request.Builder().url("$RELEASES/latest").head().build()).execute().use { response ->
        if (!response.isSuccessful) return null
        stableReleaseTag(response.request.url) ?: return null
    }
    val version = tag.removePrefix("v")
    val apk = "$RELEASES/download/$tag/ChudoSkan-$version.apk"
    if (Versions.newer(version, currentVersion)) {
        http.newCall(Request.Builder().url(apk).head().build()).execute().use { response ->
            if (!response.isSuccessful) return null
        }
    }
    return UpdateInfo(version, apk, "")
}

internal fun stableReleaseTag(url: HttpUrl): String? {
    if (url.scheme != "https" || url.host != "github.com") return null
    val path = url.pathSegments
    if (path.dropLast(1) != listOf("Nikifor3663636", "chudoskan", "releases", "tag")) return null
    return path.last().takeIf(stableTag::matches)
}