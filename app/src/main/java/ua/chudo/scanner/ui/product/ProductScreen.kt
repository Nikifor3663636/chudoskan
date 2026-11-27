package ua.chudo.scanner.ui.product

import android.content.ClipData
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material.icons.outlined.Print
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.OpenInBrowser
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.launch
import ua.chudo.scanner.chudoApp
import ua.chudo.scanner.data.Backend
import ua.chudo.scanner.data.HighlightKind
import ua.chudo.scanner.data.ProductHighlight
import ua.chudo.scanner.data.ProductHighlights
import ua.chudo.scanner.ui.stock.highlightPrice
import ua.chudo.scanner.data.Employee
import ua.chudo.scanner.data.NetAvailability
import ua.chudo.scanner.data.StockItem
import ua.chudo.scanner.print.PriceFormat
import ua.chudo.scanner.print.TagData
import ua.chudo.scanner.ui.common.BarcodeScanIcon
import ua.chudo.scanner.ui.common.ErrorState
import ua.chudo.scanner.ui.common.MineButton
import ua.chudo.scanner.ui.common.MineTone
import ua.chudo.scanner.ui.common.ProductImage
import ua.chudo.scanner.ui.common.RegionPickerSheet
import ua.chudo.scanner.ui.common.Shimmer
import ua.chudo.scanner.ui.common.openMap
import ua.chudo.scanner.ui.common.openUrl
import ua.chudo.scanner.ui.common.shareText
import ua.chudo.scanner.ui.print.PrintSheet
import ua.chudo.scanner.ui.theme.Mine
import uniffi.chudo_core.Lang
import uniffi.chudo_core.ProductDetails
import uniffi.chudo_core.ShopAvailability
import uniffi.chudo_core.StockStatus
import uniffi.chudo_core.productUrl

/** Червоний ціни зі знижкою — як на сайті «Чудо Острів». */
private val SalePrice = Color(0xFFF0443A)

/** Зелена галочка наявності — як на сайті. */
private val SiteGreen = Color(0xFF3CC85A)

