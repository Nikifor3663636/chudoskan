package ua.chudo.scanner.ui.stock

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Surface
import androidx.compose.ui.unit.sp
import ua.chudo.scanner.ui.common.BarcodeScanIcon
import ua.chudo.scanner.ui.common.MineButton
import ua.chudo.scanner.ui.common.MineHeader
import ua.chudo.scanner.data.HighlightKind
import ua.chudo.scanner.ui.common.MineSearchField
import ua.chudo.scanner.ui.common.MineTone
import ua.chudo.scanner.ui.common.PriceFont
import ua.chudo.scanner.ui.common.StatusDot
import ua.chudo.scanner.ui.theme.Mine
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.Store
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ua.chudo.scanner.chudoApp
import ua.chudo.scanner.data.AppPrefs
import ua.chudo.scanner.data.ChudoRepository
import ua.chudo.scanner.data.Employee
import ua.chudo.scanner.data.ImportResult
import ua.chudo.scanner.data.LookupResult
import ua.chudo.scanner.print.PriceTag
import ua.chudo.scanner.print.TagData
import ua.chudo.scanner.ui.print.PrintSheet
import ua.chudo.scanner.data.NetAvailability
import ua.chudo.scanner.data.NetworkAvailability
import ua.chudo.scanner.data.NetworkStockLookup
import ua.chudo.scanner.data.StoreDoc
import ua.chudo.scanner.data.StoreItem
import ua.chudo.scanner.data.ParsedStock
import ua.chudo.scanner.data.ShopStockInfo
import ua.chudo.scanner.data.ShopStockRepository
import ua.chudo.scanner.data.StockFileException
import ua.chudo.scanner.data.StockFileParser
import ua.chudo.scanner.data.StockItem
import ua.chudo.scanner.ui.auth.authMessage
import ua.chudo.scanner.ui.common.Avatar
import ua.chudo.scanner.ui.common.ErrorState
import ua.chudo.scanner.ui.common.MineCard
import ua.chudo.scanner.ui.common.MessageState
import ua.chudo.scanner.ui.common.ProductCard
import ua.chudo.scanner.ui.common.ProductImage
import ua.chudo.scanner.ui.common.UiError
import ua.chudo.scanner.ui.common.toUiError
import ua.chudo.scanner.ui.theme.InStockGreen
import ua.chudo.scanner.ui.theme.OutOfStockGrey
import uniffi.chudo_core.ProductDetails
import uniffi.chudo_core.ProductSummary
import java.text.DateFormat
import java.text.DecimalFormat

sealed interface StockState {
    data object Idle : StockState
    data class Loading(val step: String) : StockState
    data class Pick(val query: String, val items: List<ProductSummary>) : StockState
    data class NotFound(val query: String) : StockState
    /** Отсканированного штрихкода нет в выгрузке 1С. */
    data class NotInExport(val code: String) : StockState
    data class Failed(val error: UiError) : StockState
    data class Ready(
        /** `null` — на сайте товара нет, но он есть в выгрузке магазина. */
        val product: ProductDetails?,
        /** Наличие на сайте именно в магазине сотрудника; `null` — магазин не нашли в списке сайта. */
        val siteAvailable: Boolean?,
        /** Строка из выгрузки 1С; `null` — товара в выгрузке нет. */
        val item: StockItem?,
        /** Не удалось прочитать остатки (нет сети, нет доступа). */
        val stockError: String?,
        /** Залишки мережі з stock/{штрихкод}; `null` — даних немає, показуємо як раніше. */
        val net: NetAvailability? = null,
    ) : StockState

    /** Кілька товарів мережі з тим самим кодом 1С або артикулом. */
    data class NetPick(val query: String, val items: List<NetAvailability>) : StockState
}

sealed interface ImportState {
    data object Idle : ImportState
    data object Reading : ImportState
    data class Preview(val parsed: ParsedStock) : ImportState
    data class Uploading(val done: Int, val total: Int) : ImportState
    data class Done(val result: ImportResult) : ImportState
    data class Failed(val message: String) : ImportState
}

