package ua.chudo.scanner.ui.print

import androidx.compose.ui.graphics.Color
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.text.style.TextOverflow
import ua.chudo.scanner.R
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Sell
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import ua.chudo.scanner.chudoApp
import ua.chudo.scanner.data.Employee
import ua.chudo.scanner.data.PcCard
import ua.chudo.scanner.data.PcPairingException
import ua.chudo.scanner.data.PcRepository
import ua.chudo.scanner.ui.auth.authMessage
import ua.chudo.scanner.print.Cidr
import ua.chudo.scanner.print.Discovered
import ua.chudo.scanner.print.LabelKind
import ua.chudo.scanner.print.Link
import ua.chudo.scanner.print.PrintLang
import ua.chudo.scanner.print.PrintService
import ua.chudo.scanner.print.PrinterRecord
import ua.chudo.scanner.print.PrinterStatus
import ua.chudo.scanner.ui.common.MineButton
import ua.chudo.scanner.ui.common.MineCard
import ua.chudo.scanner.ui.common.MineChip
import ua.chudo.scanner.ui.common.MineHeader
import ua.chudo.scanner.ui.common.MineIconBox
import ua.chudo.scanner.ui.common.MinePrimaryButton
import ua.chudo.scanner.ui.common.MineRow
import ua.chudo.scanner.ui.common.MineStepper
import ua.chudo.scanner.ui.common.MineTone
import ua.chudo.scanner.ui.common.StatusDot
import ua.chudo.scanner.ui.theme.Mine

