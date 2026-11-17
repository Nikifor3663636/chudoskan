package ua.chudo.scanner.ui.print

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
import ua.chudo.scanner.print.A4Sheet
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

    if (askPaper) {
        AlertDialog(
            onDismissRequest = { askPaper = false },
            title = { Text("Акційні цінники") },
            text = { Text("Вставте жовтий папір у принтер.") },
            confirmButton = { TextButton(onClick = { askPaper = false; vm.print() }) { Text("Друкувати") } },
            dismissButton = { TextButton(onClick = { askPaper = false }) { Text("Скасувати") } },
        )
    }

    LaunchedEffect(scanned) {
        scanned?.let { query = it; vm.search(it); onScannedConsumed() }
    }

    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад") }
                Text("Друк цінників", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                onPrinters?.let { IconButton(onClick = it) { Icon(Icons.Rounded.Settings, "Принтери") } }
            }
        }
        item {
            OutlinedTextField(
                value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                shape = RoundedCornerShape(16.dp),
                placeholder = { Text("Артикул, штрихкод або назва") },
                trailingIcon = {
                    Row {
                        if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, "Очистити") }
                        IconButton(onClick = onScan) { Icon(Icons.Rounded.QrCodeScanner, "Сканувати") }
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
                    Text("Не знайшли «${f.query}» на сайті.")
                    TextButton(onClick = { vm.manual(f.query) }) { Text("Заповнити цінник вручну") }
                }
            }
            is FindState.Failed -> item { Text(f.message, color = MaterialTheme.colorScheme.error) }
        }

        current?.let { tag -> item { TagEditor(tag, copies, vm) } }

        if (queue.isNotEmpty()) {
            item { Text("Черга (${queue.size})", style = MaterialTheme.typography.titleMedium) }
            itemsIndexed(queue) { i, q ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(q.tag.name.ifEmpty { q.tag.barcode }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${q.tag.price} ₴ · ×${q.copies}", style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = { vm.removeFromQueue(i) }) { Icon(Icons.Rounded.Close, "Прибрати") }
                }
            }
        }

        if (current != null || queue.isNotEmpty()) {
            item { HorizontalDivider() }
            item { KindAndTemplate(template, promo, vm) }
            preview?.let { img ->
                item {
                    Image(
                        img, "Попередній перегляд",
                        Modifier.fillMaxWidth().heightIn(max = if (template.kind == LabelKind.STICKY) 220.dp else 420.dp)
                            .aspectRatio(img.width.toFloat() / img.height)
                            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline)),
                        filterQuality = FilterQuality.None,
                    )
                }
            }
            item {
                val total = queue.sumOf { it.copies } + (if (current != null) copies else 0)
                Button(
                    onClick = {
                        if (promo && template.kind == LabelKind.PLAIN && vm.promoAvailable()) askPaper = true else vm.print()
                    },
                    enabled = status != PrintStatus.Printing,
                    modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp),
                ) {
                    if (status == PrintStatus.Printing) CircularProgressIndicator(Modifier.height(22.dp).width(22.dp), strokeWidth = 2.dp, color = Color.White)
                    else { Icon(Icons.Rounded.Print, null); Spacer(Modifier.width(8.dp)); Text("Друкувати ($total)") }
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
                Text("Товар", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = vm::dropCurrent) { Icon(Icons.Rounded.Close, "Прибрати") }
            }
            OutlinedTextField(tag.name, { v -> vm.edit { it.copy(name = v) } }, Modifier.fillMaxWidth(), label = { Text("Назва") }, maxLines = 2)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PriceField(tag.price, "Ціна", Modifier.weight(1f)) { v -> vm.edit { it.copy(price = v) } }
                PriceField(tag.oldPrice, "Стара ціна", Modifier.weight(1f)) { v -> vm.edit { it.copy(oldPrice = v) } }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(tag.article, { v -> vm.edit { it.copy(article = v) } }, Modifier.weight(1f), singleLine = true, label = { Text("Артикул") })
                OutlinedTextField(
                    tag.barcode, { v -> vm.edit { it.copy(barcode = v.filter(Char::isDigit).take(13)) } }, Modifier.weight(1f), singleLine = true,
                    label = { Text("Штрихкод") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(tag.brand, { v -> vm.edit { it.copy(brand = v) } }, Modifier.weight(1f), singleLine = true, label = { Text("Бренд") })
                OutlinedTextField(tag.code, { v -> vm.edit { it.copy(code = v.trim()) } }, Modifier.weight(1f), singleLine = true, label = { Text("Код 1С") })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Кількість", Modifier.weight(1f))
                IconButton(onClick = { vm.setCopies(copies - 1) }, enabled = copies > 1) { Icon(Icons.Rounded.Remove, "−") }
                Text("$copies", style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = { vm.setCopies(copies + 1) }, enabled = copies < 99) { Icon(Icons.Rounded.Add, "+") }
            }
            OutlinedButton(onClick = vm::addToQueue, modifier = Modifier.fillMaxWidth()) { Text("Додати в чергу й шукати ще") }
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
private fun KindAndTemplate(template: Template, promo: Boolean, vm: LabelsViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LabelKind.entries.forEach { k -> FilterChip(selected = template.kind == k, onClick = { vm.setKind(k) }, label = { Text(if (k == LabelKind.STICKY) "Липкі" else "Звичайні") }) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Template.entries.filter { it.kind == template.kind }.forEach { t ->
                FilterChip(selected = template == t, onClick = { vm.setTemplate(t) }, label = { Text(t.title) })
            }
        }
        if (template.kind == LabelKind.PLAIN) {
            val available = vm.promoAvailable()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Акційний")
                    Text(
                        if (available) "Стара ціна перекреслена над новою" else "Потрібна стара ціна, більша за нову",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = promo && available, onCheckedChange = vm::setPromo, enabled = available)
            }
        }
    }
}

/** Предпросмотр листа A4 — четверть от 300 dpi. */
private const val PREVIEW_SCALE = 0.25f

