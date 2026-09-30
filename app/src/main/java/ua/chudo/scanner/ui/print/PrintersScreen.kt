package ua.chudo.scanner.ui.print

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ua.chudo.scanner.chudoApp
import ua.chudo.scanner.i18n.tr
import ua.chudo.scanner.print.FoundPrinter
import ua.chudo.scanner.print.LabelKind
import ua.chudo.scanner.print.ONLY_9100
import ua.chudo.scanner.print.PrintService
import ua.chudo.scanner.print.SavedPrinter
import uniffi.chudo_core.Lang
import java.util.UUID

class PrintersViewModel(private val svc: PrintService, private val lang: () -> Lang) : ViewModel() {
    val printers = svc.store.printers.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val _scanning = MutableStateFlow(false)
    val scanning = _scanning.asStateFlow()
    private val _found = MutableStateFlow<List<FoundPrinter>?>(null)
    val found = _found.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

    fun discover() {
        if (_scanning.value) return
        _scanning.value = true
        _message.value = null
        viewModelScope.launch {
            try {
                if (svc.discovery.localIp() == null) {
                    _message.value = lang().tr("Підключись до Wi-Fi магазину.", "Подключись к Wi-Fi магазина.")
                    _found.value = emptyList()
                } else {
                    val saved = printers.value.map { it.host }.toSet()
                    _found.value = svc.discovery.discover().filter { it.host !in saved }
                }
            } finally {
                _scanning.value = false
            }
        }
    }

    fun add(f: FoundPrinter, role: LabelKind) = viewModelScope.launch {
        svc.store.upsert(SavedPrinter(UUID.randomUUID().toString(), f.name, role, f.host, f.port, f.fingerprint))
        _found.value = _found.value?.filterNot { it.host == f.host }
    }

    /** Ручное добавление: отпечаток определяем по сети (NSD-имя Brother / ~HI Godex). */
    fun addManual(host: String, port: Int, role: LabelKind, onDone: () -> Unit) = viewModelScope.launch {
        _scanning.value = true
        try {
            val (fp, name) = svc.discovery.fingerprintOf(host, port, role)
            val title = name ?: if (role == LabelKind.STICKY) "Godex $host" else "Brother $host"
            svc.store.upsert(SavedPrinter(UUID.randomUUID().toString(), title, role, host, port, fp))
            onDone()
        } finally {
            _scanning.value = false
        }
    }

    fun setRole(p: SavedPrinter, role: LabelKind) = viewModelScope.launch { svc.store.upsert(p.copy(role = role)) }
    fun remove(p: SavedPrinter) = viewModelScope.launch { svc.store.remove(p.id) }