/** «Профіль → Принтери» — только администратор: реестр магазина, проверка, тест, настройки Godex. */
class PrintersViewModel(
    private val svc: PrintService,
    private val shop: String,
    private val employee: Employee? = null,
    private val pcRepo: PcRepository? = null,
) : ViewModel() {
    /** Комп'ютери магазину з «ЧудоСкан Друк». */
    private val _pcs = MutableStateFlow<List<PcCard>>(emptyList())
    val pcs = _pcs.asStateFlow()
    private val _pairing = MutableStateFlow(false)
    val pairing = _pairing.asStateFlow()
    private val _pairError = MutableStateFlow<String?>(null)
    val pairError = _pairError.asStateFlow()

    fun refreshPcs() {
        val r = pcRepo ?: return
        val e = employee ?: return
        viewModelScope.launch { _pcs.value = runCatching { r.pcs(e.shopId) }.getOrDefault(_pcs.value) }
    }

    /** Код із вікна програми → devices/{uid}. true — підключено. */
    fun pairPc(code: String, onDone: () -> Unit) {
        val r = pcRepo ?: return
        val e = employee ?: return
        if (_pairing.value) return
        _pairing.value = true
        _pairError.value = null
        viewModelScope.launch {
            try {
                val name = r.pair(e, code)
                _snack.emit("Комп'ютер ${name} підключено")
                onDone()
                refreshPcs()
                scan()
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: PcPairingException) {
                _pairError.value = ex.message
            } catch (ex: Exception) {
                _pairError.value = ex.authMessage()
            } finally {
                _pairing.value = false
            }
        }
    }

    fun clearPairError() { _pairError.value = null }

    fun unpairPc(pc: PcCard) {
        val r = pcRepo ?: return
        val e = employee ?: return
        viewModelScope.launch {
            try {
                r.unpair(e, pc)
                _snack.emit("Комп'ютер ${pc.pcName} відключено")
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                _snack.emit(ex.authMessage())
            }
            refreshPcs()
        }
    }

    val records = svc.records
    private val _status = MutableStateFlow<Map<String, PrinterStatus?>>(emptyMap())
    val status = _status.asStateFlow()
    private val _scanning = MutableStateFlow(false)
    val scanning = _scanning.asStateFlow()

    /** На 9100 что-то есть, но модель не узнали: в реестр само не пишется. */
    private val _unknown = MutableStateFlow<List<Discovered>>(emptyList())
    val unknown = _unknown.asStateFlow()
    private val _usb = MutableStateFlow<List<PrinterRecord>>(emptyList())
    val usb = _usb.asStateFlow()

    /** Принтеры компьютеров на связи, которых ещё нет в реестре. */
    private val _agents = MutableStateFlow<List<PrinterRecord>>(emptyList())
    val agents = _agents.asStateFlow()
    private val _testing = MutableStateFlow<String?>(null)
    val testing = _testing.asStateFlow()
    private val _snack = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val snack = _snack.asSharedFlow()

    init {
        viewModelScope.launch {
            refreshStatus(withTimeoutOrNull(5_000) { svc.records.filterNotNull().first() }.orEmpty())
            refreshPcs()
            scan()
        }
    }

    fun chosen(kind: LabelKind): String? = svc.resolver.chosen(kind)

    fun refreshStatus(list: List<PrinterRecord> = records.value.orEmpty()) {
        list.forEach { r ->
            _status.value = _status.value + (r.id to null)
            viewModelScope.launch {
                val s = runCatching { svc.status(r) }.getOrElse { PrinterStatus(false, it.message ?: "Помилка") }
                _status.value = _status.value + (r.id to s)
            }
        }
    }

    /** «Оновити» / «Знайти знову»: поиск по подсетям магазина; узнанные сами попадают в реестр. */
    fun scan() {
        if (_scanning.value) return
        _scanning.value = true
        viewModelScope.launch {
            try {
                _unknown.value = runCatching { svc.adminScan() }.getOrDefault(emptyList())
                refreshUsb()
                val inRegistry = records.value.orEmpty().map { it.id }.toSet()
                _agents.value = runCatching { svc.agentPrinters() }.getOrDefault(emptyList()).filter { it.id !in inRegistry }
                refreshStatus()
            } finally {
                _scanning.value = false
            }
        }
    }

    fun refreshUsb() {
        _usb.value = runCatching { svc.usb.list() }.getOrDefault(emptyList())
        refreshStatus(_usb.value)
    }

    fun test(r: PrinterRecord) {
        if (_testing.value != null) return
        _testing.value = r.id
        viewModelScope.launch {
            val msg = try {
                svc.test(r, shop)
            } catch (e: CancellationException) {
                throw e
            } catch (e: SecurityException) {
                "Немає дозволу USB: дозволь застосунку доступ до принтера."
            } catch (e: Exception) {
                e.message ?: e.javaClass.simpleName
            } finally {
                _testing.value = null
            }
            _snack.emit(msg)
            refreshStatus(listOf(r))
        }
    }

    fun addManual(ip: String, kind: LabelKind, onDone: () -> Unit) = viewModelScope.launch {
        try {
            svc.addManual(ip, kind)
            onDone()
        } catch (e: Exception) {
            _snack.emit(e.message ?: "Не вдалося додати")
        }
    }

    /** Неопознанное устройство: администратор сказал, что это. */
    fun addUnknown(d: Discovered, kind: LabelKind) = addManual(d.ip, kind) { _unknown.value = _unknown.value - d }

    fun setTuning(r: PrinterRecord, density: Int?, speed: Int?) = viewModelScope.launch {
        runCatching { svc.setTuning(r, density, speed) }.onFailure { _snack.emit(it.message ?: "Не вдалося зберегти") }
    }

    fun delete(r: PrinterRecord) = viewModelScope.launch {
        runCatching { svc.delete(r) }.onFailure { _snack.emit("Видаляти принтери може лише адміністратор.") }
    }

    fun rename(r: PrinterRecord, name: String, onDone: () -> Unit) = viewModelScope.launch {
        try { svc.rename(r, name); onDone() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { _snack.emit(e.message ?: "Не вдалося перейменувати") }
    }
}

fun kindShort(k: LabelKind) = if (k == LabelKind.STICKY) "40×24" else "A4"

@Composable
fun PrintersScreen(employee: Employee, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.chudoApp
    val vm: PrintersViewModel = viewModel(factory = viewModelFactory { initializer { PrintersViewModel(app.printing, employee.shop, employee, PcRepository(com.google.firebase.firestore.FirebaseFirestore.getInstance())) } })
    val records by vm.records.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val scanning by vm.scanning.collectAsStateWithLifecycle()
    val unknown by vm.unknown.collectAsStateWithLifecycle()
    val usb by vm.usb.collectAsStateWithLifecycle()
    val agents by vm.agents.collectAsStateWithLifecycle()
    val testing by vm.testing.collectAsStateWithLifecycle()
    val pcs by vm.pcs.collectAsStateWithLifecycle()
    val pairBusy by vm.pairing.collectAsStateWithLifecycle()
    val pairError by vm.pairError.collectAsStateWithLifecycle()
    var pairOpen by rememberSaveable { mutableStateOf(false) }
    var unpair by remember { mutableStateOf<PcCard?>(null) }
    val snackbar = remember { SnackbarHostState() }
    var manual by rememberSaveable { mutableStateOf(false) }
    var guide by rememberSaveable { mutableStateOf(false) }
    var editing by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { vm.snack.collect { snackbar.showSnackbar(it) } }

    // USB OTG: принтер вставили или вынули, пока экран открыт.
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) = vm.refreshUsb()
        }
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(ctx, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { runCatching { ctx.unregisterReceiver(receiver) } }
    }

    val knownStates = remember(employee.shopId) { mutableMapOf<String, Boolean>() }
    LaunchedEffect(status, records, agents) {
        (records.orEmpty() + agents).forEach { printer ->
            val ok = status[printer.id]?.ok ?: return@forEach
            val previous = knownStates.put(printer.id, ok) ?: return@forEach
            if (previous != ok) app.prefs.addActivity(
                employee, ua.chudo.scanner.data.ActivityKind.PRINTER,
                if (ok) "Принтер знову онлайн" else "Принтер не відповідає", printer.displayName,
            )
        }
    }

    val list = records.orEmpty()
    // «Основний для»: выбор этой сессии, иначе первый отвечающий принтер вида.
    val main = LabelKind.entries.associateWith { k ->
        vm.chosen(k) ?: (list + usb).firstOrNull { it.kind == k && status[it.id]?.ok == true }?.id
    }
    Box(Modifier.fillMaxSize()) {
        PrintersContent(
            PrintersUi(list, usb, agents, unknown, status, main, scanning, testing, pcs),
            PrintersActions(
                onBack = onBack, onHelp = { guide = true }, onRefresh = vm::scan,
                onTest = vm::test, onFindAgain = { vm.scan() }, onEdit = { editing = it.id },
                onAddUnknown = vm::addUnknown, onManual = { manual = true },
                onPairPc = { vm.clearPairError(); pairOpen = true }, onUnpairPc = { unpair = it },
            ),
        )
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp))
    }

    if (pairOpen) PairDialog(pairBusy, pairError, onDismiss = { pairOpen = false }) { code -> vm.pairPc(code) { pairOpen = false } }
    unpair?.let { pc ->
        AlertDialog(
            onDismissRequest = { unpair = null },
            title = { Text("Відключити ${pc.pcName}?") },
            text = { Text("Друк з телефонів на Godex цього комп'ютера зупиниться. На комп'ютері з'явиться новий код підключення.") },
            confirmButton = { TextButton(onClick = { vm.unpairPc(pc); unpair = null }) { Text("Відключити") } },
            dismissButton = { TextButton(onClick = { unpair = null }) { Text("Скасувати") } },
        )
    }
    if (guide) PrinterGuide(onClose = { guide = false })
    if (manual) ManualDialog(onDismiss = { manual = false }) { ip, kind -> vm.addManual(ip, kind) { manual = false } }
    editing?.let { id ->
        val r = list.firstOrNull { it.id == id }
        if (r == null) editing = null else EditSheet(r, vm, onDismiss = { editing = null })
    }
}

