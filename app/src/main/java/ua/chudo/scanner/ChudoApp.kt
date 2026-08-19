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
            .readTimeout(45, TimeUnit.SECONDS) // фото в ChatGPT отвечает дольше сайта
            .build()
    }

    /** Снимок из камеры для экрана результатов (в навигацию байты не передаём). */
    @Volatile var pendingPhoto: ByteArray? = null

    val repository by lazy {
        ChudoRepository(SiteClient(okHttp, WebViewFetcher(this)), prefs)
    }

    val auth by lazy { AuthRepository(this) }

    /** Только когда Firebase подключён ([AuthRepository.configured]). */
    val shopStock by lazy { ShopStockRepository(FirebaseFirestore.getInstance()) }
}

val Context.chudoApp: ChudoApp get() = applicationContext as ChudoApp
