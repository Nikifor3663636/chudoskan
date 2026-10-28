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
    /** id РїСЂРёРЅС‚РµСЂР°, РґР»СЏ РєРѕС‚РѕСЂРѕРіРѕ РёРґС‘С‚ С‚РµСЃС‚. */
    private val _testing = MutableStateFlow<String?>(null)
    val testing = _testing.asStateFlow()

    val wifiSupported get() = svc.wifi.supported
    val bluetooth get() = svc.bluetooth

    init {
        viewModelScope.launch {
            val list = svc.store.printers.first()
            refreshStatus(list)
            // РџСЂРёРЅС‚РµСЂРѕРІ РµС‰С‘ РЅРµС‚ вЂ” СЃСЂР°Р·Сѓ РёС‰РµРј РІ СЃРµС‚Рё, РєР°Рє Zebra Setup Utility.
            if (list.isEmpty()) discover()
        }
    }

    fun refreshStatus(list: List<SavedPrinter> = printers.value) {
        list.forEach { p ->
            _status.value = _status.value + (p.id to null)
            viewModelScope.launch {
                val s = runCatching { svc.status(p) }.getOrElse { PrinterStatus(false, it.message ?: "РџРѕРјРёР»РєР°") }
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
                    _message.value = "РўРµР»РµС„РѕРЅ РЅРµ РІ Wi-Fi. РџС–РґРєР»СЋС‡РёСЃСЊ РґРѕ Wi-Fi РјР°РіР°Р·РёРЅСѓ Р°Р±Рѕ РґРѕРґР°Р№ РїСЂРёРЅС‚РµСЂ С‡РµСЂРµР· В«Wi-Fi РїСЂРёРЅС‚РµСЂР°В» С‡Рё Bluetooth."
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

    /** Р СѓС‡РЅРѕРµ РґРѕР±Р°РІР»РµРЅРёРµ РїРѕ IP: РѕС‚РїРµС‡Р°С‚РѕРє Рё РјРѕРґРµР»СЊ РѕРїСЂРµРґРµР»СЏРµРј РїРѕ СЃРµС‚Рё (SNMP / mDNS / ~HI). */
    fun addManual(host: String, port: Int, role: LabelKind, lang: PrintLang, onDone: () -> Unit) = viewModelScope.launch {
        _scanning.value = true
        try {
            val (fp, name) = svc.discovery.fingerprintOf(host, port, role)
            val p = SavedPrinter(UUID.randomUUID().toString(), name ?: "РџСЂРёРЅС‚РµСЂ $host", role, host, port, fp, Transport.LAN, lang)
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
                "Р”РѕР·РІРѕР»СЊ Р·Р°СЃС‚РѕСЃСѓРЅРєСѓ РґРѕСЃС‚СѓРї РґРѕ Bluetooth / Wi-Fi."
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
fun roleTitle(role: LabelKind) = if (role == LabelKind.STICKY) "Р›РёРїРєС–" else "Р—РІРёС‡Р°Р№РЅС–"

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

    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "РќР°Р·Р°Рґ") }
                Text("РџСЂРёРЅС‚РµСЂРё", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                IconButton(onClick = { vm.refreshStatus() }) { Icon(Icons.Rounded.Refresh, "РћРЅРѕРІРёС‚Рё СЃС‚Р°С‚СѓСЃ") }
            }
            Text(
                "Р‘СѓРґСЊ-СЏРєРёР№ РїСЂРёРЅС‚РµСЂ: С‡РµСЂРµР· РјРµСЂРµР¶Сѓ РјР°РіР°Р·РёРЅСѓ, РЅР°РїСЂСЏРјСѓ С‡РµСЂРµР· Wi-Fi РїСЂРёРЅС‚РµСЂР° Р°Р±Рѕ Bluetooth. " +
                    "Р›РёРїРєС– РµС‚РёРєРµС‚РєРё вЂ” ZPL, TSPL, EPL Р°Р±Рѕ ESC/POS; Р·РІРёС‡Р°Р№РЅС– A4 вЂ” СЃРёСЃС‚РµРјРЅРёР№ РґСЂСѓРє Android.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (printers.isEmpty()) item { Text("РџСЂРёРЅС‚РµСЂС–РІ С‰Рµ РЅРµРјР°С” вЂ” РґРѕРґР°Р№ РЅРёР¶С‡Рµ.", Modifier.padding(vertical = 4.dp)) }
        items(printers, key = { it.id }) { p ->
            SavedCard(p, status[p.id], vm) { info = it }
        }
        info?.let { item { Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) { Text(it, Modifier.padding(12.dp)) } } }

        item {
            Text("Р”РѕРґР°С‚Рё РїСЂРёРЅС‚РµСЂ", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(onClick = vm::discover, enabled = !scanning) { Icon(Icons.Rounded.Search, null); Spacer(Modifier.width(6.dp)); Text("Р—РЅР°Р№С‚Рё РІ РјРµСЂРµР¶С–") }
                FilledTonalButton(onClick = { dialog = "wifi" }) { Icon(Icons.Rounded.Wifi, null); Spacer(Modifier.width(6.dp)); Text("Wi-Fi РїСЂРёРЅС‚РµСЂР°") }
                FilledTonalButton(onClick = { dialog = "bt" }) { Icon(Icons.Rounded.Bluetooth, null); Spacer(Modifier.width(6.dp)); Text("Bluetooth") }
                OutlinedButton(onClick = { dialog = "ip" }) { Icon(Icons.Rounded.Lan, null); Spacer(Modifier.width(6.dp)); Text("Р—Р° IP") }
            }
        }
        if (scanning) item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                Text("  РЁСѓРєР°С”РјРѕ РїСЂРёРЅС‚РµСЂРё РІ РјРµСЂРµР¶С– (РґРѕ 10 СЃ)вЂ¦")
            }
        }
        message?.let { m -> item { Text(m, color = MaterialTheme.colorScheme.error) } }
        found?.let { list ->
            if (list.isEmpty() && !scanning && message == null) item {
                Text("РќРѕРІРёС… РїСЂРёРЅС‚РµСЂС–РІ Сѓ РјРµСЂРµР¶С– РЅРµ Р·РЅР°Р№РґРµРЅРѕ. РџРµСЂРµРІС–СЂ, С‰Рѕ РїСЂРёРЅС‚РµСЂ СѓРІС–РјРєРЅРµРЅРёР№ С– РІ С‚РѕРјСѓ Р¶ Wi-Fi, Р°Р±Рѕ РїС–РґРєР»СЋС‡РёСЃСЊ С‡РµСЂРµР· В«Wi-Fi РїСЂРёРЅС‚РµСЂР°В» / Bluetooth.")
            }
            items(list, key = { "f" + it.host }) { f -> FoundCard(f) { role, lang -> vm.add(f, role, lang) } }
        }
        item { HowTo() }
        item { Spacer(Modifier.height(24.dp)) }
    }

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
    Transport.LAN -> "${p.host}:${p.port} В· ${fingerprintTitle(p.fingerprint)}"
    Transport.WIFI_DIRECT -> "РјРµСЂРµР¶Р° В«${p.ssid}В»" + (p.host.takeIf { it.isNotEmpty() }?.let { " В· $it" } ?: "")
    Transport.BLUETOOTH -> p.host
}

