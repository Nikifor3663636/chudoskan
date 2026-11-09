package ua.chudo.shared.auth

import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.parameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import ua.chudo.shared.KeyValueStore
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** Сотрудник: к какому магазину привязан. Магазин меняет только администратор. */
data class Employee(
    val uid: String,
    val name: String,
    val phone: String,
    val region: String,
    val shop: String,
    val shopId: String,
    val approved: Boolean,
)

sealed interface AuthState {
    data object NotConfigured : AuthState
    data object Loading : AuthState
    data object SignedOut : AuthState
    data class NoProfile(val phone: String) : AuthState
    data class Pending(val employee: Employee) : AuthState
    data class Ready(val employee: Employee) : AuthState
    data class Failed(val message: String) : AuthState
}

data class NewProfile(val name: String, val region: String, val shop: String)

/** Ошибка Firebase с кодом из ответа REST (INVALID_LOGIN_CREDENTIALS, EMAIL_EXISTS…). */
class AuthException(val code: String) : Exception(code)

fun Throwable.authMessage(): String = when (this) {
    is AuthException -> when {
        code.startsWith("INVALID_LOGIN_CREDENTIALS") || code.startsWith("INVALID_PASSWORD") || code.startsWith("EMAIL_NOT_FOUND") -> "Невірний номер або пароль."
        code.startsWith("EMAIL_EXISTS") -> "Цей номер уже зареєстровано. Увійди з паролем або попроси адміністратора скинути пароль."
        code.startsWith("WEAK_PASSWORD") -> "Пароль закороткий — щонайменше 6 символів."
        code.startsWith("TOO_MANY_ATTEMPTS") -> "Забагато спроб. Зачекай кілька хвилин."
        code.startsWith("USER_DISABLED") -> "Акаунт вимкнено. Звернись до адміністратора."
        code.startsWith("PERMISSION_DENIED") -> "Немає доступу (правила Firestore)."
        else -> "Помилка входу: $code"
    }
    else -> message?.takeIf { it.isNotBlank() }?.let { if ("resolve" in it || "connect" in it.lowercase()) "Немає інтернету." else it } ?: "Помилка"
}

/**
 * Вход по номеру и паролю через Firebase REST (Identity Toolkit + Firestore) — один код для Android и iOS,
 * без нативных SDK. Логин — служебный адрес из номера, как в Android-версии.
 */
@OptIn(ExperimentalTime::class)
class AuthRepository(private val http: HttpClient, private val kv: KeyValueStore) {
    val configured = FirebaseConfig.API_KEY.isNotEmpty()

    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var pollJob: Job? = null

    private val _state = MutableStateFlow<AuthState>(if (configured) AuthState.Loading else AuthState.NotConfigured)
    val state: StateFlow<AuthState> = _state.asStateFlow()

    private val identity = "https://identitytoolkit.googleapis.com/v1/accounts"
    private val firestore get() = "https://firestore.googleapis.com/v1/projects/${FirebaseConfig.PROJECT_ID}/databases/(default)/documents"

    init {
        if (configured) scope.launch { if (kv.get(K_REFRESH) == null) _state.value = AuthState.SignedOut else loadProfile() }
    }

    suspend fun signIn(digits: String, password: String) {
        val r = identityCall("signInWithPassword", login("+380$digits"), password)
        saveSession(r)
        loadProfile()
    }

    suspend fun register(digits: String, password: String, profile: NewProfile) {
        val r = identityCall("signUp", login("+380$digits"), password)
        saveSession(r)
        createProfile(profile)
        loadProfile()
    }

    suspend fun completeProfile(profile: NewProfile) {
        createProfile(profile)
        loadProfile()
    }

    /** Сменить пароль: проверяем текущий новым входом, затем accounts:update. */
    suspend fun changePassword(current: String, new: String) {
        val email = kv.get(K_EMAIL) ?: throw AuthException("NOT_SIGNED_IN")
        saveSession(identityCall("signInWithPassword", email, current))
        val r = post("$identity:update?key=${FirebaseConfig.API_KEY}", buildJsonObject {
            put("idToken", kv.get(K_ID)); put("password", new); put("returnSecureToken", true)
        })
        saveSession(r)
    }

