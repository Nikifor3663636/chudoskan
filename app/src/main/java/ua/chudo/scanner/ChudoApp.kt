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
    val offlineStock by lazy { ua.chudo.scanner.data.OfflineStock(this) }

    private val okHttp by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .build()
    }

    val promoSheet by lazy { ua.chudo.scanner.data.PromoSheet(okHttp) }

    val repository by lazy {
        ChudoRepository(SiteClient(okHttp, WebViewFetcher(this)), prefs)
    }

    val auth by lazy { AuthRepository(this) }

    /** Только когда Firebase подключён ([AuthRepository.configured]). */
    val shopStock by lazy { ShopStockRepository(FirebaseFirestore.getInstance()) }
    /** Залишки мережі (лише stockAdmin); знімок попереднього файлу — у filesDir. */
    val networkStock by lazy { ua.chudo.scanner.data.NetworkStockRepository(FirebaseFirestore.getInstance(), filesDir) }
    /** Показ залишків мережі: склади й дата — раз на сесію. */
    val networkLookup by lazy { ua.chudo.scanner.data.NetworkStockLookup(FirebaseFirestore.getInstance()) }
    /** Интернет-заказы на самовывоз (пока демо-источник). */
    val orders by lazy { ua.chudo.scanner.data.OrdersRepository() }
    val printing by lazy { ua.chudo.scanner.print.PrintService(this) }
    /** Оновлення застосунку з GitHub Releases. */
    val updater by lazy { ua.chudo.scanner.data.AppUpdater(this, okHttp) }
    /** Кошик продавця: скан товарів покупця → сума (далі — оплата через ПРРО). */
    val cart by lazy { ua.chudo.scanner.data.CartRepository(this, repository, networkLookup) }
}

val Context.chudoApp: ChudoApp get() = applicationContext as ChudoApp
