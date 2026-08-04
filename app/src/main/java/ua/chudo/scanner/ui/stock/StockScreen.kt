package ua.chudo.scanner.ui.stock

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import ua.chudo.scanner.chudoApp
import ua.chudo.scanner.data.AppPrefs
import ua.chudo.scanner.data.ChudoRepository
import ua.chudo.scanner.data.LookupResult
import ua.chudo.scanner.data.NoStockSource
import ua.chudo.scanner.data.StockSource
import ua.chudo.scanner.i18n.title
import ua.chudo.scanner.i18n.tr
import ua.chudo.scanner.ui.common.ErrorState
import ua.chudo.scanner.ui.common.MessageState
import ua.chudo.scanner.ui.common.ProductCard
import ua.chudo.scanner.ui.common.ProductImage
import ua.chudo.scanner.ui.common.RegionPickerSheet
import ua.chudo.scanner.ui.common.UiError
import ua.chudo.scanner.ui.common.openMap
import ua.chudo.scanner.ui.common.toUiError
import ua.chudo.scanner.ui.theme.InStockGreen
import ua.chudo.scanner.ui.theme.OutOfStockGrey
import uniffi.chudo_core.ProductDetails
import uniffi.chudo_core.ProductSummary
import uniffi.chudo_core.ShopAvailability

sealed interface StockState {
    data object Idle : StockState
    data class Loading(val step: String) : StockState
    data class Pick(val query: String, val items: List<ProductSummary>) : StockState
    data class NotFound(val query: String) : StockState
    data class Failed(val error: UiError) : StockState
    data class Ready(
        val product: ProductDetails,
        val shops: List<ShopAvailability>,
        /** Адрес → штук; `null` — источник количеств не подключён. */
        val qty: Map<String, Int>?,
    ) : StockState
}

class StockViewModel(
    private val repo: ChudoRepository,
    private val prefs: AppPrefs,
    private val source: StockSource = NoStockSource,
) : ViewModel() {
    private val _state = MutableStateFlow<StockState>(StockState.Idle)
    val state = _state.asStateFlow()
    private var job: Job? = null
    private var urlKey: String? = null
    private val lang get() = prefs.lang.value

    init {
        // Сменили город или язык — пересчитываем наличие открытого товара.
        viewModelScope.launch { prefs.region.drop(1).collect { urlKey?.let(::open) } }
        viewModelScope.launch { prefs.lang.drop(1).collect { urlKey?.let(::open) } }
    }

    fun search(raw: String) {
        val q = raw.trim().ifEmpty { return }
        launchState {
            _state.value = StockState.Loading(lang.tr("Шукаємо…", "Ищем…"))
            when (val r = repo.lookup(q) { _state.value = StockState.Loading(it) }) {
                is LookupResult.Single -> load(r.urlKey)
                is LookupResult.Many -> { urlKey = null; StockState.Pick(q, r.items) }
                is LookupResult.NotFound -> { urlKey = null; StockState.NotFound(q) }
            }
        }
    }

    fun open(key: String) = launchState { load(key) }

    fun clear() {
        job?.cancel(); urlKey = null; _state.value = StockState.Idle
    }

    private suspend fun load(key: String): StockState {
        urlKey = key
        _state.value = StockState.Loading(lang.tr("Перевіряємо магазини…", "Проверяем магазины…"))
        val p = repo.details(key)
        val region = prefs.region.value.code
        val shops = repo.shops(p.id, region)
        val qty = runCatching { source.quantities(p.id, p.article, region) }.getOrNull()
        return StockState.Ready(p, shops, qty)
    }

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

@Composable
fun StockScreen() {
    val app = LocalContext.current.chudoApp
    val vm: StockViewModel = viewModel(factory = viewModelFactory { initializer { StockViewModel(app.repository, app.prefs) } })
    val s by vm.state.collectAsStateWithLifecycle()
    val region by app.prefs.region.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var showRegions by rememberSaveable { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current

    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Text(tr("Залишки в магазинах", "Остатки в магазинах"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                tr("Знайди товар — побачиш усі магазини міста одним списком.", "Найди товар — увидишь все магазины города одним списком."),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AssistChip(
                    onClick = { showRegions = true },
                    label = { Text(region.title()) },
                    leadingIcon = { Icon(Icons.Rounded.LocationOn, null, Modifier.size(18.dp)) },
                )
            }
        }
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
            StockState.Idle -> item { QtyNote() }
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
                    tr("Спробуй артикул з цінника.", "Попробуй артикул с ценника."),
                )
            }
            is StockState.Pick -> {
                item { Text(tr("Обери товар", "Выбери товар"), style = MaterialTheme.typography.titleMedium) }
                items(st.items, key = { it.id }) { ProductCard(it, onClick = { vm.open(it.urlKey) }) }
            }
            is StockState.Ready -> {
                item { ProductHeader(st.product) }
                item { StockTable(st.shops, st.qty) }
                if (st.qty == null) item { QtyNote() }
            }
        }
    }

    if (showRegions) {
        RegionPickerSheet(
            regions = app.prefs.allRegions,
            selected = region.code,
            onPick = { app.prefs.setRegion(it.code); showRegions = false },
            onDismiss = { showRegions = false },
        )
    }
}

@Composable
private fun ProductHeader(p: ProductDetails) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        ProductImage(p.images.firstOrNull(), Modifier.size(64.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(p.name, style = MaterialTheme.typography.titleSmall, maxLines = 2)
            p.article?.let {
                Text(tr("Арт. $it", "Арт. $it"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun StockTable(shops: List<ShopAvailability>, qty: Map<String, Int>?) {
    val ctx = LocalContext.current
    if (shops.isEmpty()) {
        Text(tr("У цьому місті немає даних про магазини.", "В этом городе нет данных о магазинах."), color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val have = shops.count { it.available }
    Surface(shape = RoundedCornerShape(16.dp), tonalElevation = 1.dp) {
        Column {
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(tr("Магазин", "Магазин"), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(tr("Наявність", "Наличие"), Modifier.width(84.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                Text(tr("Шт.", "Шт."), Modifier.width(44.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End)
            }
            HorizontalDivider()
            shops.forEach { shop ->
                val color = if (shop.available) InStockGreen else OutOfStockGrey
                Row(
                    Modifier.fillMaxWidth().clickable { ctx.openMap(shop.address) }.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(shop.address, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (shop.available) tr("Є", "Есть") else tr("Немає", "Нет"),
                        Modifier.width(84.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = color,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        qty?.get(shop.address)?.toString() ?: "—",
                        Modifier.width(44.dp),
                        style = MaterialTheme.typography.titleSmall,
                        textAlign = TextAlign.End,
                    )
                }
                HorizontalDivider()
            }
            Text(
                tr("Є в $have з ${shops.size}", "Есть в $have из ${shops.size}"),
                Modifier.padding(14.dp),
                style = MaterialTheme.typography.labelLarge,
                color = if (have > 0) InStockGreen else OutOfStockGrey,
            )
        }
    }
}

@Composable
private fun QtyNote() {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(Modifier.padding(14.dp)) {
            Icon(Icons.Rounded.Info, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            Spacer(Modifier.width(10.dp))
            Text(
                tr(
                    "Кількість штук сайт не показує — лише «є / немає». Точні залишки з'являться після підключення до облікової системи мережі (1С).",
                    "Количество штук сайт не показывает — только «есть / нет». Точные остатки появятся после подключения к учётной системе сети (1С).",
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}
