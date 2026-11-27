package ua.chudo.scanner.data

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import ua.chudo.scanner.BuildConfig

/**
 * Перемикач сховища даних: `firestore` (як було) або `supabase` (Postgres). Вхід — завжди Firebase Auth.
 * Задається під час збірки: `-Pbackend=supabase` (див. app/build.gradle.kts).
 */
object Backend {
    val isSupabase: Boolean get() = BuildConfig.BACKEND == "supabase"
}

/**
 * Supabase з Firebase ID token як accessToken (Third-Party Auth): плагіна Auth немає,
 * усі права — RLS-політики (supabase/migrations).
 */
object Supabase {
    val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    val client: SupabaseClient by lazy {
        createSupabaseClient(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_KEY) {
            accessToken = { FirebaseAuth.getInstance().currentUser?.getIdToken(false)?.await()?.token }
            install(Postgrest) { serializer = io.github.jan.supabase.serializer.KotlinXSerializer(json) }
            install(Realtime)
            if (DeviceGateway.enabled) {
                val supabaseHost = java.net.URI(BuildConfig.SUPABASE_URL).host
                httpEngine = OkHttp.create {
                    config { followRedirects(false); followSslRedirects(false) }
                    addInterceptor { chain ->
                        val request = chain.request()
                        val session = if (request.url.isHttps && request.url.host == supabaseHost) {
                            FirebaseAuth.getInstance().currentUser?.uid?.let(DeviceGateway::sessionFor)
                        } else null
                        val authenticated = session?.let {
                            request.newBuilder().header("X-Device-Session", it).build()
                        } ?: request
                        chain.proceed(authenticated)
                    }
                }
            }
        }
    }

    /**
     * Supabase приймає токен лише з claim role = "authenticated". Немає — функція ensure-role ставить
     * (інших claims не додає), і токен оновлюється. Повертає true, якщо токен оновлено.
     */
    suspend fun ensureRole(user: FirebaseUser, http: OkHttpClient = OkHttpClient()): Boolean {
        val t = user.getIdToken(false).await()
        if (t.claims["role"] == "authenticated") return false
        withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url("${BuildConfig.SUPABASE_URL}/functions/v1/ensure-role")
                .header("Authorization", "Bearer ${t.token}")
                .post(ByteArray(0).toRequestBody())
                .build()
            http.newCall(req).execute().use { r -> if (!r.isSuccessful) error("ensure-role ${r.code}") }
        }
        user.getIdToken(true).await()
        return true
    }
}