class StockViewModel(
    private val employee: Employee,
    private val repo: ChudoRepository,
    private val stock: ShopStockRepository,
    private val prefs: AppPrefs,
    private val net: NetworkStockLookup? = null,
    /** Адмін магазину / мережі: «Залишки» по всіх магазинах мережі й «Що в магазині». */
    val networkView: Boolean = false,
) : ViewModel() {
    // ---------- Адмін: залишки по магазинах мережі ----------

    private val _stores = MutableStateFlow<Map<String, StoreDoc>>(emptyMap())
    val stores = _stores.asStateFlow()
    private val _ownCode = MutableStateFlow<String?>(null)
    val ownCode = _ownCode.asStateFlow()
    private val _storeCode = MutableStateFlow<String?>(null)
    val storeCode = _storeCode.asStateFlow()
    private val _storeItems = MutableStateFlow<List<StoreItem>?>(null)
    val storeItems = _storeItems.asStateFlow()
    private val _storeLoading = MutableStateFlow(false)
    val storeLoading = _storeLoading.asStateFlow()

    private fun loadStores() {
        val n = net ?: return
        viewModelScope.launch {
            runCatching { n.storesWithOwn(employee.shopId) }.getOrNull()?.let { (all, own) ->
                _stores.value = all
                _ownCode.value = own
                if (_storeCode.value == null && own != null) pickStore(own)
            }
        }
    }

    /** «Що в магазині ЧО NN». */
    fun pickStore(code: String) {
        val n = net ?: return
        _storeCode.value = code
        _storeItems.value = null
        _storeLoading.value = true
        viewModelScope.launch {
            _storeItems.value = try { n.storeItems(code) } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }
            _storeLoading.value = false
        }
    }

    /** Товар зі списку магазину → картка з розподілом по мережі (одне читання stock/{штрихкод}). */
    fun openStoreItem(it: StoreItem) = launchState {
        last = null
        _state.value = StockState.Loading("Відкриваю товар…")
        netTry { n -> n.byBarcode(employee.shopId, it.barcode) }?.let { av -> StockState.Ready(null, null, null, null, av) }
            ?: StockState.NotInExport(it.barcode)
    }

    private val _state = MutableStateFlow<StockState>(StockState.Idle)
    val state = _state.asStateFlow()

    // ---------- Пакетний друк: вибрані товари ----------

    private val _selected = MutableStateFlow<List<NetAvailability>>(emptyList())
    val selected = _selected.asStateFlow()
    private val _changedLoading = MutableStateFlow(false)
    val changedLoading = _changedLoading.asStateFlow()
    private val _batchNote = MutableStateFlow<String?>(null)
    val batchNote = _batchNote.asStateFlow()

    fun toggleSelected(av: NetAvailability) {
        _selected.value = if (_selected.value.any { it.barcode == av.barcode }) _selected.value.filter { it.barcode != av.barcode }
        else _selected.value + av
    }

    fun unselect(barcode: String) { _selected.value = _selected.value.filter { it.barcode != barcode } }

    /** «Змінені з минулого завантаження» — додати до вибраних ті, що є у своєму магазині. */
    fun addChanged() {
        val n = net ?: return
        if (_changedLoading.value) return
        _changedLoading.value = true
        _batchNote.value = null
        viewModelScope.launch {
            try {
                val found = n.changedSinceLastImport(employee.shopId)
                val have = _selected.value.map { it.barcode }.toSet()
                _selected.value = _selected.value + found.filter { it.barcode !in have }
                _batchNote.value = if (found.isEmpty()) "Змінених товарів у твоєму магазині немає." else "Додано змінених: ${found.size}"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _batchNote.value = e.authMessage()
            } finally {
                _changedLoading.value = false
            }
        }
    }

    /** Цінники до друку (вікно друку відкрите), поки не надруковано або не закрито. */
    private val _printTags = MutableStateFlow<List<PriceTag>?>(null)
    val printTags = _printTags.asStateFlow()
    private val _labelsProgress = MutableStateFlow<String?>(null)
    val labelsProgress = _labelsProgress.asStateFlow()

    /**
     * «Цінники»: ціни й назви з сайту (по 4 одночасно), штрихкод, код і артикул — з 1С → вікно друку.
     * Без ціни на сайті цінник не друкується — такі товари пропускаємо й кажемо скільки.
     */
    fun prepareLabels() {
        val list = _selected.value
        if (list.isEmpty() || _labelsProgress.value != null) return
        viewModelScope.launch {
            var done = 0
            _labelsProgress.value = "Ціни з сайту: 0 з ${list.size}…"
            val tags = coroutineScope {
                list.chunked(4).flatMap { chunk ->
                    chunk.map { av ->
                        async {
                            val site = try { siteData(av.barcode) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
                            TagData.merge(site, TagData.OneC(av.barcode, av.item.name, av.item.code, av.item.article), av.barcode).also {
                                done++
                                _labelsProgress.value = "Ціни з сайту: $done з ${list.size}…"
                            }
                        }
                    }.awaitAll()
                }
            }
            _labelsProgress.value = null
            val ok = tags.filter { it.price.isNotBlank() }
            val missing = tags.size - ok.size
            _batchNote.value = if (missing > 0) "Без ціни на сайті: $missing — їх пропущено (без ціни цінник не друкується)." else null
            if (ok.isNotEmpty()) _printTags.value = ok
        }
    }

    /** Ціна й назва з сайту за штрихкодом; кілька збігів або немає — null. */
    private suspend fun siteData(barcode: String): TagData.Site? {
        val r = (repo.lookup(barcode) {}) as? LookupResult.Single ?: return null
        val p = repo.details(r.urlKey)
        return TagData.Site(p.name, p.article, p.price?.finalPrice, p.price?.regularPrice)
    }

    fun closePrint() { _printTags.value = null }

    fun printed(message: String) {
        _printTags.value = null
        _selected.value = emptyList()
        _batchNote.value = message
    }
    private val _import = MutableStateFlow<ImportState>(ImportState.Idle)
    val import = _import.asStateFlow()
    private val _info = MutableStateFlow<ShopStockInfo?>(null)
    val info = _info.asStateFlow()

    private var job: Job? = null
    private var last: Pair<String, String?>? = null // urlKey, barcode

    init {
        refreshInfo()
        if (networkView) loadStores()
    }

    private fun refreshInfo() {
        // Залишки мережі (завантажує адмін одним файлом на всю мережу) — бачать усі; старі залишки магазину — запасний варіант.
        viewModelScope.launch {
            _info.value = runCatching { net?.info() }.getOrNull()?.let { ShopStockInfo(it.updatedAt, it.updatedBy, it.items) }
                ?: runCatching { stock.info(employee.shopId) }.getOrNull()
        }
    }

    fun search(raw: String) {
        val q = raw.trim().ifEmpty { return }
        launchState {
            _state.value = StockState.Loading("Шукаємо…")
            // Код 1С (BOC022446) — одразу в залишках мережі: на сайті його немає.
            if (NetworkAvailability.isCode(q)) netResult(q, netTry { it.byCode(employee.shopId, q) }.orEmpty())?.let { return@launchState it }
            when (val r = repo.lookup(q) { _state.value = StockState.Loading(it) }) {
                is LookupResult.Single -> load(r.urlKey, r.barcode)
                is LookupResult.Many -> { last = null; StockState.Pick(q, r.items) }
                is LookupResult.NotFound -> {
                    last = null
                    // На сайті немає — шукаємо в залишках мережі (штрихкод або артикул), потім у вивантаженні магазину.
                    val found = netTry { n ->
                        val bc = r.barcode ?: q.takeIf { it.all(Char::isDigit) }
                        bc?.let { n.byBarcode(employee.shopId, it) }?.let(::listOf) ?: n.byArticle(employee.shopId, q)
                    }.orEmpty()
                    netResult(q, found) ?: run {
                        val item = runCatching { stock.find(employee.shopId, q, listOfNotNull(r.barcode)) }.getOrNull()
                        if (item != null) StockState.Ready(null, null, item, null) else StockState.NotFound(q)
                    }
                }
            }
        }
    }

    private fun netResult(q: String, found: List<NetAvailability>): StockState? = when (found.size) {
        0 -> null
        1 -> StockState.Ready(null, null, null, null, found.single())
        else -> StockState.NetPick(q, found)
    }

    fun openNet(av: NetAvailability) { job?.cancel(); last = null; _state.value = StockState.Ready(null, null, null, null, av) }

    /** Помилка читання залишків мережі не заважає показати старі дані. */
    private suspend fun <T> netTry(block: suspend (NetworkStockLookup) -> T?): T? {
        val n = net ?: return null
        return try { block(n) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
    }

    /** Скан: ОДНЕ читання stock/{штрихкод}; немає там — як раніше, stock/bc_{код} свого магазину. */
    fun scanned(code: String) = launchState {
        last = null
        _state.value = StockState.Loading("Перевіряємо залишки…")
        netTry { it.byBarcode(employee.shopId, code) }?.let { return@launchState StockState.Ready(null, null, null, null, it) }
        try {
            stock.byBarcode(employee.shopId, code)?.let { StockState.Ready(null, null, it, null) }
                ?: StockState.NotInExport(code)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            StockState.Ready(null, null, null, e.authMessage())
        }
    }

    fun open(key: String, barcode: String? = null) = launchState { load(key, barcode) }

    fun clear() {
        job?.cancel(); last = null; _state.value = StockState.Idle
    }

    private suspend fun load(key: String, barcode: String?): StockState {
        last = key to barcode
        _state.value = StockState.Loading("Перевіряємо твій магазин…")
        val p = repo.details(key)
        val site = runCatching { repo.shops(p.id, employee.region) }.getOrNull()
        val mine = site?.firstOrNull { same(it.address, employee.shop) }?.available
        val n = netTry { l -> barcode?.let { l.byBarcode(employee.shopId, it) } ?: p.article?.let { l.byArticle(employee.shopId, it).firstOrNull() } }
        if (n != null) return StockState.Ready(p, mine, null, null, n)
        var err: String? = null
        val item = try {
            stock.find(employee.shopId, p.article, listOfNotNull(barcode))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            err = e.authMessage(); null
        }
        return StockState.Ready(p, mine, item, err)
    }

    private fun same(a: String, b: String) = norm(a) == norm(b)
    private fun norm(s: String) = s.trim().split(Regex("\\s+")).joinToString(" ").lowercase()

    // ---------- Загрузка выгрузки 1С ----------

    fun readFile(ctx: Context, uri: Uri) {
        _import.value = ImportState.Reading
        viewModelScope.launch {
            _import.value = try {
                val parsed = withContext(Dispatchers.IO) {
                    val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw StockFileException("Не удалось открыть файл.")
                    StockFileParser.parse(bytes)
                }
                ImportState.Preview(parsed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: StockFileException) {
                ImportState.Failed(e.message.orEmpty())
            } catch (e: Exception) {
                ImportState.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun upload(parsed: ParsedStock) {
        _import.value = ImportState.Uploading(0, parsed.rows.size)
        viewModelScope.launch {
            _import.value = try {
                val r = stock.import(employee, parsed.rows) { done, total -> _import.value = ImportState.Uploading(done, total) }
                refreshInfo()
                last?.let { (k, bc) -> open(k, bc) }
                ImportState.Done(r)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ImportState.Failed(e.authMessage())
            }
        }
    }

    fun dismissImport() { _import.value = ImportState.Idle }

    private fun launchState(block: suspend () -> StockState) {
        job?.cancel()
        job = viewModelScope.launch {
            _state.value = try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                StockState.Failed(e.toUiError())
            }
        }
    }
}

private val QTY_FORMAT = DecimalFormat("#,##0.###")
private fun Double.qty(): String = QTY_FORMAT.format(this)

@Composable
fun StockScreen(
    employee: Employee,
    onBack: () -> Unit,
    onScan: () -> Unit,
    /** Код со сканера (scan?mode=stock); после обработки вызываем [onScannedConsumed]. */
    scanned: String?,
    onScannedConsumed: () -> Unit,
    /** Адмін залишків / мережі: «Завантажити файл 1С» на всю мережу (розподіл по магазинах). */
    onNetworkUpload: (() -> Unit)? = null,
    onHighlights: ((HighlightKind) -> Unit)? = null,
    showBatch: Boolean = false,
    onBatchShown: () -> Unit = {},
) {
    val app = LocalContext.current.chudoApp
    val vm: StockViewModel = viewModel(
        key = "stock-${employee.uid}",
        factory = viewModelFactory { initializer { StockViewModel(employee, app.repository, app.shopStock, app.prefs, app.networkLookup, networkView = employee.managesShop || employee.stockAdmin) } },
    )
    val s by vm.state.collectAsStateWithLifecycle()
    val info by vm.info.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val changedLoading by vm.changedLoading.collectAsStateWithLifecycle()
    val batchNote by vm.batchNote.collectAsStateWithLifecycle()
    val printTags by vm.printTags.collectAsStateWithLifecycle()
    val stores by vm.stores.collectAsStateWithLifecycle()
    val ownCode by vm.ownCode.collectAsStateWithLifecycle()
    val storeCode by vm.storeCode.collectAsStateWithLifecycle()
    val storeItems by vm.storeItems.collectAsStateWithLifecycle()
    val storeLoading by vm.storeLoading.collectAsStateWithLifecycle()
    val labelsProgress by vm.labelsProgress.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    val ctx = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.readFile(ctx, it) }
    }
    val listState = rememberLazyListState()
    LaunchedEffect(showBatch) {
        if (showBatch) {
            listState.scrollToItem(if (onHighlights == null) 3 else 4)
            onBatchShown()
        }
    }
    LaunchedEffect(scanned) {
        scanned?.let { query = it; vm.scanned(it); onScannedConsumed() }
    }

    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(),
        state = listState,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { ShopHeader(employee, onBack) }
        onHighlights?.let { open ->
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = RoundedCornerShape(8.dp), color = Mine.colors.accentTone, contentColor = Mine.colors.link) {
                        Text("Залишки", Modifier.padding(horizontal = 12.dp, vertical = 8.dp), fontWeight = FontWeight.SemiBold)
                    }
                    HighlightKind.entries.forEach { kind ->
                        TextButton(onClick = { open(kind) }) { Text(kind.title, color = Mine.colors.link) }
                    }
                }
            }
        }
        // Адмін: один файл 1С на всю мережу — застосунок сам розкладає залишки по магазинах.
        // Решта (консультанти, адмін магазину) лише бачать, коли оновлено, — завантажує тільки stockAdmin (див. firestore.rules).
        if (onNetworkUpload != null) item { NetworkUploadCard(onNetworkUpload) }
        else item { UploadCard(info, onUpload = if (employee.stockAdmin) ({ picker.launch(FILE_TYPES) }) else null) }
        item {
            Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MineSearchField(
                    query, { query = it; if (it.isEmpty()) vm.clear() }, "Артикул, штрихкод або назва",
                    { keyboard?.hide(); vm.search(query) }, Modifier.weight(1f),
                )
                Surface(onClick = onScan, modifier = Modifier.size(60.dp), shape = RoundedCornerShape(18.dp), color = Mine.colors.accentTone, contentColor = Mine.colors.accent) {
                    Box(contentAlignment = Alignment.Center) { Icon(BarcodeScanIcon, "Сканувати", Modifier.size(28.dp)) }
                }
            }
        }
        item {
            LabelBatchCard(
                selected, changedLoading, labelsProgress ?: batchNote,
                onChanged = vm::addChanged, onRemove = vm::unselect, onLabels = vm::prepareLabels,
            )
        }

        when (val st = s) {
            StockState.Idle -> {}
            is StockState.Loading -> item {
                Column(Modifier.fillMaxWidth().padding(top = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Mine.colors.accent)
                    Spacer(Modifier.height(16.dp))
                    Text(st.step, fontSize = 15.sp, color = Mine.colors.textSecondary, textAlign = TextAlign.Center)
                }
            }
            is StockState.Failed -> item { ErrorState(st.error, null, { vm.search(query) }) }
            is StockState.NotFound -> item {
                MessageState(
                    Icons.Rounded.SearchOff,
                    "Не знайшли «${st.query}»",
                    "Ні на сайті, ні у вивантаженні твого магазину.",
                )
            }
            is StockState.NotInExport -> item {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    MessageState(
                        Icons.Rounded.SearchOff,
                        "Немає у вивантаженні 1С",
                        "Штрихкод ${st.code}",
                    )
                    MineButton("Шукати на сайті", { vm.search(st.code) })
                }
            }
            is StockState.Pick -> {
                item { Text("Обери товар", Modifier.padding(horizontal = 4.dp), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Mine.colors.textSecondary) }
                items(st.items, key = { it.id }) { ProductCard(it, onClick = { vm.open(it.urlKey) }) }
            }
            is StockState.NetPick -> {
                item { Text("Обери товар", Modifier.padding(horizontal = 4.dp), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Mine.colors.textSecondary) }
                items(st.items, key = { it.barcode }) { av ->
                    MineCard(Modifier.clickable { vm.openNet(av) }, padding = 16.dp) {
                        Text(av.item.name.ifBlank { av.barcode }, fontSize = 16.sp, lineHeight = 21.sp, fontWeight = FontWeight.Bold)
                        Text(netIds(av.item.code, av.item.article, av.barcode), fontSize = 14.sp, color = Mine.colors.textSecondary)
                    }
                }
            }
            is StockState.Ready -> {
                val net = st.net
                item { ProductHeader(st.product, st.item, net) }
                if (net != null) {
                    item { NetworkStockCard(net) }
                    // Адмін бачить товар одразу в усіх магазинах мережі.
                    if (vm.networkView) item { NetworkDistributionCard(NetworkAvailability.byCity(net.item, stores, ownCode ?: net.ownCode), ownCode ?: net.ownCode) }
                    st.siteAvailable?.let { have -> item { SiteLine(have) } }
                    item {
                        val chosen = selected.any { it.barcode == net.barcode }
                        MineButton(if (chosen) "Прибрати з цінників" else "Додати до цінників", { vm.toggleSelected(net) }, Modifier.fillMaxWidth())
                    }
                } else {
                    item { MyShopCard(employee, st, info != null) }
                }
            }
        }
        // Адмін: що лежить у будь-якому магазині мережі.
        if (vm.networkView) item(key = "store-stock") {
            StoreStockCard(
                NetworkAvailability.storeChoices(stores), storeCode, storeItems, storeLoading,
                onPick = vm::pickStore, onOpen = vm::openStoreItem,
            )
        }
    }

    ImportDialog(vm, employee)
    printTags?.let { tags -> PrintSheet(tags, onDismiss = vm::closePrint, onDone = vm::printed) }
}

