package ua.chudo.scanner.ui.print

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
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
import ua.chudo.scanner.i18n.tr
import ua.chudo.scanner.print.LabelKind
import ua.chudo.scanner.print.LabelTemplate
import ua.chudo.scanner.print.PriceTag
import ua.chudo.scanner.print.PrintService
import ua.chudo.scanner.print.PrinterNotFoundException
import ua.chudo.scanner.print.QueueItem
import ua.chudo.scanner.print.TagRenderer
import ua.chudo.scanner.print.formatPrice
import ua.chudo.scanner.ui.common.toUiError
import uniffi.chudo_core.Lang
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
    private val lang: () -> Lang,
) : ViewModel() {
    private val _find = MutableStateFlow<FindState>(FindState.Idle)
    val find = _find.asStateFlow()
    private val _current = MutableStateFlow<PriceTag?>(null)
    val current = _current.asStateFlow()
    private val _copies = MutableStateFlow(1)
    val copies = _copies.asStateFlow()
    private val _queue = MutableStateFlow<List<QueueItem>>(emptyList())
    val queue = _queue.asStateFlow()
    private val _template = MutableStateFlow(LabelTemplate.STICKY_58x40)
    val template = _template.asStateFlow()
    private val _printerId = MutableStateFlow<Map<LabelKind, String>>(emptyMap())
    val printerId = _printerId.asStateFlow()
    val direct = svc.store.direct.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val printers = svc.store.printers.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val _preview = MutableStateFlow<ImageBitmap?>(null)
    val preview = _preview.asStateFlow()
    private val _status = MutableStateFlow<PrintStatus>(PrintStatus.Idle)
    val status = _status.asStateFlow()
    private var findJob: Job? = null

    init {
        viewModelScope.launch {
            svc.store.lastTemplate.first()?.let { _template.value = it }
            _printerId.value = LabelKind.entries.mapNotNull { k -> svc.store.lastPrinter(k).first()?.let { k to it } }.toMap()
        }
        viewModelScope.launch {
            combine(_current, _queue, _template) { c, q, t -> (c ?: q.lastOrNull()?.tag) to t }.collectLatest { (tag, t) ->
                _preview.value = tag?.let { withContext(Dispatchers.Default) { TagRenderer.mono(it, t).toBitmap().asImageBitmap() } }
            }
        }
    }

    fun search(raw: String) {
        val q = raw.trim().ifEmpty { return }
        findJob?.cancel()
        findJob = viewModelScope.launch {
            _find.value = FindState.Loading(lang().tr("Шукаємо…", "Ищем…"))
            _find.value = try {
                when (val r = repo.lookup(q) { _find.value = FindState.Loading(it) }) {
                    is LookupResult.Single -> { setProduct(repo.details(r.urlKey), r.barcode); FindState.Idle }
                    is LookupResult.Many -> FindState.Pick(r.items, r.barcode)
                    is LookupResult.NotFound -> FindState.NotFound(q)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                FindState.Failed(e.toUiError(lang()).let { "${it.title}. ${it.hint}" })
            }
        }
    }

    fun pick(s: ProductSummary, barcode: String?) {
        findJob?.cancel()
        findJob = viewModelScope.launch {
            _find.value = FindState.Loading(lang().tr("Завантажуємо товар…", "Загружаем товар…"))
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
        setTemplate(LabelTemplate.of(kind).first())
    }

    fun setTemplate(t: LabelTemplate) {
        _template.value = t
        viewModelScope.launch { svc.store.setLastTemplate(t) }
    }

    fun setPrinter(kind: LabelKind, id: String) {
        _printerId.value = _printerId.value + (kind to id)
        viewModelScope.launch { svc.store.setLastPrinter(kind, id) }
    }

    fun setDirect(v: Boolean) { viewModelScope.launch { svc.store.setDirect(v) } }

    /** Всё из очереди плюс текущий товар. */
    private fun jobItems() = _queue.value + listOfNotNull(_current.value?.let { QueueItem(it, _copies.value) })

    fun print(activity: Activity?) {
        val items = jobItems()
        if (items.isEmpty() || _status.value == PrintStatus.Printing) return
        val t = _template.value
        val l = lang()
        val kind = t.kind
        val printer = printers.value.filter { it.role == kind }.let { list -> list.firstOrNull { it.id == _printerId.value[kind] } ?: list.firstOrNull() }
        _status.value = PrintStatus.Printing
        viewModelScope.launch {
            _status.value = try {
                when {
                    kind == LabelKind.STICKY -> {
                        printer ?: return@launch fail(l.tr("Додай принтер для липких: Профіль → Принтери.", "Добавь принтер для липких: Профиль → Принтеры."))
                        svc.printSticky(printer, items, t)
                    }
                    direct.value -> {
                        printer ?: return@launch fail(l.tr("Для прямої печаті додай Brother: Профіль → Принтери.", "Для прямой печати добавь Brother: Профиль → Принтеры."))
                        svc.printPlainDirect(printer, items)
                    }
                    else -> svc.printPlainSystem(activity ?: return@launch fail("No activity"), items)
                }
                val n = items.sumOf { it.copies }
                _queue.value = emptyList()
                _current.value = null
                PrintStatus.Done(l.tr("Надіслано на друк: $n шт.", "Отправлено на печать: $n шт."))
            } catch (e: CancellationException) {
                throw e
            } catch (e: PrinterNotFoundException) {
                PrintStatus.Error(l.tr("Принтер «${e.printer.name}» не знайдено в мережі.", "Принтер «${e.printer.name}» не найден в сети."))
            } catch (e: Exception) {
                PrintStatus.Error(l.tr("Не вдалося надрукувати: ${e.message}", "Не удалось напечатать: ${e.message}"))
            }
        }
    }

    private fun fail(msg: String) { _status.value = PrintStatus.Error(msg) }
}

@Composable
fun LabelsScreen(
    onBack: () -> Unit,
    onScan: () -> Unit,
    onPrinters: () -> Unit,
    scanned: String?,
    onScannedConsumed: () -> Unit,
) {
    val app = LocalContext.current.chudoApp
    val activity = LocalContext.current.findActivity()
    val vm: LabelsViewModel = viewModel(factory = viewModelFactory { initializer { LabelsViewModel(app.repository, app.printing) { app.prefs.lang.value } } })
    val find by vm.find.collectAsStateWithLifecycle()
    val current by vm.current.collectAsStateWithLifecycle()
    val copies by vm.copies.collectAsStateWithLifecycle()
    val queue by vm.queue.collectAsStateWithLifecycle()
    val template by vm.template.collectAsStateWithLifecycle()
    val preview by vm.preview.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(scanned) {
        scanned?.let { query = it; vm.search(it); onScannedConsumed() }
    }

    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, tr("Назад", "Назад")) }
                Text(tr("Друк цінників", "Печать ценников"), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                IconButton(onClick = onPrinters) { Icon(Icons.Rounded.Settings, tr("Принтери", "Принтеры")) }
            }
        }
        item {
            OutlinedTextField(
                value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                shape = RoundedCornerShape(16.dp),
                placeholder = { Text(tr("Артикул, штрихкод або назва", "Артикул, штрихкод или название")) },
                trailingIcon = {
                    Row {
                        if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, tr("Очистити", "Очистить")) }
                        IconButton(onClick = onScan) { Icon(Icons.Rounded.QrCodeScanner, tr("Сканувати", "Сканировать")) }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); vm.search(query) }),
            )
        }
        when (val f = find) {
            FindState.Idle -> {}
            is FindState.Loading -> item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.height(22.dp).width(22.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp)); Text(f.step)
                }
            }
            is FindState.Pick -> items(f.items, key = { it.id }) { s ->
                Card(Modifier.fillMaxWidth().clickable { vm.pick(s, f.barcode) }) {
                    Column(Modifier.padding(12.dp)) {
                        Text(s.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(listOfNotNull(s.article, s.price?.finalPrice?.let { formatPrice(it) + " ₴" }).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            is FindState.NotFound -> item {
                Column {
                    Text(tr("Не знайшли «${f.query}» на сайті.", "Не нашли «${f.query}» на сайте."))
                    TextButton(onClick = { vm.manual(f.query) }) { Text(tr("Заповнити цінник вручну", "Заполнить ценник вручную")) }
                }
            }
            is FindState.Failed -> item { Text(f.message, color = MaterialTheme.colorScheme.error) }
        }

        current?.let { tag -> item { TagEditor(tag, copies, vm) } }

        if (queue.isNotEmpty()) {
            item { Text(tr("Черга (${queue.size})", "Очередь (${queue.size})"), style = MaterialTheme.typography.titleMedium) }
            itemsIndexed(queue) { i, q ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(q.tag.name.ifEmpty { q.tag.barcode }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${q.tag.price} ₴ · ×${q.copies}", style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = { vm.removeFromQueue(i) }) { Icon(Icons.Rounded.Close, tr("Прибрати", "Убрать")) }
                }
            }
        }

        if (current != null || queue.isNotEmpty()) {
            item { HorizontalDivider() }
            item { KindAndTemplate(template, vm) }
            preview?.let { img ->
                item {
                    Image(
                        img, tr("Попередній перегляд", "Предпросмотр"),
                        Modifier.fillMaxWidth().heightIn(max = 220.dp)
                            .aspectRatio(template.widthMm.toFloat() / template.heightMm)
                            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline)),
                        filterQuality = FilterQuality.None,
                    )
                }
            }
            item { PrinterPicker(template.kind, vm, onPrinters) }
            item {
                val total = queue.sumOf { it.copies } + (if (current != null) copies else 0)
                Button(
                    onClick = { vm.print(activity) }, enabled = status != PrintStatus.Printing,
                    modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp),
                ) {
                    if (status == PrintStatus.Printing) CircularProgressIndicator(Modifier.height(22.dp).width(22.dp), strokeWidth = 2.dp, color = Color.White)
                    else { Icon(Icons.Rounded.Print, null); Spacer(Modifier.width(8.dp)); Text(tr("Друкувати ($total)", "Печатать ($total)")) }
                }
            }
        }
        when (val s = status) {
            is PrintStatus.Done -> item { Text(s.message, color = MaterialTheme.colorScheme.primary) }
            is PrintStatus.Error -> item { Text(s.message, color = MaterialTheme.colorScheme.error) }
            else -> {}
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun TagEditor(tag: PriceTag, copies: Int, vm: LabelsViewModel) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr("Товар", "Товар"), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = vm::dropCurrent) { Icon(Icons.Rounded.Close, tr("Прибрати", "Убрать")) }
            }
            OutlinedTextField(tag.name, { v -> vm.edit { it.copy(name = v) } }, Modifier.fillMaxWidth(), label = { Text(tr("Назва", "Название")) }, maxLines = 2)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PriceField(tag.price, tr("Ціна", "Цена"), Modifier.weight(1f)) { v -> vm.edit { it.copy(price = v) } }
                PriceField(tag.oldPrice, tr("Стара ціна", "Старая цена"), Modifier.weight(1f)) { v -> vm.edit { it.copy(oldPrice = v) } }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(tag.article, { v -> vm.edit { it.copy(article = v) } }, Modifier.weight(1f), singleLine = true, label = { Text(tr("Артикул", "Артикул")) })
                OutlinedTextField(
                    tag.barcode, { v -> vm.edit { it.copy(barcode = v.filter(Char::isDigit).take(13)) } }, Modifier.weight(1f), singleLine = true,
                    label = { Text(tr("Штрихкод", "Штрихкод")) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr("Кількість", "Количество"), Modifier.weight(1f))
                IconButton(onClick = { vm.setCopies(copies - 1) }, enabled = copies > 1) { Icon(Icons.Rounded.Remove, "−") }
                Text("$copies", style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = { vm.setCopies(copies + 1) }, enabled = copies < 99) { Icon(Icons.Rounded.Add, "+") }
            }
            OutlinedButton(onClick = vm::addToQueue, modifier = Modifier.fillMaxWidth()) { Text(tr("Додати в чергу й шукати ще", "Добавить в очередь и искать ещё")) }
        }
    }
}

