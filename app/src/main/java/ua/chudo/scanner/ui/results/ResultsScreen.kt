package ua.chudo.scanner.ui.results

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
import ua.chudo.scanner.data.ChudoRepository
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
    data class Failed(val error: UiError) : ResultsState
}

class ResultsViewModel(
    private val repo: ChudoRepository,
    private val app: ChudoApp,
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
            _state.value = try {
                val step: (String) -> Unit = { _state.value = ResultsState.Loading(it) }
                ResultsState.Done(repo.lookup(query, step))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ResultsState.Failed(e.toUiError())
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultsScreen(
    onBack: () -> Unit,
    onOpenProduct: (urlKey: String, barcode: String?, replace: Boolean) -> Unit,
    onScanAgain: () -> Unit,
) {
    val app = LocalContext.current.chudoApp
    val vm: ResultsViewModel = viewModel(factory = viewModelFactory {
        initializer { ResultsViewModel(app.repository, app, createSavedStateHandle()) }
    })
    val state by vm.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current

    // Один товар — сразу открываем карточку, экран результатов убираем из стека.
    LaunchedEffect(state) {
        val r = (state as? ResultsState.Done)?.result
        if (r is LookupResult.Single) {
            val bc = r.barcode.takeIf { r.via is Via.UpcArticle || r.via is Via.UpcTitle }
            onOpenProduct(r.urlKey, bc, true)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(vm.query, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад") }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            AnimatedContent(targetState = state, label = "results", contentKey = { it::class }) { s ->
                when (s) {
                    is ResultsState.Loading -> Loading(s.step)
                    is ResultsState.Failed -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        ErrorState(s.error, siteSearchUrl(Lang.UA, vm.query), vm::load)
                    }
                    is ResultsState.Done -> when (val r = s.result) {
                        is LookupResult.Single -> Loading("Відкриваємо товар…")
                        is LookupResult.Many -> ManyList(r) { urlKey ->
                            val bc = r.barcode.takeIf { r.via !is Via.Direct }
                            onOpenProduct(urlKey, bc, false)
                        }
                        is LookupResult.NotFound -> Column(
                            Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.Center,
                        ) {
                            MessageState(
                                icon = Icons.Rounded.SearchOff,
                                title = "Не знайшли «${r.query}»",
                                text = buildString {
                                    append("Спробуй ввести артикул з цінника — він зазвичай у дужках у назві.")
                                    if (r.triedArticles.isNotEmpty()) {
                                        append("\n\nПеревірили артикули: ${r.triedArticles.joinToString()}")
                                    }
                                },
                                primary = "Шукати на сайті" to { ctx.openUrl(siteSearchUrl(Lang.UA, r.query)) },
                                secondary = "Сканувати ще" to onScanAgain,
                            )
                        }
                    }
                }
            }
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
        CircularProgressIndicator(Modifier.size(48.dp), strokeWidth = 4.dp)
        Spacer(Modifier.height(20.dp))
        AnimatedContent(step, label = "step") {
            Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ManyList(r: LookupResult.Many, onClick: (String) -> Unit) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Знайдено ${r.items.size}", style = MaterialTheme.typography.titleMedium)
                viaNote(r.via)?.let { ViaNote(it) }
            }
        }
        items(r.items, key = { it.id }) { item -> ProductCard(item, onClick = { onClick(item.urlKey) }) }
    }
}

private fun viaNote(via: Via): String? = when (via) {
    Via.Direct, Via.Remembered -> null
    is Via.UpcArticle -> "Штрихкод знайшли у відкритій базі → артикул ${via.article}. Обери свій товар — ми його запам'ятаємо."
    is Via.UpcTitle -> "Шукали за назвою з відкритої бази: «${via.title}». Перевір, що це саме твій товар."
}

@Composable
private fun ViaNote(text: String) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.tertiaryContainer) {
        Text(
            text,
            Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
        )
    }
}
