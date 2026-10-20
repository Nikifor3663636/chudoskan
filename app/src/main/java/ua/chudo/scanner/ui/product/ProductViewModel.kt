package ua.chudo.scanner.ui.product

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ua.chudo.scanner.data.AppPrefs
import ua.chudo.scanner.data.ChudoRepository
import ua.chudo.scanner.data.HistoryItem
import ua.chudo.scanner.ui.common.UiError
import ua.chudo.scanner.ui.common.toUiError
import uniffi.chudo_core.ProductDetails
import uniffi.chudo_core.ShopAvailability

sealed interface ShopsState {
    data object Loading : ShopsState
    data class Loaded(val shops: List<ShopAvailability>) : ShopsState
    data class Failed(val error: UiError) : ShopsState
}

data class ProductUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val product: ProductDetails? = null,
    val error: UiError? = null,
    val shops: ShopsState = ShopsState.Loading,
    /** Штрихкод, найденный через открытую базу и ещё не привязанный. */
    val barcodeToRemember: String? = null,
)

class ProductViewModel(
    private val repo: ChudoRepository,
    private val prefs: AppPrefs,
    handle: SavedStateHandle,
) : ViewModel() {
    val urlKey: String = checkNotNull(handle["urlKey"])
    private val barcode: String? = handle.get<String>("bc")?.takeIf { it.isNotBlank() }

    private val _state = MutableStateFlow(
        ProductUiState(barcodeToRemember = barcode?.takeIf { prefs.rememberedProduct(it) != urlKey })
    )
    val state = _state.asStateFlow()
    private var shopsJob: Job? = null

    init {
        load()
        viewModelScope.launch { prefs.region.drop(1).collect { loadShops() } }
    }

    fun load(refresh: Boolean = false) = viewModelScope.launch {
        _state.update { it.copy(loading = !refresh, refreshing = refresh, error = null) }
        try {
            val p = repo.details(urlKey)
            _state.update { it.copy(loading = false, refreshing = false, product = p) }
            prefs.addToHistory(HistoryItem(p.urlKey, p.name, p.article, p.images.firstOrNull()))
            loadShops()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(loading = false, refreshing = false, error = e.toUiError()) }
        }
    }

    fun loadShops() {
        val id = _state.value.product?.id ?: return
        shopsJob?.cancel()
        shopsJob = viewModelScope.launch {
            _state.update { it.copy(shops = ShopsState.Loading) }
            val next = try {
                ShopsState.Loaded(repo.shops(id, prefs.region.value.code))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ShopsState.Failed(e.toUiError())
            }
            _state.update { it.copy(shops = next) }
        }
    }

    fun rememberBarcode() {
        val bc = _state.value.barcodeToRemember ?: return
        prefs.rememberBarcode(bc, urlKey)
        _state.update { it.copy(barcodeToRemember = null) }
    }

    fun dismissRemember() = _state.update { it.copy(barcodeToRemember = null) }
}
