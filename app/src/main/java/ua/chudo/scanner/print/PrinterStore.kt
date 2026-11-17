package ua.chudo.scanner.print

import android.content.Context
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
class PrinterStore(context: Context) {
    private val ds = context.applicationContext.printerData

    val lastTemplate: Flow<Template?> = ds.data.map { p -> Template.entries.firstOrNull { it.name == p[TEMPLATE] } }

    suspend fun setLastTemplate(t: Template) = ds.edit { it[TEMPLATE] = t.name }

    suspend fun lastSync(): Long = ds.data.first()[SYNC] ?: 0L
    suspend fun setLastSync(at: Long) = ds.edit { it[SYNC] = at }

    private companion object {
        val TEMPLATE = stringPreferencesKey("last_template")
        val SYNC = longPreferencesKey("last_sync")
    }
}
