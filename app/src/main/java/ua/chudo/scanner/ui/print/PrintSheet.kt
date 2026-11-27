package ua.chudo.scanner.ui.print

import androidx.annotation.DrawableRes
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import ua.chudo.scanner.R
import ua.chudo.scanner.chudoApp
import ua.chudo.scanner.print.LabelFormat
import ua.chudo.scanner.print.LabelItem
import ua.chudo.scanner.print.LabelKind
import ua.chudo.scanner.print.LabelRenderer
import ua.chudo.scanner.print.PriceFormat
import ua.chudo.scanner.print.PriceTag
import ua.chudo.scanner.print.QueueItem
import ua.chudo.scanner.print.Template
import ua.chudo.scanner.print.toItem
import ua.chudo.scanner.print.PrinterRecord
import ua.chudo.scanner.print.PrintService
import ua.chudo.scanner.print.PreferredPrinterUnavailable
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
    Template.BIG -> "Крупногабарит 148×105"
    Template.BIG_140x100 -> "Крупногабарит 140×100"
}

/** «Альбомна: 20 на аркуш». */
fun orientationNote(t: Template, landscape: Boolean): String =
    t.layout(landscape)?.let { "${if (landscape) "Альбомна" else "Книжна"}: ${it.perPage} на аркуш" }.orEmpty()

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
    /** Скільки різних товарів у пакеті (1 — один цінник). */
    val items: Int = 1,
    /** Альбомний аркуш для A4-шаблону (для етикетки 40×24 не показуємо). */
    val landscape: Boolean = Template.TAG_68x38.defaultLandscape,
)

