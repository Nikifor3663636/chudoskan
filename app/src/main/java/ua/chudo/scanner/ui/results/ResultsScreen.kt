package ua.chudo.scanner.ui.results

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import ua.chudo.scanner.data.Employee
import ua.chudo.scanner.data.OfflineStockItem
import ua.chudo.scanner.data.search
import java.text.DateFormat
import java.util.Date
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import ua.chudo.scanner.ui.theme.Mine
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ua.chudo.scanner.ChudoApp
import ua.chudo.scanner.chudoApp
import ua.chudo.scanner.data.Backend
import ua.chudo.scanner.data.NetAvailability
import ua.chudo.scanner.ui.stock.NetworkStockCard
import uniffi.chudo_core.classifyQuery
import uniffi.chudo_core.isBarcode
import ua.chudo.scanner.data.ProductHighlight
import ua.chudo.scanner.data.ProductHighlights
import ua.chudo.scanner.data.HighlightKind
import ua.chudo.scanner.ui.stock.highlightPrice
import ua.chudo.scanner.data.LookupResult
import ua.chudo.scanner.data.Via
import ua.chudo.scanner.ui.common.ErrorState
import ua.chudo.scanner.ui.common.MessageState
import ua.chudo.scanner.R
import ua.chudo.scanner.data.Cart
import ua.chudo.scanner.ui.common.ProductImage
import ua.chudo.scanner.ui.common.StatusDot
import ua.chudo.scanner.ui.theme.PriceStyle
import uniffi.chudo_core.ProductSummary
import uniffi.chudo_core.StockStatus
import kotlin.math.roundToLong
import ua.chudo.scanner.ui.common.UiError
import ua.chudo.scanner.ui.common.openUrl
import ua.chudo.scanner.ui.common.toUiError
import uniffi.chudo_core.siteSearchUrl
import uniffi.chudo_core.Lang

internal sealed interface ResultsState {
    data object Empty : ResultsState
    data class Loading(val step: String) : ResultsState
    data class Done(val result: LookupResult) : ResultsState
    data class Stock(val availability: NetAvailability) : ResultsState
    data class Failed(val error: UiError) : ResultsState
    data class Offline(val updatedAt: Long?, val matches: List<OfflineStockItem>) : ResultsState
}

/** Товари категорії показуємо списком навіть коли товар один — не відкриваємо картку самі. */
internal fun categoryResult(title: String, items: List<ProductSummary>): ResultsState =
    ResultsState.Done(
        if (items.isEmpty()) LookupResult.NotFound(title, null, emptyList())
        else LookupResult.Many(title, null, items, Via.Direct),
    )