private val FILE_TYPES = arrayOf(
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "application/vnd.ms-excel",
    "text/csv",
    "text/comma-separated-values",
    "text/plain",
    "application/octet-stream",
)

@Composable
private fun ShopHeader(e: Employee, onBack: () -> Unit) {
    val m = Mine.colors
    Column {
        MineHeader("Залишки магазину", onBack)
        Text(e.shop, Modifier.padding(start = 4.dp), fontSize = 15.sp, color = m.textSecondary)
    }
}

/** Вивантаження з 1С: коли оновлено, скільки позицій; кнопка завантаження — лише якщо [onUpload] не null. */
@Composable
private fun UploadCard(info: ShopStockInfo?, onUpload: (() -> Unit)?) {
    val m = Mine.colors
    Surface(shape = RoundedCornerShape(20.dp), color = m.card, contentColor = m.text, border = BorderStroke(1.dp, m.line)) {
        Column(Modifier.fillMaxWidth().padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(if (info == null) m.warning else m.success)
                Spacer(Modifier.width(8.dp))
                Text(
                    if (info == null) "Залишки ще не завантажено"
                    else "Оновлено " + (info.updatedAt?.let { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(it) } ?: "—"),
                    fontSize = 16.sp, fontWeight = FontWeight.Bold,
                )
            }
            info?.let {
                Text(
                    "${it.rows} позицій" + (it.updatedBy?.let { by -> ", завантажив(ла) $by" } ?: ""),
                    Modifier.padding(top = 4.dp), fontSize = 14.sp, color = m.textSecondary,
                )
            }
            if (onUpload != null) {
                MineButton("Завантажити файл з 1С", onUpload, Modifier.padding(top = 14.dp).fillMaxWidth(), tone = if (info == null) MineTone.ACCENT else MineTone.OUTLINE)
                Text(
                    "Excel (.xlsx) або CSV з колонками «Артикул» чи «Штрихкод» і «Кількість» чи «Залишок».",
                    Modifier.padding(top = 8.dp), fontSize = 13.sp, lineHeight = 18.sp, color = m.textSecondary,
                )
            } else if (info == null) {
                Text(
                    "Залишки завантажує адміністратор мережі.",
                    Modifier.padding(top = 6.dp), fontSize = 14.sp, color = m.textSecondary,
                )
            }
        }
    }
}

