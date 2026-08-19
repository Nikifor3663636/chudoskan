package ua.chudo.scanner.data

import android.app.Activity
import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseException
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
    val email: String,
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
    data class NoProfile(val email: String) : AuthState
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
                snap == null || !snap.exists() -> AuthState.NoProfile(user.email?.takeIf { it.isNotEmpty() } ?: user.phoneNumber.orEmpty())
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

    /** Вход по телефону. При регистрации ([profile] != null) создаёт анкету, если её ещё нет. */
    suspend fun signInWithPhone(credential: PhoneAuthCredential, profile: NewProfile?) {
        val user = auth.signInWithCredential(credential).await().user ?: error("Firebase не вернул пользователя")
        if (profile != null && !db.collection(USERS).document(user.uid).get().await().exists()) {
            saveProfile(user, profile.name, profile.region, profile.shop)
        }
    }

    /** Старый аккаунт с email: вход есть, телефон не привязан. */
    fun needsPhoneLink(): Boolean = auth.currentUser?.phoneNumber.isNullOrEmpty() && auth.currentUser != null

    /** Привязать телефон к текущему аккаунту — uid не меняется. */
    suspend fun linkPhone(credential: PhoneAuthCredential) {
        val user = auth.currentUser ?: error("Не выполнен вход")
        val linked = user.linkWithCredential(credential).await().user ?: user
        linked.getIdToken(true).await() // правила проверяют phone_number из свежего токена
        val doc = db.collection(USERS).document(linked.uid)
        if (doc.get().await().exists()) doc.update("phone", linked.phoneNumber.orEmpty()).await()
    }

    suspend fun signIn(email: String, password: String) {
        auth.signInWithEmailAndPassword(email.trim(), password).await()
    }

    /** Дописать анкету, если аккаунт уже создан. */
    suspend fun completeProfile(name: String, region: String, shop: String) {
        saveProfile(auth.currentUser ?: error("Не выполнен вход"), name, region, shop)
    }

    suspend fun resetPassword(email: String) {
        auth.sendPasswordResetEmail(email.trim()).await()
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
                "email" to (user.email ?: ""),
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
        email = getString("email") ?: user.email.orEmpty(),
        phone = getString("phone")?.takeIf { it.isNotEmpty() } ?: user.phoneNumber.orEmpty(),
        region = getString("region").orEmpty(),
        shop = getString("shop").orEmpty(),
        shopId = getString("shopId").orEmpty(),
        approved = getBoolean("approved") == true,
    )

    companion object {
        const val USERS = "users"

        /** Стабильный id магазина: город + хеш адреса (адрес бывает длинным и с «/»). */
        fun shopId(region: String, shop: String): String {
            val norm = shop.trim().split(Regex("\\s+")).joinToString(" ").lowercase()
            val hash = MessageDigest.getInstance("SHA-1").digest(norm.toByteArray())
                .joinToString("") { "%02x".format(it) }.take(12)
            return "${region}_$hash"
        }
    }
}
