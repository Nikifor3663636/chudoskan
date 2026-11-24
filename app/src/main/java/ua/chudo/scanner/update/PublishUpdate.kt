package ua.chudo.scanner.update

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await
import java.io.File

/** Що з'ясували про APK перед публікацією. */
data class ApkFacts(val packageName: String, val versionCode: Long, val versionName: String, val sha256: String, val sameSigner: Boolean)

object PublishCheck {
    /** `null` — можна публікувати; інакше — пояснення, чому ні. */
    fun problem(ourPackage: String, apk: ApkFacts, published: Long): String? = when {
        apk.packageName != ourPackage -> "Це не ЧудоСкан: інший застосунок (${apk.packageName})."
        !apk.sameSigner -> "APK підписано іншим ключем — телефони його не встановлять. Візьміть APK з релізу GitHub."
        apk.versionCode <= published -> "Версія ${apk.versionName} (${apk.versionCode}) не новіша за опубліковану ($published)."
        else -> null
    }
}

/** Готове до публікації: користувач бачить «Опублікувати X.Y?». */
data class PublishDraft(val fileId: String, val facts: ApkFacts, val published: AppMeta?)

/**
 * «Профіль → Опублікувати оновлення» (лише superAdmin): посилання на APK з Google Диска →
 * завантажити, прочитати версію й підпис, порахувати sha256 → `meta/app` + запис в adminLog.
 */
class PublishUpdate(
    private val db: FirebaseFirestore,
    private val updates: UpdateManager,
    private val downloader: ApkDownloader,
    private val cacheDir: File,
    private val ourPackage: String,
) {
    suspend fun prepare(link: String): PublishDraft {
        val id = DriveLinks.fileId(link) ?: throw UpdateException("Не схоже на посилання Google Диска. Скопіюйте «Поділитися → Копіювати посилання».")
        val f = downloader.download(DriveLinks.directUrl(id), File(File(cacheDir, "publish").apply { mkdirs() }, "check.apk"))
        val info = updates.archiveInfo(f) ?: throw UpdateException("За посиланням не APK.")
        val facts = ApkFacts(
            info.packageName, updates.versionCode(info), info.versionName.orEmpty(), ApkChecks.sha256(f),
            ApkChecks.sameSigner(updates.installedSigners(), updates.signers(info)),
        )
        f.delete()
        val published = AppMetaStore(db).read()
        PublishCheck.problem(ourPackage, facts, published?.versionCode ?: 0)?.let { throw UpdateException(it) }
        return PublishDraft(id, facts, published)
    }

    /** [required] — «Обов'язкове»: minVersionCode = versionCode. */
    suspend fun publish(d: PublishDraft, required: Boolean, notes: String, byUid: String, byName: String) {
        val f = d.facts
        val min = if (required) f.versionCode else (d.published?.minVersionCode ?: 0)
        db.batch()
            .set(
                db.collection("meta").document("app"),
                mapOf(
                    "versionCode" to f.versionCode, "versionName" to f.versionName, "apkUrl" to DriveLinks.directUrl(d.fileId),
                    "sha256" to f.sha256, "minVersionCode" to min, "notes" to notes.take(500), "source" to AppMeta.DRIVE,
                    "publishedBy" to byName.take(100), "publishedAt" to FieldValue.serverTimestamp(),
                ),
                SetOptions.merge(),
            )
            .set(
                db.collection("adminLog").document(),
                mapOf(
                    "by" to byUid, "at" to FieldValue.serverTimestamp(), "action" to "appPublish",
                    "details" to "версія ${f.versionName} (${f.versionCode})" + (if (required) ", обов'язкова" else ""),
                ),
            )
            .commit().await()
    }

    /** «Джерело: GitHub» — якщо Диск видає «перевищено ліміт завантажень». */
    suspend fun setSource(source: String) {
        db.collection("meta").document("app").set(mapOf("source" to source), SetOptions.merge()).await()
    }
}