    fun test(host: String, port: Int, role: LabelKind, onResult: (String) -> Unit) = viewModelScope.launch {
        val l = lang()
        val msg = try {
            if (svc.test(host, port, role)) {
                if (role == LabelKind.STICKY) l.tr("Тестову етикетку надіслано.", "Тестовая этикетка отправлена.")
                else l.tr("Принтер відповідає.", "Принтер отвечает.")
            } else {
                l.tr("Немає відповіді від $host:$port.", "Нет ответа от $host:$port.")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            l.tr("Помилка: ${e.message}", "Ошибка: ${e.message}")
        }
        onResult(msg)
    }
}

@Composable
fun roleTitle(role: LabelKind) = if (role == LabelKind.STICKY) tr("Липкі", "Липкие") else tr("Звичайні", "Обычные")

@Composable
fun PrintersScreen(onBack: () -> Unit) {
    val app = LocalContext.current.chudoApp
    val vm: PrintersViewModel = viewModel(factory = viewModelFactory { initializer { PrintersViewModel(app.printing) { app.prefs.lang.value } } })
    val printers by vm.printers.collectAsStateWithLifecycle()
    val scanning by vm.scanning.collectAsStateWithLifecycle()
    val found by vm.found.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var manual by rememberSaveable { mutableStateOf(false) }
    var info by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, tr("Назад", "Назад")) }
                Text(tr("Принтери", "Принтеры"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            Text(
                tr(
                    "Лише мережеві принтери (Wi-Fi/LAN). Липкі — Godex, звичайні (A4) — Brother.",
                    "Только сетевые принтеры (Wi-Fi/LAN). Липкие — Godex, обычные (A4) — Brother.",
                ),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (printers.isEmpty()) item {
            Text(tr("Принтерів ще немає.", "Принтеров пока нет."), Modifier.padding(vertical = 8.dp))
        }
        items(printers, key = { it.id }) { p ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text(p.name, style = MaterialTheme.typography.titleMedium)
                    Text("${p.host}:${p.port} · ${fingerprintTitle(p.fingerprint)}", style = MaterialTheme.typography.bodySmall)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        LabelKind.entries.forEach { r ->
                            FilterChip(selected = p.role == r, onClick = { vm.setRole(p, r) }, label = { Text(roleTitle(r)) })
                        }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { vm.test(p.host, p.port, p.role) { info = it } }) { Text(tr("Тест", "Тест")) }
                        IconButton(onClick = { vm.remove(p) }) { Icon(Icons.Rounded.Delete, tr("Видалити", "Удалить")) }
                    }
                }
            }
        }
        item {
            info?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { manual = true }) {
                    Icon(Icons.Rounded.Add, null); Text(tr("Додати вручну", "Добавить вручную"))
                }
                Button(onClick = vm::discover, enabled = !scanning) {
                    Icon(Icons.Rounded.Wifi, null); Text(tr("Знайти в мережі", "Найти в сети"))
                }
            }
        }
        if (scanning) item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                Text(tr("  Шукаємо принтери…", "  Ищем принтеры…"))
            }
        }
        message?.let { m -> item { Text(m, color = MaterialTheme.colorScheme.error) } }
        found?.let { list ->
            if (list.isEmpty() && !scanning) item { Text(tr("Нових принтерів не знайдено.", "Новых принтеров не найдено.")) }
            items(list, key = { "f" + it.host }) { f -> FoundRow(f) { role -> vm.add(f, role) } }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }

    if (manual) ManualDialog(vm, scanning, onDismiss = { manual = false })
}

@Composable
private fun fingerprintTitle(fp: String) = when {
    fp.startsWith("brother:") -> "Brother (NSD)"
    fp.startsWith("godex:") -> "Godex (~HI)"
    fp == ONLY_9100 -> tr("єдиний :9100", "единственный :9100")
    else -> tr("лише за IP", "только по IP")
}

@Composable
private fun FoundRow(f: FoundPrinter, onAdd: (LabelKind) -> Unit) {
    var role by remember(f.host) { mutableStateOf(f.role) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(f.name, style = MaterialTheme.typography.titleMedium)
            Text("${f.host}:${f.port} · ${fingerprintTitle(f.fingerprint)}", style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                LabelKind.entries.forEach { r -> FilterChip(selected = role == r, onClick = { role = r }, label = { Text(roleTitle(r)) }) }
                Spacer(Modifier.weight(1f))
                Button(onClick = { onAdd(role) }) { Text(tr("Додати", "Добавить")) }
            }
        }
    }
}

@Composable
private fun ManualDialog(vm: PrintersViewModel, busy: Boolean, onDismiss: () -> Unit) {
    var host by rememberSaveable { mutableStateOf("") }
    var port by rememberSaveable { mutableStateOf("9100") }
    var role by rememberSaveable { mutableStateOf(LabelKind.STICKY) }
    var result by remember { mutableStateOf<String?>(null) }
    val ipOk = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""").matchEntire(host.trim())
        ?.groupValues?.drop(1)?.all { it.toInt() in 0..255 } == true
    val portNum = port.toIntOrNull()?.takeIf { it in 1..65535 }
    val valid = ipOk && portNum != null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Новий принтер", "Новый принтер")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    host, { host = it.filter { c -> c.isDigit() || c == '.' } }, singleLine = true,
                    label = { Text("IP") }, placeholder = { Text("192.168.1.50") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                OutlinedTextField(
                    port, { port = it.filter(Char::isDigit).take(5) }, singleLine = true,
                    label = { Text(tr("Порт", "Порт")) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    LabelKind.entries.forEach { r -> FilterChip(selected = role == r, onClick = { role = r }, label = { Text(roleTitle(r)) }) }
                }
                TextButton(enabled = valid, onClick = { vm.test(host.trim(), portNum!!, role) { result = it } }) { Text(tr("Тест", "Тест")) }
                result?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(enabled = valid && !busy, onClick = { vm.addManual(host.trim(), portNum!!, role, onDismiss) }) {
                Text(tr("Зберегти", "Сохранить"))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Скасувати", "Отмена")) } },
    )
}
