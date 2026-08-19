package ua.chudo.scanner.ui.stock

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Close
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ua.chudo.scanner.chudoApp
import ua.chudo.scanner.data.AppPrefs
import ua.chudo.scanner.data.ChudoRepository
import ua.chudo.scanner.data.Employee
import ua.chudo.scanner.data.ImportResult
import ua.chudo.scanner.data.LookupResult
import ua.chudo.scanner.data.ParsedStock
import ua.chudo.scanner.data.ShopStockInfo
import ua.chudo.scanner.data.ShopStockRepository
import ua.chudo.scanner.data.StockFileException
import ua.chudo.scanner.data.StockFileParser
import ua.chudo.scanner.data.StockItem
import ua.chudo.scanner.i18n.tr
import ua.chudo.scanner.ui.auth.authMessage
import ua.chudo.scanner.ui.common.ErrorState
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
    ) : StockState
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
) : ViewModel() {
    private val _state = MutableStateFlow<StockState>(StockState.Idle)
    val state = _state.asStateFlow()
    private val _import = MutableStateFlow<ImportState>(ImportState.Idle)
    val import = _import.asStateFlow()
    private val _info = MutableStateFlow<ShopStockInfo?>(null)
    val info = _info.asStateFlow()

    private var job: Job? = null
    private var last: Pair<String, String?>? = null // urlKey, barcode
    private val lang get() = prefs.lang.value

    init {
        refreshInfo()
        viewModelScope.launch { prefs.lang.drop(1).collect { last?.let { (k, bc) -> open(k, bc) } } }
    }

    private fun refreshInfo() {
        viewModelScope.launch { _info.value = runCatching { stock.info(employee.shopId) }.getOrNull() }
    }

    fun search(raw: String) {
        val q = raw.trim().ifEmpty { return }
        launchState {
            _state.value = StockState.Loading(lang.tr("Шукаємо…", "Ищем…"))
            when (val r = repo.lookup(q) { _state.value = StockState.Loading(it) }) {
                is LookupResult.Single -> load(r.urlKey, r.barcode)
                is LookupResult.Many -> { last = null; StockState.Pick(q, r.items) }
                is LookupResult.NotFound -> {
                    last = null
                    // На сайте нет — может, есть в выгрузке магазина (по артикулу или штрихкоду).
                    val item = runCatching { stock.find(employee.shopId, q, listOfNotNull(r.barcode)) }.getOrNull()
                    if (item != null) StockState.Ready(null, null, item, null) else StockState.NotFound(q)
                }
            }
        }
    }

    fun open(key: String, barcode: String? = null) = launchState { load(key, barcode) }

    fun clear() {
        job?.cancel(); last = null; _state.value = StockState.Idle
    }

    private suspend fun load(key: String, barcode: String?): StockState {
        last = key to barcode
        _state.value = StockState.Loading(lang.tr("Перевіряємо твій магазин…", "Проверяем твой магазин…"))
        val p = repo.details(key)
        val site = runCatching { repo.shops(p.id, employee.region) }.getOrNull()
        val mine = site?.firstOrNull { same(it.address, employee.shop) }?.available
        var err: String? = null
        val item = try {
            stock.find(employee.shopId, p.article, listOfNotNull(barcode))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            err = e.authMessage(lang); null
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
                ImportState.Failed(e.authMessage(lang))
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
                StockState.Failed(e.toUiError(lang))
            }
        }
    }
}

private val QTY_FORMAT = DecimalFormat("#,##0.###")
private fun Double.qty(): String = QTY_FORMAT.format(this)

