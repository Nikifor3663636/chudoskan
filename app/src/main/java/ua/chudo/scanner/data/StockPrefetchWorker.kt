package ua.chudo.scanner.data

import android.content.Context
import android.net.ConnectivityManager
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import ua.chudo.scanner.chudoApp

/**
 * Після входу качає пакет залишків мережі у фоні, щоб перший скан не чекав завантаження.
 * Мобільна мережа — лише якщо пакет < 2 МБ; більший — чекаємо Wi-Fi ([scheduleWifi]).
 */
class StockPrefetchWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext.chudoApp
        if (!app.auth.configured) return Result.success()
        val cm = applicationContext.getSystemService(ConnectivityManager::class.java)
        val unmetered = cm?.isActiveNetworkMetered == false
        return try {
            if (!app.networkLookup.prefetch(unmetered)) scheduleWifi(applicationContext)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.success()
        }
    }

    companion object {
        private const val NAME = "stock-prefetch"

        fun schedule(ctx: Context) = enqueue(ctx, NAME, NetworkType.CONNECTED, ExistingWorkPolicy.REPLACE)

        fun scheduleWifi(ctx: Context) = enqueue(ctx, "$NAME-wifi", NetworkType.UNMETERED, ExistingWorkPolicy.KEEP)

        private fun enqueue(ctx: Context, name: String, network: NetworkType, policy: ExistingWorkPolicy) {
            val req = OneTimeWorkRequestBuilder<StockPrefetchWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(network).build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(name, policy, req)
        }
    }
}
