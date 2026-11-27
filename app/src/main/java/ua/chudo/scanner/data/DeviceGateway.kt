package ua.chudo.scanner.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import ua.chudo.scanner.BuildConfig
import java.nio.ByteBuffer
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.TimeUnit
import java.util.UUID

internal enum class DeviceAccess { Approved, Pending, Blocked }

internal object DeviceGateway {
    val enabled: Boolean get() = BuildConfig.DEVICE_GATEWAY_URL.isNotEmpty()

    private data class Session(val uid: String, val id: String)
    @Volatile private var session: Session? = null

    fun sessionFor(uid: String): String? = session?.takeIf { it.uid == uid }?.id

    fun clear() { session = null }

    private const val KEYSTORE = "AndroidKeyStore"
    private const val PURPOSE: Byte = 1
    private const val ENCODING = Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
    private val domain = "chudoscan/device-proof/v1\u0000".encodeToByteArray()
    private val json = "application/json".toMediaType()
    private val http = OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false)
        // The first request after Render Free scales to zero may wait for a cold start.
        .readTimeout(75, TimeUnit.SECONDS)
        .callTimeout(75, TimeUnit.SECONDS)
        .build()

    suspend fun check(user: FirebaseUser): DeviceAccess {
        val token = user.getIdToken(false).await().token ?: error("Відсутній токен входу")
        return withContext(Dispatchers.IO) {
            val alias = "chudoscan.device.v1." + encode(MessageDigest.getInstance("SHA-256").digest(user.uid.encodeToByteArray()))
            val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            val key = store.getCertificate(alias)?.publicKey as? ECPublicKey
            val begin = post(token, "begin", JSONObject().apply {
                key?.let { put("public_key", encode(sec1(it))) }
            })
            val access = when (begin.getString("status")) {
                "pending", "another_pending" -> DeviceAccess.Pending
                "blocked" -> DeviceAccess.Blocked
                "enroll" -> enroll(token, store, alias, begin)
                "prove" -> prove(token, user.uid, store, alias, begin)
                else -> error("Невідома відповідь сервера пристроїв")
            }
            if (access != DeviceAccess.Approved && session?.uid == user.uid) clear()
            access
        }
    }

    private fun enroll(token: String, store: KeyStore, alias: String, response: JSONObject): DeviceAccess {
        val challenge = nonce(response)
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE).apply {
            initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setAttestationChallenge(challenge)
                .build())
        }.generateKeyPair()
        val chain = checkNotNull(store.getCertificateChain(alias))
        val submitted = post(token, "enroll", JSONObject()
            .put("nonce", encode(challenge))
            .put("certificates", JSONArray(chain.map { encode(it.encoded) })))
        return when (submitted.getString("status")) {
            "pending", "another_pending" -> DeviceAccess.Pending
            "blocked" -> DeviceAccess.Blocked
            else -> error("Невідома відповідь на заявку пристрою")
        }
    }

    private fun prove(token: String, uid: String, store: KeyStore, alias: String, response: JSONObject): DeviceAccess {
        val nonce = nonce(response)
        val account = uid.encodeToByteArray()
        require(account.size in 1..128)
        val payload = ByteBuffer.allocate(domain.size + account.size + 42)
            .put(domain).put(PURPOSE).put(account.size.toByte()).put(account)
            .putLong(response.getLong("expires_at")).put(nonce).array()
        val proof = Signature.getInstance("SHA256withECDSA").run {
            initSign(store.getKey(alias, null) as java.security.PrivateKey)
            update(payload)
            sign()
        }
        val verified = post(token, "prove", JSONObject()
            .put("nonce", encode(nonce)).put("signature", encode(proof)))
        return when (verified.getString("status")) {
            "approved" -> {
                val id = UUID.fromString(verified.getString("session")).toString()
                session = Session(uid, id)
                DeviceAccess.Approved
            }
            "pending", "another_pending" -> DeviceAccess.Pending
            "blocked" -> DeviceAccess.Blocked
            else -> error("Сервер не підтвердив вхід пристрою")
        }
    }

    private fun post(token: String, path: String, data: JSONObject): JSONObject {
        val url = BuildConfig.DEVICE_GATEWAY_URL.toHttpUrl()
        require(url.isHttps && url.username.isEmpty() && url.password.isEmpty())
        val request = Request.Builder()
            .url(url.newBuilder().addPathSegments("v1/android/$path").build())
            .header("Authorization", "Bearer $token")
            .post(data.toString().toRequestBody(json))
            .build()
        return http.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Сервер пристроїв: HTTP ${response.code}" }
            JSONObject(checkNotNull(response.body).string())
        }
    }

    private fun nonce(response: JSONObject): ByteArray =
        Base64.decode(response.getString("nonce"), ENCODING).also { require(it.size == 32) }

    private fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, ENCODING)

    private fun sec1(key: ECPublicKey): ByteArray {
        fun coordinate(value: java.math.BigInteger): ByteArray {
            val bytes = value.toByteArray()
            val unsigned = if (bytes.size == 33 && bytes[0] == 0.toByte()) bytes.copyOfRange(1, 33) else bytes
            require(unsigned.size <= 32)
            return ByteArray(32 - unsigned.size) + unsigned
        }
        return byteArrayOf(4) + coordinate(key.w.affineX) + coordinate(key.w.affineY)
    }
}
