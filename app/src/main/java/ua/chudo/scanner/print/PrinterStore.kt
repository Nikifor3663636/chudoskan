package ua.chudo.scanner.print

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.printerData by preferencesDataStore("printers")

/** Принтеры (роль, IP, отпечаток), последний шаблон и последний принтер — в DataStore. */
class PrinterStore(context: Context) {
    private val ds = context.applicationContext.printerData

    val printers: Flow<List<SavedPrinter>> = ds.data.map { decode(it[LIST]) }
    val lastTemplate: Flow<LabelTemplate?> = ds.data.map { p -> LabelTemplate.entries.firstOrNull { it.name == p[TEMPLATE] } }
    val direct: Flow<Boolean> = ds.data.map { it[DIRECT] == true }
    fun lastPrinter(role: LabelKind): Flow<String?> = ds.data.map { it[lastKey(role)] }

    suspend fun upsert(p: SavedPrinter) = ds.edit { prefs ->
        val list = decode(prefs[LIST]).filterNot { it.id == p.id } + p
        prefs[LIST] = encode(list)
    }

    suspend fun remove(id: String) = ds.edit { prefs -> prefs[LIST] = encode(decode(prefs[LIST]).filterNot { it.id == id }) }

    suspend fun setLastTemplate(t: LabelTemplate) = ds.edit { it[TEMPLATE] = t.name }
    suspend fun setLastPrinter(role: LabelKind, id: String) = ds.edit { it[lastKey(role)] = id }
    suspend fun setDirect(v: Boolean) = ds.edit { it[DIRECT] = v }

    private fun encode(list: List<SavedPrinter>) = JSONArray().apply {
        list.forEach {
            put(
                JSONObject().put("id", it.id).put("name", it.name).put("role", it.role.name)
                    .put("host", it.host).put("port", it.port).put("fp", it.fingerprint),
            )
        }
    }.toString()

    private fun decode(s: String?): List<SavedPrinter> = runCatching {
        val arr = JSONArray(s ?: "[]")
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            SavedPrinter(
                id = o.getString("id"),
                name = o.optString("name"),
                role = runCatching { LabelKind.valueOf(o.getString("role")) }.getOrDefault(LabelKind.STICKY),
                host = o.getString("host"),
                port = o.optInt("port", 9100),
                fingerprint = o.optString("fp"),
            )
        }
    }.getOrDefault(emptyList())

    private companion object {
        val LIST = stringPreferencesKey("list")
        val TEMPLATE = stringPreferencesKey("last_template")
        val DIRECT = booleanPreferencesKey("plain_direct")
        fun lastKey(role: LabelKind): Preferences.Key<String> = stringPreferencesKey("last_printer_${role.name}")
    }
}
