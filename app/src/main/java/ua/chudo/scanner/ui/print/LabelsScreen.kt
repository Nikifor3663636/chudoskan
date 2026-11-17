package ua.chudo.scanner.ui.print

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.material3.SwitchDefaults
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.sp
import ua.chudo.scanner.ui.common.BarcodeScanIcon
import ua.chudo.scanner.ui.common.MineButton
import ua.chudo.scanner.ui.common.MineChip
import ua.chudo.scanner.ui.common.MineListRow
import ua.chudo.scanner.ui.common.MinePrimaryButton
import ua.chudo.scanner.ui.common.MineSearchField
import ua.chudo.scanner.ui.common.MineStepper
import ua.chudo.scanner.ui.theme.Mine
import android.app.Activity
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ua.chudo.scanner.chudoApp
import ua.chudo.scanner.data.ChudoRepository
import ua.chudo.scanner.data.LookupResult
import ua.chudo.scanner.print.LabelKind
import ua.chudo.scanner.print.PriceTag
import ua.chudo.scanner.print.PrintService
import ua.chudo.scanner.print.LabelFormat
import ua.chudo.scanner.print.LabelRenderer
import ua.chudo.scanner.print.PrintException
import ua.chudo.scanner.print.QueueItem
import ua.chudo.scanner.print.Template
import ua.chudo.scanner.print.expand
import ua.chudo.scanner.print.formatPrice
import ua.chudo.scanner.print.toItem
import ua.chudo.scanner.ui.common.toUiError
import uniffi.chudo_core.ProductDetails
import uniffi.chudo_core.ProductSummary

sealed interface FindState {
    data object Idle : FindState
    data class Loading(val step: String) : FindState
    data class Pick(val items: List<ProductSummary>, val barcode: String?) : FindState
    data class NotFound(val query: String) : FindState
    data class Failed(val message: String) : FindState
}

sealed interface PrintStatus {
    data object Idle : PrintStatus
    data object Printing : PrintStatus
    data class Done(val message: String) : PrintStatus
    data class Error(val message: String) : PrintStatus
}