/** Друк цінника: формат, кількість і локальний вибір принтера. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrintSheet(tag: PriceTag, onDismiss: () -> Unit, onDone: (String) -> Unit) = PrintSheet(listOf(tag), onDismiss, onDone = onDone)

/** Пакет цінників (із «Залишків»): один документ на кілька аркушів; кількість — для кожного товару. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrintSheet(
    tags: List<PriceTag>,
    onDismiss: () -> Unit,
    openSheetPreview: Boolean = false,
    inline: Boolean = false,
    onBusy: (Boolean) -> Unit = {},
    onDone: (String) -> Unit,
) {
    val tag = tags.first()
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
    val landscape by remember(format) { svc.store.landscape(format) }.collectAsStateWithLifecycle(format.defaultLandscape)

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
    val items = tags.map { it.toItem() }
    val single = tags.size == 1
    val canPromo = items.any { it.canPromo }
    fun print(override: PrinterRecord? = null) {
        if (busy) return
        busy = true
        onBusy(true)
        error = null
        alternative = null
        val template = format
        val queue = tags.map { QueueItem(it, qty) }
        val withPromo = promo && canPromo && template.kind == LabelKind.PLAIN
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
            } finally {
                busy = false
                onBusy(false)
            }
        }
    }
    val content: @Composable () -> Unit = {
        PrintSheetContent(
            PrintSheetUi(
                subtitle = if (single) listOfNotNull(tag.name.ifBlank { null }, item.price?.let { "${PriceFormat.tag(it).first} ₴" }).joinToString(", ")
                    else "Товарів: ${tags.size} · кількість — для кожного",
                items = tags.size,
                format = format,
                promo = promo && canPromo,
                oldPrice = when { single && item.canPromo -> PriceFormat.tag(item.oldPrice!!).first; !single && canPromo -> ""; else -> null },
                qty = qty, printer = line, busy = busy, error = error, alternative = alternative?.displayName,
                landscape = landscape,
            ),
            scroll = !inline,
            preview = { PrintPreview(items, format, qty, promo && canPromo, landscape, openSheetPreview) },
            onLandscape = { v -> if (!busy) scope.launch { svc.store.setLandscape(format, v) } },
            onFormat = { if (!busy) { format = it; error = null; alternative = null; scope.launch { svc.store.setLastTemplate(it) } } },
            onPromo = { promo = it },
            onQty = { qty = it },
            onPrint = { print() },
            onChoosePrinter = { choosing = true },
            onAlternative = { alternative?.let { print(it) } },
        )
    }
    if (inline) {
        Surface(
            shape = RoundedCornerShape(8.dp), color = Mine.colors.card, contentColor = Mine.colors.text,
            border = BorderStroke(1.dp, Mine.colors.line),
        ) { Column(Modifier.padding(top = 12.dp)) { content() } }
    } else {
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = onDismiss, sheetState = sheet, containerColor = Mine.colors.sheet, contentColor = Mine.colors.text,
            shape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp),
        ) { content() }
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
    onLandscape: (Boolean) -> Unit = {},
    preview: (@Composable () -> Unit)? = null,
    scroll: Boolean = true,
) {
    val m = Mine.colors
    Column(Modifier.fillMaxWidth().then(if (scroll) Modifier.verticalScroll(rememberScrollState()) else Modifier)) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp)) {
            Text("Друк цінника", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = m.text)
            Text(
                ui.subtitle, Modifier.padding(top = 2.dp), fontSize = 14.sp, lineHeight = 21.sp,
                color = m.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Template.entries.forEach { template ->
                PrintChip(formatTitle(template), ui.format == template, enabled = !ui.busy) { onFormat(template) }
            }
        }
        Column(Modifier.padding(horizontal = 16.dp)) {
            preview?.invoke()
            if (ui.format.kind == LabelKind.PLAIN) {
                Column(Modifier.padding(top = 8.dp)) { OrientationPicker(ui.format, ui.landscape, onLandscape) }
            }
            if (ui.oldPrice != null && ui.format.kind == LabelKind.PLAIN) {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Акційний цінник", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        Text(
                            if (ui.oldPrice.isBlank()) "Стара ціна буде закреслена · жовтий папір"
                            else "Стара ціна ${ui.oldPrice} ₴ закреслена · жовтий папір",
                            fontSize = 13.sp, lineHeight = 19.5.sp, color = m.textTertiary,
                        )
                    }
                    Switch(
                        ui.promo, onPromo, enabled = !ui.busy,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = m.text, checkedThumbColor = m.card, checkedBorderColor = m.text,
                            uncheckedTrackColor = m.surface, uncheckedThumbColor = m.textSecondary, uncheckedBorderColor = m.outline,
                        ),
                    )
                }
                HorizontalDivider(color = m.line)
            }
            Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Кількість", Modifier.weight(1f), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = m.text)
                PrintCopies(ui.qty, enabled = !ui.busy, onQty = onQty)
            }
            HorizontalDivider(color = m.line)
            PrinterStatusLine(ui.printer, !ui.busy, onChoosePrinter)
            ui.error?.let {
                Text(it, Modifier.padding(bottom = 8.dp), fontSize = 14.sp, lineHeight = 21.sp, color = m.warningText)
            }
            ui.alternative?.let { name ->
                TextButton(onClick = onAlternative, enabled = !ui.busy) {
                    Text("Друкувати на $name", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = m.link)
                }
            }
        }
        HorizontalDivider(color = m.line)
        Surface(
            onClick = onPrint, enabled = !ui.busy,
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 10.dp, end = 16.dp, bottom = 28.dp).heightIn(min = 52.dp),
            shape = RoundedCornerShape(6.dp), color = if (ui.busy) m.surface else m.accent,
            contentColor = if (ui.busy) m.textSecondary else m.onAccent,
        ) {
            Row(
                Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (ui.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = m.accent)
                else Icon(painterResource(R.drawable.ic_redesign_printer), null, Modifier.size(20.dp))
                Text(
                    when {
                        ui.busy -> "Друкуємо…"
                        ui.error != null -> "Спробувати ще"
                        else -> printButtonText(ui.format, ui.qty * ui.items)
                    },
                    fontSize = 16.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun PrintChip(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val m = Mine.colors
    val shape = RoundedCornerShape(6.dp)
    Box(
        Modifier.height(32.dp).clip(shape).background(if (selected) m.text else m.card)
            .border(1.dp, if (selected) m.text else m.outline, shape)
            .toggleable(selected, enabled = enabled, role = Role.Checkbox, onValueChange = { onClick() })
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = if (selected) m.card else m.textSecondary)
    }
}

@Composable
private fun PrintCopies(quantity: Int, enabled: Boolean, onQty: (Int) -> Unit) {
    val m = Mine.colors
    val shape = RoundedCornerShape(6.dp)
    Row(
        Modifier.height(40.dp).clip(shape).background(m.card).border(1.dp, m.outline, shape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PrintCopyButton(R.drawable.ic_redesign_minus, "Менше", enabled && quantity > 1) { onQty(quantity - 1) }
        Text(quantity.toString(), Modifier.widthIn(min = 36.dp), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = m.text, textAlign = TextAlign.Center)
        PrintCopyButton(R.drawable.ic_redesign_plus, "Більше", enabled && quantity < 99) { onQty(quantity + 1) }
    }
}

@Composable
private fun PrintCopyButton(@DrawableRes icon: Int, label: String, enabled: Boolean, onClick: () -> Unit) {
    val m = Mine.colors
    IconButton(onClick, Modifier.size(width = 40.dp, height = 38.dp), enabled = enabled) {
        Icon(painterResource(icon), label, Modifier.size(18.dp), tint = if (enabled) m.text else m.textTertiary)
    }
}

@Composable
private fun PrintPreview(items: List<LabelItem>, format: Template, quantity: Int, promo: Boolean, landscape: Boolean, openSheetPreview: Boolean) {
    val context = LocalContext.current
    val renderer = remember(context) { LabelRenderer(context) }
    val layout = format.layout(landscape)
    val capacity = layout?.perPage
    val filled = capacity?.let { minOf(items.size * quantity, it) }
    val pages = capacity?.let { (items.size * quantity + it - 1) / it }
    var showSheet by rememberSaveable { mutableStateOf(openSheetPreview) }
    val first = items.firstOrNull() ?: return
    val label by produceState<ImageBitmap?>(null, first, format, promo) {
        value = withContext(Dispatchers.Default) {
            renderer.render(first, LabelFormat.of(format, promo), 203.2f).asImageBitmap()
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(shape = RoundedCornerShape(8.dp), color = Mine.colors.surface, border = BorderStroke(1.dp, Mine.colors.line)) {
            val stripes = Mine.colors.card
            Box(
                Modifier.fillMaxWidth().heightIn(min = 150.dp).drawBehind {
                    val step = 16.dp.toPx()
                    var x = -size.height
                    while (x < size.width) {
                        drawLine(stripes, Offset(x, size.height), Offset(x + size.height, 0f), 8.dp.toPx())
                        x += step
                    }
                }.padding(18.dp),
                contentAlignment = Alignment.Center,
            ) {
                label?.let { bitmap ->
                    Image(
                        bitmap, "Попередній перегляд цінника",
                        modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp)
                            .background(if (promo) Color(ua.chudo.scanner.print.PROMO_PAPER) else Color.White),
                    )
                }
            }
        }
        if (capacity != null) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("$capacity на аркуші A4 · $pages арк.", Modifier.weight(1f), fontSize = 13.sp, color = Mine.colors.textSecondary)
                TextButton(onClick = { showSheet = true }) { Icon(painterResource(R.drawable.ic_redesign_eye), null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Переглянути аркуш") }
            }
        }
    }
    if (showSheet && capacity != null && filled != null) {
        val sheet by produceState<ImageBitmap?>(null, listOf(items, format, quantity, promo, landscape)) {
            value = withContext(Dispatchers.Default) {
                val cells = List(filled) { index -> items[index / quantity] }
                renderer.sheetPreview(cells, format, promo, 100f, landscape).asImageBitmap()
            }
        }
        AlertDialog(
            onDismissRequest = { showSheet = false },
            title = { Text("Аркуш A4") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("${if (landscape) "Альбомна" else "Книжкова"} · $capacity місць · $filled заповнено", color = Mine.colors.textSecondary)
                    sheet?.let { Image(it, "Розкладка цінників на першому аркуші", Modifier.fillMaxWidth()) }
                    Text("Порожні місця не друкуються.", fontSize = 13.sp, color = Mine.colors.textSecondary)
                }
            },
            confirmButton = { TextButton(onClick = { showSheet = false }) { Text("Закрити") } },
        )
    }
}

/** Кнопка вибору принтера зі станом підключення. */
@Composable
private fun PrinterStatusLine(p: PrinterLine, enabled: Boolean, onClick: () -> Unit) {
    val m = Mine.colors
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(R.drawable.ic_redesign_printer), null, Modifier.size(20.dp), tint = m.textSecondary)
        Column(Modifier.weight(1f)) {
            Text(p.model ?: "Автоматично", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = m.text)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusDot(when (p.online) { true -> m.success; false -> m.warning; null -> m.outline }, Modifier.size(8.dp))
                Text(
                    when {
                        p.online == null -> "Шукаю принтер…"
                        p.model == null -> "Виберіть принтер"
                        p.missing -> "Принтер недоступний"
                        p.online -> "Онлайн"
                        else -> "Не відповідає"
                    },
                    fontSize = 13.sp, color = m.textTertiary,
                )
            }
        }
        Text("Змінити", fontSize = 14.sp, color = m.textTertiary)
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
    ModalBottomSheet(
        onDismissRequest = onDismiss, containerColor = m.sheet, contentColor = m.text,
        shape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp),
    ) {
        LazyColumn(Modifier.fillMaxWidth()) {
            item {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
                    Text("Принтер · ${kindShort(kind)}", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text("Вибір запам’ятається на цьому телефоні.", fontSize = 14.sp, color = m.textSecondary)
                }
                HorizontalDivider(color = m.line)
                PrinterChoice("Автоматично", null, R.drawable.ic_redesign_printer, preferred == "auto", !saving) { select("auto") }
            }
            if (printers == null && error == null) item {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(24.dp), color = m.accent, strokeWidth = 2.dp)
                }
            }
            items(printers.orEmpty(), key = { it.id }) { printer ->
                val routeIcon = if (printer.transport == ua.chudo.scanner.print.Link.AGENT) R.drawable.ic_redesign_laptop
                    else R.drawable.ic_redesign_printer
                PrinterChoice(
                    printer.displayName, listOf(printer.model, printer.address).filter(String::isNotBlank).joinToString(" · "),
                    routeIcon, preferred == printer.id, !saving, statuses[printer.id],
                ) { select(printer.id) }
            }
            if (printers?.isEmpty() == true) item {
                Text("Принтерів цього формату не знайдено", Modifier.padding(16.dp), color = m.textSecondary)
            }
            error?.let { message -> item { Text(message, Modifier.padding(16.dp), color = m.warningText) } }
            item {
                Row(
                    Modifier.fillMaxWidth().padding(start = 8.dp, top = 10.dp, end = 8.dp, bottom = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    TextButton(onClick = onDismiss) { Text("Назад", color = m.textSecondary) }
                    TextButton(onClick = { refresh++ }, enabled = !saving) { Text("Оновити", color = m.link) }
                }
            }
        }
    }
}