/** Всё, что показывает экран (для превью — без ViewModel). */
data class PrintersUi(
    val records: List<PrinterRecord>,
    val usb: List<PrinterRecord>,
    val agents: List<PrinterRecord>,
    val unknown: List<Discovered>,
    val status: Map<String, PrinterStatus?>,
    /** Вид → id принтера, который сейчас берётся для печати. */
    val main: Map<LabelKind, String?>,
    val scanning: Boolean,
    val testing: String?,
    /** Комп'ютери магазину з «ЧудоСкан Друк» (devices + agents). */
    val pcs: List<PcCard> = emptyList(),
)

class PrintersActions(
    val onBack: () -> Unit = {},
    val onHelp: () -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onTest: (PrinterRecord) -> Unit = {},
    val onFindAgain: (PrinterRecord) -> Unit = {},
    val onEdit: (PrinterRecord) -> Unit = {},
    val onAddUnknown: (Discovered, LabelKind) -> Unit = { _, _ -> },
    val onManual: () -> Unit = {},
    val onPairPc: () -> Unit = {},
    val onUnpairPc: (PcCard) -> Unit = {},
)

@Composable
fun PrintersContent(ui: PrintersUi, a: PrintersActions) {
    val m = Mine.colors
    val network = ui.records.filter { it.transport == Link.TCP }
    val viaPc = ui.records.filter { it.transport == Link.AGENT } + ui.agents
    LazyColumn(Modifier.fillMaxSize().background(m.bg).statusBarsPadding()) {
        item {
            Row(
                Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = a.onBack, modifier = Modifier.size(40.dp)) {
                    Icon(painterResource(R.drawable.ic_redesign_chevron_left), "Назад", Modifier.size(24.dp), tint = m.textSecondary)
                }
                Text("Принтери", Modifier.weight(1f).padding(start = 4.dp), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = m.text)
                IconButton(onClick = a.onRefresh, enabled = !ui.scanning, modifier = Modifier.size(40.dp)) {
                    Icon(painterResource(R.drawable.ic_redesign_refresh_cw), "Знайти знову", Modifier.size(20.dp), tint = m.textSecondary)
                }
            }
            if (ui.scanning) androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth(), color = m.accent, trackColor = m.line)
        }
        item(key = "h-net") { SectionTitle("У мережі магазину") }
        items(network, key = { it.id }) { r -> RecordRow(r, ui, a) }
        items(ui.unknown, key = { "u" + it.ip }) { d -> UnknownRow(d, a.onAddUnknown) }
        if (network.isEmpty() && ui.unknown.isEmpty() && !ui.scanning) item(key = "e-net") { Empty("У мережі принтерів не знайдено.") }

        if (ui.usb.isNotEmpty()) item(key = "h-usb") { SectionTitle("USB") }
        items(ui.usb, key = { it.id }) { r -> RecordRow(r, ui, a) }

        item(key = "h-pc") { SectionTitle("Через комп’ютер") }
        items(ui.pcs, key = { "card" + it.uid }) { pc -> PcRow(pc, a.onUnpairPc) }
        items(viaPc, key = { "pc" + it.id }) { r -> RecordRow(r, ui, a) }
        item(key = "pair") {
            LinkRow(R.drawable.ic_redesign_plus, "Підключити комп’ютер", "Код з програми «ЧудоСкан Друк»", a.onPairPc)
            LinkRow(R.drawable.ic_redesign_plus, "Додати за IP", "Мережевий принтер, порт 9100", a.onManual)
            LinkRow(R.drawable.ic_redesign_settings, "Допомога", "Як підключити принтер", a.onHelp)
        }
        item {
            Text(
                "Принтери знаходяться автоматично й спільні для всіх телефонів магазину.",
                Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp), fontSize = 13.sp, color = m.textTertiary,
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    val m = Mine.colors
    Column {
        Text(text, Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = m.textSecondary)
        HorizontalDivider(color = m.line)
    }
}

