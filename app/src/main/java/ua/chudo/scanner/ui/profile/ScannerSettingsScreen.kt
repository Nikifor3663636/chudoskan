package ua.chudo.scanner.ui.profile

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ua.chudo.scanner.R
import ua.chudo.scanner.chudoApp
import ua.chudo.scanner.data.Backend
import ua.chudo.scanner.data.Employee
import ua.chudo.scanner.data.OfflineStockSnapshot
import ua.chudo.scanner.data.ScannerOptions
import ua.chudo.scanner.ui.theme.Mine

@Composable
fun ScannerSettingsScreen(employee: Employee, onBack: () -> Unit) {
    val app = LocalContext.current.chudoApp
    val prefs = app.prefs
    val scope = rememberCoroutineScope()
    var snapshot by remember(employee) { mutableStateOf<OfflineStockSnapshot?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(employee) {
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
    ScannerSettingsContent(
        options = options,
        onBack = onBack,
        onOptions = prefs::setScanner,
        offline = if (Backend.isSupabase) OfflineSettingsUi(snapshot?.rows?.size, loading, error) else null,
        onOffline = ::changeCache,
    )
}

internal data class OfflineSettingsUi(val rows: Int?, val loading: Boolean, val error: String? = null)

@Composable
internal fun ScannerSettingsContent(
    options: ScannerOptions,
    onBack: () -> Unit,
    onOptions: (ScannerOptions) -> Unit,
    offline: OfflineSettingsUi?,
    onOffline: (Boolean) -> Unit,
) {
    val m = Mine.colors
    Column(Modifier.fillMaxSize().background(m.bg).statusBarsPadding().verticalScroll(rememberScrollState())) {
        Row(
            Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                Icon(painterResource(R.drawable.ic_redesign_chevron_left), "Назад", Modifier.size(24.dp), tint = m.textSecondary)
            }
            Text("Налаштування сканера", Modifier.padding(start = 8.dp), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = m.text)
        }
        HorizontalDivider(color = m.line)
        Setting("Звук при успішному скані", "Короткий сигнал", options.sound) { onOptions(options.copy(sound = it)) }
        Setting("Вібрація", "Відчутно, коли телефон у кишені", options.vibrate) { onOptions(options.copy(vibrate = it)) }
        Setting("Сканувати підряд", "Камера не закривається після скану", options.continuous) { onOptions(options.copy(continuous = it)) }
        if (offline != null) {
            ProfileSection("Офлайн")
            HorizontalDivider(color = m.line)
            Setting(
                "Зберігати залишки свого магазину",
                when {
                    offline.loading -> "Завантаження…"
                    offline.rows != null -> "${offline.rows} товарів · до 3 днів"
                    else -> "Завантажте копію залишків для пошуку й сканування без мережі"
                },
                offline.rows != null,
                enabled = !offline.loading,
                onChange = onOffline,
            )
            if (offline.rows != null) TextButton(
                onClick = { onOffline(true) }, enabled = !offline.loading, modifier = Modifier.padding(horizontal = 8.dp),
            ) { Text("Оновити копію", color = if (offline.loading) m.textTertiary else m.link) }
            offline.error?.let { Text(it, Modifier.padding(16.dp), fontSize = 13.sp, color = m.warningText) }
        }
    }
}

@Composable
private fun Setting(title: String, detail: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val m = Mine.colors
    Row(
        Modifier.fillMaxWidth().background(m.card).toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontWeight = FontWeight.Medium, color = m.text, fontSize = 15.sp, lineHeight = 19.5.sp)
            Text(detail, color = m.textTertiary, fontSize = 13.sp)
        }
        val offset by animateDpAsState(if (checked) 21.dp else 3.dp, tween(200), label = "settingSwitch")
        val track = if (checked) m.text else m.outline
        Box(
            Modifier.size(width = 44.dp, height = 26.dp).background(track.copy(alpha = if (enabled) 1f else 0.5f), CircleShape),
        ) {
            Box(
                Modifier.align(Alignment.CenterStart).offset(x = offset).size(20.dp)
                    .shadow(1.dp, CircleShape).background(Color.White, CircleShape),
            )
        }
    }
    HorizontalDivider(color = m.line)
}