@Composable
private fun PriceField(value: String, label: String, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value, { onChange(it.filter { c -> c.isDigit() || c == ',' || c == '.' }.replace('.', ',')) }, modifier,
        singleLine = true, label = { Text(label) }, suffix = { Text("₴") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
    )
}

@Composable
private fun KindAndTemplate(template: LabelTemplate, vm: LabelsViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LabelKind.entries.forEach { k -> FilterChip(selected = template.kind == k, onClick = { vm.setKind(k) }, label = { Text(roleTitle(k)) }) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LabelTemplate.of(template.kind).forEach { t -> FilterChip(selected = template == t, onClick = { vm.setTemplate(t) }, label = { Text(t.title) }) }
        }
    }
}

@Composable
private fun PrinterPicker(kind: LabelKind, vm: LabelsViewModel, onPrinters: () -> Unit) {
    val printers by vm.printers.collectAsStateWithLifecycle()
    val selected by vm.printerId.collectAsStateWithLifecycle()
    val direct by vm.direct.collectAsStateWithLifecycle()
    val mine = printers.filter { it.role == kind }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (kind == LabelKind.PLAIN) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(tr("Пряма печать", "Прямая печать"))
                    Text(
                        if (direct) tr("PDF напряму на IP:9100 Brother", "PDF напрямую на IP:9100 Brother")
                        else tr("Через системний друк Android", "Через системную печать Android"),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = direct, onCheckedChange = vm::setDirect)
            }
            if (!direct) return@Column
        }
        if (mine.isEmpty()) {
            TextButton(onClick = onPrinters) { Text(tr("Додати принтер", "Добавить принтер")) }
        } else {
            val current = mine.firstOrNull { it.id == selected[kind] } ?: mine.first()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                mine.forEach { p -> FilterChip(selected = p.id == current.id, onClick = { vm.setPrinter(kind, p.id) }, label = { Text(p.name, maxLines = 1) }) }
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