/** «Код BOC022446 · Арт. 39217 · 4823077601234». */
private fun netIds(code: String, article: String, barcode: String) =
    listOfNotNull(code.ifBlank { null }?.let { "Код $it" }, article.ifBlank { null }?.let { "Арт. $it" }, barcode).joinToString(" · ")

@Composable
private fun ProductHeader(p: ProductDetails?, item: StockItem?, net: NetAvailability? = null) {
    val m = Mine.colors
    Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        ProductImage(p?.images?.firstOrNull(), Modifier.size(64.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(p?.name ?: net?.item?.name?.ifBlank { null } ?: item?.name ?: "—", fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold, color = m.text, maxLines = 2)
            if (net != null) Text(netIds(net.item.code, net.item.article.ifBlank { p?.article.orEmpty() }, net.barcode), fontSize = 14.sp, color = m.textSecondary)
            else (p?.article ?: item?.article)?.let { Text("Арт. $it", fontSize = 14.sp, color = m.textSecondary) }
        }
    }
}

@Composable
private fun SiteLine(have: Boolean) {
    val m = Mine.colors
    Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        StatusDot(if (have) m.success else m.outline)
        Spacer(Modifier.width(8.dp))
        Text(if (have) "Є на сайті" else "Немає на сайті", fontSize = 15.sp, color = m.textSecondary)
    }
}

