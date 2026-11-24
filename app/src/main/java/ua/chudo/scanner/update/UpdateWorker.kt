package ua.chudo.scanner.update

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import ua.chudo.scanner.chudoApp
import java.util.concurrent.TimeUnit

/**
 * Раз на 6 год (і при старті), коли є мережа: перевірити, завантажити (Wi-Fi або < 50 МБ), перевірити підпис.
 * Ставимо самі лише коли застосунок у фоні — роботу на екрані не перебиваємо.
 */
class UpdateWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext.chudoApp
        val u = app.updates
        return try {
            val info = when (val d = u.check()) {
                is UpdateDecision.Available -> d.info
                is UpdateDecision.Required -> d.info
                UpdateDecision.None -> null
            } ?: return Result.success()
            val unmetered = u.isUnmetered()
            val f = u.download(info, unmetered)
            if (f == null) {
                if (!unmetered) scheduleWifi(applicationContext)
                return Result.success()
            }
            // Незбережений кошик «Термінал» уже у файлі (CartStore) — оновлення його не загубить.
            if (!app.inForeground && u.canInstall()) u.install(f)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val NAME = "app-update"

        fun schedule(ctx: Context) {
            val net = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            val wm = WorkManager.getInstance(ctx)
            wm.enqueueUniquePeriodicWork(
                NAME, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<UpdateWorker>(6, TimeUnit.HOURS).setConstraints(net).build(),
            )
            wm.enqueueUniqueWork("$NAME-now", ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<UpdateWorker>().setConstraints(net).build())
        }

        fun scheduleWifi(ctx: Context) {
            val wifi = Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build()
            WorkManager.getInstance(ctx).enqueueUniqueWork("$NAME-wifi", ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<UpdateWorker>().setConstraints(wifi).build())
        }
    }
}
