package ua.chudo.scanner.ui.print

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ua.chudo.scanner.chudoApp
import ua.chudo.scanner.print.FoundPrinter
import ua.chudo.scanner.print.LabelKind
import ua.chudo.scanner.print.ONLY_9100
import ua.chudo.scanner.print.PrintLang
import ua.chudo.scanner.print.PrintService
import ua.chudo.scanner.print.PrinterStatus
import ua.chudo.scanner.print.SavedPrinter
import ua.chudo.scanner.print.Transport
import ua.chudo.scanner.ui.theme.InStockGreen
import java.util.UUID

class PrintersViewModel(private val svc: PrintService) : ViewModel() {
    val printers = svc.store.printers.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val _scanning = MutableStateFlow(false)
    val scanning = _scanning.asStateFlow()
    private val _found = MutableStateFlow<List<FoundPrinter>?>(null)
    val found = _found.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    private val _status = MutableStateFlow<Map<String, PrinterStatus?>>(emptyMap())
    val status = _status.asStateFlow()
    /** id принтера, для которого идёт тест. */
    private val _testing = MutableStateFlow<String?>(null)
    val testing = _testing.asStateFlow()

    val wifiSupported get() = svc.wifi.supported
    val bluetooth get() = svc.bluetooth

    init {
        viewModelScope.launch {
            val list = svc.store.printers.first()
            refreshStatus(list)
            // Принтеров ещё нет — сразу ищем в сети, как Zebra Setup Utility.
            if (list.isEmpty()) discover()
        }
    }

    fun refreshStatus(list: List<SavedPrinter> = printers.value) {
        list.forEach { p ->
            _status.value = _status.value + (p.id to null)
            viewModelScope.launch {
                val s = runCatching { svc.status(p) }.getOrElse { PrinterStatus(false, it.message ?: "Помилка") }
                _status.value = _status.value + (p.id to s)
            }
        }
    }

    fun discover() {
        if (_scanning.value) return
        _scanning.value = true
        _message.value = null
        viewModelScope.launch {
            try {
                if (svc.discovery.localIp() == null) {
                    _message.value = "Телефон не в Wi-Fi. Підключись до Wi-Fi магазину або додай принтер через «Wi-Fi принтера» чи Bluetooth."
                    _found.value = emptyList()
                } else {
                    val saved = printers.value.filter { it.transport == Transport.LAN }.map { it.host }.toSet()
                    _found.value = svc.discovery.discover().filter { it.host !in saved }
                }
            } finally {
                _scanning.value = false
            }
        }
    }

    fun add(f: FoundPrinter, role: LabelKind, lang: PrintLang) = viewModelScope.launch {
        val p = SavedPrinter(UUID.randomUUID().toString(), f.name, role, f.host, f.port, f.fingerprint, Transport.LAN, lang)
        svc.store.upsert(p)
        _found.value = _found.value?.filterNot { it.host == f.host }
        refreshStatus(listOf(p))
    }

    /** Ручное добавление по IP: отпечаток и модель определяем по сети (SNMP / mDNS / ~HI). */
    fun addManual(host: String, port: Int, role: LabelKind, lang: PrintLang, onDone: () -> Unit) = viewModelScope.launch {
        _scanning.value = true
        try {
            val (fp, name) = svc.discovery.fingerprintOf(host, port, role)
            val p = SavedPrinter(UUID.randomUUID().toString(), name ?: "Принтер $host", role, host, port, fp, Transport.LAN, lang)
            svc.store.upsert(p)
            refreshStatus(listOf(p))
            onDone()
        } finally {
            _scanning.value = false
        }
    }

    fun save(p: SavedPrinter) = viewModelScope.launch {
        svc.store.upsert(p)
        refreshStatus(listOf(p))
    }

    fun setRole(p: SavedPrinter, role: LabelKind) = save(p.copy(role = role, lang = if (role == LabelKind.PLAIN) PrintLang.PDF else p.lang.takeIf { it != PrintLang.PDF } ?: PrintLang.ZPL))
    fun setLang(p: SavedPrinter, lang: PrintLang) = save(p.copy(lang = lang))
    fun remove(p: SavedPrinter) = viewModelScope.launch { svc.store.remove(p.id) }