private fun fingerprintTitle(fp: String) = when {
    fp.startsWith("brother:") || fp.startsWith("nsd:") -> "Р·РЅР°Р№РґРµРЅРѕ Р·Р° С–РјРµРЅРµРј"
    fp.startsWith("godex:") -> "Godex"
    fp.startsWith("snmp:") -> "Р·РЅР°Р№РґРµРЅРѕ Р·Р° РјРѕРґРµР»Р»СЋ"
    fp == ONLY_9100 -> "С”РґРёРЅРёР№ РїСЂРёРЅС‚РµСЂ РµС‚РёРєРµС‚РѕРє"
    else -> "Р»РёС€Рµ Р·Р° IP"
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
                    Text("${p.transport.title} В· ${address(p)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { vm.remove(p) }) { Icon(Icons.Rounded.Delete, "Р’РёРґР°Р»РёС‚Рё") }
            }
            StatusLine(status)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                LabelKind.entries.forEach { r -> FilterChip(selected = p.role == r, onClick = { vm.setRole(p, r) }, label = { Text(roleTitle(r)) }) }
                if (p.role == LabelKind.STICKY) LangPicker(p.lang) { vm.setLang(p, it) }
            }
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (p.role == LabelKind.STICKY) "РўРµСЃС‚ РґСЂСѓРєСѓС” РїСЂРѕР±РЅСѓ РµС‚РёРєРµС‚РєСѓ" else "РўРµСЃС‚ РїРµСЂРµРІС–СЂСЏС” Р·РІ'СЏР·РѕРє",
                    Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (testing == p.id) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else TextButton(onClick = { vm.test(p, onInfo) }, enabled = testing == null) { Text("РўРµСЃС‚") }
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
        Text(s?.text ?: "РџРµСЂРµРІС–СЂСЏС”РјРѕвЂ¦", style = MaterialTheme.typography.bodySmall, color = if (s?.ok == false) color else MaterialTheme.colorScheme.onSurface)
    }
}