/**
 * Картка товару в точності як сторінка товару на сайті «Чудо Острів» (мобільна версія):
 * хлібні крихти, назва, артикул, фото зі знижкою, «В наявності», плашка ціни з кнопкою,
 * «Наявність в магазинах» з містом, «Опис», «Характеристики». Замість «Купити» — «Друкувати цінник».
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductScreen(
    employee: Employee,
    onBack: () -> Unit,
    onScan: () -> Unit,
    openPrint: Boolean = false,
    onPreviousProduct: (() -> Unit)? = null,
    onNextProduct: (() -> Unit)? = null,
) {
    val ctx = LocalContext.current
    val app = ctx.chudoApp
    val vm: ProductViewModel = viewModel(factory = viewModelFactory {
        initializer { ProductViewModel(app.repository, app.prefs, createSavedStateHandle()) }
    })
    val s by vm.state.collectAsStateWithLifecycle()
    val region by app.prefs.region.collectAsStateWithLifecycle()
    var showRegions by rememberSaveable { mutableStateOf(false) }
    var printSheet by rememberSaveable { mutableStateOf(openPrint) }
    var own by remember { mutableStateOf<StockItem?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val p = s.product
    val url = productUrl(Lang.UA, p?.urlKey ?: vm.urlKey, p?.urlSuffix ?: ".html")

    var highlights by remember(p?.id) { mutableStateOf(emptyList<ProductHighlight>()) }
    LaunchedEffect(p?.id, s.refreshing, employee.uid) {
        if (Backend.isSupabase && p != null && !s.refreshing) {
            try {
                highlights = ProductHighlights.forProduct(vm.highlightBarcode, p.article)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                highlights = emptyList()
            }
        }
    }
    var net by remember { mutableStateOf<NetAvailability?>(null) }
    // Залишки мережі: ОДНЕ читання stock/{штрихкод} (без штрихкоду — за артикулом).
    // Немає даних — як раніше: залишок свого магазину з shops/{shopId}/stock у списку наявності сайту.
    LaunchedEffect(p?.id) {
        if (p == null) return@LaunchedEffect
        net = runCatching {
            val bc = vm.highlightBarcode
            if (bc != null) app.networkLookup.byBarcode(employee.shopId, bc)
            else p.article?.let { app.networkLookup.byArticle(employee.shopId, it).firstOrNull() }
        }.getOrNull()
        if (net == null) own = runCatching { app.shopStock.find(employee.shopId, p.article, listOfNotNull(vm.barcode)) }.getOrNull()
    }

    Box(Modifier.fillMaxSize().background(pageBg())) {
        Column(Modifier.fillMaxSize()) {
            TopBar(onBack, onShare = { ctx.shareText(listOfNotNull(p?.name, url).joinToString("\n")) }, onSite = { ctx.openUrl(url) }, onScan = onScan)
            PullToRefreshBox(isRefreshing = s.refreshing, onRefresh = { vm.load(refresh = true) }, modifier = Modifier.weight(1f)) {
                when {
                    s.loading -> ProductSkeleton()
                    p == null -> s.error?.let { ErrorState(it, url, { vm.load() }, Modifier.align(Alignment.Center)) }
                    else -> ProductPage(
                        p, s.shops, employee.shop, own, region.name, vm.barcode, s.barcodeToRemember,
                        onCity = { showRegions = true },
                        onRetryShops = vm::loadShops, onMap = ctx::openMap,
                        onRemember = vm::rememberBarcode, onForget = vm::dismissRemember, net = net, highlights = highlights,
                        onPreviousProduct = onPreviousProduct, onNextProduct = onNextProduct,
                    )
                }
            }
            if (p != null && !s.loading) {
                val cartCode = vm.highlightBarcode ?: net?.barcode ?: own?.barcode
                ProductActions(onPrint = { printSheet = true }, onCart = cartCode?.let { code ->
                    {
                        app.cart.add(code)
                        scope.launch { snackbar.showSnackbar("Додано до кошика") }
                    }
                })
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 76.dp))
    }

    val product = p
    if (printSheet && product != null) {
        val price = product.price
        PrintSheet(
            // Штрихкод, код і артикул — з 1С (stock/{штрихкод}), назва й ціни — з сайту.
            tag = TagData.merge(
                TagData.Site(product.name, product.article, price?.finalPrice, price?.regularPrice),
                net?.let { TagData.OneC(it.barcode, it.item.name, it.item.code, it.item.article) },
                vm.highlightBarcode ?: own?.barcode,
            ),
            onDismiss = { printSheet = false },
            onDone = { msg ->
                app.prefs.addActivity(employee, ua.chudo.scanner.data.ActivityKind.PRINT, msg, product.name)
                scope.launch { snackbar.showSnackbar(msg) }
            },
        )
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

/** Сторінка товару без ViewModel — для екрана й для знімків. */
@Composable
fun ProductPage(
    p: ProductDetails,
    shops: ShopsState,
    myShop: String,
    own: StockItem?,
    city: String,
    barcode: String?,
    toRemember: String? = null,
    onCity: () -> Unit = {},
    onRetryShops: () -> Unit = {},
    onMap: (String) -> Unit = {},
    onRemember: () -> Unit = {},
    onForget: () -> Unit = {},
    /** Залишки мережі з stock/{штрихкод}; `null` — даних немає, лише наявність із сайту. */
    net: NetAvailability? = null,
    highlights: List<ProductHighlight> = emptyList(),
    onPreviousProduct: (() -> Unit)? = null,
    onNextProduct: (() -> Unit)? = null,
) {
    val swipe = if (onPreviousProduct != null || onNextProduct != null) {
        Modifier.pointerInput(onPreviousProduct, onNextProduct) {
            var distance = 0f
            detectHorizontalDragGestures(
                onDragStart = { distance = 0f },
                onDragEnd = {
                    if (distance < -64.dp.toPx()) onNextProduct?.invoke()
                    if (distance > 64.dp.toPx()) onPreviousProduct?.invoke()
                },
            ) { change, amount -> change.consume(); distance += amount }
        }
    } else Modifier
    LazyColumn(Modifier.background(pageBg()), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { Gallery(p.images, p.price?.discountPercent ?: 0.0) }
        item {
            Column(Modifier.fillMaxWidth().then(swipe)) {
                Breadcrumbs(p.categories)
                Title(p)
                ProductCodes(p.article, barcode ?: net?.barcode ?: own?.barcode, net?.item?.code)
                PriceBox(p)
                highlights.firstOrNull { it.kind == HighlightKind.PROMO.key }?.endsOn?.let { end ->
                    Text("Акція діє до $end включно", Modifier.padding(horizontal = H, vertical = 3.dp), fontSize = 12.sp, color = Mine.colors.textSecondary)
                }
            }
        }
        if (highlights.isNotEmpty()) item {
            Row(Modifier.padding(horizontal = H), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                highlights.forEach { highlight ->
                    val kind = HighlightKind.entries.firstOrNull { it.key == highlight.kind }
                    if (kind != null) {
                        val price = highlight.promoPrice?.let { " · ${highlightPrice(it)}" }.orEmpty()
                        Text(kind.badge + price, color = Mine.colors.accent, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        net?.own?.let { qty -> item { OwnStock(qty) } }
            ?: own?.let { stock -> item { OwnStock(stock.qty) } }
            ?: item { Availability(p.stock) }
        toRemember?.let { bc -> item { RememberCard(bc, onRemember, onForget) } }
        net?.let { av -> item { NetworkStores(av) } }
        item { SectionDivider() }
        item { SectionTitle("Наявність за даними сайту") }
        item { CityField(city, onCity) }
        shopsSection(shops, myShop, own.takeUnless { net?.own != null }, onRetry = onRetryShops, onMap = onMap)
        if (p.description.isNotBlank()) {
            item { SectionDivider() }
            item { Description(p.description) }
        }

    }
}

private val H = 20.dp

/** Как на сайте: страница белая, плашка цены светло-серая; в тёмной теме — токены Mine. */
@Composable
private fun pageBg(): Color = if (Mine.colors.dark) Mine.colors.bg else Color.White

@Composable
private fun boxBg(): Color = if (Mine.colors.dark) Mine.colors.surface else Color(0xFFF2F3F5)

@Composable
private fun TopBar(onBack: () -> Unit, onShare: () -> Unit, onSite: () -> Unit, onScan: () -> Unit) {
    val m = Mine.colors
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        RoundButton(Icons.AutoMirrored.Rounded.ArrowBack, "Назад", onBack)
        Spacer(Modifier.weight(1f))
        RoundButton(Icons.Rounded.Share, "Поділитися", onShare)
        RoundButton(Icons.Rounded.OpenInBrowser, "Відкрити на сайті", onSite)
        RoundButton(BarcodeScanIcon, "Сканувати", onScan)
    }
}

@Composable
private fun RoundButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    val m = Mine.colors
    Surface(onClick = onClick, modifier = Modifier.size(52.dp), shape = CircleShape, color = Color.Transparent, contentColor = m.text) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, label, Modifier.size(22.dp)) }
    }
}

