package ua.chudo.scanner.print

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.printerData by preferencesDataStore("printers")

/**
 * Локальные настройки печати (DataStore): последний шаблон и время последней фоновой проверки принтеров.
 * Сами принтеры — в общем реестре магазина ([PrinterRegistry]).
 */
class PrinterStore internal constructor(private val ds: DataStore<Preferences>) {
    constructor(context: Context) : this(context.applicationContext.printerData)

    fun preferred(kind: LabelKind): Flow<String> = ds.data.map { it[preferredKey(kind)] ?: "auto" }
    suspend fun setPreferred(kind: LabelKind, printerId: String) = ds.edit { it[preferredKey(kind)] = printerId }
    private fun preferredKey(kind: LabelKind) = stringPreferencesKey("preferred_${kind.wire}")

    val lastTemplate: Flow<Template?> = ds.data.map { p -> Template.entries.firstOrNull { it.name == p[TEMPLATE] } }

    suspend fun setLastTemplate(t: Template) = ds.edit { it[TEMPLATE] = t.name }

    /** «Книжна / Альбомна» — окремо для кожного A4-шаблону; за замовчуванням [Template.defaultLandscape]. */
    fun landscape(t: Template): Flow<Boolean> = ds.data.map { it[landscapeKey(t)] ?: t.defaultLandscape }
    suspend fun setLandscape(t: Template, v: Boolean) = ds.edit { it[landscapeKey(t)] = v }
    private fun landscapeKey(t: Template) = booleanPreferencesKey("landscape_${t.name}")

    suspend fun lastSync(): Long = ds.data.first()[SYNC] ?: 0L
    suspend fun setLastSync(at: Long) = ds.edit { it[SYNC] = at }

    private companion object {
        val TEMPLATE = stringPreferencesKey("last_template")
        val SYNC = longPreferencesKey("last_sync")
    }
}