/** Кількість у своєму магазині — великим шрифтом цін (як на цінниках). */
@Composable
private fun MyShopCard(e: Employee, st: StockState.Ready, uploaded: Boolean) {
    val m = Mine.colors
    Surface(shape = RoundedCornerShape(20.dp), color = m.card, contentColor = m.text, border = BorderStroke(1.dp, m.line)) {
        Column(Modifier.fillMaxWidth().padding(18.dp)) {
            Text("У твоєму магазині", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = m.textSecondary)
            val item = st.item
            when {
                item != null -> Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        item.qty.qty(), fontFamily = PriceFont, fontWeight = FontWeight.ExtraBold, fontSize = 48.sp, lineHeight = 52.sp,
                        color = if (item.qty > 0) m.success else m.logout,
                    )
                    Text(" шт", Modifier.padding(bottom = 8.dp), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
                st.stockError != null -> Text(st.stockError, Modifier.padding(top = 6.dp), fontSize = 15.sp, color = m.warningText)
                !uploaded -> Text("Залишки ще не завантажені адміністратором.", Modifier.padding(top = 6.dp), fontSize = 16.sp)
                else -> Text(
                    "Цього товару немає у вивантаженні (0 шт або інший артикул).",
                    Modifier.padding(top = 6.dp), fontSize = 16.sp, color = m.textSecondary,
                )
            }
            st.siteAvailable?.let { have ->
                Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(if (have) m.success else m.outline)
                    Spacer(Modifier.width(8.dp))
                    Text(if (have) "Є на сайті" else "Немає на сайті", fontSize = 15.sp, color = m.textSecondary)
                }
            }
        }
    }
}