/** Р’С‹РїР°РґР°СЋС‰РёР№ РІС‹Р±РѕСЂ СЏР·С‹РєР° РїСЂРёРЅС‚РµСЂР° СЌС‚РёРєРµС‚РѕРє. */
@Composable
private fun LangPicker(lang: PrintLang, onPick: (PrintLang) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(selected = true, onClick = { open = true }, label = { Text("РњРѕРІР°: ${lang.title} в–ѕ") })
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
            Text("${f.host}:${f.port} В· ${fingerprintTitle(f.fingerprint)}", style = MaterialTheme.typography.bodySmall)
            RoleAndLang(role, lang, { r ->
                role = r
                lang = if (r == LabelKind.PLAIN) PrintLang.PDF else lang.takeIf { it != PrintLang.PDF } ?: PrintLang.ZPL
            }, { lang = it })
            Button(onClick = { onAdd(role, lang) }, modifier = Modifier.align(Alignment.End)) { Text("Р”РѕРґР°С‚Рё") }
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
    fun draft() = SavedPrinter("draft", "РџСЂРёРЅС‚РµСЂ ${host.trim()}", role, host.trim(), portNum ?: 9100, "ip:${host.trim()}", Transport.LAN, if (role == LabelKind.PLAIN) PrintLang.PDF else lang)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("РџСЂРёРЅС‚РµСЂ Р·Р° IP") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("IP РІРёРґРЅРѕ РЅР° СЃС‚РѕСЂС–РЅС†С– РЅР°Р»Р°С€С‚СѓРІР°РЅСЊ РїСЂРёРЅС‚РµСЂР° (РґСЂСѓРєСѓС”С‚СЊСЃСЏ РєРЅРѕРїРєРѕСЋ FEED / РјРµРЅСЋ).", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    host, { host = it.filter { c -> c.isDigit() || c == '.' } }, singleLine = true,
                    label = { Text("IP") }, placeholder = { Text("192.168.1.50") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                OutlinedTextField(
                    port, { port = it.filter(Char::isDigit).take(5) }, singleLine = true,
                    label = { Text("РџРѕСЂС‚") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                RoleAndLang(role, lang, { role = it }, { lang = it })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(enabled = valid && testing == null, onClick = { vm.test(draft()) { result = it } }) { Text("РўРµСЃС‚") }
                    if (testing == "draft") CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                }
                result?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(enabled = valid && !busy, onClick = { vm.addManual(host.trim(), portNum!!, role, draft().lang, onDismiss) }) { Text("Р—Р±РµСЂРµРіС‚Рё") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("РЎРєР°СЃСѓРІР°С‚Рё") } },
    )
}

/** Wi-Fi СЃР°РјРѕРіРѕ РїСЂРёРЅС‚РµСЂР°: DIRECT-xx (Brother Wireless Direct), Wi-Fi Direct, СЂРµР¶РёРј С‚РѕС‡РєРё РґРѕСЃС‚СѓРїР°. */
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
        title = { Text("Wi-Fi РїСЂРёРЅС‚РµСЂР°") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Text(
                        if (vm.wifiSupported) "Р‘РµР· СЂРѕСѓС‚РµСЂР°: С‚РµР»РµС„РѕРЅ РїС–РґРєР»СЋС‡РёС‚СЊСЃСЏ РґРѕ РјРµСЂРµР¶С– СЃР°РјРѕРіРѕ РїСЂРёРЅС‚РµСЂР° Р»РёС€Рµ РЅР° С‡Р°СЃ РґСЂСѓРєСѓ (Android РїРѕРїСЂРѕСЃРёС‚СЊ РґРѕР·РІС–Р»). " +
                            "РќР°Р·РІР° РјРµСЂРµР¶С– Р№ РїР°СЂРѕР»СЊ вЂ” РЅР° СЃС‚РѕСЂС–РЅС†С– РЅР°Р»Р°С€С‚СѓРІР°РЅСЊ РїСЂРёРЅС‚РµСЂР°. Brother: Wireless Direct, РјРµСЂРµР¶Р° В«DIRECT-вЂ¦В»."
                        else "РџРѕС‚СЂС–Р±РµРЅ Android 10 Р°Р±Рѕ РЅРѕРІС–С€Рµ. РќР° С†СЊРѕРјСѓ С‚РµР»РµС„РѕРЅС– РїС–РґРєР»СЋС‡РёСЃСЊ РґРѕ Wi-Fi РїСЂРёРЅС‚РµСЂР° РІ РЅР°Р»Р°С€С‚СѓРІР°РЅРЅСЏС… С– РґРѕРґР°Р№ Р№РѕРіРѕ В«Р—Р° IPВ».",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                item { OutlinedTextField(ssid, { ssid = it }, singleLine = true, label = { Text("РќР°Р·РІР° Wi-Fi РїСЂРёРЅС‚РµСЂР° (SSID)") }, placeholder = { Text("DIRECT-xxBRxxxx") }) }
                item {
                    OutlinedTextField(
                        pass, { pass = it }, singleLine = true, label = { Text("РџР°СЂРѕР»СЊ Wi-Fi (РїРѕСЂРѕР¶РЅСЊРѕ вЂ” Р±РµР· РїР°СЂРѕР»СЏ)") },
                        visualTransformation = PasswordVisualTransformation(),
                        isError = pass.isNotEmpty() && pass.length < 8,
                        supportingText = { if (pass.isNotEmpty() && pass.length < 8) Text("Р©РѕРЅР°Р№РјРµРЅС€Рµ 8 СЃРёРјРІРѕР»С–РІ") },
                    )
                }
                item { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("РќР°Р·РІР° РІ Р·Р°СЃС‚РѕСЃСѓРЅРєСѓ (РЅРµРѕР±РѕРІ'СЏР·РєРѕРІРѕ)") }) }
                item {
                    OutlinedTextField(
                        host, { host = it.filter { c -> c.isDigit() || c == '.' } }, singleLine = true,
                        label = { Text("IP РїСЂРёРЅС‚РµСЂР° (РїРѕСЂРѕР¶РЅСЊРѕ вЂ” РІРёР·РЅР°С‡РёРјРѕ СЃР°РјС–)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                }
                item {
                    OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) }, singleLine = true, label = { Text("РџРѕСЂС‚") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
                item { RoleAndLang(role, lang, { role = it }, { lang = it }) }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(enabled = valid && vm.wifiSupported && testing == null, onClick = { vm.test(draft()) { result = it } }) { Text("РўРµСЃС‚") }
                        if (testing == "draft") CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    }
                }
                result?.let { item { Text(it, style = MaterialTheme.typography.bodySmall) } }
            }
        },
        confirmButton = {
            TextButton(enabled = valid && vm.wifiSupported, onClick = { vm.save(draft(UUID.randomUUID().toString())); onDismiss() }) { Text("Р—Р±РµСЂРµРіС‚Рё") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("РЎРєР°СЃСѓРІР°С‚Рё") } },
    )
}

