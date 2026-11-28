package ua.chudo.scanner.data

import com.google.firebase.auth.FirebaseAuth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import ua.chudo.scanner.BuildConfig

/** Відповідь `request_password_reset` (supabase/migrations/…_password_requests.sql). */
enum class ResetRequestResult { OK, TOO_SOON, TOO_MANY, BAD_PHONE, BAD_SHOP;
    companion object {
        fun parse(raw: String): ResetRequestResult = when (raw.trim().trim('"')) {
            "ok" -> OK
            "too_soon" -> TOO_SOON
            "too_many" -> TOO_MANY
            "bad_phone" -> BAD_PHONE
            "bad_shop" -> BAD_SHOP
            else -> error("Невідома відповідь сервера: $raw")
        }
    }
}

/** Повідомлення під кнопкою «Надіслати запит»; null — запит прийнято. */
fun ResetRequestResult.problem(): String? = when (this) {
    ResetRequestResult.OK -> null
    ResetRequestResult.TOO_SOON -> "Запит з цього номера вже надіслано. Повторити можна за 10 хвилин."
    ResetRequestResult.TOO_MANY -> "Забагато запитів у цей магазин. Спробуй за годину або звернись до адміна особисто."
    ResetRequestResult.BAD_PHONE -> "Введи номер повністю: +380 XX XXX XX XX."
    ResetRequestResult.BAD_SHOP -> "Цей магазин ще не зареєстрований у застосунку. Обери свій магазин."
}

/** Номер у форматі бази: 9 цифр після +380 → «+380XXXXXXXXX», інакше null. */
fun resetPhone(digits: String): String? = digits.filter(Char::isDigit).takeIf { it.length == 9 }?.let { "+380$it" }

data class PasswordRequest(
    val id: String,
    val phone: String,
    val createdAt: java.util.Date?,
    /** Співробітник з тим самим номером саме в цьому магазині; null — такого немає (скинути не вийде). */
    val uid: String?,
    val name: String?,
)

/** Чому не вдалося скинути — для адміна. */
class ResetFailed(message: String) : Exception(message)

/** Помилка функції reset-password → зрозумілий текст. */
fun resetError(code: Int, error: String?): String = when {
    error == "no_employee" -> "Співробітника з цим номером у вашому магазині немає. Відхиліть запит."
    error == "closed" -> "Запит уже закрито."
    code == 401 || code == 403 || error == "forbidden" -> "Немає прав скинути цей пароль."
    else -> "Не вдалося скинути пароль ($code). Спробуйте ще раз."
}

/**
 * Запити на скидання пароля (лише Supabase). Створює анонім — через RPC з обмеженнями;
 * читає й закриває адмін магазину; сам пароль задає функція reset-password.
 */
object PasswordRequests {
    private val db get() = Supabase.client

    suspend fun send(digits: String, shopId: String): ResetRequestResult {
        val phone = resetPhone(digits) ?: return ResetRequestResult.BAD_PHONE
        if (shopId.isBlank()) return ResetRequestResult.BAD_SHOP
        val r = db.postgrest.rpc("request_password_reset", buildJsonObject { put("p_phone", phone); put("p_shop", shopId) })
        return ResetRequestResult.parse(r.data)
    }

    @Serializable
    private data class Row(
        val id: String,
        val phone: String,
        @SerialName("created_at") val createdAt: String? = null,
        val uid: String? = null,
        val name: String? = null,
    )

    suspend fun pending(shopId: String): List<PasswordRequest> =
        db.postgrest.rpc("password_requests_for", buildJsonObject { put("p_shop", shopId) }).decodeList<Row>()
            .map { PasswordRequest(it.id, it.phone, parseTime(it.createdAt), it.uid, it.name?.ifBlank { null }) }

    suspend fun reject(id: String, adminUid: String) {
        db.from("password_requests").update(buildJsonObject {
            put("status", "rejected"); put("done_by", adminUid); put("done_at", java.time.OffsetDateTime.now().toString())
        }) { filter { eq("id", id) } }
    }

    /** Тимчасовий пароль — повертається один раз, ніде не зберігається. */
    suspend fun reset(id: String, http: OkHttpClient = OkHttpClient()): String {
        val user = FirebaseAuth.getInstance().currentUser ?: throw ResetFailed("Увійдіть знову.")
        val token = user.getIdToken(false).await().token ?: throw ResetFailed("Увійдіть знову.")
        val session = if (DeviceGateway.enabled) DeviceGateway.sessionFor(user.uid) else null
        return withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url("${BuildConfig.SUPABASE_URL}/functions/v1/reset-password")
                .header("Authorization", "Bearer $token")
                .apply { session?.let { header("X-Device-Session", it) } }
                .post(buildJsonObject { put("request_id", id) }.toString().toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(req).execute().use { r ->
                val body = r.body?.string().orEmpty()
                val json = runCatching { Supabase.json.parseToJsonElement(body).jsonObject }.getOrNull()
                if (!r.isSuccessful) throw ResetFailed(resetError(r.code, json?.get("error")?.jsonPrimitive?.contentOrNull))
                (json?.get("password") as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotEmpty)
                    ?: throw ResetFailed(resetError(r.code, null))
            }
        }
    }
}