    suspend fun updateName(name: String) {
        val uid = kv.get(K_UID) ?: return
        val r = http.patch("$firestore/users/$uid?updateMask.fieldPaths=name") {
            header("Authorization", "Bearer ${token()}")
            header("Content-Type", "application/json")
            setBody(buildJsonObject { putJsonObject("fields") { putJsonObject("name") { put("stringValue", name.trim()) } } }.toString())
        }
        check(r)
        loadProfile()
    }

    fun signOut() {
        pollJob?.cancel()
        listOf(K_ID, K_REFRESH, K_UID, K_EMAIL, K_EXP).forEach { kv.put(it, null) }
        _state.value = AuthState.SignedOut
    }

    /** Анкета сотрудника; пока заявка ждёт подтверждения — проверяем раз в 15 с. */
    private suspend fun loadProfile() {
        _state.value = try {
            val uid = kv.get(K_UID) ?: return signOut()
            val r = http.get("$firestore/users/$uid") { header("Authorization", "Bearer ${token()}") }
            when {
                r.status.value == 404 -> AuthState.NoProfile(phone())
                r.status.value >= 400 -> AuthState.Failed(errorCode(r.bodyAsText()))
                else -> toEmployee(uid, json.parseToJsonElement(r.bodyAsText()).jsonObject).let {
                    if (it.approved) AuthState.Ready(it) else AuthState.Pending(it)
                }
            }
        } catch (e: AuthException) {
            if (e.code.startsWith("TOKEN") || e.code.startsWith("INVALID_REFRESH") || e.code.startsWith("USER_NOT_FOUND")) {
                signOut(); return
            }
            AuthState.Failed(e.authMessage())
        } catch (e: Exception) {
            AuthState.Failed(e.authMessage())
        }
        if (_state.value is AuthState.Pending && pollJob?.isActive != true) {
            pollJob = scope.launch {
                while (isActive && _state.value is AuthState.Pending) { delay(15_000); loadProfile() }
            }
        }
    }

    private suspend fun createProfile(p: NewProfile) {
        val uid = kv.get(K_UID) ?: throw AuthException("NOT_SIGNED_IN")
        fun str(v: String) = buildJsonObject { put("stringValue", v) }
        val body = buildJsonObject {
            put("writes", buildJsonArray {
                add(buildJsonObject {
                    putJsonObject("update") {
                        put("name", "projects/${FirebaseConfig.PROJECT_ID}/databases/(default)/documents/users/$uid")
                        putJsonObject("fields") {
                            put("name", str(p.name.trim()))
                            put("phone", str(phone()))
                            put("region", str(p.region))
                            put("shop", str(p.shop.trim()))
                            put("shopId", str(shopId(p.region, p.shop)))
                            putJsonObject("approved") { put("booleanValue", false) }
                        }
                    }
                    putJsonObject("currentDocument") { put("exists", false) }
                    put("updateTransforms", buildJsonArray {
                        add(buildJsonObject { put("fieldPath", "createdAt"); put("setToServerValue", "REQUEST_TIME") })
                    })
                })
            })
        }
        val r = http.post("$firestore:commit") {
            header("Authorization", "Bearer ${token()}")
            header("Content-Type", "application/json")
            setBody(body.toString())
        }
        check(r)
    }

    private fun toEmployee(uid: String, doc: JsonObject): Employee {
        val f = doc["fields"]?.jsonObject ?: JsonObject(emptyMap())
        fun s(k: String) = f[k]?.jsonObject?.get("stringValue")?.jsonPrimitive?.contentOrNull.orEmpty()
        return Employee(
            uid = uid,
            name = s("name"),
            phone = s("phone").ifEmpty { phone() },
            region = s("region"),
            shop = s("shop"),
            shopId = s("shopId"),
            approved = f["approved"]?.jsonObject?.get("booleanValue")?.jsonPrimitive?.boolean == true,
        )
    }

