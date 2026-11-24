package ua.chudo.scanner.update

import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** `meta/app`: опублікована версія (Google Диск) і мінімальна версія; пише лише superAdmin. */
data class AppMeta(
    val versionCode: Long = 0,
    val versionName: String = "",
    val apkUrl: String = "",
    val sha256: String = "",
    val minVersionCode: Long = 0,
    val notes: String = "",
    /** Текст для екрана «Потрібне оновлення». */
    val message: String = "",
    /** "drive" (типово) або "github" — якщо Диск видає «перевищено ліміт». */
    val source: String = DRIVE,
) {
    fun toInfo(): UpdateInfo? =
        if (versionCode > 0 && apkUrl.isNotBlank() && sha256.isNotBlank()) UpdateInfo(versionCode, versionName, apkUrl, sha256, minVersionCode, notes) else null

    companion object {
        const val DRIVE = "drive"
        const val GITHUB = "github"
    }
}

class AppMetaStore(private val db: FirebaseFirestore) {
    /** Одне читання. */
    suspend fun read(): AppMeta? {
        val d = db.collection("meta").document("app").get().await()
        if (!d.exists()) return null
        return AppMeta(
            d.getLong("versionCode") ?: 0, d.getString("versionName").orEmpty(), d.getString("apkUrl").orEmpty(),
            d.getString("sha256").orEmpty(), d.getLong("minVersionCode") ?: 0, d.getString("notes").orEmpty(),
            d.getString("message").orEmpty(), d.getString("source") ?: AppMeta.DRIVE,
        )
    }
}

/** Основне джерело: meta/app → APK на Google Диску. */
class DriveSource(private val meta: suspend () -> AppMeta?) : UpdateSource {
    override suspend fun latest(): UpdateInfo? = meta()?.takeIf { it.source != AppMeta.GITHUB }?.toInfo()
}

/** Запасне: version.json останнього релізу GitHub (посилання latest/download — без лімітів API). */
class GitHubSource(private val http: OkHttpClient) : UpdateSource {
    override suspend fun latest(): UpdateInfo? = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(VERSION_URL).build()).execute().use { r ->
            if (!r.isSuccessful) return@withContext null
            VersionJson.parse(r.body?.string().orEmpty(), APK_URL)
        }
    }

    companion object {
        const val BASE = "https://github.com/Nikifor3663636/chudoskan/releases/latest/download"
        const val VERSION_URL = "$BASE/version.json"
        const val APK_URL = "$BASE/ChudoSkan.apk"
    }
}

/** Диск, а коли meta/app порожній або перемкнено на GitHub — GitHub. */
class UpdateSources(private val drive: UpdateSource, private val github: UpdateSource) : UpdateSource {
    override suspend fun latest(): UpdateInfo? =
        runCatching { drive.latest() }.getOrNull() ?: runCatching { github.latest() }.getOrNull()
}
