package ua.chudo.scanner.ui.layout

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ua.chudo.scanner.chudoApp
import ua.chudo.scanner.data.ChudoRepository
import ua.chudo.scanner.data.Employee
import ua.chudo.scanner.data.LayoutPin
import ua.chudo.scanner.data.LayoutRepository
import ua.chudo.scanner.data.LookupResult
import ua.chudo.scanner.data.ShopStockRepository
import ua.chudo.scanner.data.StockItem
import ua.chudo.scanner.ui.common.MessageState
import ua.chudo.scanner.ui.common.toUiError
import ua.chudo.shared.layout.Box
import ua.chudo.shared.layout.BrandMatcher
import ua.chudo.shared.layout.MapCamera
import ua.chudo.shared.layout.MapLabel
import ua.chudo.shared.layout.ShopLayout
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/** Что искали: [key] — штрихкод или бренд (ключ ручного пина), [auto] — индексы найденных подписей. */
data class LayoutHit(val title: String, val key: String, val brand: String?, val auto: List<Int>)

/** Место на карте: подпись или ручной пин. */
data class Place(val text: String, val box: Box, val pin: LayoutPin? = null)

sealed interface StockState {
    data class Loading(val label: String) : StockState
    data class Items(val label: String, val items: List<StockItem>) : StockState
    data class Failed(val label: String, val message: String) : StockState
}

class StoreLayoutViewModel(
    private val employee: Employee,
    private val layouts: LayoutRepository,
    private val repo: ChudoRepository,
    private val stock: ShopStockRepository?,
) : ViewModel() {
    private val shopId = employee.shopId
    private val _layout = MutableStateFlow<ShopLayout?>(null)
    val layout = _layout.asStateFlow()
    private val _image = MutableStateFlow<ImageBitmap?>(null)
    val image = _image.asStateFlow()
    private val _loaded = MutableStateFlow(false)
    val loaded = _loaded.asStateFlow()
    private val _hit = MutableStateFlow<LayoutHit?>(null)
    val hit = _hit.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    private val _stock = MutableStateFlow<StockState?>(null)
    val stockState = _stock.asStateFlow()
    val pins = (if (stock != null && shopId.isNotEmpty()) layouts.pinsFlow(shopId) else emptyFlow())
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private var matcher = BrandMatcher()
    private var job: Job? = null

    val uid get() = employee.uid

    init {
        viewModelScope.launch {
            _layout.value = layouts.layout(shopId)
            if (_layout.value != null) _image.value = layouts.image(shopId)?.asImageBitmap()
            matcher = BrandMatcher(layouts.synonyms())
            _loaded.value = true
        }
    }

    fun search(raw: String) {
        val q = raw.trim().ifEmpty { return }
        val l = _layout.value ?: return
        job?.cancel()
        job = viewModelScope.launch {
            _busy.value = true
            _message.value = null
            try {
                val digits = q.filter(Char::isDigit)
                val barcode = digits.takeIf { it.length >= 8 && it.length == q.length }
                // Текст, совпавший с подписью, — это бренд: сеть не нужна.
                if (barcode == null) {
                    val direct = l.labels.indices.filter { matcher.same(q, l.labels[it].text) }
                    if (direct.isNotEmpty()) {
                        _hit.value = LayoutHit(q, BrandMatcher.norm(q), q, direct)
                        return@launch
                    }
                }
                val name = productName(q, barcode)
                val brand = BrandMatcher.brandOf(name)
                val key = barcode ?: brand?.let(BrandMatcher::norm) ?: BrandMatcher.norm(q)
                _hit.value = LayoutHit(name ?: q, key, brand, matcher.find(l.labels, brand, name ?: q))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _message.value = e.toUiError().let { "${it.title}. ${it.hint}" }
            } finally {
                _busy.value = false
            }
        }
    }

    /** Название товара: остатки магазина (по штрихкоду — одно чтение), потом сайт. */
    private suspend fun productName(q: String, barcode: String?): String? {
        if (barcode != null && stock != null) stock.byBarcode(shopId, barcode)?.name?.let { return it }
        return when (val r = repo.lookup(q)) {
            is LookupResult.Single -> repo.details(r.urlKey).name
            is LookupResult.Many -> r.items.firstOrNull()?.name
            is LookupResult.NotFound -> null
        }
    }

    fun clear() { _hit.value = null }

    /** Места: ручные пины по ключу важнее автопоиска. */
    fun places(hit: LayoutHit, l: ShopLayout, pins: List<LayoutPin>): List<Place> {
        val brandKey = hit.brand?.let(BrandMatcher::norm)
        val mine = pins.filter { it.key == hit.key || (brandKey != null && it.key == brandKey) }
        if (mine.isNotEmpty()) return mine.map { Place(it.label ?: "Вказано вручну", Box(it.x - PIN_BOX / 2, it.y - PIN_BOX / 2, PIN_BOX, PIN_BOX), it) }
        return hit.auto.map { Place(l.labels[it].text, l.labels[it].box) }
    }

    fun addPin(fx: Float, fy: Float) {
        val hit = _hit.value ?: return
        val label = _layout.value?.labels?.firstOrNull { it.box.contains(fx, fy) }?.text
        viewModelScope.launch {
            _message.value = try {
                layouts.addPin(shopId, employee.uid, hit.key, fx, fy, label); "Місце збережено"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "Не вдалося зберегти: ${e.message}"
            }
        }
    }

    fun deletePin(pin: LayoutPin) {
        viewModelScope.launch {
            _message.value = try {
                layouts.deletePin(shopId, pin.key); "Позначку видалено"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "Не вдалося видалити: ${e.message}"
            }
        }
    }

    fun openStock(label: String) {
        val s = stock ?: return
        _stock.value = StockState.Loading(label)
        viewModelScope.launch {
            _stock.value = try {
                StockState.Items(label, s.byBrand(shopId, label))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                StockState.Failed(label, e.toUiError().title)
            }
        }
    }

    fun closeStock() { _stock.value = null }

    companion object {
        const val PIN_BOX = 0.01f
    }
}