internal class ResultsViewModel(
    private val app: ChudoApp,
    private val employee: Employee,
    handle: SavedStateHandle,
) : ViewModel() {
    val query: String = checkNotNull(handle["q"])
    /** `uid` категорії з «Каталогу»: тоді `query` — її назва, а товари беремо з категорії. */
    val category: String? = handle.get<String>("cat")?.takeIf(String::isNotBlank)
    private val _state = MutableStateFlow<ResultsState>(if (query.isBlank()) ResultsState.Empty else ResultsState.Loading("Шукаємо…"))
    val state = _state.asStateFlow()
    private var job: Job? = null

    init { if (query.isNotBlank()) load() }

    fun load() {
        job?.cancel()
        category?.let { uid ->
            job = viewModelScope.launch { _state.value = ResultsState.Loading("Відкриваємо категорію…"); _state.value = loadCategory(uid) }
            return
        }
        job = viewModelScope.launch {
            _state.value = ResultsState.Loading("Шукаємо…")
            val manager = app.getSystemService(ConnectivityManager::class.java)
            val connected = manager?.getNetworkCapabilities(manager.activeNetwork)
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ?: false
            val snapshot = app.offlineStock.load(employee)
            val online = when {
                !connected -> null
                snapshot == null -> lookupOnline()
                else -> withTimeoutOrNull(4_000L) { lookupOnline() }
            }
            val matches = if (online == null || online is ResultsState.Failed ||
                online is ResultsState.Done && online.result is LookupResult.NotFound
            ) snapshot?.let { withContext(Dispatchers.Default) { it.search(query) } }.orEmpty()
            else emptyList()
            _state.value = if (online == null || matches.isNotEmpty()) {
                ResultsState.Offline(snapshot?.updatedAt, matches)
            } else online
        }
    }

    private suspend fun loadCategory(uid: String): ResultsState = try {
        categoryResult(query, app.repository.categoryProducts(uid))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ResultsState.Failed(e.toUiError())
    }

    private suspend fun lookupOnline(): ResultsState {
        val classified = classifyQuery(query)
        val stock = if (isBarcode(classified.kind)) {
            try {
                app.networkLookup.byBarcode(employee.shopId, classified.normalized)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        } else null

        return try {
            val result = app.repository.lookup(query, stock) { _state.value = ResultsState.Loading(it) }
            if (result is LookupResult.NotFound && stock != null) ResultsState.Stock(stock)
            else ResultsState.Done(result)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            stock?.let { ResultsState.Stock(it) } ?: ResultsState.Failed(e.toUiError())
        }
    }
}

@Composable
fun ResultsScreen(
    employee: Employee,
    onBack: () -> Unit,
    onBrowseProducts: (List<String>) -> Unit = {},
    searchHistory: List<String>,
    onQueryUsed: (String) -> Unit,
    onClearHistory: () -> Unit,
    onSearch: (String) -> Unit,
    onCatalog: () -> Unit,
    selectedProduct: String? = null,
    tablet: Boolean = false,
    onOpenProduct: (urlKey: String, barcode: String?, scanned: String?, replace: Boolean) -> Unit,
) {
    val app = LocalContext.current.chudoApp
    val vm: ResultsViewModel = viewModel(factory = viewModelFactory {
        initializer { ResultsViewModel(app, employee, createSavedStateHandle()) }
    })
    val state by vm.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    var draft by rememberSaveable(vm.query) { mutableStateOf(vm.query) }
    var shopOnly by rememberSaveable(vm.query) { mutableStateOf(false) }
    var promoOnly by rememberSaveable(vm.query) { mutableStateOf(false) }
    var newOnly by rememberSaveable(vm.query) { mutableStateOf(false) }
    // Кількість у своєму магазині для рядків результатів — за артикулом (залишки 1С).
    val own = remember(vm.query) { androidx.compose.runtime.mutableStateMapOf<String, Double>() }
    LaunchedEffect(state) {
        val many = (state as? ResultsState.Done)?.result as? LookupResult.Many ?: return@LaunchedEffect
        kotlinx.coroutines.supervisorScope {
            many.items.mapNotNull { it.article?.takeIf(String::isNotBlank) }.distinct().take(40).forEach { article ->
                launch {
                    val key = articleKey(article)
                    if (key in own) return@launch
                    val qty = try {
                        app.networkLookup.byArticle(employee.shopId, article).firstOrNull()?.own
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                    if (qty != null) own[key] = qty
                }
            }
        }
    }
    val newArticles by androidx.compose.runtime.produceState(emptySet<String>(), newOnly, vm.query) {
        if (newOnly && Backend.isSupabase) value = try {
            ProductHighlights.page(HighlightKind.NEW, vm.query, 0).map { articleKey(it.article) }.toSet()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptySet()
        }
    }
    // Назву категорії в історію пошуку не пишемо — це не запит користувача.
    LaunchedEffect(vm.query) { if (vm.query.isNotBlank() && vm.category == null) onQueryUsed(vm.query) }
    fun submit() {
        val clean = draft.trim()
        if (clean.isNotEmpty()) {
            keyboard?.hide()
            if (clean != vm.query) onSearch(clean)
        }
    }

    // Один товар — сразу открываем карточку, экран результатов убираем из стека.
    LaunchedEffect(state) {
        val r = (state as? ResultsState.Done)?.result
        if (r is LookupResult.Single) {
            val bc = r.barcode.takeIf { r.via is Via.UpcArticle || r.via is Via.UpcTitle }
            onOpenProduct(r.urlKey, bc, r.barcode, true)
        }
    }

    Column(Modifier.fillMaxSize().background(if (tablet) Mine.colors.card else Mine.colors.bg).statusBarsPadding().imePadding()) {
        ResultsHeader(draft, { draft = it }, onBack, ::submit, focus) {
            if (vm.query.isNotEmpty()) onSearch("") else draft = ""
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SearchChip("Є в магазині", selected = shopOnly, onClick = { shopOnly = !shopOnly })
            SearchChip("Акції", selected = promoOnly, onClick = { promoOnly = !promoOnly })
            SearchChip("Новинки", selected = newOnly, onClick = { newOnly = !newOnly })
            SearchChip("Каталог", icon = R.drawable.ic_redesign_layout_grid, onClick = onCatalog)
        }
        Box(Modifier.fillMaxSize()) {
            AnimatedContent(targetState = state, label = "results", contentKey = { it::class }) { s ->
                when (s) {
                    ResultsState.Empty -> SearchHistory(searchHistory, onSearch, onClearHistory)
                    is ResultsState.Loading -> Loading(s.step)
                    is ResultsState.Failed -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        ErrorState(s.error, siteSearchUrl(Lang.UA, vm.query), vm::load)
                    }
                    is ResultsState.Stock -> StockResult(s.availability)
                    is ResultsState.Offline -> OfflineResults(s, shopOnly, vm::load)
                    is ResultsState.Done -> when (val r = s.result) {
                        is LookupResult.Single -> if (tablet) {
                            Column {
                                SearchSection("Знайдено 1 товар")
                                Text(
                                    vm.query, Modifier.fillMaxWidth().clickable {
                                        val barcode = r.barcode.takeIf { r.via is Via.UpcArticle || r.via is Via.UpcTitle }
                                        onOpenProduct(r.urlKey, barcode, r.barcode, true)
                                    }.padding(horizontal = 16.dp, vertical = 12.dp),
                                    fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Mine.colors.text,
                                )
                            }
                        } else Loading("Відкриваємо товар…")
                        is LookupResult.Many -> {
                            val filters = SearchFilters(shopOnly, promoOnly, newOnly)
                            val items = applySearchFilters(r.items, filters, own, newArticles)
                            ManyList(r, items, selectedProduct, own, filters.any) { urlKey ->
                                val bc = r.barcode.takeIf { r.via !is Via.Direct }
                                onBrowseProducts(items.map { it.urlKey }.distinct())
                                onOpenProduct(urlKey, bc, null, false)
                            }
                        }
                        is LookupResult.NotFound -> Column(
                            Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                        ) {
                            HighlightMatches(r.query, r.barcode)
                            SearchEmptyState(
                                R.drawable.ic_redesign_package_search,
                                title = "Нічого не знайшли",
                                message = "Перевірте цифри або шукайте за артикулом. Товар може бути відсутній на сайті мережі.",
                                top = 48,
                            ) {
                                SearchChip("Ввести штрихкод вручну", onClick = {
                                    draft = ""
                                    focus.requestFocus()
                                    keyboard?.show()
                                })
                                Text(
                                    "Шукати на сайті",
                                    Modifier.clickable { ctx.openUrl(siteSearchUrl(Lang.UA, r.query)) }.padding(8.dp),
                                    fontSize = 14.sp, color = Mine.colors.link,
                                )
                                if (r.triedArticles.isNotEmpty()) Text(
                                    "Перевірили артикули: ${r.triedArticles.joinToString()}",
                                    fontSize = 13.sp, color = Mine.colors.textTertiary,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OfflineResults(result: ResultsState.Offline, shopOnly: Boolean, onRetry: () -> Unit) {
    val matches = if (shopOnly) result.matches.filter { it.quantity > 0 } else result.matches
    val m = Mine.colors
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            val date = result.updatedAt?.let { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it)) }
            Row(
                Modifier.padding(horizontal = 16.dp).fillMaxWidth()
                    .background(m.warningTint, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(painterResource(R.drawable.ic_redesign_triangle_alert), null, Modifier.padding(top = 1.dp).size(18.dp), tint = m.warningText)
                Text(
                    date?.let { "Офлайн · залишки вашого магазину станом на $it" }
                        ?: "Офлайн-каталог недоступний. Завантажте залишки в налаштуваннях сканера, коли з’явиться інтернет.",
                    fontSize = 14.sp, lineHeight = 19.6.sp, color = m.warningText,
                )
            }
        }
        if (matches.isEmpty()) item {
            SearchEmptyState(
                R.drawable.ic_redesign_package_search,
                title = if (result.updatedAt == null) "Немає з’єднання" else "Нічого не знайшли",
                message = if (result.updatedAt == null) "Підключіться до інтернету, щоб завантажити каталог." else "Спробуйте іншу назву, артикул або штрихкод.",
                top = 48,
            ) { SearchChip("Повторити пошук", onClick = onRetry) }
        } else item {
            SearchSection("${matches.size} ${goodsWord(matches.size)}")
            HorizontalDivider(color = m.line)
        }
        items(matches) { item ->
            Row(
                Modifier.fillMaxWidth().background(m.card).heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(48.dp).background(m.surface, RoundedCornerShape(6.dp)).border(1.dp, m.line, RoundedCornerShape(6.dp)),
                    contentAlignment = Alignment.Center,
                ) { SearchIcon(R.drawable.ic_redesign_package, 22) }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(item.name.ifBlank { item.barcode }, fontSize = 15.sp, lineHeight = 19.5.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOf(item.article.takeIf(String::isNotBlank)?.let { "арт. $it" }, item.code, item.barcode)
                            .filterNotNull().filter(String::isNotBlank).distinct().joinToString(" · "),
                        fontSize = 13.sp, color = m.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    if (item.quantity > 0) "${java.text.NumberFormat.getInstance().format(item.quantity)} шт" else "немає",
                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    color = when {
                        item.quantity <= 0 -> m.textTertiary
                        item.quantity <= 2 -> m.warningText
                        else -> m.success
                    },
                )
            }
            HorizontalDivider(color = m.line)
        }
    }
}

private fun goodsWord(n: Int) = when {
    n % 100 in 11..14 -> "товарів"
    n % 10 == 1 -> "товар"
    n % 10 in 2..4 -> "товари"
    else -> "товарів"
}

@Composable
private fun StockResult(stock: NetAvailability) {
    val m = Mine.colors
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stock.item.name.ifBlank { stock.barcode }, fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold, color = m.text)
            Text(
                listOfNotNull(stock.item.article.takeIf(String::isNotBlank)?.let { "Арт. $it" }, stock.item.code.takeIf(String::isNotBlank)?.let { "Код 1С $it" }, stock.barcode)
                    .joinToString(" · "),
                fontSize = 13.sp, color = m.textTertiary,
            )
            Text("Товару немає на сайті мережі — показуємо залишки з 1С.", Modifier.padding(top = 4.dp), fontSize = 13.sp, color = m.textSecondary)
        }
        NetworkStockCard(stock, Modifier.padding(horizontal = 16.dp).fillMaxWidth())
    }
}

@Composable
private fun HighlightMatches(query: String, barcode: String?) {
    val matches by androidx.compose.runtime.produceState<List<ProductHighlight>>(emptyList(), query, barcode) {
        if (Backend.isSupabase) {
            try {
                value = ProductHighlights.forProduct(barcode, query.takeIf { barcode == null })
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                value = emptyList()
            }
        }
    }
    if (matches.isNotEmpty()) {
        SearchSection("Є в акціях і новинках")
        HorizontalDivider(color = Mine.colors.line)
    }
    matches.forEach { match ->
        val kind = HighlightKind.entries.firstOrNull { it.key == match.kind } ?: HighlightKind.NEW
        ua.chudo.scanner.ui.stock.HighlightProductRow(match, kind) {}
    }
}

@Composable
private fun ResultsHeader(
    query: String,
    onQuery: (String) -> Unit,
    onBack: () -> Unit,
    onSubmit: () -> Unit,
    focus: FocusRequester,
    onClear: () -> Unit,
) {
    val m = Mine.colors
    val shape = RoundedCornerShape(6.dp)
    Row(
        Modifier.fillMaxWidth().height(52.dp).padding(start = 8.dp, end = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
            Icon(painterResource(R.drawable.ic_redesign_chevron_left), "Назад", Modifier.size(24.dp), tint = m.textSecondary)
        }
        Row(
            Modifier.weight(1f).height(42.dp).clip(shape).background(m.card).border(1.dp, m.outline, shape)
                .padding(start = 12.dp, end = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SearchIcon(R.drawable.ic_redesign_search, 18)
            BasicTextField(
                query, onQuery, modifier = Modifier.weight(1f).focusRequester(focus).semantics { contentDescription = "Пошук" },
                singleLine = true, textStyle = MaterialTheme.typography.bodyLarge.copy(color = m.text),
                cursorBrush = SolidColor(m.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                decorationBox = { field ->
                    if (query.isEmpty()) Text(
                        "Штрихкод, артикул, назва", color = m.textTertiary,
                        style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    field()
                },
            )
            if (query.isNotEmpty()) IconButton(onClick = onClear, modifier = Modifier.size(32.dp)) {
                Icon(painterResource(R.drawable.ic_redesign_x), "Очистити", Modifier.size(16.dp), tint = m.textTertiary)
            }
        }
    }
}

@Composable
private fun SearchChip(label: String, selected: Boolean? = null, @DrawableRes icon: Int? = null, onClick: () -> Unit) {
    val m = Mine.colors
    val shape = RoundedCornerShape(6.dp)
    val active = selected == true
    val color = if (active) m.card else m.textSecondary
    val action = if (selected != null) Modifier.toggleable(selected, role = Role.Checkbox, onValueChange = { onClick() })
        else Modifier.clickable(role = Role.Button, onClick = onClick)
    Row(
        Modifier.height(32.dp).clip(shape).background(if (active) m.text else m.card)
            .border(1.dp, if (active) m.text else m.outline, shape).then(action).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(painterResource(icon), null, Modifier.size(16.dp), tint = color)
        Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = color)
    }
}

@Composable
private fun SearchHistory(history: List<String>, onSearch: (String) -> Unit, onClear: () -> Unit) {
    val m = Mine.colors
    LazyColumn {
        item { SearchSection("Ви шукали", if (history.isNotEmpty()) onClear else null) }
        if (history.isEmpty()) item { SearchEmptyState(R.drawable.ic_redesign_history, message = "Тут з’являться останні запити") }
        else {
            item { HorizontalDivider(color = m.line) }
            items(history) { query ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).background(m.card)
                        .clickable { onSearch(query) }.padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(painterResource(R.drawable.ic_redesign_history), null, Modifier.size(18.dp), tint = m.text)
                    Text(query, Modifier.weight(1f), fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    SearchIcon(R.drawable.ic_redesign_arrow_up_down, 16)
                }
                HorizontalDivider(color = m.line)
            }
        }
        item {
            SearchSection("Шукати можна за")
            Text(
                "штрихкодом (13 цифр), артикулом, кодом 1С (BOC…) або словами з назви.",
                Modifier.padding(horizontal = 16.dp), fontSize = 14.sp, color = m.textTertiary,
            )
        }
    }
}

@Composable
private fun SearchSection(title: String, onClear: (() -> Unit)? = null) {
    val m = Mine.colors
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, Modifier.weight(1f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = m.textSecondary)
        if (onClear != null) Text("Очистити", Modifier.clickable(onClick = onClear), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = m.link)
    }
}

@Composable
private fun SearchIcon(@DrawableRes icon: Int, size: Int) {
    Icon(painterResource(icon), null, Modifier.size(size.dp), tint = Mine.colors.textTertiary)
}

@Composable
private fun SearchEmptyState(
    @DrawableRes icon: Int,
    message: String,
    title: String? = null,
    top: Int = 32,
    actions: @Composable () -> Unit = {},
) {
    val m = Mine.colors
    Column(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = top.dp, bottom = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.size(48.dp).background(m.surface, CircleShape).border(1.dp, m.line, CircleShape),
            contentAlignment = Alignment.Center,
        ) { Icon(painterResource(icon), null, Modifier.size(20.dp), tint = m.textSecondary) }
        if (title != null) Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Text(message, Modifier.padding(horizontal = 18.dp), fontSize = 14.sp, color = m.textSecondary, textAlign = TextAlign.Center)
        actions()
    }
}

@Composable
private fun Loading(step: String) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(Modifier.size(48.dp), strokeWidth = 4.dp, color = Mine.colors.accent)
        Spacer(Modifier.height(20.dp))
        AnimatedContent(step, label = "step") {
            Text(it, fontSize = 16.sp, color = Mine.colors.textSecondary)
        }
    }
}