class LabelsViewModel(
    private val repo: ChudoRepository,
    private val svc: PrintService,
) : ViewModel() {
    private val _find = MutableStateFlow<FindState>(FindState.Idle)
    val find = _find.asStateFlow()
    private val _current = MutableStateFlow<PriceTag?>(null)
    val current = _current.asStateFlow()
    private val _copies = MutableStateFlow(1)
    val copies = _copies.asStateFlow()
    private val _queue = MutableStateFlow<List<QueueItem>>(emptyList())
    val queue = _queue.asStateFlow()
    private val _template = MutableStateFlow(Template.TAG_68x38)
    val template = _template.asStateFlow()
    private val _promo = MutableStateFlow(false)
    val promo = _promo.asStateFlow()
    private val _preview = MutableStateFlow<ImageBitmap?>(null)
    val preview = _preview.asStateFlow()
    private val _status = MutableStateFlow<PrintStatus>(PrintStatus.Idle)
    val status = _status.asStateFlow()
    private var findJob: Job? = null

    init {
        viewModelScope.launch {
            svc.store.lastTemplate.first()?.let { _template.value = it }
        }
        viewModelScope.launch {
            combine(_current, _queue, _template, _promo) { _, _, t, p -> t to p }.collectLatest { (t, p) ->
                val items = jobItems()
                _preview.value = if (items.isEmpty()) null else withContext(Dispatchers.Default) {
                    if (t == Template.LABEL_40x24) {
                        // Ровно то, что уйдёт на Godex: 1-битная картинка 320×200.
                        svc.renderer.mono(items.first().tag.toItem(), LabelFormat.Label40x24, LabelRenderer.GODEX_DPI).toBitmap()
                    } else {
                        val cells = expand(items.map { it.tag.toItem() to it.copies })
                        svc.renderer.sheetPreview(cells, t, p && promoAvailable(), LabelRenderer.SHEET_DPI * PREVIEW_SCALE)
                    }.asImageBitmap()
                }
            }
        }
    }

    fun search(raw: String) {
        val q = raw.trim().ifEmpty { return }
        findJob?.cancel()
        findJob = viewModelScope.launch {
            _find.value = FindState.Loading("Шукаємо…")
            _find.value = try {
                when (val r = repo.lookup(q) { _find.value = FindState.Loading(it) }) {
                    is LookupResult.Single -> { setProduct(repo.details(r.urlKey), r.barcode); FindState.Idle }
                    is LookupResult.Many -> FindState.Pick(r.items, r.barcode)
                    is LookupResult.NotFound -> FindState.NotFound(q)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                FindState.Failed(e.toUiError().let { "${it.title}. ${it.hint}" })
            }
        }
    }

    fun pick(s: ProductSummary, barcode: String?) {
        findJob?.cancel()
        findJob = viewModelScope.launch {
            _find.value = FindState.Loading("Завантажуємо товар…")
            _find.value = try {
                setProduct(repo.details(s.urlKey), barcode); FindState.Idle
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                FindState.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /** Товара нет на сайте — заполнить ценник вручную (штрихкод подставляем, если искали по нему). */
    fun manual(query: String) {
        val digits = query.filter(Char::isDigit)
        _current.value = PriceTag("", "", "", "", if (digits.length >= 12 && digits.length == query.trim().length) digits else "")
        _copies.value = 1
        _find.value = FindState.Idle
    }

    private fun setProduct(p: ProductDetails, barcode: String?) {
        val price = p.price
        _current.value = PriceTag(
            name = p.name,
            price = price?.finalPrice?.let(::formatPrice).orEmpty(),
            oldPrice = price?.takeIf { it.regularPrice > it.finalPrice + 0.001 }?.regularPrice?.let(::formatPrice).orEmpty(),
            article = p.article.orEmpty(),
            barcode = barcode.orEmpty(),
        )
        _copies.value = 1
        _status.value = PrintStatus.Idle
    }

    fun edit(f: (PriceTag) -> PriceTag) { _current.value = _current.value?.let(f) }
    fun setCopies(n: Int) { _copies.value = n.coerceIn(1, 99) }
    fun dropCurrent() { _current.value = null }

    fun addToQueue() {
        val tag = _current.value ?: return
        _queue.value = _queue.value + QueueItem(tag, _copies.value)
        _current.value = null
        _copies.value = 1
    }

    fun removeFromQueue(index: Int) { _queue.value = _queue.value.filterIndexed { i, _ -> i != index } }

    fun setKind(kind: LabelKind) {
        if (_template.value.kind == kind) return
        setTemplate(Template.entries.first { it.kind == kind })
    }

    fun setPromo(v: Boolean) { _promo.value = v }

    /** «Акційний» имеет смысл, только если хоть у одного товара старая цена больше новой. */
    fun promoAvailable() = jobItems().any { it.tag.toItem().canPromo }

    fun setTemplate(t: Template) {
        _template.value = t
        viewModelScope.launch { svc.store.setLastTemplate(t) }
    }


    /** Всё из очереди плюс текущий товар. */
    private fun jobItems() = _queue.value + listOfNotNull(_current.value?.let { QueueItem(it, _copies.value) })

    /** Принтер выбирается сам (реестр магазина → поиск → компьютер магазина). */
    fun print() {
        val items = jobItems()
        if (items.isEmpty() || _status.value == PrintStatus.Printing) return
        val t = _template.value
        _status.value = PrintStatus.Printing
        viewModelScope.launch {
            _status.value = try {
                val promo = _promo.value && t.kind == LabelKind.PLAIN && promoAvailable()
                svc.print(items, t, promo)
                _queue.value = emptyList()
                _current.value = null
                PrintStatus.Done("Надруковано")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                PrintStatus.Error(e.message ?: "Не вдалося надрукувати")
            }
        }
    }

}

@Composable
fun LabelsScreen(
    onBack: () -> Unit,
    onScan: () -> Unit,
    /** «Принтери» — только администратору; сотрудник принтер не настраивает. */
    onPrinters: (() -> Unit)?,
    scanned: String?,
    onScannedConsumed: () -> Unit,
) {
    val app = LocalContext.current.chudoApp
    val vm: LabelsViewModel = viewModel(factory = viewModelFactory { initializer { LabelsViewModel(app.repository, app.printing) } })
    val find by vm.find.collectAsStateWithLifecycle()
    val current by vm.current.collectAsStateWithLifecycle()
    val copies by vm.copies.collectAsStateWithLifecycle()
    val queue by vm.queue.collectAsStateWithLifecycle()
    val template by vm.template.collectAsStateWithLifecycle()
    val preview by vm.preview.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val promo by vm.promo.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var askPaper by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val m = Mine.colors

    if (askPaper) {
        AlertDialog(
            onDismissRequest = { askPaper = false },
            title = { Text("Акційні цінники") },
            text = { Text("Встав жовтий папір у принтер.") },
            confirmButton = { TextButton(onClick = { askPaper = false; vm.print() }) { Text("Друкувати") } },
            dismissButton = { TextButton(onClick = { askPaper = false }) { Text("Скасувати") } },
        )
    }

    LaunchedEffect(scanned) {
        scanned?.let { query = it; vm.search(it); onScannedConsumed() }
    }

    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Друк цінників", Modifier.weight(1f), fontSize = 28.sp, fontWeight = FontWeight.Bold, color = m.text)
                onPrinters?.let { IconButton(onClick = it) { Icon(Icons.Rounded.Settings, "Принтери", tint = m.textSecondary) } }
            }
            Text(
                "Знайди товар, перевір ціну й друкуй. Принтер обирається сам.",
                Modifier.padding(top = 4.dp), fontSize = 15.sp, lineHeight = 21.sp, color = m.textSecondary,
            )
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MineSearchField(query, { query = it }, "Артикул, штрихкод або назва", { keyboard?.hide(); vm.search(query) }, Modifier.weight(1f))
                Surface(onClick = onScan, modifier = Modifier.size(60.dp), shape = RoundedCornerShape(18.dp), color = m.accentTone, contentColor = m.accent) {
                    Box(contentAlignment = Alignment.Center) { Icon(BarcodeScanIcon, "Сканувати", Modifier.size(28.dp)) }
                }
            }
        }
        when (val f = find) {
            FindState.Idle -> if (current == null && queue.isEmpty()) item {
                Surface(shape = RoundedCornerShape(20.dp), color = m.surface, contentColor = m.text) {
                    Column(Modifier.fillMaxWidth().padding(18.dp)) {
                        Text("Черга порожня", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text(
                            "Відскануй штрихкод або введи артикул — ціна й назва підтягнуться із сайту. Можна додати кілька товарів і надрукувати разом.",
                            Modifier.padding(top = 6.dp), fontSize = 15.sp, lineHeight = 21.sp, color = m.textSecondary,
                        )
                    }
                }
            }
            is FindState.Loading -> item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = m.accent)
                    Spacer(Modifier.width(10.dp)); Text(f.step, fontSize = 15.sp, color = m.textSecondary)
                }
            }
            is FindState.Pick -> {
                item { Text("Обери товар", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = m.textSecondary) }
                items(f.items, key = { it.id }) { s ->
                    MineListRow(onClick = { vm.pick(s, f.barcode) }, divider = s != f.items.last()) {
                        Column {
                            Text(s.name, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = m.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOfNotNull(s.article?.let { "Арт. $it" }, s.price?.finalPrice?.let { formatPrice(it) + " ₴" }).joinToString(", "),
                                fontSize = 14.sp, color = m.textSecondary,
                            )
                        }
                    }
                }
            }
            is FindState.NotFound -> item {
                Column {
                    Text("«${f.query}» на сайті не знайшли.", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = m.text)
                    MineButton("Заповнити цінник вручну", { vm.manual(f.query) }, Modifier.padding(top = 10.dp))
                }
            }
            is FindState.Failed -> item { Text(f.message, fontSize = 15.sp, color = m.warningText) }
        }

        current?.let { tag -> item { TagEditor(tag, copies, vm) } }

        if (queue.isNotEmpty()) {
            item { Text("У черзі ${queue.size}", Modifier.padding(top = 6.dp), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = m.text) }
            itemsIndexed(queue) { i, q ->
                MineListRow(onClick = null, divider = i != queue.lastIndex) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(q.tag.name.ifEmpty { q.tag.barcode }, fontSize = 16.sp, color = m.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${q.tag.price} ₴, ${q.copies} шт", fontSize = 14.sp, color = m.textSecondary)
                        }
                        IconButton(onClick = { vm.removeFromQueue(i) }) { Icon(Icons.Rounded.Close, "Прибрати з черги", tint = m.textSecondary) }
                    }
                }
            }
        }

        if (current != null || queue.isNotEmpty()) {
            item { FormatPicker(template, promo, vm) }
            preview?.let { img ->
                item {
                    Image(
                        img, "Попередній перегляд",
                        Modifier.fillMaxWidth().heightIn(max = if (template.kind == LabelKind.STICKY) 220.dp else 420.dp)
                            .aspectRatio(img.width.toFloat() / img.height)
                            .clip(RoundedCornerShape(12.dp)).border(BorderStroke(1.dp, m.line), RoundedCornerShape(12.dp)),
                        filterQuality = FilterQuality.None,
                    )
                }
            }
            item {
                val total = queue.sumOf { it.copies } + (if (current != null) copies else 0)
                MinePrimaryButton(
                    if (status == PrintStatus.Printing) "Друкую…" else printButtonText(template, total),
                    onClick = { if (promo && template.kind == LabelKind.PLAIN && vm.promoAvailable()) askPaper = true else vm.print() },
                    height = 58.dp, enabled = status != PrintStatus.Printing,
                )
            }
        }
        when (val s = status) {
            is PrintStatus.Done -> item { Text(s.message, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = m.success) }
            is PrintStatus.Error -> item { Text(s.message, fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium, color = m.warningText) }
            else -> {}
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

/** Цінник, який редагуємо: назва, ціни, артикул, штрихкод, бренд, код 1С, кількість. */
@Composable
private fun TagEditor(tag: PriceTag, copies: Int, vm: LabelsViewModel) {
    val m = Mine.colors
    Surface(shape = RoundedCornerShape(20.dp), color = m.card, contentColor = m.text, border = BorderStroke(1.dp, m.line)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Цінник", Modifier.weight(1f), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                IconButton(onClick = vm::dropCurrent) { Icon(Icons.Rounded.Close, "Прибрати", tint = m.textSecondary) }
            }
            OutlinedTextField(tag.name, { v -> vm.edit { it.copy(name = v) } }, Modifier.fillMaxWidth(), label = { Text("Назва") }, maxLines = 3, shape = RoundedCornerShape(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PriceField(tag.price, "Ціна", Modifier.weight(1f)) { v -> vm.edit { it.copy(price = v) } }
                PriceField(tag.oldPrice, "Стара ціна", Modifier.weight(1f)) { v -> vm.edit { it.copy(oldPrice = v) } }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(tag.article, { v -> vm.edit { it.copy(article = v) } }, Modifier.weight(1f), singleLine = true, label = { Text("Артикул") }, shape = RoundedCornerShape(14.dp))
                OutlinedTextField(
                    tag.barcode, { v -> vm.edit { it.copy(barcode = v.filter(Char::isDigit).take(13)) } }, Modifier.weight(1f), singleLine = true,
                    label = { Text("Штрихкод") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), shape = RoundedCornerShape(14.dp),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(tag.brand, { v -> vm.edit { it.copy(brand = v) } }, Modifier.weight(1f), singleLine = true, label = { Text("Бренд") }, shape = RoundedCornerShape(14.dp))
                OutlinedTextField(tag.code, { v -> vm.edit { it.copy(code = v.trim()) } }, Modifier.weight(1f), singleLine = true, label = { Text("Код 1С") }, shape = RoundedCornerShape(14.dp))
            }
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Кількість", Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.Medium)
                MineStepper(copies, vm::setCopies)
            }
            MineButton("Додати в чергу й шукати ще", vm::addToQueue, Modifier.fillMaxWidth().padding(top = 4.dp))
        }
    }
}

