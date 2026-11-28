package ua.chudo.scanner.data

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.security.MessageDigest

/** Сотрудник: к какому магазину привязан. Магазин меняет только администратор в консоли Firebase. */
data class Employee(
    val uid: String,
    val name: String,
    val phone: String,
    val region: String,
    /** Адрес магазина как на сайте — по нему находим строку наличия. */
    val shop: String,
    val shopId: String,
    /** Новых сотрудников подтверждает администратор (поле `approved` в users/{uid}). */
    val approved: Boolean,
    /** Администратор магазина (поле `admin` в users/{uid}, ставится только в консоли): видит «Профіль → Принтери». */
    val admin: Boolean = false,
    /**
     * Завантажує залишки мережі (`stockAdmin` або `superAdmin` у users/{uid}, лише в консолі Firebase):
     * бачить «Профіль → Залишки мережі». Те саме перевіряють правила (isStockAdmin).
     */
    val stockAdmin: Boolean = false,
    /** Адмін мережі (`superAdmin` у users/{uid}, лише в консолі): «Профіль → Адміністрування мережі». */
    val superAdmin: Boolean = false,
    /**
     * Тимчасово «працює як у магазині ЧО NN» (лише superAdmin, лише на цю сесію): shopId/shop/region —
     * того магазину; [homeShopId] — свій магазин з анкети. Анкету не змінює.
     */
    val actingCode: String? = null,
    val homeShopId: String = shopId,
    /** Адмін скинув пароль — після входу спершу «Придумайте новий пароль» (users.must_change_password). */
    val mustChangePassword: Boolean = false,
    /** Посада в магазині (users.job_title), ставить адмін магазину. */
    val jobTitle: JobTitle? = null,
) {
    /** Налаштовує принтери магазину: адмін магазину або адмін мережі. */
    val managesShop: Boolean get() = admin || superAdmin

    /** «Працювати як у магазині»: той самий співробітник, інший поточний магазин. */
    fun actingIn(shopId: String, shop: String, region: String, code: String?): Employee =
        copy(shopId = shopId, shop = shop, region = region, actingCode = code ?: shopId, homeShopId = homeShopId)
}

sealed interface AuthState {
    /** В сборке нет google-services.json. */
    data object NotConfigured : AuthState
    data object Loading : AuthState
    data object SignedOut : AuthState
    /** Аккаунт есть, а анкеты сотрудника нет (регистрация прервалась). */
    data class NoProfile(val phone: String) : AuthState
    data class Pending(val employee: Employee) : AuthState
    data class Ready(val employee: Employee) : AuthState
    data object DevicePending : AuthState
    data object DeviceBlocked : AuthState
    data class Failed(val message: String) : AuthState
}

/** Анкета сотрудника при регистрации. */
data class NewProfile(val name: String, val region: String, val shop: String)

/**
 * Вход по номеру телефона и паролю, без SMS (SMS в Firebase — только на платном тарифе).
 * Номер не подтверждается кодом: доступ всё равно открывает администратор (`approved`).
 *
 * Firestore:
 * - `users/{uid}` — анкета сотрудника ([Employee]);
 * - `shops/{shopId}` — когда и кем загружены остатки;
 * - `shops/{shopId}/stock/{key}` — строка остатков (см. [ShopStockRepository]).
 * Доступ к магазину — только у подтверждённых сотрудников этого магазина (firestore.rules).
 */
class AuthRepository(context: Context) {
    val configured: Boolean = FirebaseApp.getApps(context).isNotEmpty()

    private val auth by lazy { FirebaseAuth.getInstance() }
    private val db by lazy { FirebaseFirestore.getInstance() }

    private val _state = MutableStateFlow<AuthState>(if (configured) AuthState.Loading else AuthState.NotConfigured)
    val state: StateFlow<AuthState> = _state.asStateFlow()

    private var profileListener: ListenerRegistration? = null