    /** Действующий idToken; за минуту до истечения обновляем по refreshToken. */
    private suspend fun token(): String {
        val exp = kv.get(K_EXP)?.toLongOrNull() ?: 0
        if (Clock.System.now().toEpochMilliseconds() < exp - 60_000) kv.get(K_ID)?.let { return it }
        val refresh = kv.get(K_REFRESH) ?: throw AuthException("TOKEN_EXPIRED")
        val r = http.submitForm(
            "https://securetoken.googleapis.com/v1/token?key=${FirebaseConfig.API_KEY}",
            parameters { append("grant_type", "refresh_token"); append("refresh_token", refresh) },
        )
        val text = r.bodyAsText()
        if (r.status.value >= 400) throw AuthException(errorCode(text))
        val o = json.parseToJsonElement(text).jsonObject
        fun v(k: String) = o[k]?.jsonPrimitive?.contentOrNull
        kv.put(K_ID, v("id_token")); kv.put(K_REFRESH, v("refresh_token"))
        kv.put(K_EXP, (Clock.System.now().toEpochMilliseconds() + (v("expires_in")?.toLongOrNull() ?: 3600) * 1000).toString())
        return v("id_token") ?: throw AuthException("TOKEN_EXPIRED")
    }

    private suspend fun identityCall(method: String, email: String, password: String): JsonObject =
        post("$identity:$method?key=${FirebaseConfig.API_KEY}", buildJsonObject {
            put("email", email); put("password", password); put("returnSecureToken", true)
        })

    private suspend fun post(url: String, body: JsonObject): JsonObject {
        val r = http.post(url) { header("Content-Type", "application/json"); setBody(body.toString()) }
        val text = r.bodyAsText()
        if (r.status.value >= 400) throw AuthException(errorCode(text))
        return json.parseToJsonElement(text).jsonObject
    }

    private suspend fun check(r: HttpResponse) {
        if (r.status.value >= 400) throw AuthException(errorCode(r.bodyAsText()))
    }

    private fun saveSession(o: JsonObject) {
        fun v(k: String) = o[k]?.jsonPrimitive?.contentOrNull
        v("idToken")?.let { kv.put(K_ID, it) }
        v("refreshToken")?.let { kv.put(K_REFRESH, it) }
        v("localId")?.let { kv.put(K_UID, it) }
        v("email")?.let { kv.put(K_EMAIL, it) }
        kv.put(K_EXP, (Clock.System.now().toEpochMilliseconds() + (v("expiresIn")?.toLongOrNull() ?: 3600) * 1000).toString())
    }

    private fun errorCode(text: String): String = runCatching {
        val e = json.parseToJsonElement(text).jsonObject["error"]
        (e as? JsonObject)?.let { it["status"]?.jsonPrimitive?.contentOrNull?.takeIf { s -> s == "PERMISSION_DENIED" } ?: it["message"]?.jsonPrimitive?.contentOrNull }
            ?: e?.jsonPrimitive?.contentOrNull
    }.getOrNull() ?: text.take(120)

    private fun phone(): String = kv.get(K_EMAIL)?.substringBefore('@')?.let { "+$it" }.orEmpty()

    companion object {
        private const val K_ID = "auth_id"
        private const val K_REFRESH = "auth_refresh"
        private const val K_UID = "auth_uid"
        private const val K_EMAIL = "auth_email"
        private const val K_EXP = "auth_exp"

        /** +380961234567 → 380961234567@tel.chudoostriv.app (как в Android-версии). */
        fun login(phone: String) = phone.filter(Char::isDigit) + "@tel.chudoostriv.app"

        /** Стабильный id магазина: город + SHA-1 адреса (совпадает с Android). */
        fun shopId(region: String, shop: String): String {
            val norm = shop.trim().split(Regex("\\s+")).joinToString(" ").lowercase()
            return "${region}_" + Sha1.hex(norm.encodeToByteArray()).take(12)
        }
    }
}