@Composable
private fun PriceField(value: String, label: String, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value, { onChange(it.filter { c -> c.isDigit() || c == ',' || c == '.' }.replace('.', ',')) }, modifier,
        singleLine = true, label = { Text(label) }, suffix = { Text("₴") }, shape = RoundedCornerShape(14.dp),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
    )
}

/** Формат — один ряд чипов (как в листе печати); акционный — переключатель. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FormatPicker(template: Template, promo: Boolean, vm: LabelsViewModel) {
    val m = Mine.colors
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Формат", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = m.textSecondary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Template.entries.forEach { t -> MineChip(formatTitle(t), template == t, { vm.setTemplate(t) }) }
        }
        if (template.kind == LabelKind.PLAIN) {
            val available = vm.promoAvailable()
            Surface(shape = RoundedCornerShape(16.dp), color = m.surface, contentColor = m.text) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Акційний цінник", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        Text(
                            if (available) "Стара ціна буде закреслена. Встав жовтий папір." else "Потрібна стара ціна, більша за нову.",
                            fontSize = 13.sp, lineHeight = 17.sp, color = m.textSecondary,
                        )
                    }
                    Switch(
                        checked = promo && available, onCheckedChange = vm::setPromo, enabled = available,
                        colors = SwitchDefaults.colors(checkedTrackColor = m.accent, checkedThumbColor = m.onAccent, checkedBorderColor = Color.Transparent),
                    )
                }
            }
        }
    }
}

/** Предпросмотр листа A4 — четверть от 300 dpi. */
private const val PREVIEW_SCALE = 0.25f