    /** Supabase: завантаження анкети й підписка Realtime на неї. */
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate)
    private var profileJob: kotlinx.coroutines.Job? = null

    init {
        if (configured) auth.addAuthStateListener { onUser(it.currentUser) }
    }

    private fun onUser(user: FirebaseUser?) {
        profileListener?.remove()
        profileListener = null
        profileJob?.cancel()
        profileJob = null
        DeviceGateway.clear()
        if (user == null) {
            _state.value = AuthState.SignedOut
            return
        }
        _state.value = AuthState.Loading
        if (Backend.isSupabase) { profileJob = scope.launch { watchSupabase(user) }; return }
        // Слушаем анкету: как только администратор поставит approved = true, вход откроется сам.
        profileListener = db.collection(USERS).document(user.uid).addSnapshotListener { snap, err ->
            if (auth.currentUser?.uid != user.uid) return@addSnapshotListener
            _state.value = when {
                err != null -> AuthState.Failed(err.message ?: err.javaClass.simpleName)
                snap == null || !snap.exists() -> AuthState.NoProfile(phoneOf(user))
                else -> snap.toEmployee(user).let { if (it.approved) AuthState.Ready(it) else AuthState.Pending(it) }
            }
        }
    }

    /**
     * Supabase: роль у токені (ensure-role) → анкета; далі — Realtime: як тільки адмін поставить
     * approved = true, вхід відкриється сам (як підписка Firestore).
     */
    private suspend fun watchSupabase(user: FirebaseUser) {
        suspend fun read() {
            val row = SupabaseUsers.profile(user.uid)
            if (auth.currentUser?.uid != user.uid) return
            _state.value = when {
                row == null -> AuthState.NoProfile(phoneOf(user))
                else -> row.employee(phoneOf(user)).let { if (it.approved) AuthState.Ready(it) else AuthState.Pending(it) }
            }
        }
        try {
            Supabase.ensureRole(user)
            if (DeviceGateway.enabled) {
                watchDeviceAccess(user)
            } else {
                read()
                SupabaseUsers.changes("users", "uid=eq.${user.uid}").collect { runCatching { read() } }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (auth.currentUser?.uid == user.uid) _state.value = AuthState.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    private suspend fun watchDeviceAccess(user: FirebaseUser) {
        while (auth.currentUser?.uid == user.uid) {
            val next = try {
                val employee = SupabaseUsers.deviceLoginProfile()?.employee(phoneOf(user))
                when {
                    employee == null -> AuthState.NoProfile(phoneOf(user))
                    !employee.approved -> AuthState.Pending(employee)
                    else -> when (DeviceGateway.check(user)) {
                        DeviceAccess.Pending -> AuthState.DevicePending
                        DeviceAccess.Blocked -> AuthState.DeviceBlocked
                        DeviceAccess.Approved -> AuthState.Ready(employee)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                AuthState.Failed("Не вдалося перевірити доступ. Повторюємо автоматично.")
            }
            if (auth.currentUser?.uid != user.uid) return
            if (next !is AuthState.Ready) DeviceGateway.clear()
            _state.value = next
            delay(30_000)
        }
    }

    /** Вход по номеру и паролю. [digits] — 9 цифр после +380. */
    suspend fun signIn(digits: String, password: String) {
        auth.signInWithEmailAndPassword(loginFor("+380$digits"), password).await()
    }

    /** Регистрация: аккаунт «номер + пароль» и анкета (неподтверждённая). */
    suspend fun register(digits: String, password: String, profile: NewProfile) {
        val user = auth.createUserWithEmailAndPassword(loginFor("+380$digits"), password).await().user
            ?: error("Firebase не повернув користувача")
        saveProfile(user, profile.name, profile.region, profile.shop)
    }

    /** Сменить пароль: Firebase требует подтвердить текущий. */
    suspend fun changePassword(current: String, new: String) {
        val user = auth.currentUser ?: error("Не виконано вхід")
        val login = user.email ?: loginFor(phoneOf(user))
        user.reauthenticate(EmailAuthProvider.getCredential(login, current)).await()
        user.updatePassword(new).await()
    }

    /**
     * Новий пароль замість тимчасового: вхід щойно був, тож Firebase не просить поточний.
     * Потім знімаємо позначку в базі й одразу пускаємо в застосунок.
     */
    suspend fun replaceTemporaryPassword(new: String) {
        val user = auth.currentUser ?: error("Не виконано вхід")
        user.updatePassword(new).await()
        if (Backend.isSupabase) SupabaseUsers.passwordChanged()
        (_state.value as? AuthState.Ready)?.let { _state.value = AuthState.Ready(it.employee.copy(mustChangePassword = false)) }
    }

    /** Дописать анкету, если аккаунт уже создан. */
    suspend fun completeProfile(name: String, region: String, shop: String) {
        saveProfile(auth.currentUser ?: error("Не виконано вхід"), name, region, shop)
    }

    fun signOut() = auth.signOut()

    /** Имя сотрудник меняет сам; магазин и подтверждение — только администратор. */
    suspend fun updateName(name: String) {
        val uid = auth.currentUser?.uid ?: error("Не виконано вхід")
        if (Backend.isSupabase) { SupabaseUsers.updateName(uid, name.trim()); return }
        db.collection(USERS).document(uid).update("name", name.trim()).await()
    }

    private suspend fun saveProfile(user: FirebaseUser, name: String, region: String, shop: String) {
        if (Backend.isSupabase) {
            Supabase.ensureRole(user)
            SupabaseUsers.saveProfile(user.uid, name.trim(), phoneOf(user), region, shop.trim(), shopId(region, shop))
            return
        }
        db.collection(USERS).document(user.uid).set(
            mapOf(
                "name" to name.trim(),
                "phone" to phoneOf(user),
                "region" to region,
                "shop" to shop.trim(),
                "shopId" to shopId(region, shop),
                "approved" to false,
                "createdAt" to FieldValue.serverTimestamp(),
            ),
        ).await()
    }

    private fun DocumentSnapshot.toEmployee(user: FirebaseUser) = Employee(
        uid = user.uid,
        name = getString("name").orEmpty(),
        phone = getString("phone")?.takeIf { it.isNotEmpty() } ?: phoneOf(user),
        region = getString("region").orEmpty(),
        shop = getString("shop").orEmpty(),
        shopId = getString("shopId").orEmpty(),
        approved = getBoolean("approved") == true,
        admin = getBoolean("admin") == true,
        stockAdmin = getBoolean("approved") == true && (getBoolean("stockAdmin") == true || getBoolean("superAdmin") == true),
        superAdmin = getBoolean("approved") == true && getBoolean("superAdmin") == true,
    )

    companion object {
        const val USERS = "users"
        private const val LOGIN_DOMAIN = "@tel.chudoostriv.app"

        /**
         * Firebase умеет пароль только в паре с email, поэтому логин — служебный адрес из номера
         * (+380961234567 → 380961234567@tel.chudoostriv.app). Пользователь его не видит; писем на него нет.
         */
        fun loginFor(phone: String): String = phone.filter(Char::isDigit) + LOGIN_DOMAIN

        /** Номер из служебного логина (или привязанный телефон у старых аккаунтов). */
        fun phoneOf(user: FirebaseUser): String =
            user.email?.takeIf { it.endsWith(LOGIN_DOMAIN) }?.let { "+" + it.removeSuffix(LOGIN_DOMAIN) }
                ?: user.phoneNumber.orEmpty()

        /** Стабильный id магазина: город + хеш адреса (адрес бывает длинным и с «/»). */
        fun shopId(region: String, shop: String): String {
            val norm = shop.trim().split(Regex("\\s+")).joinToString(" ").lowercase()
            val hash = MessageDigest.getInstance("SHA-1").digest(norm.toByteArray())
                .joinToString("") { "%02x".format(it) }.take(12)
            return "${region}_$hash"
        }
    }
}
