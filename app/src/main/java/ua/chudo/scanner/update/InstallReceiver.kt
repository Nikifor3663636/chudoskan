package ua.chudo.scanner.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import ua.chudo.scanner.chudoApp

/**
 * Результат сесії PackageInstaller. Потрібне підтвердження — показуємо системний діалог «Встановити»,
 * але лише коли застосунок на екрані (з фону Android не дає відкривати вікна) — інакше лишаємо банер.
 */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val app = ctx.chudoApp
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirm != null && app.inForeground) ctx.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> Unit
            PackageInstaller.STATUS_FAILURE_ABORTED -> Unit
            else -> app.updates.failed(
                "Оновлення не встановилося: " + (intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "невідома помилка"),
            )
        }
    }
}