/** Bluetooth: СЃРїРёСЃРѕРє СЃРїР°СЂРµРЅРЅС‹С… СѓСЃС‚СЂРѕР№СЃС‚РІ; СЃРїР°СЂРёРІР°РЅРёРµ вЂ” РІ РЅР°СЃС‚СЂРѕР№РєР°С… С‚РµР»РµС„РѕРЅР°. */
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
        title = { Text("Bluetooth-РїСЂРёРЅС‚РµСЂ") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                when {
                    !bt.available -> item { Text("РќР° С†СЊРѕРјСѓ С‚РµР»РµС„РѕРЅС– РЅРµРјР°С” Bluetooth.") }
                    !granted -> item {
                        Column {
                            Text("РџРѕС‚СЂС–Р±РµРЅ РґРѕР·РІС–Р» РЅР° Bluetooth.")
                            TextButton(onClick = { launcher.launch(Manifest.permission.BLUETOOTH_CONNECT) }) { Text("Р”РѕР·РІРѕР»РёС‚Рё") }
                        }
                    }
                    !bt.enabled -> item {
                        Column {
                            Text("Bluetooth РІРёРјРєРЅРµРЅРѕ.")
                            TextButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }) { Text("Р’С–РґРєСЂРёС‚Рё РЅР°Р»Р°С€С‚СѓРІР°РЅРЅСЏ") }
                        }
                    }
                    else -> {
                        item {
                            Text(
                                "РЎРїРѕС‡Р°С‚РєСѓ СЃРїР°СЂ РїСЂРёРЅС‚РµСЂ Сѓ РЅР°Р»Р°С€С‚СѓРІР°РЅРЅСЏС… Bluetooth С‚РµР»РµС„РѕРЅР° (PIN Р·Р°Р·РІРёС‡Р°Р№ 0000 Р°Р±Рѕ 1234), РїРѕС‚С–Рј РѕР±РµСЂРё Р№РѕРіРѕ С‚СѓС‚.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        if (devices.isEmpty()) item { Text("РЎРїР°СЂРµРЅРёС… РїСЂРёСЃС‚СЂРѕС—РІ РЅРµРјР°С”.") }
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
                                TextButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }) { Text("РЎРїР°СЂРёС‚Рё РЅРѕРІРёР№") }
                                TextButton(onClick = { refresh++ }) { Text("РћРЅРѕРІРёС‚Рё СЃРїРёСЃРѕРє") }
                            }
                        }
                        if (picked != null) {
                            item { RoleAndLang(role, lang, { role = it }, { lang = it }) }
                            item {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    TextButton(enabled = testing == null, onClick = { draft()?.let { p -> vm.test(p) { result = it } } }) { Text("РўРµСЃС‚") }
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
            TextButton(enabled = picked != null, onClick = { draft(UUID.randomUUID().toString())?.let(vm::save); onDismiss() }) { Text("Р—Р±РµСЂРµРіС‚Рё") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("РЎРєР°СЃСѓРІР°С‚Рё") } },
    )
}