private val ORANGE = Color(0xFFFF8A00)

@Composable
fun StoreLayoutScreen(employee: Employee, onScan: () -> Unit, scanned: String?, onScannedConsumed: () -> Unit) {
    val app = LocalContext.current.chudoApp
    val firebase = app.auth.configured
    val vm: StoreLayoutViewModel = viewModel(
        key = "layout-${employee.shopId}",
        factory = viewModelFactory { initializer { StoreLayoutViewModel(employee, app.layouts, app.repository, if (firebase) app.shopStock else null) } },
    )
    val layout by vm.layout.collectAsStateWithLifecycle()
    val image by vm.image.collectAsStateWithLifecycle()
    val loaded by vm.loaded.collectAsStateWithLifecycle()
    val hit by vm.hit.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val pins by vm.pins.collectAsStateWithLifecycle()
    val stockState by vm.stockState.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var pinMode by remember { mutableStateOf(false) }
    var focusPlace by remember { mutableStateOf<Place?>(null) }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(scanned) {
        scanned?.let { query = it; vm.search(it); onScannedConsumed() }
    }

    if (!loaded) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val l = layout
    val img = image
    if (l == null || img == null) {
        Box(Modifier.fillMaxSize().statusBarsPadding(), contentAlignment = Alignment.Center) {
            MessageState(icon = Icons.Rounded.Map, title = "План магазину ще не додано", text = "Коли план твого магазину з'явиться, тут можна буде знайти, де лежить товар.")
        }
        return
    }
    val places = hit?.let { vm.places(it, l, pins) }.orEmpty()

    Box(Modifier.fillMaxSize().statusBarsPadding()) {
        ShopMap(
            image = img, layout = l, places = places, pinMode = pinMode, uid = vm.uid,
            onLabel = { vm.openStock(it.text) },
            onPinAt = { fx, fy -> pinMode = false; vm.addPin(fx, fy) },
            onDeletePin = vm::deletePin,
            focus = places,
            focusPlace = focusPlace,
            onFocused = { focusPlace = null },
        )
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                shape = RoundedCornerShape(16.dp),
                placeholder = { Text("Де лежить: бренд, штрихкод, артикул") },
                trailingIcon = {
                    Row {
                        if (busy) CircularProgressIndicator(Modifier.size(22.dp).padding(2.dp), strokeWidth = 2.dp)
                        if (query.isNotEmpty()) IconButton(onClick = { query = ""; vm.clear(); pinMode = false }) { Icon(Icons.Rounded.Close, "Очистити") }
                        IconButton(onClick = onScan) { Icon(Icons.Rounded.QrCodeScanner, "Сканувати") }
                    }
                },
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); vm.search(query) }),
            )
            if (pinMode) {
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Торкніться місця на карті", Modifier.weight(1f))
                        TextButton(onClick = { pinMode = false }) { Text("Скасувати") }
                    }
                }
            }
            message?.let { Card(Modifier.fillMaxWidth()) { Text(it, Modifier.padding(12.dp)) } }
        }
        hit?.let { h ->
            if (!pinMode) {
                ResultCard(h, places, firebase, Modifier.align(Alignment.BottomCenter), onPin = { pinMode = true }, onPlace = { focusPlace = it })
            }
        }
    }

    stockState?.let { s -> StockDialog(s, vm::closeStock) }
}


