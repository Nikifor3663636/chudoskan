package ua.chudo.scanner.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import ua.chudo.scanner.ui.theme.ThemeMode
import uniffi.chudo_core.Region
import uniffi.chudo_core.defaultRegion
import uniffi.chudo_core.regions

data class HistoryItem(
    val urlKey: String,
    val name: String,
    val article: String?,
    val image: String?,
    val priceKop: Long? = null,
)

data class ScannerOptions(val sound: Boolean = false, val vibrate: Boolean = true, val continuous: Boolean = true)

enum class ActivityKind(val title: String) { PRINT("Друк"), ORDER("Замовлення"), SCAN("Скани"), CART("Кошик"), STOCK("Залишки"), PRINTER("Принтери") }

data class ShopActivity(val kind: ActivityKind, val title: String, val detail: String, val time: Long)

class AppPrefs(context: Context) {
    private val sp = context.getSharedPreferences("chudo", Context.MODE_PRIVATE)

    private val _scanner = MutableStateFlow(
        ScannerOptions(sp.getBoolean("scan_sound", false), sp.getBoolean("scan_vibrate", true), sp.getBoolean("scan_continuous", true)),
    )
    val scanner: StateFlow<ScannerOptions> = _scanner.asStateFlow()

    fun setScanner(options: ScannerOptions) {
        sp.edit {
            putBoolean("scan_sound", options.sound)
            putBoolean("scan_vibrate", options.vibrate)
            putBoolean("scan_continuous", options.continuous)
        }
        _scanner.value = options
    }

    private val _activityRevision = MutableStateFlow(0)
    val activityRevision = _activityRevision.asStateFlow()

    private fun activityKey(employee: Employee) = "activity_${employee.uid}_${employee.shopId}"

    fun activities(employee: Employee): List<ShopActivity> = runCatching {
        val saved = JSONArray(sp.getString(activityKey(employee), "[]"))
        List(saved.length()) { index ->
            val row = saved.getJSONObject(index)
            ShopActivity(ActivityKind.valueOf(row.getString("kind")), row.getString("title"), row.getString("detail"), row.getLong("time"))
        }
    }.getOrDefault(emptyList())

    @Synchronized
    fun addActivity(employee: Employee, kind: ActivityKind, title: String, detail: String, time: Long = System.currentTimeMillis()) {
        val event = ShopActivity(kind, title, detail, time)
        val old = activities(employee)
        if (event in old) return
        val rows = JSONArray()
        (listOf(event) + old).sortedByDescending { it.time }.take(100).forEach {
            rows.put(JSONObject().put("kind", it.kind.name).put("title", it.title).put("detail", it.detail).put("time", it.time))
        }
        sp.edit { putString(activityKey(employee), rows.toString()) }
        _activityRevision.update { it + 1 }
    }

    fun notificationsReadAt(employee: Employee): Long = sp.getLong(activityKey(employee) + "_read", 0)

    fun readNotifications(employee: Employee, time: Long) {
        sp.edit { putLong(activityKey(employee) + "_read", time) }
        _activityRevision.update { it + 1 }
    }

    val allRegions: List<Region> = regions()

    private val _region = MutableStateFlow(regionByCode(sp.getString(KEY_REGION, null)))
    val region: StateFlow<Region> = _region.asStateFlow()

    private val _history = MutableStateFlow(readHistory())
    val history: StateFlow<List<HistoryItem>> = _history.asStateFlow()

    private val _themeMode = MutableStateFlow(
        ThemeMode.entries.firstOrNull { it.name == sp.getString(KEY_THEME, null) } ?: ThemeMode.SYSTEM,
    )

    /** «Тема оформлення»: темна, світла або як у системі. */
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    fun setThemeMode(mode: ThemeMode) {
        sp.edit { putString(KEY_THEME, mode.name) }
        _themeMode.value = mode
    }

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
                priceKop = o.optLong("p").takeIf { it > 0 },
            )
        }
    }.getOrDefault(emptyList())

    private fun writeHistory(list: List<HistoryItem>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("k", it.urlKey).put("n", it.name).put("a", it.article ?: "").put("i", it.image ?: "").put("p", it.priceKop))
        }
        sp.edit { putString(KEY_HISTORY, arr.toString()) }
    }

    private companion object {
        const val KEY_REGION = "region"
        const val KEY_THEME = "theme"
        const val KEY_HISTORY = "history"
        const val KEY_BARCODE = "bc:"
        const val MAX_HISTORY = 20
    }
}