@Composable
private fun ImportDialog(vm: StockViewModel, e: Employee) {
    val st by vm.import.collectAsStateWithLifecycle()
    when (val s = st) {
        ImportState.Idle -> {}
        ImportState.Reading -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Читаємо файл…") },
            text = { LinearProgressIndicator(Modifier.fillMaxWidth()) },
            confirmButton = {},
        )
        is ImportState.Preview -> AlertDialog(
            onDismissRequest = vm::dismissImport,
            title = { Text("Завантажити залишки?") },
            text = {
                val c = s.parsed.columns
                Column {
                    Text("Позицій: ${s.parsed.rows.size}", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    c.article?.let { Text("Артикул: «$it»") }
                    c.barcode?.let { Text("Штрихкод: «$it»") }
                    c.name?.let { Text("Назва: «$it»") }
                    Text("Кількість: «${c.qty}»")
                    Spacer(Modifier.height(8.dp))
                    s.parsed.rows.take(3).forEach { r ->
                        Text(
                            "${r.article ?: r.barcode} — ${r.qty.qty()}" + (r.name?.let { " · $it" } ?: ""),
                            style = MaterialTheme.typography.bodySmall, maxLines = 1,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Залишки магазину «${e.shop}» буде замінено цим файлом.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = { TextButton(onClick = { vm.upload(s.parsed) }) { Text("Завантажити") } },
            dismissButton = { TextButton(onClick = vm::dismissImport) { Text("Скасувати") } },
        )
        is ImportState.Uploading -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Завантажуємо…") },
            text = {
                Column {
                    if (s.total > 0) LinearProgressIndicator({ s.done.toFloat() / s.total }, Modifier.fillMaxWidth())
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Text("Порівнюємо з попереднім вивантаженням і пишемо зміни.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {},
        )
        is ImportState.Done -> AlertDialog(
            onDismissRequest = vm::dismissImport,
            title = { Text("Готово") },
            text = {
                Text(
                    "Позицій у магазині: ${s.result.total}. Змінено: ${s.result.changed}, прибрано: ${s.result.removed}.",
                )
            },
            confirmButton = { TextButton(onClick = vm::dismissImport) { Text("OK") } },
        )
        is ImportState.Failed -> AlertDialog(
            onDismissRequest = vm::dismissImport,
            title = { Text("Не вдалося завантажити") },
            text = { Text(s.message) },
            confirmButton = { TextButton(onClick = vm::dismissImport) { Text("OK") } },
        )
    }
}

/**
 * Пакетний друк: «Змінені з минулого завантаження» і вибрані товари → «Цінники»
 * (один документ на кілька аркушів у «Друк цінників»).
 */
@Composable
private fun LabelBatchCard(
    selected: List<NetAvailability>, loading: Boolean, note: String?,
    onChanged: () -> Unit, onRemove: (String) -> Unit, onLabels: () -> Unit,
) {
    val m = Mine.colors
    Surface(shape = RoundedCornerShape(20.dp), color = m.card, contentColor = m.text, border = BorderStroke(1.dp, m.line)) {
        Column(Modifier.fillMaxWidth().padding(18.dp)) {
            Text("Цінники", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(
                if (selected.isEmpty()) "Додай товари кнопкою «Додати до цінників» або візьми змінені з минулого завантаження."
                else "Вибрано: ${selected.size}",
                Modifier.padding(top = 4.dp), fontSize = 14.sp, lineHeight = 19.sp, color = m.textSecondary,
            )
            selected.take(MAX_SHOWN).forEach { av ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(av.item.name.ifBlank { av.barcode }, Modifier.weight(1f), fontSize = 15.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    IconButton(onClick = { onRemove(av.barcode) }) { Icon(Icons.Rounded.Close, "Прибрати", tint = m.textSecondary) }
                }
            }
            if (selected.size > MAX_SHOWN) Text("… і ще ${selected.size - MAX_SHOWN}", fontSize = 14.sp, color = m.textSecondary)
            note?.let { Text(it, Modifier.padding(top = 6.dp), fontSize = 14.sp, color = m.textSecondary) }
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MineButton("Змінені з минулого завантаження", onChanged, Modifier.weight(1f), busy = loading, enabled = !loading)
            }
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MineButton("Цінники для вибраних", onLabels, Modifier.weight(1f), tone = MineTone.ACCENT, enabled = selected.isNotEmpty())
            }
        }
    }
}

private const val MAX_SHOWN = 5