/** «Головна • Для малюків • Дитячі іграшки» — серым, одной строкой с прокруткой. */
@Composable
private fun Breadcrumbs(categories: List<String>) {
    val m = Mine.colors
    if (categories.isNotEmpty()) Text(
        categories.takeLast(2).joinToString(" · "), Modifier.fillMaxWidth().padding(horizontal = H, vertical = 7.dp),
        fontSize = 12.sp, color = m.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun Title(p: ProductDetails) {
    Text(
        p.name,
        Modifier.padding(horizontal = H).padding(top = 2.dp),
        fontSize = 19.sp,
        lineHeight = 23.sp,
        fontWeight = FontWeight.Bold,
        color = Mine.colors.text,
    )
}

/** Фото на белом, слева сверху красная плашка скидки; точки страниц — оранжевая активная. */
@Composable
private fun Gallery(images: List<String>, discount: Double) {
    val m = Mine.colors
    Column {
        Box(Modifier.fillMaxWidth().background(Color.White)) {
            if (images.isEmpty()) {
                ProductImage(null, Modifier.fillMaxWidth().aspectRatio(1.3f))
            } else {
                val pager = rememberPagerState { images.size }
                var fullScreenPage by rememberSaveable { mutableStateOf<Int?>(null) }
                fullScreenPage?.let { index ->
                    PhotoViewer(images, index) { fullScreenPage = null }
                }
                Column {
                    HorizontalPager(pager, pageSpacing = 12.dp) { page ->
                        ProductImage(images[page], Modifier.fillMaxWidth().aspectRatio(1.5f).clickable(
                            onClickLabel = "Відкрити фото",
                            onClick = { fullScreenPage = page },
                        ))
                    }
                    if (images.size > 1) {
                        Row(Modifier.fillMaxWidth().background(Color.White).padding(vertical = 12.dp), horizontalArrangement = Arrangement.Center) {
                            repeat(images.size) { i ->
                                Box(
                                    Modifier.padding(horizontal = 4.dp).size(9.dp).clip(CircleShape)
                                        .background(if (i == pager.currentPage) m.accent else Color(0xFFD6D8DD)),
                                )
                            }
                        }
                    }
                }
            }
            if (discount >= 1) {
                Text(
                    "-${discount.toInt()}%",
                    Modifier.padding(12.dp).clip(RoundedCornerShape(5.dp)).background(Color(0xFFFBE9E8)).padding(horizontal = 8.dp, vertical = 3.dp),
                    color = Color(0xFFB5221B), fontSize = 13.sp, fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

/** «✓ В наявності» — зелёная галочка, как на сайте. */
@Composable
private fun Availability(stock: StockStatus) {
    val m = Mine.colors
    val (text, ok) = when (stock) {
        StockStatus.IN_STOCK -> "В наявності" to true
        StockStatus.OUT_OF_STOCK -> "Немає в наявності" to false
        StockStatus.UNKNOWN -> "Наявність уточнюйте" to false
    }
    Row(Modifier.padding(horizontal = H).padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (ok) Icons.Rounded.CheckCircle else Icons.Rounded.Cancel, null, Modifier.size(24.dp), tint = if (ok) SiteGreen else m.outline)
        Spacer(Modifier.width(8.dp))
        Text(text, fontSize = 16.sp, color = m.text)
    }
}

/**
 * Серая плашка цены: старая цена зачёркнута, новая — красным крупно (без скидки — обычным цветом),
 * под ней большая оранжевая кнопка (на сайте «Купити») и справа квадратная кнопка (на сайте «в обране»).
 */
@Composable
private fun PriceBox(p: ProductDetails) {
    val m = Mine.colors
    val price = p.price
    Row(
        Modifier.fillMaxWidth().padding(horizontal = H, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (price == null) {
            Text("Ціна недоступна", fontSize = 16.sp, color = m.textSecondary)
        } else {
            Text("${PriceFormat.tag(price.finalPrice).first} ₴", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = m.text)
            if (price.regularPrice > price.finalPrice) {
                Text("${PriceFormat.tag(price.regularPrice).first} ₴", fontSize = 15.sp, color = m.textSecondary, textDecoration = TextDecoration.LineThrough)
                Text("−${PriceFormat.tag(price.regularPrice - price.finalPrice).first} ₴", fontSize = 13.sp, color = SalePrice)
            }
        }
    }
}

@Composable
internal fun ProductActions(onPrint: () -> Unit, onCart: (() -> Unit)?) {
    val m = Mine.colors
    Surface(color = m.card, contentColor = m.text) {
        Column(Modifier.navigationBarsPadding()) {
            HorizontalDivider(color = m.line)
            Row(Modifier.fillMaxWidth().padding(horizontal = H, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(
                    onClick = { onCart?.invoke() }, enabled = onCart != null,
                    modifier = Modifier.size(56.dp), shape = RoundedCornerShape(9.dp),
                    border = BorderStroke(1.dp, m.line), color = m.card, contentColor = m.text,
                ) {
                    Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.ShoppingCart, "Додати до кошика") }
                }
                Surface(onClick = onPrint, modifier = Modifier.weight(1f).height(56.dp), shape = RoundedCornerShape(9.dp), color = m.accent, contentColor = m.onAccent) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Print, null)
                        Text("Друкувати цінник", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun OwnStock(quantity: Double) {
    val m = Mine.colors
    HorizontalDivider(color = m.line)
    Row(Modifier.fillMaxWidth().padding(horizontal = H, vertical = 13.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("${ua.chudo.scanner.ui.stock.qtyText(quantity)} шт", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = m.text)
        Column {
            Text("У вашому магазині", fontSize = 13.sp, color = m.textSecondary)
            Text(if (quantity > 0) "Є на складі магазину" else "Немає на складі магазину", fontSize = 15.sp, color = m.text)
        }
    }
}

@Composable
private fun NetworkStores(net: NetAvailability) {
    val m = Mine.colors
    val total = ua.chudo.scanner.ui.stock.qtyText(net.item.q.values.sum())
    HorizontalDivider(color = m.line)
    Row(
        Modifier.fillMaxWidth().background(boxBg()).padding(horizontal = H, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(net.cityName?.let { "Магазини · $it" } ?: "Залишки мережі", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = m.textSecondary)
        Text("$total шт у мережі", fontSize = 13.sp, color = m.accent, fontWeight = FontWeight.SemiBold)
    }
    net.city.forEach { shop ->
        Row(Modifier.fillMaxWidth().padding(horizontal = H, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(shop.name, Modifier.weight(1f), fontSize = 14.sp, color = m.text)
            Text("${ua.chudo.scanner.ui.stock.qtyText(shop.qty)} шт", fontSize = 14.sp, color = m.textSecondary)
        }
    }
}

@Composable
private fun PhotoViewer(images: List<String>, index: Int, onClose: () -> Unit) {
    val pager = rememberPagerState(initialPage = index) { images.size }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
                ProductImage(images[page], Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 56.dp))
            }
            androidx.compose.material3.IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().size(56.dp)) {
                Icon(Icons.Rounded.Close, "Закрити фото", tint = Color.White)
            }
            Text("${pager.currentPage + 1} / ${images.size}", Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp), color = Color.White)
        }
    }
}

@Composable
private fun SectionDivider() {
    HorizontalDivider(Modifier.padding(top = 24.dp), thickness = 1.dp, color = Mine.colors.line)
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, Modifier.padding(horizontal = H).padding(top = 20.dp, bottom = 14.dp), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Mine.colors.text)
}

/** Поле «Місто» как на сайте: подпись оранжевым сверху, название, стрелка. */
@Composable
private fun CityField(city: String, onClick: () -> Unit) {
    val m = Mine.colors
    Surface(
        onClick = onClick, modifier = Modifier.padding(horizontal = H).fillMaxWidth(), shape = RoundedCornerShape(14.dp),
        color = m.card, contentColor = m.text, border = BorderStroke(1.dp, m.outline),
    ) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Місто", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = m.accent)
                Text(city, fontSize = 18.sp)
            }
            Icon(Icons.Rounded.ExpandMore, "Змінити місто")
        }
    }
}

/** Магазины города: зелёная галочка «є», серый крестик «немає». Свой магазин — первым и с количеством из 1С. */
private fun androidx.compose.foundation.lazy.LazyListScope.shopsSection(
    state: ShopsState,
    myShop: String,
    own: StockItem?,
    onRetry: () -> Unit,
    onMap: (String) -> Unit,
) {
    when (state) {
        ShopsState.Loading -> items(3) { Shimmer(Modifier.padding(horizontal = H, vertical = 6.dp).fillMaxWidth().height(36.dp)) }
        is ShopsState.Failed -> item {
            val m = Mine.colors
            Column(Modifier.padding(horizontal = H, vertical = 8.dp)) {
                Text(state.error.title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = m.text)
                Text(state.error.hint, fontSize = 14.sp, color = m.textSecondary)
                MineButton("Оновити наявність", onRetry, Modifier.padding(top = 8.dp))
            }
        }
        is ShopsState.Loaded -> {
            val mine = state.shops.filter { it.address.equals(myShop, ignoreCase = true) }
            val shops = mine + state.shops.filterNot { it in mine }.sortedByDescending { it.available }
            if (shops.isEmpty()) {
                item { Text("У цьому місті немає даних про магазини.", Modifier.padding(horizontal = H, vertical = 8.dp), fontSize = 15.sp, color = Mine.colors.textSecondary) }
            } else {
                items(shops, key = { it.address }) { shop ->
                    ShopRow(shop, mineQty = if (shop in mine) own?.qty?.toInt() else null, isMine = shop in mine) { onMap(shop.address) }
                }
            }
        }
    }
}

@Composable
private fun ShopRow(shop: ShopAvailability, mineQty: Int?, isMine: Boolean, onClick: () -> Unit) {
    val m = Mine.colors
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = H, vertical = 7.dp), verticalAlignment = Alignment.Top) {
        Icon(
            if (shop.available) Icons.Rounded.CheckCircle else Icons.Rounded.Cancel, null,
            Modifier.padding(top = 1.dp).size(22.dp), tint = if (shop.available) SiteGreen else m.outline,
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(shop.address, fontSize = 16.sp, lineHeight = 22.sp, color = if (shop.available) m.text else m.textSecondary)
            if (isMine) {
                Text(
                    if (mineQty != null) "Твій магазин, за даними 1С: $mineQty шт" else "Твій магазин",
                    fontSize = 14.sp, fontWeight = FontWeight.Bold, color = m.accent,
                )
            }
        }
    }
}

/** Опис із можливістю розгорнути текст. */
@Composable
private fun Description(text: String) {
    val m = Mine.colors
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.animateContentSize()) {
        SectionTitle("Опис")
        Text(
            text, Modifier.padding(horizontal = H), fontSize = 15.sp, lineHeight = 23.sp, color = m.text,
            maxLines = if (expanded) Int.MAX_VALUE else 6, overflow = TextOverflow.Ellipsis,
        )
        Row(Modifier.padding(horizontal = H).padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Text(
                if (expanded) "Згорнути" else "Читати повністю", Modifier.clickable { expanded = !expanded },
                fontSize = 16.sp, fontWeight = FontWeight.Bold, color = m.accent,
            )
        }
    }
}