@Composable
private fun Empty(text: String) {
    val m = Mine.colors
    Column(Modifier.fillMaxWidth().background(m.card)) {
        Text(text, Modifier.padding(horizontal = 16.dp, vertical = 14.dp), fontSize = 14.sp, color = m.textSecondary)
        HorizontalDivider(color = m.line)
    }
}

/** Рядок списку як у дизайні: іконка, назва, підпис, дія справа. */
@Composable
private fun PrinterRow(
    @DrawableRes icon: Int,
    title: String,
    onClick: (() -> Unit)?,
    sub: @Composable () -> Unit,
    trailing: @Composable () -> Unit = {},
) {
    val m = Mine.colors
    Row(
        Modifier.fillMaxWidth().background(m.card).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(icon), null, Modifier.size(20.dp), tint = m.textSecondary)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontSize = 15.sp, lineHeight = 19.5.sp, fontWeight = FontWeight.Medium, color = m.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            sub()
        }
        trailing()
    }
    HorizontalDivider(color = m.line)
}

@Composable
private fun RowSub(text: String, color: Color = Mine.colors.textTertiary, dot: Color? = null) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        dot?.let { StatusDot(it, Modifier.size(8.dp)) }
        Text(text, fontSize = 13.sp, lineHeight = 17.sp, color = color)
    }
}

