package ua.chudo.scanner.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import uniffi.chudo_core.Lang
import uniffi.chudo_core.Region
import uniffi.chudo_core.defaultAiModel
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

    private val _lang = MutableStateFlow(if (sp.getString(KEY_LANG, "ua") == "ru") Lang.RU else Lang.UA)
    val lang: StateFlow<Lang> = _lang.asStateFlow()

    private val _history = MutableStateFlow(readHistory())
    val history: StateFlow<List<HistoryItem>> = _history.asStateFlow()

    fun setRegion(code: String) {
        sp.edit { putString(KEY_REGION, code) }
        _region.value = regionByCode(code)
    }

    fun setLang(lang: Lang) {
        sp.edit { putString(KEY_LANG, if (lang == Lang.RU) "ru" else "ua") }
        _lang.value = lang
    }

    // Ключ OpenAI живёт там же, где его хранил старый APK (prefs/openai_key),
    // поэтому после обновления поверх старой версии вводить заново не нужно.
    private val legacy = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)

    private val _aiKey = MutableStateFlow(legacy.getString("openai_key", null)?.takeIf { it.isNotBlank() })
    val aiKey: StateFlow<String?> = _aiKey.asStateFlow()

    private val _aiModel = MutableStateFlow(sp.getString(KEY_AI_MODEL, null) ?: defaultAiModel())
    val aiModel: StateFlow<String> = _aiModel.asStateFlow()

    fun setAi(key: String?, model: String) {
        val k = key?.trim()?.takeIf { it.isNotEmpty() }
        val m = model.trim().ifEmpty { defaultAiModel() }
        legacy.edit { if (k == null) remove("openai_key") else putString("openai_key", k) }
        sp.edit { putString(KEY_AI_MODEL, m) }
        _aiKey.value = k
        _aiModel.value = m
    }

    /** Привязать телефон старому email-аккаунту предлагаем один раз. */
    fun phoneLinkOffered(uid: String): Boolean = sp.getBoolean("phone_link_offered:$uid", false)

    fun markPhoneLinkOffered(uid: String) = sp.edit { putBoolean("phone_link_offered:$uid", true) }

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
        const val KEY_LANG = "lang"
        const val KEY_HISTORY = "history"
        const val KEY_BARCODE = "bc:"
        const val KEY_AI_MODEL = "ai_model"
        const val MAX_HISTORY = 20
    }
}
