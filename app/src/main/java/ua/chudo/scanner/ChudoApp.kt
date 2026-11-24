package ua.chudo.scanner

import android.app.Application
import android.content.Context
import com.google.firebase.firestore.FirebaseFirestore
import okhttp3.OkHttpClient
import ua.chudo.scanner.data.AppPrefs
import ua.chudo.scanner.data.AuthRepository
import ua.chudo.scanner.data.ShopStockRepository
import ua.chudo.scanner.data.ChudoRepository
import ua.chudo.scanner.data.SiteClient
import ua.chudo.scanner.data.WebViewFetcher
import java.util.concurrent.TimeUnit

/** Ручной DI: всё приложение — один контейнер. */
class ChudoApp : Application() {
    val prefs by lazy { AppPrefs(this) }

    private val okHttp by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .build()
    }

    val repository by lazy {
        ChudoRepository(SiteClient(okHttp, WebViewFetcher(this)), prefs)
    }

    val auth by lazy { AuthRepository(this) }

    /** Только когда Firebase подключён ([AuthRepository.configured]). */
    val shopStock by lazy { ShopStockRepository(FirebaseFirestore.getInstance()) }
    /** Залишки мережі (лише stockAdmin); знімок попереднього файлу — у filesDir. */
    val networkStock by lazy { ua.chudo.scanner.data.NetworkStockRepository(FirebaseFirestore.getInstance(), filesDir) }
    /** Видалення старих stock/{штрихкод} (лише stockAdmin). */
    val legacyCleanup by lazy { ua.chudo.scanner.data.LegacyStockCleanup(ua.chudo.scanner.data.FirestoreLegacyStockStore(FirebaseFirestore.getInstance())) }
    /** Показ залишків мережі: склади й дата — раз на сесію. */
    val networkLookup by lazy { ua.chudo.scanner.data.NetworkStockLookup(ua.chudo.scanner.data.FirestoreStockStore(FirebaseFirestore.getInstance()), filesDir) }
    /** «Термінал»: кошики, передані на касу (sales/{id}). */
    val sales by lazy { ua.chudo.scanner.terminal.SalesRepository(FirebaseFirestore.getInstance()) { com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid } }
    /** Оплата карткою: провайдер магазину (shops/{shopId}.payment); у release — лише справжні. */
    val payments by lazy { ua.chudo.scanner.payment.PaymentService(sales, ua.chudo.scanner.payment.PaymentRegistry.forBuild()) }
    /** Интернет-заказы на самовывоз (пока демо-источник). */
    val orders by lazy { ua.chudo.scanner.data.OrdersRepository() }
    val printing by lazy { ua.chudo.scanner.print.PrintService(this) }

    /** Автооновлення: meta/app (Google Диск), запасне — GitHub Releases. */
    val updates by lazy {
        val meta: suspend () -> ua.chudo.scanner.update.AppMeta? = {
            if (auth.configured) ua.chudo.scanner.update.AppMetaStore(FirebaseFirestore.getInstance()).read() else null
        }
        ua.chudo.scanner.update.UpdateManager(
            this,
            ua.chudo.scanner.update.UpdateSources(ua.chudo.scanner.update.DriveSource(meta), ua.chudo.scanner.update.GitHubSource(okHttp)),
            ua.chudo.scanner.update.ApkDownloader(okHttp.newBuilder().readTimeout(120, TimeUnit.SECONDS).build()),
            meta,
        )
    }

    private var started = 0

    /** Застосунок на екрані: тихе встановлення оновлення — лише коли ні. */
    val inForeground: Boolean get() = started > 0

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(a: android.app.Activity) { started++ }
            override fun onActivityStopped(a: android.app.Activity) { started = (started - 1).coerceAtLeast(0) }
            override fun onActivityCreated(a: android.app.Activity, b: android.os.Bundle?) = Unit
            override fun onActivityResumed(a: android.app.Activity) = Unit
            override fun onActivityPaused(a: android.app.Activity) = Unit
            override fun onActivitySaveInstanceState(a: android.app.Activity, b: android.os.Bundle) = Unit
            override fun onActivityDestroyed(a: android.app.Activity) = Unit
        })
    }
}

val Context.chudoApp: ChudoApp get() = applicationContext as ChudoApp