    fun test(p: SavedPrinter, onResult: (String) -> Unit) {
        if (_testing.value != null) return
        _testing.value = p.id
        viewModelScope.launch {
            val msg = try {
                svc.test(p)
            } catch (e: CancellationException) {
                throw e
            } catch (e: SecurityException) {
                "Дозволь застосунку доступ до Bluetooth / Wi-Fi."
            } catch (e: Exception) {
                e.message ?: e.javaClass.simpleName
            } finally {
                _testing.value = null
            }
            onResult(msg)
        }
    }
}

@Composable
fun roleTitle(role: LabelKind) = if (role == LabelKind.STICKY) "Липкі" else "Звичайні"

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PrintersScreen(onBack: () -> Unit) {
    val app = LocalContext.current.chudoApp
    val vm: PrintersViewModel = viewModel(factory = viewModelFactory { initializer { PrintersViewModel(app.printing) } })
    val printers by vm.printers.collectAsStateWithLifecycle()
    val scanning by vm.scanning.collectAsStateWithLifecycle()
    val found by vm.found.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    var dialog by rememberSaveable { mutableStateOf<String?>(null) } // ip / wifi / bt
    var info by remember { mutableStateOf<String?>(null) }
    var guide by rememberSaveable { mutableStateOf(false) }

    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад") }
                Text("Принтери", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                IconButton(onClick = { guide = true }) { Icon(Icons.AutoMirrored.Rounded.HelpOutline, "Інструкція") }
                IconButton(onClick = { vm.refreshStatus() }) { Icon(Icons.Rounded.Refresh, "Оновити статус") }
            }
            Text(
                "Будь-який принтер: через мережу магазину, напряму через Wi-Fi принтера або Bluetooth. " +
                    "Липкі етикетки — ZPL, TSPL, EPL або ESC/POS; звичайні A4 — системний друк Android.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (printers.isEmpty()) {
            item { Text("Принтерів ще немає — додай нижче.", Modifier.padding(vertical = 4.dp)) }
            item { GuideEntry { guide = true } }
        }
        items(printers, key = { it.id }) { p ->
            SavedCard(p, status[p.id], vm) { info = it }
        }
        info?.let { item { Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) { Text(it, Modifier.padding(12.dp)) } } }

        item {
            Text("Додати принтер", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(onClick = vm::discover, enabled = !scanning) { Icon(Icons.Rounded.Search, null); Spacer(Modifier.width(6.dp)); Text("Знайти в мережі") }
                FilledTonalButton(onClick = { dialog = "wifi" }) { Icon(Icons.Rounded.Wifi, null); Spacer(Modifier.width(6.dp)); Text("Wi-Fi принтера") }
                FilledTonalButton(onClick = { dialog = "bt" }) { Icon(Icons.Rounded.Bluetooth, null); Spacer(Modifier.width(6.dp)); Text("Bluetooth") }
                OutlinedButton(onClick = { dialog = "ip" }) { Icon(Icons.Rounded.Lan, null); Spacer(Modifier.width(6.dp)); Text("За IP") }
            }
        }
        if (scanning) item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                Text("  Шукаємо принтери в мережі (до 10 с)…")
            }
        }
        message?.let { m -> item { Text(m, color = MaterialTheme.colorScheme.error) } }
        found?.let { list ->
            if (list.isEmpty() && !scanning && message == null) item {
                Text("Нових принтерів у мережі не знайдено. Перевір, що принтер увімкнений і в тому ж Wi-Fi, або підключись через «Wi-Fi принтера» / Bluetooth.")
            }
            items(list, key = { "f" + it.host }) { f -> FoundCard(f) { role, lang -> vm.add(f, role, lang) } }
        }
        if (printers.isNotEmpty()) item { GuideEntry { guide = true } }
        item { Spacer(Modifier.height(24.dp)) }
    }

    if (guide) PrinterGuide(onClose = { guide = false })

    when (dialog) {
        "ip" -> ManualDialog(vm, scanning, onDismiss = { dialog = null })
        "wifi" -> WifiDialog(vm, onDismiss = { dialog = null })
        "bt" -> BluetoothDialog(vm, onDismiss = { dialog = null })
    }
}

