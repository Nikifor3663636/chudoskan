package ua.chudo.scanner.update

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** Опис нової версії: з meta/app (Google Диск) або з version.json релізу GitHub. */
data class UpdateInfo(
    val versionCode: Long,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val minVersionCode: Long = 0,
    val notes: String = "",
)

fun interface UpdateSource { suspend fun latest(): UpdateInfo? }

/** Що робити з оновленням. */
sealed interface UpdateDecision {
    data object None : UpdateDecision
    data class Available(val info: UpdateInfo) : UpdateDecision
    /** Поточна версія нижча за мінімальну — робота заблокована до оновлення. */
    data class Required(val info: UpdateInfo?, val message: String) : UpdateDecision
}

object UpdatePolicy {
    const val REQUIRED_MESSAGE = "Ця версія застаріла. Оновіть застосунок, щоб продовжити роботу."

    /** [metaMin] і [metaMessage] — з meta/app: суперадмін може підняти мінімальну версію без нового релізу. */
    fun decide(current: Long, info: UpdateInfo?, metaMin: Long = 0, metaMessage: String? = null): UpdateDecision {
        val min = maxOf(metaMin, info?.minVersionCode ?: 0)
        val newer = info?.takeIf { it.versionCode > current }
        return when {
            current < min -> UpdateDecision.Required(newer, metaMessage?.takeIf { it.isNotBlank() } ?: REQUIRED_MESSAGE)
            newer != null -> UpdateDecision.Available(newer)
            else -> UpdateDecision.None
        }
    }

    /** Мобільна мережа — лише якщо APK менший за [MAX_METERED]. */
    const val MAX_METERED = 50L * 1024 * 1024

    fun mayDownload(unmetered: Boolean, sizeBytes: Long?): Boolean = unmetered || (sizeBytes != null && sizeBytes in 1 until MAX_METERED)
}

/** Перевірки завантаженого APK — до встановлення. */
object ApkChecks {
    fun sha256(input: InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        input.use { while (true) { val n = it.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun sha256(file: File): String = sha256(file.inputStream())

    fun sameSha(expected: String, actual: String) = expected.trim().equals(actual.trim(), ignoreCase = true) && expected.isNotBlank()

    /** Підписи — набори SHA-256 сертифікатів; оновлення лише тим самим ключем, що й встановлений застосунок. */
    fun sameSigner(installed: Set<String>, apk: Set<String>) = installed.isNotEmpty() && installed == apk

    fun certDigest(cert: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(cert).joinToString("") { "%02x".format(it) }
}

/**
 * version.json — плоский об'єкт, який пише CI: {versionCode, versionName, sha256, minVersionCode, notes, publishedAt}.
 * Свій маленький розбір, щоб не тягнути бібліотеку й щоб працювало в JVM-тестах.
 */
object VersionJson {
    private fun str(json: String, key: String): String? =
        Regex("\"$key\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(json)?.groupValues?.get(1)
            ?.replace("\\n", "\n")?.replace("\\\"", "\"")?.replace("\\/", "/")?.replace("\\\\", "\\")

    private fun num(json: String, key: String): Long? =
        Regex("\"$key\"\\s*:\\s*(\\d+)").find(json)?.groupValues?.get(1)?.toLongOrNull()

    fun parse(json: String, apkUrl: String): UpdateInfo? {
        val code = num(json, "versionCode") ?: return null
        val sha = str(json, "sha256")?.takeIf { it.matches(Regex("^[0-9a-fA-F]{64}$")) } ?: return null
        return UpdateInfo(code, str(json, "versionName").orEmpty(), str(json, "apkUrl") ?: apkUrl, sha.lowercase(), num(json, "minVersionCode") ?: 0, str(json, "notes").orEmpty())
    }
}