/** Мала кнопка «Тест» / «Знайти» / «Відключити». */
@Composable
private fun SmallButton(text: String, onClick: () -> Unit, enabled: Boolean = true, busy: Boolean = false, danger: Boolean = false) {
    val m = Mine.colors
    Surface(
        onClick = onClick, enabled = enabled && !busy, modifier = Modifier.height(36.dp),
        shape = RoundedCornerShape(50), color = if (danger) Color.Transparent else m.card,
        contentColor = if (danger) m.danger else m.text,
        border = if (danger) null else BorderStroke(1.dp, m.outline),
    ) {
        Box(Modifier.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
            if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = m.accent)
            else Text(text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun RecordRow(r: PrinterRecord, ui: PrintersUi, a: PrintersActions) {
    val m = Mine.colors
    val status = ui.status[r.id]
    val inRegistry = ui.records.any { it.id == r.id } || r.transport == Link.USB
    val address = r.address
    val title = listOf(r.name, r.model).filter(String::isNotBlank).distinct().joinToString(" · ").ifBlank { address }
    val main = if (ui.main[r.kind] == r.id) "основний для ${kindShort(r.kind)}" else null
    val icon = if (r.transport == Link.AGENT) R.drawable.ic_redesign_laptop else R.drawable.ic_redesign_printer
    val edit: (() -> Unit)? = if (ui.records.any { it.id == r.id }) ({ a.onEdit(r) }) else null
    PrinterRow(
        icon, title, onClick = edit,
        sub = {
            val caption = listOfNotNull(address.takeIf { it.isNotBlank() && !title.contains(it) }, main).joinToString(" · ")
            if (caption.isNotEmpty()) RowSub(caption)
            if (status == null) RowSub("Перевіряю…")
            else if (status.ok != true || !inRegistry) RowSub(
                status.text + if (!inRegistry) " · ще не в реєстрі" else "",
                color = if (status.ok == false) m.warningText else m.textTertiary,
                dot = when (status.ok) { true -> m.success; false -> m.warning; null -> m.outline },
            )
        },
    ) {
        if (status?.ok == false) SmallButton("Знайти", { a.onFindAgain(r) })
        else SmallButton("Тест", { a.onTest(r) }, enabled = ui.testing == null, busy = ui.testing == r.id)
    }
}

@Composable
private fun LinkRow(@DrawableRes icon: Int, title: String, sub: String, onClick: () -> Unit) {
    PrinterRow(icon, title, onClick, sub = { RowSub(sub) }) {
        Icon(painterResource(R.drawable.ic_redesign_chevron_right), null, Modifier.size(18.dp), tint = Mine.colors.textTertiary)
    }
}

/** Неопознанное устройство на 9100: администратор говорит, что это, и оно попадает в реестр. */
@Composable
private fun UnknownRow(d: Discovered, onAdd: (Discovered, LabelKind) -> Unit) {
    PrinterRow(
        R.drawable.ic_redesign_printer, d.model ?: d.name ?: "Невідомий пристрій", onClick = null,
        sub = { RowSub("${d.ip} · порт ${d.port} · що це за принтер?") },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SmallButton("40×24", { onAdd(d, LabelKind.STICKY) })
            SmallButton("A4", { onAdd(d, LabelKind.PLAIN) })
        }
    }
}

/** «Змінити»: щільність і швидкість Godex, видалення з реєстру. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditSheet(r: PrinterRecord, vm: PrintersViewModel, onDismiss: () -> Unit) {
    val m = Mine.colors
    var confirm by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var name by remember(r.id, r.name) { mutableStateOf(r.name) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = m.sheet, contentColor = m.text) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 30.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column {
                Text(r.displayName, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("${r.kind.title} · ${r.address}", fontSize = 14.sp, color = m.textSecondary)
            }
            // Назва зберігається в реєстрі магазину; USB-принтер цього телефону туди не потрапляє.
            val canRename = r.transport != Link.USB
            if (canRename && renaming) {
                OutlinedTextField(name, { if (it.length <= 40) name = it },
                    label = { Text("Назва принтера") }, supportingText = { Text("${name.length}/40 · порожня назва — модель та адреса") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                MineButton("Зберегти назву", { vm.rename(r, name) { renaming = false } }, Modifier.fillMaxWidth())
            } else if (canRename) {
                MineButton("Перейменувати", { renaming = true }, Modifier.fillMaxWidth())
            }
            if (r.lang == PrintLang.EZPL) {
                Tuning("Щільність", r.density, 10, 0..19) { vm.setTuning(r, it, r.speed) }
                Tuning("Швидкість, дюйм/с", r.speed, 3, 2..5) { vm.setTuning(r, r.density, it) }
                Text(
                    "«Як у самотесті» — застосунок не змінює значення, збережене в принтері.",
                    fontSize = 13.sp, lineHeight = 18.sp, color = m.textSecondary,
                )
            }
            if (confirm) {
                Text("Прибрати «${r.displayName}» з реєстру магазину? Якщо принтер у мережі, пошук знайде його знову.", fontSize = 15.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MineButton("Скасувати", { confirm = false }, Modifier.weight(1f))
                    MineButton("Видалити", { vm.delete(r); onDismiss() }, Modifier.weight(1f), MineTone.DANGER)
                }
            } else {
                MineButton("Видалити з реєстру", { confirm = true }, Modifier.fillMaxWidth(), MineTone.DANGER)
            }
        }
    }
}

@Composable
private fun Tuning(title: String, value: Int?, start: Int, range: IntRange, onChange: (Int?) -> Unit) {
    val m = Mine.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            if (value == null) Text("як у самотесті", fontSize = 13.sp, color = m.textSecondary)
            else TextButton(onClick = { onChange(null) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                Text("Повернути як у самотесті", fontSize = 13.sp, color = m.accent)
            }
        }
        if (value == null) MineButton("Задати", { onChange(start) }) else MineStepper(value, { onChange(it) }, range)
    }
}

/** «Додати вручну»: IP + вид. */
@Composable
private fun ManualDialog(onDismiss: () -> Unit, onAdd: (String, LabelKind) -> Unit) {
    var ip by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf(LabelKind.PLAIN) }
    val valid = Cidr.parseIp(ip) != null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Додати вручну") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    ip, { ip = it.filter { c -> c.isDigit() || c == '.' } }, singleLine = true,
                    label = { Text("IP принтера") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MineChip("Етикетки 40×24", kind == LabelKind.STICKY, { kind = LabelKind.STICKY }, height = 40.dp)
                    MineChip("A4", kind == LabelKind.PLAIN, { kind = LabelKind.PLAIN }, height = 40.dp)
                }
                Text("Порт 9100. Модель і MAC визначимо самі; підмережа принтера додасться до пошуку.", fontSize = 13.sp, color = Mine.colors.textSecondary)
            }
        },
        confirmButton = { TextButton(enabled = valid, onClick = { onAdd(ip.trim(), kind) }) { Text("Додати") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Скасувати") } },
    )
}