@Composable
private fun ResultCard(h: LayoutHit, places: List<Place>, canPin: Boolean, modifier: Modifier, onPin: () -> Unit, onPlace: (Place) -> Unit) {
    Card(modifier.fillMaxWidth().padding(12.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(h.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (places.isEmpty()) {
                Text("Місце не знайдено", color = MaterialTheme.colorScheme.error)
                if (canPin) Button(onClick = onPin) { Text("Вказати місце") }
            } else {
                Text("Знайдено місць: ${places.size}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyColumn(Modifier.heightIn(max = 160.dp)) {
                    items(places) { p ->
                        Text(
                            (if (p.pin != null) "📍 " else "• ") + p.text,
                            Modifier.fillMaxWidth().clickable { onPlace(p) }.padding(vertical = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StockDialog(s: StockState, onClose: () -> Unit) {
    val label = when (s) {
        is StockState.Loading -> s.label
        is StockState.Items -> s.label
        is StockState.Failed -> s.label
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("ТМ $label") },
        text = {
            when (s) {
                is StockState.Loading -> CircularProgressIndicator()
                is StockState.Failed -> Text(s.message)
                is StockState.Items -> if (s.items.isEmpty()) {
                    Text("У залишках магазину немає товарів цього бренду.")
                } else {
                    LazyColumn(Modifier.heightIn(max = 400.dp)) {
                        items(s.items) { item ->
                            Row(Modifier.padding(vertical = 6.dp)) {
                                Text(item.name.orEmpty(), Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text("${formatQty(item.qty)} шт.", Modifier.padding(start = 8.dp), fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Закрити") } },
    )
}

private fun formatQty(v: Double) = if (v == Math.floor(v)) v.toLong().toString() else v.toString()

@Composable
private fun ShopMap(
    image: ImageBitmap,
    layout: ShopLayout,
    places: List<Place>,
    pinMode: Boolean,
    uid: String,
    onLabel: (MapLabel) -> Unit,
    onPinAt: (Float, Float) -> Unit,
    onDeletePin: (LayoutPin) -> Unit,
    focus: List<Place>,
    focusPlace: Place?,
    onFocused: () -> Unit,
) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    var cam by remember { mutableStateOf<MapCamera?>(null) }
    val density = LocalDensity.current
    val touch = with(density) { 28.dp.toPx() }

    LaunchedEffect(size) {
        if (size.width > 0) cam = MapCamera(image.width.toFloat(), image.height.toFloat(), size.width.toFloat(), size.height.toFloat()).clamped()
    }
    // Новые места — карта сама показывает их все.
    LaunchedEffect(focus, cam != null) {
        val c = cam ?: return@LaunchedEffect
        if (focus.isNotEmpty()) cam = c.fit(focus.map { it.box })
    }
    LaunchedEffect(focusPlace) {
        val p = focusPlace ?: return@LaunchedEffect
        cam?.let { cam = it.centerOn(p.box.cx, p.box.cy, max(it.zoom, 3f)) }
        onFocused()
    }

    val currentPlaces by rememberUpdatedState(places)
    val currentPinMode by rememberUpdatedState(pinMode)
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        0f, 1f, infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart), label = "pulse",
    )

    Canvas(
        Modifier.fillMaxSize()
            .onSizeChanged { size = it }
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    cam = cam?.let { it.zoomAt(centroid.x, centroid.y, it.zoom * zoom).pan(pan.x, pan.y) }
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { o -> cam = cam?.let { it.zoomAt(o.x, o.y, if (it.zoom >= MapCamera.MAX_ZOOM - 0.01f) 1f else it.zoom * 2f) } },
                    onTap = { o ->
                        val c = cam ?: return@detectTapGestures
                        val (fx, fy) = c.toImage(o.x, o.y)
                        if (fx !in 0f..1f || fy !in 0f..1f) return@detectTapGestures
                        if (currentPinMode) onPinAt(fx, fy) else layout.labels.firstOrNull { it.box.contains(fx, fy) }?.let(onLabel)
                    },
                    onLongPress = { o ->
                        val c = cam ?: return@detectTapGestures
                        currentPlaces.mapNotNull { it.pin }.filter { it.by == uid }.firstOrNull { p ->
                            val (sx, sy) = c.toScreen(p.x, p.y)
                            hypot(sx - o.x, sy - o.y) < touch
                        }?.let(onDeletePin)
                    },
                )
            },
    ) {
        val c = cam ?: return@Canvas
        drawImage(
            image,
            dstOffset = IntOffset(c.tx.roundToInt(), c.ty.roundToInt()),
            dstSize = IntSize(c.contentW.roundToInt(), c.contentH.roundToInt()),
            filterQuality = FilterQuality.Medium,
        )
        val r = 8.dp.toPx()
        places.forEach { p ->
            val (sx, sy) = c.toScreen(p.box.cx, p.box.cy)
            val center = Offset(sx, sy)
            drawCircle(ORANGE.copy(alpha = 0.45f * (1f - pulse)), radius = r + 22.dp.toPx() * pulse, center = center)
            drawCircle(Color.White, radius = r + 2.dp.toPx(), center = center)
            drawCircle(ORANGE, radius = r, center = center)
        }
    }
}