/** РљРѕСЂРѕС‚РєР° С–РЅСЃС‚СЂСѓРєС†С–СЏ вЂ” СЏРє Zebra Setup Utility, Р°Р»Рµ РґР»СЏ Р±СѓРґСЊ-СЏРєРѕРіРѕ РїСЂРёРЅС‚РµСЂР°. */
@Composable
private fun HowTo() {
    var open by rememberSaveable { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(Modifier.padding(14.dp)) {
            Row(Modifier.fillMaxWidth().clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
                Text("РЇРє РїС–РґРєР»СЋС‡РёС‚Рё РїСЂРёРЅС‚РµСЂ", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Text(if (open) "в–ґ" else "в–ѕ")
            }
            if (open) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "вЂў РњРµСЂРµР¶Р° РјР°РіР°Р·РёРЅСѓ. РџСЂРёРЅС‚РµСЂ С– С‚РµР»РµС„РѕРЅ РјР°СЋС‚СЊ Р±СѓС‚Рё РІ РѕРґРЅРѕРјСѓ Wi-Fi. РќР°С‚РёСЃРЅРё В«Р—РЅР°Р№С‚Рё РІ РјРµСЂРµР¶С–В» вЂ” РјРѕРґРµР»СЊ С– РјРѕРІР° РІРёР·РЅР°С‡Р°С‚СЊСЃСЏ СЃР°РјС–.\n" +
                        "  РџС–РґРєР»СЋС‡РёС‚Рё СЃР°Рј РїСЂРёРЅС‚РµСЂ РґРѕ Wi-Fi РјР°РіР°Р·РёРЅСѓ вЂ” РѕРґРёРЅ СЂР°Р· Р· Р№РѕРіРѕ РјРµРЅСЋ (Network / WLAN), РєРЅРѕРїРєРѕСЋ WPS РЅР° СЂРѕСѓС‚РµСЂС– " +
                        "Р°Р±Рѕ РїСЂРѕРіСЂР°РјРѕСЋ РІРёСЂРѕР±РЅРёРєР° (Godex вЂ” GoLabel в†’ Wi-Fi Tool, Brother вЂ” РјРµРЅСЋ WLAN в†’ РњР°Р№СЃС‚РµСЂ РЅР°Р»Р°С€С‚СѓРІР°РЅРЅСЏ).\n\n" +
                        "вЂў Wi-Fi РїСЂРёРЅС‚РµСЂР° (Р±РµР· СЂРѕСѓС‚РµСЂР°). РЈРІС–РјРєРЅРё РЅР° РїСЂРёРЅС‚РµСЂС– Wireless Direct / Wi-Fi Direct, РІРІРµРґРё РЅР°Р·РІСѓ РјРµСЂРµР¶С– Р№ РїР°СЂРѕР»СЊ " +
                        "Р· Р№РѕРіРѕ СЃС‚РѕСЂС–РЅРєРё РЅР°Р»Р°С€С‚СѓРІР°РЅСЊ.\n\n" +
                        "вЂў Bluetooth. РЎРїР°СЂ РїСЂРёРЅС‚РµСЂ Сѓ РЅР°Р»Р°С€С‚СѓРІР°РЅРЅСЏС… С‚РµР»РµС„РѕРЅР° Р№ РѕР±РµСЂРё Р№РѕРіРѕ Сѓ СЃРїРёСЃРєСѓ.\n\n" +
                        "вЂў РњРѕРІР°. РЇРєС‰Рѕ С‚РµСЃС‚РѕРІР° РµС‚РёРєРµС‚РєР° РїРѕСЂРѕР¶РЅСЏ Р°Р±Рѕ Р· СЃРёРјРІРѕР»Р°РјРё Р·Р°РјС–СЃС‚СЊ С†С–РЅРё вЂ” РѕР±РµСЂРё С–РЅС€Сѓ РјРѕРІСѓ (ZPL в†’ TSPL в†’ EPL в†’ ESC/POS) С– РїРѕРІС‚РѕСЂРё С‚РµСЃС‚. " +
                        "Р—РІРёС‡Р°Р№РЅС– A4 РґСЂСѓРєСѓСЋС‚СЊСЃСЏ С‡РµСЂРµР· СЃРёСЃС‚РµРјРЅРёР№ РґСЂСѓРє Android вЂ” РїС–РґС…РѕРґРёС‚СЊ Р±СѓРґСЊ-СЏРєРёР№ РїСЂРёРЅС‚РµСЂ С–Р· СЃРµСЂРІС–СЃРѕРј РґСЂСѓРєСѓ (Mopria / РїР»Р°РіС–РЅ РІРёСЂРѕР±РЅРёРєР°).",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
