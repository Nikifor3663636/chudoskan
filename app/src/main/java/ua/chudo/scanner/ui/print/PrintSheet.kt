package ua.chudo.scanner.ui.print

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TextButton
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import ua.chudo.scanner.chudoApp
import ua.chudo.scanner.print.LabelKind
import ua.chudo.scanner.print.PriceFormat
import ua.chudo.scanner.print.PriceTag
import ua.chudo.scanner.print.QueueItem
import ua.chudo.scanner.print.Template
import ua.chudo.scanner.print.toItem
import ua.chudo.scanner.print.PrinterRecord
import ua.chudo.scanner.print.PrintService
import ua.chudo.scanner.print.PreferredPrinterUnavailable
import ua.chudo.scanner.ui.common.MineChip
import ua.chudo.scanner.ui.common.MinePrimaryButton
import ua.chudo.scanner.ui.common.MineStepper
import ua.chudo.scanner.ui.common.StatusDot
import ua.chudo.scanner.ui.theme.Mine

/** «Друкувати 2 цінники», «Друкувати 5 етикеток». */
fun printButtonText(t: Template, n: Int): String {
    val forms = if (t == Template.LABEL_40x24) Triple("етикетку", "етикетки", "етикеток") else Triple("цінник", "цінники", "цінників")
    val w = when {
        n % 100 in 11..14 -> forms.third
        n % 10 == 1 -> forms.first
        n % 10 in 2..4 -> forms.second
        else -> forms.third
    }
    return "Друкувати $n $w"
}

fun formatTitle(t: Template) = when (t) {
    Template.LABEL_40x24 -> "Етикетка 40×24"
    Template.TAG_68x38 -> "Цінник 68×38"
    Template.BIG -> "Крупногабарит"
}

/** Назва та стан обраного принтера. [missing] — обраного немає серед доступних, [model] — «Обраний принтер не підключено». */
data class PrinterLine(val model: String?, val online: Boolean?, val missing: Boolean = false)

/** Состояние листа печати (без ViewModel — для превью). */
data class PrintSheetUi(
    val subtitle: String,
    val format: Template,
    val promo: Boolean,
    /** null — у товара нет старой цены, переключатель не показываем. */
    val oldPrice: String?,
    val qty: Int,
    val printer: PrinterLine,
    val busy: Boolean = false,
    /** Принтер не нашли / ошибка печати: одно сообщение, кнопка становится «Спробувати ще». */
    val error: String? = null,
    val alternative: String? = null,
)

