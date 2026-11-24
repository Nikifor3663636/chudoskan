package ua.chudo.scanner.update

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import androidx.core.net.toUri
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Що показати в застосунку. */
data class UpdateUi(
    val decision: UpdateDecision = UpdateDecision.None,
    /** 0..1 під час завантаження. */
    val progress: Float? = null,
    /** APK завантажено й перевірено — можна ставити. */
    val ready: File? = null,
    val error: String? = null,
    val checking: Boolean = false,
)

/**
 * Автооновлення: джерело → завантаження в cacheDir → sha256 і той самий підпис, що в установленого
 * застосунку → PackageInstaller. На Android 12+ без підтвердження, якщо система дозволяє.
 */
class UpdateManager(
    private val ctx: Context,
    private val source: UpdateSource,
    private val downloader: ApkDownloader,
    private val meta: suspend () -> AppMeta?,
) {
    private val lock = Mutex()
    private val _ui = MutableStateFlow(UpdateUi())
    val ui = _ui.asStateFlow()

    val currentCode: Long get() = packageInfo(ctx.packageName)?.let(::versionCode) ?: 0
    val currentName: String get() = packageInfo(ctx.packageName)?.versionName.orEmpty()

    /** Перевірка: одне читання meta/app + (за потреби) version.json. */
    suspend fun check(): UpdateDecision {
        _ui.value = _ui.value.copy(checking = true, error = null)
        val m = runCatching { meta() }.getOrNull()
        val info = runCatching { source.latest() }.getOrNull()
        val d = UpdatePolicy.decide(currentCode, info, m?.minVersionCode ?: 0, m?.message)
        _ui.value = _ui.value.copy(decision = d, checking = false, ready = _ui.value.ready?.takeIf { d !is UpdateDecision.None && it.exists() })
        return d
    }

    /** Завантажує й перевіряє APK; [unmetered] = false — лише якщо файл < 50 МБ. */
    suspend fun download(info: UpdateInfo, unmetered: Boolean = isUnmetered()): File? = lock.withLock {
        _ui.value.ready?.takeIf { it.exists() && it.name == fileName(info) }?.let { return it }
        _ui.value = _ui.value.copy(progress = 0f, error = null)
        try {
            val dir = File(ctx.cacheDir, "update").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val f = downloader.download(info.apkUrl, File(dir, fileName(info)), if (unmetered) null else UpdatePolicy.MAX_METERED) { done, total ->
                if (total > 0) _ui.value = _ui.value.copy(progress = done.toFloat() / total)
            }
            verify(f, info)
            _ui.value = _ui.value.copy(progress = null, ready = f)
            f
        } catch (e: Exception) {
            _ui.value = _ui.value.copy(progress = null, error = e.message ?: "Не вдалося завантажити оновлення.")
            null
        }
    }

    /** Інший sha256 або інший підпис — файл видаляємо, не ставимо. */
    suspend fun verify(f: File, info: UpdateInfo) = withContext(Dispatchers.IO) {
        if (!ApkChecks.sameSha(info.sha256, ApkChecks.sha256(f))) { f.delete(); throw UpdateException("Файл оновлення пошкоджено або підмінено (sha256 не збігається).") }
        val apk = archiveInfo(f) ?: run { f.delete(); throw UpdateException("Завантажений файл — не APK.") }
        if (apk.packageName != ctx.packageName || !ApkChecks.sameSigner(signers(packageInfo(ctx.packageName)), signers(apk))) {
            f.delete(); throw UpdateException("Оновлення підписано іншим ключем — встановлення скасовано.")
        }
    }

    /** Чи дозволено «Встановлення з цього джерела». */
    fun canInstall(): Boolean = ctx.packageManager.canRequestPackageInstalls()

    fun installSettingsIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${ctx.packageName}".toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Сесія PackageInstaller; на Android 12+ — без підтвердження, якщо попередню версію ставив сам застосунок. */
    fun install(f: File) {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            f.inputStream().use { input -> s.openWrite("app.apk", 0, f.length()).use { out -> input.copyTo(out); s.fsync(out) } }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            val intent = PendingIntent.getBroadcast(ctx, id, Intent(ctx, InstallReceiver::class.java).setPackage(ctx.packageName), flags)
            s.commit(intent.intentSender)
        }
    }

    fun failed(message: String) { _ui.value = _ui.value.copy(error = message) }

    fun isUnmetered(): Boolean = ctx.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered == false

    private fun fileName(info: UpdateInfo) = "ChudoSkan-${info.versionCode}.apk"

    private fun packageInfo(name: String): PackageInfo? = runCatching { ctx.packageManager.getPackageInfo(name, signFlags()) }.getOrNull()

    fun archiveInfo(f: File): PackageInfo? = ctx.packageManager.getPackageArchiveInfo(f.path, signFlags())

    @Suppress("DEPRECATION")
    @SuppressLint("PackageManagerGetSignatures")
    private fun signFlags() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

    /** Підписи встановленого застосунку. */
    fun installedSigners(): Set<String> = signers(packageInfo(ctx.packageName))

    @Suppress("DEPRECATION")
    fun signers(p: PackageInfo?): Set<String> {
        p ?: return emptySet()
        val sigs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val si = p.signingInfo ?: return emptySet()
            if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
        } else p.signatures
        return sigs.orEmpty().map { ApkChecks.certDigest(it.toByteArray()) }.toSet()
    }

    @Suppress("DEPRECATION")
    fun versionCode(p: PackageInfo): Long = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) p.longVersionCode else p.versionCode.toLong()
}