@Composable
fun StockScreen(employee: Employee, onProfile: () -> Unit) {
    val app = LocalContext.current.chudoApp
    val vm: StockViewModel = viewModel(
        key = "stock-${employee.uid}",
        factory = viewModelFactory { initializer { StockViewModel(employee, app.repository, app.shopStock, app.prefs) } },
    )
    val s by vm.state.collectAsStateWithLifecycle()
    val info by vm.info.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    val ctx = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.readFile(ctx, it) }
    }

    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { ShopHeader(employee, onProfile) }
        item { UploadCard(info) { picker.launch(FILE_TYPES) } }
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                placeholder = { Text(tr("Артикул, штрихкод або назва", "Артикул, штрихкод или название")) },
                leadingIcon = { Icon(Icons.Rounded.Inventory2, null) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { query = ""; vm.clear() }) {
                        Icon(Icons.Rounded.Close, tr("Очистити", "Очистить"))
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); vm.search(query) }),
            )
        }

        when (val st = s) {
            StockState.Idle -> {}
            is StockState.Loading -> item {
                Column(Modifier.fillMaxWidth().padding(top = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text(st.step, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                }
            }
            is StockState.Failed -> item { ErrorState(st.error, null, { vm.search(query) }) }
            is StockState.NotFound -> item {
                MessageState(
                    Icons.Rounded.SearchOff,
                    tr("Не знайшли «${st.query}»", "Не нашли «${st.query}»"),
                    tr("Ні на сайті, ні у вивантаженні твого магазину.", "Ни на сайте, ни в выгрузке твоего магазина."),
                )
            }
            is StockState.Pick -> {
                item { Text(tr("Обери товар", "Выбери товар"), style = MaterialTheme.typography.titleMedium) }
                items(st.items, key = { it.id }) { ProductCard(it, onClick = { vm.open(it.urlKey) }) }
            }
            is StockState.Ready -> {
                item { ProductHeader(st.product, st.item) }
                item { MyShopCard(employee, st, info != null) }
            }
        }
    }

    ImportDialog(vm, employee)
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
private fun ShopHeader(e: Employee, onProfile: () -> Unit) {
    Row(verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Text(tr("Залишки магазину", "Остатки магазина"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Store, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(6.dp))
                Text(e.shop, style = MaterialTheme.typography.bodyMedium)
            }
            Text(e.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onProfile) {
            Icon(Icons.Rounded.AccountCircle, tr("Профіль", "Профиль"))
        }
    }
}

@Composable
private fun UploadCard(info: ShopStockInfo?, onUpload: () -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Text(
                if (info == null) {
                    tr("Залишки магазину ще не завантажено.", "Остатки магазина ещё не загружены.")
                } else {
                    val date = info.updatedAt?.let { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(it) } ?: "—"
                    tr(
                        "Вивантаження 1С: $date · ${info.rows} позицій" + (info.updatedBy?.let { " · $it" } ?: ""),
                        "Выгрузка 1С: $date · ${info.rows} позиций" + (info.updatedBy?.let { " · $it" } ?: ""),
                    )
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(Modifier.height(10.dp))
            FilledTonalButton(onClick = onUpload) {
                Icon(Icons.Rounded.UploadFile, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(tr("Завантажити вивантаження 1С", "Загрузить выгрузку 1С"))
            }
            Text(
                tr("Excel (.xlsx) або CSV з колонками «Артикул» / «Штрихкод» і «Кількість» / «Залишок».",
                    "Excel (.xlsx) или CSV с колонками «Артикул» / «Штрихкод» и «Количество» / «Остаток»."),
                Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

@Composable
private fun ProductHeader(p: ProductDetails?, item: StockItem?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        ProductImage(p?.images?.firstOrNull(), Modifier.size(64.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(p?.name ?: item?.name ?: "—", style = MaterialTheme.typography.titleSmall, maxLines = 2)
            (p?.article ?: item?.article)?.let {
                Text(tr("Арт. $it", "Арт. $it"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun MyShopCard(e: Employee, st: StockState.Ready, uploaded: Boolean) {
    Surface(shape = RoundedCornerShape(16.dp), tonalElevation = 1.dp) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(e.shop, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            val item = st.item
            when {
                item != null -> Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        item.qty.qty(),
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                        color = if (item.qty > 0) InStockGreen else OutOfStockGrey,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(tr("шт. на залишку", "шт. на остатке"), Modifier.padding(bottom = 6.dp), style = MaterialTheme.typography.titleMedium)
                }
                st.stockError != null -> Text(st.stockError, color = MaterialTheme.colorScheme.error)
                !uploaded -> Text(tr("Завантаж вивантаження 1С, щоб бачити кількість.", "Загрузи выгрузку 1С, чтобы видеть количество."))
                else -> Text(
                    tr("У вивантаженні магазину цього товару немає (0 шт. або інший артикул).", "В выгрузке магазина этого товара нет (0 шт. или другой артикул)."),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            st.siteAvailable?.let { have ->
                Spacer(Modifier.height(10.dp))
                Text(
                    tr("На сайті: ", "На сайте: ") + if (have) tr("є", "есть") else tr("немає", "нет"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (have) InStockGreen else OutOfStockGrey,
                )
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
            title = { Text(tr("Читаємо файл…", "Читаем файл…")) },
            text = { LinearProgressIndicator(Modifier.fillMaxWidth()) },
            confirmButton = {},
        )
        is ImportState.Preview -> AlertDialog(
            onDismissRequest = vm::dismissImport,
            title = { Text(tr("Завантажити залишки?", "Загрузить остатки?")) },
            text = {
                val c = s.parsed.columns
                Column {
                    Text(tr("Позицій: ${s.parsed.rows.size}", "Позиций: ${s.parsed.rows.size}"), fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    c.article?.let { Text(tr("Артикул: «$it»", "Артикул: «$it»")) }
                    c.barcode?.let { Text(tr("Штрихкод: «$it»", "Штрихкод: «$it»")) }
                    c.name?.let { Text(tr("Назва: «$it»", "Название: «$it»")) }
                    Text(tr("Кількість: «${c.qty}»", "Количество: «${c.qty}»"))
                    Spacer(Modifier.height(8.dp))
                    s.parsed.rows.take(3).forEach { r ->
                        Text(
                            "${r.article ?: r.barcode} — ${r.qty.qty()}" + (r.name?.let { " · $it" } ?: ""),
                            style = MaterialTheme.typography.bodySmall, maxLines = 1,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        tr("Залишки магазину «${e.shop}» буде замінено цим файлом.", "Остатки магазина «${e.shop}» будут заменены этим файлом."),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = { TextButton(onClick = { vm.upload(s.parsed) }) { Text(tr("Завантажити", "Загрузить")) } },
            dismissButton = { TextButton(onClick = vm::dismissImport) { Text(tr("Скасувати", "Отмена")) } },
        )
        is ImportState.Uploading -> AlertDialog(
            onDismissRequest = {},
            title = { Text(tr("Завантажуємо…", "Загружаем…")) },
            text = {
                Column {
                    if (s.total > 0) LinearProgressIndicator({ s.done.toFloat() / s.total }, Modifier.fillMaxWidth())
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Text(tr("Порівнюємо з попереднім вивантаженням і пишемо зміни.", "Сравниваем с прошлой выгрузкой и пишем изменения."), style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {},
        )
        is ImportState.Done -> AlertDialog(
            onDismissRequest = vm::dismissImport,
            title = { Text(tr("Готово", "Готово")) },
            text = {
                Text(
                    tr(
                        "Позицій у магазині: ${s.result.total}. Змінено: ${s.result.changed}, прибрано: ${s.result.removed}.",
                        "Позиций в магазине: ${s.result.total}. Изменено: ${s.result.changed}, убрано: ${s.result.removed}.",
                    ),
                )
            },
            confirmButton = { TextButton(onClick = vm::dismissImport) { Text("OK") } },
        )
        is ImportState.Failed -> AlertDialog(
            onDismissRequest = vm::dismissImport,
            title = { Text(tr("Не вдалося завантажити", "Не удалось загрузить")) },
            text = { Text(s.message) },
            confirmButton = { TextButton(onClick = vm::dismissImport) { Text("OK") } },
        )
    }
}