/** Друк цінника: формат, кількість і локальний вибір принтера. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrintSheet(tag: PriceTag, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    val svc = LocalContext.current.chudoApp.printing
    val scope = rememberCoroutineScope()
    var format by rememberSaveable { mutableStateOf(Template.TAG_68x38) }
    var promo by rememberSaveable { mutableStateOf(true) }
    var qty by rememberSaveable { mutableIntStateOf(1) }
    var line by remember { mutableStateOf(PrinterLine(null, null)) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var choosing by remember { mutableStateOf(false) }
    var statusRevision by remember { mutableIntStateOf(0) }
    var alternative by remember { mutableStateOf<PrinterRecord?>(null) }
    val preferred by remember(format.kind) { svc.store.preferred(format.kind) }.collectAsStateWithLifecycle("auto")
    val records by svc.records.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { svc.store.lastTemplate.collect { t -> if (t != null) format = t } }
    LaunchedEffect(format.kind, preferred, records, statusRevision) {
        line = PrinterLine(null, null)
        try {
            line = svc.quickStatus(format.kind)?.let { (name, ok) -> PrinterLine(name, ok, missing = name == PrintService.MISSING) }
                ?: PrinterLine(null, false)
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            error = e.message
            line = PrinterLine(null, false)
        }
    }
    val item = tag.toItem()
    fun print(override: PrinterRecord? = null) {
        if (busy) return
        busy = true
        error = null
        alternative = null
        val template = format
        val queue = listOf(QueueItem(tag, qty))
        val withPromo = promo && item.canPromo && template.kind == LabelKind.PLAIN
        scope.launch {
            try {
                val r = if (override == null) svc.print(queue, template, withPromo)
                    else { svc.printOn(override, queue, template, withPromo); override }
                line = PrinterLine(r.displayName, true)
                onDone("Надруковано")
                onDismiss()
            } catch (e: CancellationException) { throw e
            } catch (e: PreferredPrinterUnavailable) {
                error = e.message
                line = if (e.pick.missing) PrinterLine(PrintService.MISSING, false, missing = true)
                    else PrinterLine(e.pick.record.displayName, false)
                alternative = e.pick.alternative
            } catch (e: Exception) {
                error = e.message ?: "Не вдалося надрукувати"
            } finally { busy = false }
        }
    }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Mine.colors.sheet, contentColor = Mine.colors.text) {
        PrintSheetContent(
            PrintSheetUi(
                subtitle = listOfNotNull(tag.name.ifBlank { null }, item.price?.let { "${PriceFormat.tag(it).first} ₴" }).joinToString(", "),
                format = format,
                promo = promo && item.canPromo,
                oldPrice = if (item.canPromo) PriceFormat.tag(item.oldPrice!!).first else null,
                qty = qty, printer = line, busy = busy, error = error, alternative = alternative?.displayName,
            ),
            onFormat = { if (!busy) { format = it; error = null; alternative = null; scope.launch { svc.store.setLastTemplate(it) } } },
            onPromo = { promo = it },
            onQty = { qty = it },
            onPrint = { print() },
            onChoosePrinter = { choosing = true },
            onAlternative = { alternative?.let { print(it) } },
        )
    }
    if (choosing) PrinterPicker(svc, format.kind, preferred, onDismiss = { choosing = false }) { id ->
        svc.store.setPreferred(format.kind, id)
        svc.resolver.forget(format.kind)
        error = null
        alternative = null
        choosing = false
        statusRevision++
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PrintSheetContent(
    ui: PrintSheetUi,
    onFormat: (Template) -> Unit = {},
    onPromo: (Boolean) -> Unit = {},
    onQty: (Int) -> Unit = {},
    onPrint: () -> Unit = {},
    onChoosePrinter: () -> Unit = {},
    onAlternative: () -> Unit = {},
) {
    val m = Mine.colors
    Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 30.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column {
            Text("Друк цінника", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = m.text)
            Text(ui.subtitle, Modifier.padding(top = 4.dp), fontSize = 14.sp, lineHeight = 20.sp, color = m.textSecondary)
        }
        Column {
            Text("Формат", Modifier.padding(bottom = 8.dp), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = m.textSecondary)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Template.entries.forEach { t -> MineChip(formatTitle(t), ui.format == t, { onFormat(t) }) }
            }
        }
        if (ui.oldPrice != null && ui.format.kind == LabelKind.PLAIN) {
            Surface(shape = RoundedCornerShape(16.dp), color = m.card, contentColor = m.text) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Акційний цінник", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        Text("Стара ціна ${ui.oldPrice} ₴ буде закреслена. Встав жовтий папір.", fontSize = 13.sp, lineHeight = 17.sp, color = m.textSecondary)
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(
                        ui.promo, onPromo,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = m.accent, checkedThumbColor = m.onAccent, checkedBorderColor = Color.Transparent,
                            uncheckedTrackColor = m.surface, uncheckedThumbColor = m.textSecondary, uncheckedBorderColor = m.outline,
                        ),
                    )
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Кількість", Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.Medium, color = m.text)
            MineStepper(ui.qty, onQty)
        }
        PrinterStatusLine(ui.printer, !ui.busy, onChoosePrinter)
        ui.error?.let { Text(it, fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium, color = m.warningText) }
        ui.alternative?.let { name ->
            Surface(onClick = onAlternative, enabled = !ui.busy, color = m.accent,
                contentColor = m.onAccent, shape = RoundedCornerShape(28.dp)) {
                Text("Друкувати на $name", Modifier.fillMaxWidth().padding(16.dp),
                    fontSize = 17.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            }
        }
        MinePrimaryButton(
            when {
                ui.busy -> "Друкую…"
                ui.error != null -> "Спробувати ще"
                else -> printButtonText(ui.format, ui.qty)
            },
            onPrint, height = 58.dp, enabled = !ui.busy,
        )
    }
}

/** Кнопка вибору принтера зі станом підключення. */
@Composable
private fun PrinterStatusLine(p: PrinterLine, enabled: Boolean, onClick: () -> Unit) {
    val m = Mine.colors
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(horizontal = 4.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        StatusDot(
            when (p.online) {
                true -> m.success
                false -> m.warning
                null -> m.outline
            },
        )
        Spacer(Modifier.width(8.dp))
        Text(
            when {
                p.online == null -> "Шукаю принтер… ▾"
                p.model == null -> "Автоматично · вибрати принтер ▾"
                p.missing -> "${p.model} ▾"
                p.online -> "${p.model} · онлайн ▾"
                else -> "${p.model} · не відповідає ▾"
            },
            fontSize = 14.sp, color = m.textSecondary,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PrinterPicker(
    svc: PrintService, kind: LabelKind, preferred: String,
    onDismiss: () -> Unit, onSelect: suspend (String) -> Unit,
) {
    val m = Mine.colors
    val scope = rememberCoroutineScope()
    var printers by remember(kind) { mutableStateOf<List<PrinterRecord>?>(null) }
    var statuses by remember(kind) { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(kind, refresh) {
        error = null
        printers = null
        statuses = emptyMap()
        try {
            val list = svc.availablePrinters(kind)
            printers = list
            list.forEach { r -> launch { statuses = statuses + (r.id to svc.isAlive(r)) } }
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            error = e.message ?: "Не вдалося завантажити принтери"
        }
    }
    fun select(id: String) {
        if (saving) return
        saving = true
        scope.launch {
            try { onSelect(id) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "Не вдалося зберегти вибір" }
            finally { saving = false }
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = m.sheet, contentColor = m.text) {
        LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text("Принтер · ${kindShort(kind)}", fontSize = 22.sp, fontWeight = FontWeight.Bold) }
            item {
                Surface(onClick = { select("auto") }, enabled = !saving, shape = RoundedCornerShape(16.dp), color = m.card) {
                    Text("Автоматично" + if (preferred == "auto") "  ✓" else "", Modifier.fillMaxWidth().padding(16.dp), color = m.text)
                }
            }
            if (printers == null && error == null) item { CircularProgressIndicator(color = m.accent) }
            items(printers.orEmpty(), key = { it.id }) { r ->
                Surface(onClick = { select(r.id) }, enabled = !saving, shape = RoundedCornerShape(16.dp), color = m.card) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(r.displayName + if (preferred == r.id) "  ✓" else "", fontWeight = FontWeight.Bold, color = m.text)
                        Text("${r.model} · ${r.address}", fontSize = 13.sp, color = m.textSecondary)
                        val status = statuses[r.id]
                        if (status == null) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = m.accent)
                        else Text(if (status) "Онлайн" else "Не відповідає", color = if (status) m.success else m.warningText, fontSize = 14.sp)
                    }
                }
            }
            if (printers?.isEmpty() == true) item { Text("Принтерів цього формату не знайдено", color = m.textSecondary) }
            error?.let { message -> item { Text(message, color = m.warningText) } }
            item { TextButton(onClick = { refresh++ }, enabled = !saving) { Text("Оновити", color = m.accent) } }
            item { Spacer(Modifier.size(20.dp)) }
        }
    }
}