private fun transportIcon(t: Transport): ImageVector = when (t) {
    Transport.LAN -> Icons.Rounded.Router
    Transport.WIFI_DIRECT -> Icons.Rounded.Wifi
    Transport.BLUETOOTH -> Icons.Rounded.Bluetooth
}

private fun address(p: SavedPrinter) = when (p.transport) {
    Transport.LAN -> "${p.host}:${p.port} · ${fingerprintTitle(p.fingerprint)}"
    Transport.WIFI_DIRECT -> "мережа «${p.ssid}»" + (p.host.takeIf { it.isNotEmpty() }?.let { " · $it" } ?: "")
    Transport.BLUETOOTH -> p.host
}

private fun fingerprintTitle(fp: String) = when {
    fp.startsWith("brother:") || fp.startsWith("nsd:") -> "знайдено за іменем"
    fp.startsWith("godex:") -> "Godex"
    fp.startsWith("snmp:") -> "знайдено за моделлю"
    fp == ONLY_9100 -> "єдиний принтер етикеток"
    else -> "лише за IP"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SavedCard(p: SavedPrinter, status: PrinterStatus?, vm: PrintersViewModel, onInfo: (String) -> Unit) {
    val testing by vm.testing.collectAsStateWithLifecycle()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(transportIcon(p.transport), null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    Text("${p.transport.title} · ${address(p)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { vm.remove(p) }) { Icon(Icons.Rounded.Delete, "Видалити") }
            }
            StatusLine(status)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                LabelKind.entries.forEach { r -> FilterChip(selected = p.role == r, onClick = { vm.setRole(p, r) }, label = { Text(roleTitle(r)) }) }
                if (p.role == LabelKind.STICKY) LangPicker(p.lang) { vm.setLang(p, it) }
            }
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (p.role == LabelKind.STICKY) "Тест друкує пробну етикетку" else "Тест перевіряє зв'язок",
                    Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (testing == p.id) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else TextButton(onClick = { vm.test(p, onInfo) }, enabled = testing == null) { Text("Тест") }
            }
        }
    }
}

