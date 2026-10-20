package ua.chudo.scanner.data

import android.app.Activity
import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseException
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.PhoneAuthCredential
import com.google.firebase.auth.PhoneAuthOptions
import com.google.firebase.auth.PhoneAuthProvider
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

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

/** Анкета, которую сохраняем после первого входа по телефону. */
data class NewProfile(val name: String, val region: String, val shop: String)

sealed interface PhoneEvent {
    data class CodeSent(val verificationId: String, val token: PhoneAuthProvider.ForceResendingToken) : PhoneEvent
    /** Код подставлен автоматически (или номер подтверждён без SMS). */
    data class Verified(val credential: PhoneAuthCredential) : PhoneEvent
    data class Failed(val error: Exception) : PhoneEvent
}

/**
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

    /** Попросить задать пароль: вход был по SMS («Забув пароль») или пароля ещё нет. */
    private val _askPassword = MutableStateFlow(false)
    val askPassword: StateFlow<Boolean> = _askPassword.asStateFlow()

    init {
        if (configured) auth.addAuthStateListener { onUser(it.currentUser) }
    }

    private fun onUser(user: FirebaseUser?) {
        profileListener?.remove()
        profileListener = null
        if (user == null) {
            _askPassword.value = false
            _state.value = AuthState.SignedOut
            return
        }
        _state.value = AuthState.Loading
        if (!hasPassword()) _askPassword.value = true
        // Слушаем анкету: как только администратор поставит approved = true, вход откроется сам.
        profileListener = db.collection(USERS).document(user.uid).addSnapshotListener { snap, err ->
            if (auth.currentUser?.uid != user.uid) return@addSnapshotListener
            _state.value = when {
                err != null -> AuthState.Failed(err.message ?: err.javaClass.simpleName)
                snap == null || !snap.exists() -> AuthState.NoProfile(user.phoneNumber.orEmpty())
                else -> snap.toEmployee(user).let { if (it.approved) AuthState.Ready(it) else AuthState.Pending(it) }
            }
        }
    }

    /** Отправить SMS на номер +380XXXXXXXXX. [resend] — токен для повторной отправки. */
    fun sendCode(
        activity: Activity,
        phone: String,
        resend: PhoneAuthProvider.ForceResendingToken?,
        onEvent: (PhoneEvent) -> Unit,
    ) {
        val builder = PhoneAuthOptions.newBuilder(auth)
            .setPhoneNumber(phone)
            .setTimeout(60L, TimeUnit.SECONDS)
            .setActivity(activity)
            .setCallbacks(object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
                override fun onVerificationCompleted(credential: PhoneAuthCredential) = onEvent(PhoneEvent.Verified(credential))
                override fun onVerificationFailed(e: FirebaseException) = onEvent(PhoneEvent.Failed(e))
                override fun onCodeSent(id: String, token: PhoneAuthProvider.ForceResendingToken) =
                    onEvent(PhoneEvent.CodeSent(id, token))
            })
        if (resend != null) builder.setForceResendingToken(resend)
        PhoneAuthProvider.verifyPhoneNumber(builder.build())
    }

    fun credential(verificationId: String, code: String): PhoneAuthCredential =
        PhoneAuthProvider.getCredential(verificationId, code.trim())

    /**
     * Вход по SMS: регистрация ([profile] + [password]) или «Забув пароль».
     * После входа без пароля попросим задать новый.
     */
    suspend fun signInWithPhone(credential: PhoneAuthCredential, profile: NewProfile?, password: String?) {
        val user = auth.signInWithCredential(credential).await().user ?: error("Firebase не повернув користувача")
        if (profile != null && !db.collection(USERS).document(user.uid).get().await().exists()) {
            saveProfile(user, profile.name, profile.region, profile.shop)
        }
        if (password != null) setPassword(password) else _askPassword.value = true
    }

    /** Вход по номеру и паролю. [digits] — 9 цифр после +380. */
    suspend fun signInWithPassword(digits: String, password: String) {
        auth.signInWithEmailAndPassword(loginFor("+380$digits"), password).await()
    }

    fun hasPassword(): Boolean = auth.currentUser?.providerData?.any { it.providerId == EmailAuthProvider.PROVIDER_ID } == true

    /**
     * Задать пароль. Сразу после входа по SMS старый пароль не нужен;
     * иначе Firebase требует свежий вход — тогда сначала [reauthenticate].
     */
    suspend fun setPassword(password: String) {
        val user = auth.currentUser ?: error("Не виконано вхід")
        val phone = user.phoneNumber?.takeIf { it.isNotEmpty() } ?: error("До акаунта не прив'язано телефон")
        if (hasPassword()) {
            user.updatePassword(password).await()
        } else {
            user.linkWithCredential(EmailAuthProvider.getCredential(loginFor(phone), password)).await()
        }
        _askPassword.value = false
    }

    /** Подтвердить текущий пароль перед сменой. */
    suspend fun reauthenticate(currentPassword: String) {
        val user = auth.currentUser ?: error("Не виконано вхід")
        val login = user.email ?: loginFor(user.phoneNumber.orEmpty())
        user.reauthenticate(EmailAuthProvider.getCredential(login, currentPassword)).await()
    }

    fun skipPassword() { _askPassword.value = false }

    /** Дописать анкету, если аккаунт уже создан. */
    suspend fun completeProfile(name: String, region: String, shop: String) {
        saveProfile(auth.currentUser ?: error("Не выполнен вход"), name, region, shop)
    }

    fun signOut() = auth.signOut()

    /** Имя сотрудник меняет сам; магазин и подтверждение — только администратор. */
    suspend fun updateName(name: String) {
        val uid = auth.currentUser?.uid ?: error("Не выполнен вход")
        db.collection(USERS).document(uid).update("name", name.trim()).await()
    }

    private suspend fun saveProfile(user: FirebaseUser, name: String, region: String, shop: String) {
        db.collection(USERS).document(user.uid).set(
            mapOf(
                "name" to name.trim(),
                "phone" to (user.phoneNumber ?: ""),
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
        phone = getString("phone")?.takeIf { it.isNotEmpty() } ?: user.phoneNumber.orEmpty(),
        region = getString("region").orEmpty(),
        shop = getString("shop").orEmpty(),
        shopId = getString("shopId").orEmpty(),
        approved = getBoolean("approved") == true,
    )

    companion object {
        const val USERS = "users"

        /**
         * Firebase умеет пароль только в паре с email, поэтому логин — служебный адрес из номера
         * (+380961234567 → 380961234567@tel.chudoostriv.app). Пользователь его не видит; писем на него нет.
         */
        fun loginFor(phone: String): String = phone.filter(Char::isDigit) + "@tel.chudoostriv.app"

        /** Стабильный id магазина: город + хеш адреса (адрес бывает длинным и с «/»). */
        fun shopId(region: String, shop: String): String {
            val norm = shop.trim().split(Regex("\\s+")).joinToString(" ").lowercase()
            val hash = MessageDigest.getInstance("SHA-1").digest(norm.toByteArray())
                .joinToString("") { "%02x".format(it) }.take(12)
            return "${region}_$hash"
        }
    }
}
