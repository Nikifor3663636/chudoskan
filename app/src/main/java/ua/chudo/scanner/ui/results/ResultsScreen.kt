package ua.chudo.scanner.ui.results

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import ua.chudo.scanner.ui.common.ProductCard
import ua.chudo.scanner.ui.common.UiError
import ua.chudo.scanner.ui.common.openUrl
import ua.chudo.scanner.ui.common.toUiError
import uniffi.chudo_core.siteSearchUrl
import uniffi.chudo_core.Lang

sealed interface ResultsState {
    data class Loading(val step: String) : ResultsState
    data class Done(val result: LookupResult) : ResultsState
    data class Stock(val availability: NetAvailability) : ResultsState
    data class Failed(val error: UiError) : ResultsState
}

class ResultsViewModel(
    private val app: ChudoApp,
    private val shopId: String,
    handle: SavedStateHandle,
) : ViewModel() {
    val query: String = checkNotNull(handle["q"])
    private val _state = MutableStateFlow<ResultsState>(ResultsState.Loading("Шукаємо…"))
    val state = _state.asStateFlow()
    private var job: Job? = null

    init { load() }

    fun load() {
        job?.cancel()
        job = viewModelScope.launch {
            _state.value = ResultsState.Loading("Шукаємо…")
            val classified = classifyQuery(query)
            val stock = if (isBarcode(classified.kind)) {
                try {
                    app.networkLookup.byBarcode(shopId, classified.normalized)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
            } else null

            _state.value = try {
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultsScreen(
    shopId: String,
    onBack: () -> Unit,
    onOpenProduct: (urlKey: String, barcode: String?, scanned: String?, replace: Boolean) -> Unit,
) {
    val app = LocalContext.current.chudoApp
    val vm: ResultsViewModel = viewModel(factory = viewModelFactory {
        initializer { ResultsViewModel(app, shopId, createSavedStateHandle()) }
    })
    val state by vm.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current

    // Один товар — сразу открываем карточку, экран результатов убираем из стека.
    LaunchedEffect(state) {
        val r = (state as? ResultsState.Done)?.result
        if (r is LookupResult.Single) {
            val bc = r.barcode.takeIf { r.via is Via.UpcArticle || r.via is Via.UpcTitle }
            onOpenProduct(r.urlKey, bc, r.barcode, true)
        }
    }

    Column(Modifier.fillMaxSize().background(Mine.colors.bg)) {
        ResultsHeader(vm.query, onBack)
        Box(Modifier.fillMaxSize()) {
            AnimatedContent(targetState = state, label = "results", contentKey = { it::class }) { s ->
                when (s) {
                    is ResultsState.Loading -> Loading(s.step)
                    is ResultsState.Failed -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        ErrorState(s.error, siteSearchUrl(Lang.UA, vm.query), vm::load)
                    }
                    is ResultsState.Stock -> StockResult(s.availability)
                    is ResultsState.Done -> when (val r = s.result) {
                        is LookupResult.Single -> Loading("Відкриваємо товар…")
                        is LookupResult.Many -> ManyList(r) { urlKey ->
                            val bc = r.barcode.takeIf { r.via !is Via.Direct }
                            onOpenProduct(urlKey, bc, null, false)
                        }
                        is LookupResult.NotFound -> Column(
                            Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.Center,
                        ) {
                            HighlightMatches(r.query, r.barcode)
                            MessageState(
                                icon = Icons.Rounded.SearchOff,
                                title = "Товарів не знайдено",
                                text = buildString {
                                    append("Спробуй ввести артикул з цінника — він зазвичай у дужках у назві.")
                                    if (r.triedArticles.isNotEmpty()) {
                                        append("\n\nПеревірили артикули: ${r.triedArticles.joinToString()}")
                                    }
                                },
                                primary = "Шукати на сайті" to { ctx.openUrl(siteSearchUrl(Lang.UA, r.query)) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StockResult(stock: NetAvailability) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stock.item.name.ifBlank { stock.barcode }, style = MaterialTheme.typography.titleLarge, color = Mine.colors.text)
        stock.item.article.takeIf { it.isNotBlank() }?.let {
            Text("Артикул $it", color = Mine.colors.textSecondary)
        }
        NetworkStockCard(stock, Modifier.fillMaxWidth())
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
    matches.forEach { match ->
        val kind = HighlightKind.entries.firstOrNull { it.key == match.kind }
        val price = match.promoPrice?.let { " · ${highlightPrice(it)}" }.orEmpty()
        Text("${match.name} · ${kind?.badge.orEmpty()}$price", Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
    }
}

/** «Пошук» и запрос — крупно, с круглой кнопкой «назад». */
@Composable
private fun ResultsHeader(query: String, onBack: () -> Unit) {
    val m = Mine.colors
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(onClick = onBack, modifier = Modifier.size(48.dp), shape = CircleShape, color = m.card, contentColor = m.text, border = BorderStroke(1.dp, m.line)) {
            Box(contentAlignment = Alignment.Center) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад", Modifier.size(22.dp)) }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("Результати пошуку", fontSize = 14.sp, color = m.textSecondary)
            Text(query, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = m.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
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
private fun ManyList(r: LookupResult.Many, onClick: (String) -> Unit) {
    LazyColumn(
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Знайдено ${r.items.size}", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Mine.colors.textSecondary)
                viaNote(r.via)?.let { ViaNote(it) }
            }
        }
        items(r.items, key = { it.id }) { item -> ProductCard(item, onClick = { onClick(item.urlKey) }) }
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