@Composable
private fun ManyList(
    r: LookupResult.Many,
    products: List<ProductSummary>,
    selectedProduct: String?,
    own: Map<String, Double>,
    filtered: Boolean,
    onClick: (String) -> Unit,
) {
    val m = Mine.colors
    LazyColumn {
        item {
            val count = products.size
            val noun = when {
                count % 100 in 11..14 -> "товарів"
                count % 10 == 1 -> "товар"
                count % 10 in 2..4 -> "товари"
                else -> "товарів"
            }
            SearchSection("$count $noun")
            viaNote(r.via)?.let { ViaNote(it) }
            if (products.isNotEmpty()) HorizontalDivider(color = m.line)
        }
        if (products.isEmpty()) item {
            SearchEmptyState(
                R.drawable.ic_redesign_package_search,
                message = if (filtered) "За цими фільтрами товарів немає" else "Нічого не знайшли",
            )
        }
        items(products, key = { it.id }) { item ->
            val selected = item.urlKey == selectedProduct
            Row(
                Modifier.fillMaxWidth().background(if (selected) m.surface else m.card).clickable { onClick(item.urlKey) }
                    .then(if (selected) Modifier.drawBehind {
                        drawRect(m.accent, size = androidx.compose.ui.geometry.Size(3.dp.toPx(), size.height))
                    } else Modifier)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val shape = RoundedCornerShape(6.dp)
                Box(
                    Modifier.size(48.dp).clip(shape).background(m.surface).border(1.dp, m.line, shape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (item.image.isNullOrBlank()) SearchIcon(R.drawable.ic_redesign_package, 22)
                    else ProductImage(item.image, Modifier.fillMaxSize())
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(item.name, fontSize = 15.sp, lineHeight = 19.5.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    item.article?.takeIf { it.isNotBlank() }?.let {
                        Text("арт. $it", fontSize = 13.sp, color = m.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    item.price?.let {
                        Text(Cart.money((it.finalPrice * 100).roundToLong()), style = PriceStyle.copy(fontSize = 16.sp))
                    }
                    val price = item.price
                    if (price != null && price.regularPrice > price.finalPrice + 0.001) {
                        Text(Cart.money((price.regularPrice * 100).roundToLong()), fontSize = 13.sp, color = m.textTertiary, textDecoration = TextDecoration.LineThrough)
                    } else own[articleKey(item.article)]?.let { qty ->
                        Text(
                            if (qty > 0) "${java.text.NumberFormat.getInstance().format(qty)} шт" else "немає",
                            fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                            color = when {
                                qty <= 0 -> m.textTertiary
                                qty <= 2 -> m.warningText
                                else -> m.success
                            },
                        )
                    } ?: run {
                        val available = item.stock == StockStatus.IN_STOCK
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            StatusDot(if (available) m.success else m.outline, Modifier.size(8.dp))
                            Text(
                                when (item.stock) {
                                    StockStatus.IN_STOCK -> "Є на сайті"
                                    StockStatus.OUT_OF_STOCK -> "Немає на сайті"
                                    StockStatus.UNKNOWN -> "Невідомо"
                                },
                                fontSize = 13.sp, color = if (available) m.success else m.textTertiary,
                            )
                        }
                    }
                }
            }
            HorizontalDivider(color = m.line)
        }
    }
}

private fun viaNote(via: Via): String? = when (via) {
    Via.Direct, Via.Remembered, Via.StockArticle -> null
    is Via.UpcArticle -> "Штрихкод знайшли у відкритій базі, артикул ${via.article}. Обери свій товар — ми його запам'ятаємо."
    is Via.UpcTitle -> "Шукали за назвою з відкритої бази: «${via.title}». Перевір, що це саме твій товар."
}

@Composable
private fun ViaNote(text: String) {
    Surface(shape = RoundedCornerShape(14.dp), color = Mine.colors.accentTone, contentColor = Mine.colors.text) {
        Text(text, Modifier.padding(12.dp), fontSize = 14.sp, lineHeight = 20.sp)
    }
}
