package ua.chudo.scanner.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ua.chudo.scanner.chudoApp
import ua.chudo.scanner.ui.common.MineHeader
import ua.chudo.scanner.ui.theme.Mine

@Composable
fun ScannerSettingsScreen(employee: ua.chudo.scanner.data.Employee, onBack: () -> Unit) {
    val app = LocalContext.current.chudoApp
    val prefs = app.prefs
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var snapshot by androidx.compose.runtime.remember(employee) { androidx.compose.runtime.mutableStateOf<ua.chudo.scanner.data.OfflineStockSnapshot?>(null) }
    var loading by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(true) }
    var error by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    androidx.compose.runtime.LaunchedEffect(employee) {
        try {
            snapshot = app.offlineStock.load(employee)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            error = "Не вдалося прочитати збережені залишки."
        } finally {
            loading = false
        }
    }
    fun changeCache(save: Boolean) {
        scope.launch {
            loading = true
            error = null
            try {
                if (save) snapshot = app.offlineStock.download(employee, app.networkLookup)
                else { app.offlineStock.remove(employee); snapshot = null }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "Не вдалося оновити офлайн-залишки."
            } finally {
                loading = false
            }
        }
    }
    val options by prefs.scanner.collectAsStateWithLifecycle()
    val m = Mine.colors
    Column(Modifier.fillMaxSize().background(m.bg).statusBarsPadding()) {
        Column(Modifier.padding(horizontal = 16.dp)) { MineHeader("Налаштування сканера", onBack) }
        Setting("Звук при успішному скані", "Короткий сигнал", options.sound) { prefs.setScanner(options.copy(sound = it)) }
        Setting("Вібрація", "Відгук при зчитуванні штрихкоду", options.vibrate) { prefs.setScanner(options.copy(vibrate = it)) }
        Setting("Сканувати підряд", "Камера залишається відкритою після скану товару", options.continuous) { prefs.setScanner(options.copy(continuous = it)) }
        if (ua.chudo.scanner.data.Backend.isSupabase) {
            Text("Офлайн", Modifier.padding(16.dp), color = m.textSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Setting(
                "Зберігати залишки свого магазину",
                if (loading) "Завантаження…" else snapshot?.let { "${it.rows.size} товарів · до 3 днів" } ?: "Завантажте копію залишків для сканування без мережі",
                snapshot != null,
                enabled = !loading,
                onChange = ::changeCache,
            )
            if (snapshot != null) androidx.compose.material3.TextButton(onClick = { changeCache(true) }, enabled = !loading) { Text("Оновити копію") }
            error?.let { Text(it, Modifier.padding(16.dp), color = m.warningText) }
        }
    }
}

@Composable
private fun Setting(title: String, detail: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val m = Mine.colors
    Row(
        Modifier.fillMaxWidth().background(m.card).toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, color = m.text, fontSize = 16.sp)
            Text(detail, color = m.textSecondary, fontSize = 13.sp)
        }
        Switch(checked, onCheckedChange = null, enabled = enabled)
    }
    HorizontalDivider(color = m.line)
}