@Composable
private fun PrinterChoice(
    title: String, detail: String?, @DrawableRes icon: Int, selected: Boolean, enabled: Boolean,
    online: Boolean? = null, onClick: () -> Unit,
) {
    val m = Mine.colors
    Row(
        Modifier.fillMaxWidth().background(m.card).clickable(enabled = enabled, onClick = onClick)
            .heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(icon), null, Modifier.size(20.dp), tint = m.textSecondary)
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = m.text)
            detail?.let {
                Text(it, fontSize = 13.sp, color = m.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (detail != null && online == null) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = m.accent)
        } else if (online != null) {
            val tint = if (online) m.success else m.warningText
            val background = if (online) m.successTint else m.warningTint
            Row(
                Modifier.height(22.dp).background(background, RoundedCornerShape(4.dp)).padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusDot(tint, Modifier.size(8.dp))
                Text(if (online) "онлайн" else "не відповідає", fontSize = 12.sp, color = tint)
            }
        }
        if (selected) Icon(painterResource(R.drawable.ic_redesign_check), "Вибрано", Modifier.size(20.dp), tint = m.text)
    }
    HorizontalDivider(color = m.line)
}

/** «Книжна / Альбомна» для A4-шаблону — окремо для кожного шаблону. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OrientationPicker(t: Template, landscape: Boolean, onChange: (Boolean) -> Unit) {
    val m = Mine.colors
    Column {
        Text("Аркуш A4", Modifier.padding(bottom = 8.dp), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = m.textSecondary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PrintChip("Книжна", !landscape, enabled = true) { onChange(false) }
            PrintChip("Альбомна", landscape, enabled = true) { onChange(true) }
        }
        Text(orientationNote(t, landscape), Modifier.padding(top = 6.dp), fontSize = 13.sp, color = m.textSecondary)
    }
}
