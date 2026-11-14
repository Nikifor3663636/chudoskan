package ua.chudo.scanner.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import ua.chudo.scanner.ui.layout.Box
import ua.chudo.scanner.ui.layout.MapLabel
import ua.chudo.scanner.ui.layout.ShopLayout

/** Уточнение сотрудника: товар [key] (штрихкод или бренд) лежит в точке [x],[y] (доли картинки). */
data class LayoutPin(val key: String, val x: Float, val y: Float, val label: String?, val by: String)

/**
 * План магазина из assets/layout/{shopId}/ (map.webp + layout.json) и ручные пины
 * `shops/{shopId}/layoutPins/{key}` — см. firestore.rules.
 */
class LayoutRepository(context: Context, private val db: FirebaseFirestore) {
    private val assets = context.applicationContext.assets

    suspend fun layout(shopId: String): ShopLayout? = withContext(Dispatchers.IO) {
        if (shopId.isEmpty()) return@withContext null
        val text = runCatching { assets.open("layout/$shopId/layout.json").bufferedReader().use { it.readText() } }.getOrNull()
            ?: return@withContext null
        val o = JSONObject(text)
        val size = o.getJSONArray("imageSize")
        ShopLayout(
            shopId = o.optString("shopId", shopId),
            width = size.getInt(0),
            height = size.getInt(1),
            labels = o.optJSONArray("labels").objects().map { MapLabel(it.getString("text"), it.getJSONArray("box").box()) },
            sections = o.optJSONArray("sections").objects().map { it.getJSONArray("box").box() },
        )
    }

    suspend fun image(shopId: String): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            assets.open("layout/$shopId/map.webp").use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_SIDE) sample *= 2
            assets.open("layout/$shopId/map.webp").use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
        }.getOrNull()
    }

    suspend fun synonyms(): Map<String, String> = withContext(Dispatchers.IO) {
        runCatching {
            val o = JSONObject(assets.open("layout/synonyms.json").bufferedReader().use { it.readText() })
            o.keys().asSequence().associateWith { o.getString(it) }
        }.getOrDefault(emptyMap())
    }

    private fun pins(shopId: String) = db.collection("shops").document(shopId).collection("layoutPins")

    fun pinsFlow(shopId: String): Flow<List<LayoutPin>> = callbackFlow {
        val reg = pins(shopId).addSnapshotListener { snap, _ ->
            trySend(
                snap?.documents.orEmpty().mapNotNull { d ->
                    LayoutPin(
                        key = d.getString("key") ?: return@mapNotNull null,
                        x = d.getDouble("x")?.toFloat() ?: return@mapNotNull null,
                        y = d.getDouble("y")?.toFloat() ?: return@mapNotNull null,
                        label = d.getString("label"),
                        by = d.getString("by").orEmpty(),
                    )
                },
            )
        }
        awaitClose { reg.remove() }
    }

    suspend fun addPin(shopId: String, uid: String, key: String, x: Float, y: Float, label: String?) {
        val data = mutableMapOf<String, Any>("key" to key, "x" to x.toDouble(), "y" to y.toDouble(), "by" to uid, "at" to FieldValue.serverTimestamp())
        label?.let { data["label"] = it }
        pins(shopId).document(docId(key)).set(data).await()
    }

    suspend fun deletePin(shopId: String, key: String) {
        pins(shopId).document(docId(key)).delete().await()
    }

    companion object {
        private const val MAX_SIDE = 4096

        /** Ключ пина → id документа (без «/»). */
        fun docId(key: String) = key.replace('/', '_').take(200)

        private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else List(length()) { getJSONObject(it) }

        private fun JSONArray.box() = Box(getDouble(0).toFloat(), getDouble(1).toFloat(), getDouble(2).toFloat(), getDouble(3).toFloat())
    }
}
