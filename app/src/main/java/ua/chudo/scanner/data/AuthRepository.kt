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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
)

sealed interface AuthState {
    /** В сборке нет google-services.json. */
    data object NotConfigured : AuthState
    data object Loading : AuthState
    data object SignedOut : AuthState
    /** Аккаунт есть, а анкеты сотрудника нет (регистрация прервалась). */
    data class NoProfile(val phone: String) : AuthState
    data class Pending(val employee: Employee) : AuthState
    data class Ready(val employee: Employee) : AuthState
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

    init {
        if (configured) auth.addAuthStateListener { onUser(it.currentUser) }
    }

    private fun onUser(user: FirebaseUser?) {
        profileListener?.remove()
        profileListener = null
        if (user == null) {
            _state.value = AuthState.SignedOut
            return
        }
        _state.value = AuthState.Loading
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

    /** Дописать анкету, если аккаунт уже создан. */
    suspend fun completeProfile(name: String, region: String, shop: String) {
        saveProfile(auth.currentUser ?: error("Не виконано вхід"), name, region, shop)
    }

    fun signOut() = auth.signOut()

    /** Имя сотрудник меняет сам; магазин и подтверждение — только администратор. */
    suspend fun updateName(name: String) {
        val uid = auth.currentUser?.uid ?: error("Не виконано вхід")
        db.collection(USERS).document(uid).update("name", name.trim()).await()
    }

    private suspend fun saveProfile(user: FirebaseUser, name: String, region: String, shop: String) {
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