@Composable
private fun StatusLine(s: PrinterStatus?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        val color = when (s?.ok) {
            true -> InStockGreen
            false -> MaterialTheme.colorScheme.error
            null -> MaterialTheme.colorScheme.outline
        }
        if (s == null) CircularProgressIndicator(Modifier.size(10.dp), strokeWidth = 1.5.dp)
        else Box(Modifier.size(10.dp).background(color, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(s?.text ?: "Перевіряємо…", style = MaterialTheme.typography.bodySmall, color = if (s?.ok == false) color else MaterialTheme.colorScheme.onSurface)
    }
}

/** Выпадающий выбор языка принтера этикеток. */
@Composable
private fun LangPicker(lang: PrintLang, onPick: (PrintLang) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(selected = true, onClick = { open = true }, label = { Text("Мова: ${lang.title} ▾") })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            PrintLang.LABEL.forEach { l ->
                DropdownMenuItem(
                    text = { Column { Text(l.title); Text(l.hint, style = MaterialTheme.typography.bodySmall) } },
                    onClick = { onPick(l); open = false },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RoleAndLang(role: LabelKind, lang: PrintLang, onRole: (LabelKind) -> Unit, onLang: (PrintLang) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        LabelKind.entries.forEach { r -> FilterChip(selected = role == r, onClick = { onRole(r) }, label = { Text(roleTitle(r)) }) }
        if (role == LabelKind.STICKY) LangPicker(lang, onLang)
    }
}

@Composable
private fun FoundCard(f: FoundPrinter, onAdd: (LabelKind, PrintLang) -> Unit) {
    var role by remember(f.host) { mutableStateOf(f.role) }
    var lang by remember(f.host) { mutableStateOf(f.lang) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(f.name, style = MaterialTheme.typography.titleMedium)
            Text("${f.host}:${f.port} · ${fingerprintTitle(f.fingerprint)}", style = MaterialTheme.typography.bodySmall)
            RoleAndLang(role, lang, { r ->
                role = r
                lang = if (r == LabelKind.PLAIN) PrintLang.PDF else lang.takeIf { it != PrintLang.PDF } ?: PrintLang.ZPL
            }, { lang = it })
            Button(onClick = { onAdd(role, lang) }, modifier = Modifier.align(Alignment.End)) { Text("Додати") }
        }
    }
}

@Composable
private fun ManualDialog(vm: PrintersViewModel, busy: Boolean, onDismiss: () -> Unit) {
    var host by rememberSaveable { mutableStateOf("") }
    var port by rememberSaveable { mutableStateOf("9100") }
    var role by rememberSaveable { mutableStateOf(LabelKind.STICKY) }
    var lang by rememberSaveable { mutableStateOf(PrintLang.ZPL) }
    var result by remember { mutableStateOf<String?>(null) }
    val testing by vm.testing.collectAsStateWithLifecycle()
    val ipOk = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""").matchEntire(host.trim())
        ?.groupValues?.drop(1)?.all { it.toInt() in 0..255 } == true
    val portNum = port.toIntOrNull()?.takeIf { it in 1..65535 }
    val valid = ipOk && portNum != null
    fun draft() = SavedPrinter("draft", "Принтер ${host.trim()}", role, host.trim(), portNum ?: 9100, "ip:${host.trim()}", Transport.LAN, if (role == LabelKind.PLAIN) PrintLang.PDF else lang)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Принтер за IP") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("IP видно на сторінці налаштувань принтера (друкується кнопкою FEED / меню).", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    host, { host = it.filter { c -> c.isDigit() || c == '.' } }, singleLine = true,
                    label = { Text("IP") }, placeholder = { Text("192.168.1.50") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                OutlinedTextField(
                    port, { port = it.filter(Char::isDigit).take(5) }, singleLine = true,
                    label = { Text("Порт") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                RoleAndLang(role, lang, { role = it }, { lang = it })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(enabled = valid && testing == null, onClick = { vm.test(draft()) { result = it } }) { Text("Тест") }
                    if (testing == "draft") CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                }
                result?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(enabled = valid && !busy, onClick = { vm.addManual(host.trim(), portNum!!, role, draft().lang, onDismiss) }) { Text("Зберегти") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Скасувати") } },
    )
}

/** Wi-Fi самого принтера: DIRECT-xx (Brother Wireless Direct), Wi-Fi Direct, режим точки доступа. */
@Composable
private fun WifiDialog(vm: PrintersViewModel, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var ssid by rememberSaveable { mutableStateOf("") }
    var pass by rememberSaveable { mutableStateOf("") }
    var host by rememberSaveable { mutableStateOf("") }
    var port by rememberSaveable { mutableStateOf("9100") }
    var role by rememberSaveable { mutableStateOf(LabelKind.PLAIN) }
    var lang by rememberSaveable { mutableStateOf(PrintLang.ZPL) }
    var result by remember { mutableStateOf<String?>(null) }
    val testing by vm.testing.collectAsStateWithLifecycle()
    val portNum = port.toIntOrNull()?.takeIf { it in 1..65535 }
    val valid = ssid.isNotBlank() && (pass.isEmpty() || pass.length >= 8) && portNum != null
    fun draft(id: String = "draft") = SavedPrinter(
        id, name.trim().ifEmpty { ssid.trim() }, role, host.trim(), portNum ?: 9100, "wifi:${ssid.trim()}",
        Transport.WIFI_DIRECT, if (role == LabelKind.PLAIN) PrintLang.PDF else lang, ssid.trim(), pass,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Wi-Fi принтера") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Text(
                        if (vm.wifiSupported) "Без роутера: телефон підключиться до мережі самого принтера лише на час друку (Android попросить дозвіл). " +
                            "Назва мережі й пароль — на сторінці налаштувань принтера. Brother: Wireless Direct, мережа «DIRECT-…»."
                        else "Потрібен Android 10 або новіше. На цьому телефоні підключись до Wi-Fi принтера в налаштуваннях і додай його «За IP».",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                item { OutlinedTextField(ssid, { ssid = it }, singleLine = true, label = { Text("Назва Wi-Fi принтера (SSID)") }, placeholder = { Text("DIRECT-xxBRxxxx") }) }
                item {
                    OutlinedTextField(
                        pass, { pass = it }, singleLine = true, label = { Text("Пароль Wi-Fi (порожньо — без пароля)") },
                        visualTransformation = PasswordVisualTransformation(),
                        isError = pass.isNotEmpty() && pass.length < 8,
                        supportingText = { if (pass.isNotEmpty() && pass.length < 8) Text("Щонайменше 8 символів") },
                    )
                }
                item { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Назва в застосунку (необов'язково)") }) }
                item {
                    OutlinedTextField(
                        host, { host = it.filter { c -> c.isDigit() || c == '.' } }, singleLine = true,
                        label = { Text("IP принтера (порожньо — визначимо самі)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                }
                item {
                    OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) }, singleLine = true, label = { Text("Порт") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
                item { RoleAndLang(role, lang, { role = it }, { lang = it }) }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(enabled = valid && vm.wifiSupported && testing == null, onClick = { vm.test(draft()) { result = it } }) { Text("Тест") }
                        if (testing == "draft") CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    }
                }
                result?.let { item { Text(it, style = MaterialTheme.typography.bodySmall) } }
            }
        },
        confirmButton = {
            TextButton(enabled = valid && vm.wifiSupported, onClick = { vm.save(draft(UUID.randomUUID().toString())); onDismiss() }) { Text("Зберегти") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Скасувати") } },
    )
}

/** Bluetooth: список спаренных устройств; спаривание — в настройках телефона. */
@Composable
private fun BluetoothDialog(vm: PrintersViewModel, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val bt = vm.bluetooth
    var granted by remember { mutableStateOf(bt.permissionGranted()) }
    var refresh by remember { mutableStateOf(0) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) {
        if (!granted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) launcher.launch(Manifest.permission.BLUETOOTH_CONNECT)
    }
    val devices = remember(granted, refresh) { bt.bonded() }
    var picked by remember { mutableStateOf<Pair<String, String>?>(null) }
    var role by rememberSaveable { mutableStateOf(LabelKind.STICKY) }
    var lang by rememberSaveable { mutableStateOf(PrintLang.ZPL) }
    var result by remember { mutableStateOf<String?>(null) }
    val testing by vm.testing.collectAsStateWithLifecycle()
    fun draft(id: String = "draft") = picked?.let { (name, mac) ->
        SavedPrinter(id, name, role, mac, 0, "bt:$mac", Transport.BLUETOOTH, if (role == LabelKind.PLAIN) PrintLang.PDF else lang)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Bluetooth-принтер") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                when {
                    !bt.available -> item { Text("На цьому телефоні немає Bluetooth.") }
                    !granted -> item {
                        Column {
                            Text("Потрібен дозвіл на Bluetooth.")
                            TextButton(onClick = { launcher.launch(Manifest.permission.BLUETOOTH_CONNECT) }) { Text("Дозволити") }
                        }
                    }
                    !bt.enabled -> item {
                        Column {
                            Text("Bluetooth вимкнено.")
                            TextButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }) { Text("Відкрити налаштування") }
                        }
                    }
                    else -> {
                        item {
                            Text(
                                "Спочатку спар принтер у налаштуваннях Bluetooth телефона (PIN зазвичай 0000 або 1234), потім обери його тут.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        if (devices.isEmpty()) item { Text("Спарених пристроїв немає.") }
                        items(devices, key = { it.second }) { d ->
                            Surface(
                                Modifier.fillMaxWidth().clickable { picked = d; result = null },
                                shape = MaterialTheme.shapes.medium,
                                tonalElevation = if (picked == d) 4.dp else 0.dp,
                                color = if (picked == d) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                            ) {
                                Column(Modifier.padding(10.dp)) {
                                    Text(d.first)
                                    Text(d.second, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        item {
                            Row {
                                TextButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }) { Text("Спарити новий") }
                                TextButton(onClick = { refresh++ }) { Text("Оновити список") }
                            }
                        }
                        if (picked != null) {
                            item { RoleAndLang(role, lang, { role = it }, { lang = it }) }
                            item {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    TextButton(enabled = testing == null, onClick = { draft()?.let { p -> vm.test(p) { result = it } } }) { Text("Тест") }
                                    if (testing == "draft") CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                }
                            }
                        }
                        result?.let { item { Text(it, style = MaterialTheme.typography.bodySmall) } }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = picked != null, onClick = { draft(UUID.randomUUID().toString())?.let(vm::save); onDismiss() }) { Text("Зберегти") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Скасувати") } },
    )
}