/** Коди для звірки товару. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ProductCodes(article: String?, barcode: String?, code1C: String?) {
    val values = listOfNotNull(
        article?.takeIf(String::isNotBlank)?.let { "Арт." to it },
        code1C?.takeIf(String::isNotBlank)?.let { "Код 1С" to it },
        barcode?.takeIf(String::isNotBlank)?.let { "Штрихкод" to it },
    )
    if (values.isEmpty()) return
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    androidx.compose.foundation.layout.FlowRow(
        Modifier.padding(horizontal = H, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        values.forEach { (label, value) ->
            Text(
                if (label == "Штрихкод") value else "$label $value",
                Modifier.clickable(onClickLabel = "Скопіювати $label") {
                    scope.launch { clipboard.setClipEntry(ClipData.newPlainText(label, value).toClipEntry()) }
                }.padding(vertical = 4.dp),
                fontSize = 12.sp, color = Mine.colors.textSecondary,
            )
        }
    }
}

@Composable
private fun RememberCard(barcode: String, onYes: () -> Unit, onNo: () -> Unit) {
    val m = Mine.colors
    Surface(Modifier.padding(horizontal = H).padding(top = 14.dp), shape = RoundedCornerShape(20.dp), color = m.accentTone, contentColor = m.text) {
        Column(Modifier.padding(16.dp)) {
            Text("Це той товар?", fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text(
                "Штрихкод $barcode знайшли через відкриту базу. Запам'ятай — наступного разу товар відкриється одразу.",
                Modifier.padding(top = 4.dp), fontSize = 14.sp, lineHeight = 20.sp, color = m.textSecondary,
            )
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MineButton("Запам'ятати", onYes, tone = MineTone.ACCENT)
                MineButton("Ні", onNo)
            }
        }
    }
}

@Composable
private fun ProductSkeleton() {
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Shimmer(Modifier.fillMaxWidth().height(30.dp))
        Shimmer(Modifier.fillMaxWidth(0.4f).height(14.dp))
        Shimmer(Modifier.fillMaxWidth().aspectRatio(1.3f), 16.dp)
        Shimmer(Modifier.fillMaxWidth().height(150.dp), 20.dp)
    }
}
