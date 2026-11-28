package ua.chudo.scanner.ui.results

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import kotlinx.coroutines.launch
import ua.chudo.scanner.R
import ua.chudo.scanner.chudoApp
import ua.chudo.scanner.data.ChudoRepository
import ua.chudo.scanner.ui.common.ErrorState
import ua.chudo.scanner.ui.common.UiError
import ua.chudo.scanner.ui.common.toUiError
import ua.chudo.scanner.ui.theme.Mine
import uniffi.chudo_core.Category

sealed interface CatalogState {
    data object Loading : CatalogState
    data class Done(val categories: List<Category>) : CatalogState
    data class Failed(val error: UiError) : CatalogState
}

/** Без мережі чи при збої сайту — стан помилки з «Спробувати ще раз», а не порожній список. */
internal suspend fun loadCatalog(load: suspend () -> List<Category>): CatalogState = try {
    CatalogState.Done(load())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    CatalogState.Failed(e.toUiError())
}

internal class CatalogViewModel(private val load: suspend () -> List<Category>) : ViewModel() {
    private val _state = MutableStateFlow<CatalogState>(CatalogState.Loading)
    val state = _state.asStateFlow()
    private var job: Job? = null

    init { reload() }

    fun reload() {
        job?.cancel()
        job = viewModelScope.launch {
            _state.value = CatalogState.Loading
            _state.value = loadCatalog(load)
        }
    }

    companion object {
        fun from(repository: ChudoRepository) = CatalogViewModel { repository.categories() }
    }
}

/** «1284» → «1 284» з нерозривним пробілом, як у макеті. */
fun groupDigits(n: Long): String = n.toString().reversed().chunked(3).joinToString(" ").reversed()

/** Заголовок товарів категорії: сторінка менша за категорію — «Перші 60 з 2 234». */
fun categoryHeader(shown: Int, total: Int?): String? =
    total?.takeIf { it > shown }?.let { "Перші $shown з ${groupDigits(it.toLong())}" }

@Composable
fun CatalogScreen(onBack: () -> Unit, onCategory: (Category) -> Unit) {
    val app = LocalContext.current.chudoApp
    val vm: CatalogViewModel = viewModel(factory = viewModelFactory { initializer { CatalogViewModel.from(app.repository) } })
    val state by vm.state.collectAsStateWithLifecycle()
    CatalogContent(state, onBack, vm::reload, onCategory)
}

@Composable
fun CatalogContent(state: CatalogState, onBack: () -> Unit, onRetry: () -> Unit, onCategory: (Category) -> Unit) {
    val m = Mine.colors
    Column(Modifier.fillMaxSize().background(m.bg).statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(painterResource(R.drawable.ic_redesign_chevron_left), "Назад", Modifier.size(24.dp), tint = m.textSecondary)
            }
            Text(
                "Каталог", Modifier.weight(1f).padding(start = 4.dp),
                fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = m.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Box(
                Modifier.height(22.dp).background(m.infoTint, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center,
            ) { Text("Нове", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = m.info) }
            Box(Modifier.width(8.dp))
        }
        when (state) {
            CatalogState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = m.text, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
            }
            is CatalogState.Failed -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                ErrorState(state.error, null, onRetry)
            }
            is CatalogState.Done -> if (state.categories.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    ErrorState(UiError("Категорій поки немає", "Сайт не віддав жодної категорії. Спробуй пізніше."), null, onRetry)
                }
            } else LazyColumn(Modifier.fillMaxSize()) {
                item { HorizontalDivider(color = m.line) }
                items(state.categories, key = { it.uid }) { category -> CategoryRow(category) { onCategory(category) } }
            }
        }
    }
}

@Composable
private fun CategoryRow(category: Category, onClick: () -> Unit) {
    val m = Mine.colors
    Row(
        Modifier.fillMaxWidth().background(m.card).clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(R.drawable.ic_redesign_layers), null, Modifier.size(18.dp), tint = m.text)
        Text(
            category.name, Modifier.weight(1f), fontSize = 15.sp, lineHeight = 19.5.sp, fontWeight = FontWeight.Medium,
            color = m.text, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        Text(groupDigits(category.count.toLong()), fontSize = 14.sp, color = m.textTertiary, style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"))
        Icon(painterResource(R.drawable.ic_redesign_chevron_right), null, Modifier.size(18.dp), tint = m.textTertiary)
    }
    HorizontalDivider(color = m.line)
}