/** ПК з «ЧудоСкан Друк»: на зв'язку, Godex і стан, черга, остання помилка; «Відключити». */
@Composable
private fun PcRow(pc: PcCard, onUnpair: (PcCard) -> Unit) {
    val m = Mine.colors
    val ok = pc.online && pc.printerOk && pc.printer != null
    PrinterRow(
        R.drawable.ic_redesign_laptop, pc.printer?.let { "Етикетки · $it" } ?: "ПК «${pc.pcName}»", onClick = null,
        sub = {
            RowSub(
                "ПК «${pc.pcName}» ${if (pc.online) "онлайн" else "немає зв'язку"} · у черзі ${pc.queue}",
                dot = if (ok) m.success else m.warning,
            )
            if (pc.printer == null) RowSub("Godex не знайдено", color = m.warningText)
            else if (!pc.printerOk) RowSub(pc.printerText ?: "Godex не готовий", color = m.warningText)
            if (pc.lastError.isNotBlank()) RowSub("Остання помилка: ${pc.lastError}", color = m.warningText)
        },
    ) {
        SmallButton("Відключити", { onUnpair(pc) }, danger = true)
    }
}

/** «Підключити комп'ютер»: 6 цифр з вікна програми «ЧудоСкан Друк». */
@Composable
private fun PairDialog(busy: Boolean, error: String?, onDismiss: () -> Unit, onPair: (String) -> Unit) {
    var code by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Підключити комп'ютер") },
        text = {
            Column {
                Text("Відкрий на комп'ютері з Godex програму «ЧудоСкан Друк» і введи код із синьої смуги.", fontSize = 15.sp, lineHeight = 20.sp)
                OutlinedTextField(
                    code, { v -> code = v.filter(Char::isDigit).take(6) }, Modifier.padding(top = 12.dp).fillMaxWidth(),
                    label = { Text("Код, 6 цифр") }, singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword),
                )
                error?.let { Text(it, Modifier.padding(top = 8.dp), fontSize = 14.sp, color = Mine.colors.warningText) }
            }
        },
        confirmButton = { TextButton(onClick = { onPair(code) }, enabled = !busy && code.length == 6) { Text(if (busy) "Підключаю…" else "Підключити") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Скасувати") } },
    )
}
