package ua.chudo.scanner.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import uniffi.chudo_core.Region
import uniffi.chudo_core.defaultRegion
import uniffi.chudo_core.regions

data class HistoryItem(
    val urlKey: String,
    val name: String,
    val article: String?,
    val image: String?,
)

class AppPrefs(context: Context) {
    private val sp = context.getSharedPreferences("chudo", Context.MODE_PRIVATE)

    val allRegions: List<Region> = regions()

    private val _region = MutableStateFlow(regionByCode(sp.getString(KEY_REGION, null)))
    val region: StateFlow<Region> = _region.asStateFlow()

    private val _history = MutableStateFlow(readHistory())
    val history: StateFlow<List<HistoryItem>> = _history.asStateFlow()

    fun setRegion(code: String) {
        sp.edit { putString(KEY_REGION, code) }
        _region.value = regionByCode(code)
    }

    /** Штрихкод → url_key товара, который пользователь подтвердил вручную. */
    fun rememberedProduct(barcode: String): String? = sp.getString("$KEY_BARCODE$barcode", null)

    fun rememberBarcode(barcode: String, urlKey: String) =
        sp.edit { putString("$KEY_BARCODE$barcode", urlKey) }

    fun addToHistory(item: HistoryItem) {
        _history.update { old ->
            (listOf(item) + old.filterNot { it.urlKey == item.urlKey }).take(MAX_HISTORY)
        }
        writeHistory(_history.value)
    }

    fun clearHistory() {
        _history.value = emptyList()
        sp.edit { remove(KEY_HISTORY) }
    }

    private fun regionByCode(code: String?): Region =
        allRegions.firstOrNull { it.code == code }
            ?: allRegions.first { it.code == defaultRegion() }

    private fun readHistory(): List<HistoryItem> = runCatching {
        val arr = JSONArray(sp.getString(KEY_HISTORY, "[]"))
        List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            HistoryItem(
                urlKey = o.getString("k"),
                name = o.getString("n"),
                article = o.optString("a").ifEmpty { null },
                image = o.optString("i").ifEmpty { null },
            )
        }
    }.getOrDefault(emptyList())

    private fun writeHistory(list: List<HistoryItem>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("k", it.urlKey).put("n", it.name).put("a", it.article ?: "").put("i", it.image ?: ""))
        }
        sp.edit { putString(KEY_HISTORY, arr.toString()) }
    }

    private companion object {
        const val KEY_REGION = "region"
        const val KEY_HISTORY = "history"
        const val KEY_BARCODE = "bc:"
        const val MAX_HISTORY = 20
    }
}
