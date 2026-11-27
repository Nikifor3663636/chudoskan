package ua.chudo.scanner.ui.product

import android.content.ClipData
import androidx.annotation.DrawableRes
import androidx.compose.foundation.border
import androidx.compose.material3.IconButton
import androidx.compose.ui.res.painterResource
import ua.chudo.scanner.R
import ua.chudo.scanner.ui.home.updatedText
import ua.chudo.scanner.ui.stock.qtyText
import ua.chudo.scanner.ui.theme.CodeStyle
import ua.chudo.scanner.ui.theme.PriceStyle
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
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
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToLong
import ua.chudo.scanner.data.Employee
import ua.chudo.scanner.data.NetAvailability
import ua.chudo.scanner.data.StockItem
import ua.chudo.scanner.data.StoreQty
import ua.chudo.scanner.print.TagData
import ua.chudo.scanner.ui.common.ErrorState
import ua.chudo.scanner.ui.common.MineButton
import ua.chudo.scanner.ui.common.MineTone
import ua.chudo.scanner.ui.common.ProductImage
import ua.chudo.scanner.ui.common.RegionPickerSheet
import ua.chudo.scanner.ui.common.Shimmer
import ua.chudo.scanner.ui.common.openMap
import ua.chudo.scanner.ui.common.openUrl
import ua.chudo.scanner.ui.common.shareText
import ua.chudo.scanner.ui.common.shopsOfCity
import ua.chudo.scanner.ui.print.PrintSheet
import ua.chudo.scanner.ui.theme.Mine
import uniffi.chudo_core.Lang
import uniffi.chudo_core.ProductDetails
import uniffi.chudo_core.ShopAvailability
import uniffi.chudo_core.StockStatus
import uniffi.chudo_core.productUrl

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductScreen(
    employee: Employee,
    onBack: () -> Unit,
    onScan: () -> Unit,
    openPrint: Boolean = false,
    onPreviousProduct: (() -> Unit)? = null,
    onNextProduct: (() -> Unit)? = null,
    tabletModel: ProductViewModel? = null,
) {
    val ctx = LocalContext.current
    val app = ctx.chudoApp
    val vm: ProductViewModel = tabletModel ?: viewModel(factory = viewModelFactory {
        initializer { ProductViewModel(app.repository, app.prefs, createSavedStateHandle()) }
    })
    val s by vm.state.collectAsStateWithLifecycle()
    val region by app.prefs.region.collectAsStateWithLifecycle()
    var showRegions by rememberSaveable { mutableStateOf(false) }
    var printSheet by rememberSaveable { mutableStateOf(openPrint) }
    var inlinePrinting by remember { mutableStateOf(false) }
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

    fun printTag(product: ProductDetails) = TagData.merge(
        TagData.Site(product.name, product.article, product.price?.finalPrice, product.price?.regularPrice),
        net?.let { TagData.OneC(it.barcode, it.item.name, it.item.code, it.item.article) },
        vm.highlightBarcode ?: own?.barcode,
    )
    fun printed(product: ProductDetails, message: String) {
        app.prefs.addActivity(employee, ua.chudo.scanner.data.ActivityKind.PRINT, message, product.name)
        scope.launch { snackbar.showSnackbar(message) }
    }

    Box(Modifier.fillMaxSize().background(Mine.colors.bg)) {
        Column(Modifier.fillMaxSize()) {
            TopBar(onBack, onShare = { ctx.shareText(listOfNotNull(p?.name, url).joinToString("\n")) }, onScan = onScan)
            PullToRefreshBox(isRefreshing = s.refreshing, onRefresh = { vm.load(refresh = true) }, modifier = Modifier.weight(1f)) {
                when {
                    s.loading -> ProductSkeleton()
                    p == null -> s.error?.let { ErrorState(it, url, { vm.load() }, Modifier.align(Alignment.Center)) }
                    else -> ProductPage(
                        p, s.shops, employee.shop, own, region.name, vm.barcode, s.barcodeToRemember,
                        onCity = { showRegions = true },
                        onRetryShops = vm::loadShops, onMap = ctx::openMap,
                        onRemember = vm::rememberBarcode, onForget = vm::dismissRemember, net = net, highlights = highlights,
                        onPreviousProduct = onPreviousProduct, onNextProduct = onNextProduct, tablet = tabletModel != null,
                        printBusy = inlinePrinting,
                        printPanel = if (tabletModel != null) ({
                            PrintSheet(
                                listOf(printTag(p)), onDismiss = {}, inline = true,
                                onBusy = { inlinePrinting = it }, onDone = { printed(p, it) },
                            )
                        }) else null,
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
        PrintSheet(
            tag = printTag(product), onDismiss = { printSheet = false }, onDone = { printed(product, it) },
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

@Composable
internal fun TabletProduct(
    employee: Employee, key: String, barcode: String?, scanned: String?,
    onClose: () -> Unit, onScan: () -> Unit,
) {
    val app = LocalContext.current.chudoApp
    val owner = remember(employee.shopId, key, barcode, scanned) {
        object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
    }
    DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
    val model: ProductViewModel = viewModel(viewModelStoreOwner = owner, factory = viewModelFactory {
        initializer {
            ProductViewModel(app.repository, app.prefs, key, barcode, scanned)
        }
    })
    ProductScreen(employee, onBack = onClose, onScan = onScan, tabletModel = model)
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
    tablet: Boolean = false,
    printPanel: (@Composable () -> Unit)? = null,
    printBusy: Boolean = false,
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
    val m = Mine.colors
    val new = highlights.any { it.kind == HighlightKind.NEW.key }
    val details: @Composable (Modifier) -> Unit = { modifier ->
        Column(modifier.background(m.card).padding(start = H, top = 14.dp, end = H, bottom = 16.dp)) {
            p.categories.lastOrNull()?.let {
                Text(it, Modifier.padding(bottom = 6.dp), fontSize = 12.sp, color = m.textTertiary)
            }
            Text(p.name, fontSize = 18.sp, lineHeight = 23.4.sp, fontWeight = FontWeight.SemiBold, color = m.text)
            ProductCodes(p.article, barcode ?: net?.barcode ?: own?.barcode, net?.item?.code)
            PriceBox(p)
            highlights.firstOrNull { it.kind == HighlightKind.PROMO.key }?.let { promo ->
                promo.endsOn?.let { end ->
                    Text("Акція діє до $end включно", Modifier.padding(top = 4.dp), fontSize = 13.sp, color = m.textSecondary)
                }
                val promoPrice = promo.promoPrice
                if (promoPrice != null && promoPrice != p.price?.finalPrice) {
                    Text("Акція · ${productPrice(promoPrice)}", Modifier.padding(top = 4.dp), fontSize = 13.sp, color = m.onDanger)
                }
            }
        }
    }
    LazyColumn(
        Modifier.fillMaxSize().background(m.bg),
        userScrollEnabled = !printBusy,
        contentPadding = if (tablet) PaddingValues(horizontal = 24.dp, vertical = 12.dp) else PaddingValues(bottom = 4.dp),
    ) {
        if (tablet) item {
            val shape = RoundedCornerShape(8.dp)
            BoxWithConstraints(Modifier.fillMaxWidth().clip(shape).border(1.dp, m.line, shape).background(m.card)) {
                if (maxWidth >= 490.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(150.dp).background(m.surface)) {
                            Gallery(p.images, p.price?.discountPercent ?: 0.0, new, compact = true)
                        }
                        details(Modifier.weight(1f))
                    }
                } else {
                    Column {
                        Gallery(p.images, p.price?.discountPercent ?: 0.0, new, compact = true)
                        details(Modifier.fillMaxWidth())
                    }
                }
            }
        } else {
            item { Gallery(p.images, p.price?.discountPercent ?: 0.0, new) }
            item {
                details(Modifier.fillMaxWidth().then(swipe))
                HorizontalDivider(color = m.line)
            }
        }
        if (printPanel != null) {
            item {
                val availability: @Composable () -> Unit = {
                    when {
                        net?.own != null -> OwnStock(net.own)
                        own != null -> OwnStock(own.qty)
                        else -> Availability(p.stock)
                    }
                    net?.let { NetworkStores(it, myShop) }
                }
                BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 16.dp)) {
                    if (maxWidth >= 608.dp) {
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Column(Modifier.weight(1f)) { availability() }
                            Box(Modifier.width(300.dp)) { printPanel() }
                        }
                    } else Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Column { availability() }
                        printPanel()
                    }
                }
            }
        } else {
            net?.own?.let { qty -> item { OwnStock(qty) } }
                ?: own?.let { stock -> item { OwnStock(stock.qty) } }
                ?: item { Availability(p.stock) }
            net?.let { av -> item { NetworkStores(av, myShop) } }
        }
        toRemember?.let { bc -> item { RememberCard(bc, onRemember, onForget) } }
        item {
            Row(
                Modifier.fillMaxWidth().padding(start = H, end = H, top = 20.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Наявність за даними сайту", Modifier.weight(1f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = m.textSecondary)
                CityField(city, onCity)
            }
        }
        shopsSection(shops, myShop, own.takeUnless { net?.own != null }, onRetry = onRetryShops, onMap = onMap)
        if (p.description.isNotBlank()) item { Description(p.description) }
        item {
            val update = updatedText(net?.updatedAt)
            Text(
                listOfNotNull("Ціна й опис — із сайту мережі.", update?.let { "$it." }).joinToString(" "),
                Modifier.padding(start = H, end = H, top = 12.dp, bottom = 16.dp),
                fontSize = 12.sp, color = m.textTertiary,
            )
        }
    }
}

private val H = 16.dp

@Composable
private fun TopBar(onBack: () -> Unit, onShare: () -> Unit, onScan: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Mine.colors.bg).statusBarsPadding().height(52.dp).padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProductIconButton(R.drawable.ic_redesign_chevron_left, "Назад", onBack, 24)
        Spacer(Modifier.weight(1f))
        ProductIconButton(R.drawable.ic_redesign_external_link, "Поділитися посиланням", onShare)
        ProductIconButton(R.drawable.ic_redesign_scan_barcode, "Сканувати", onScan)
    }
}

@Composable
private fun ProductIconButton(@DrawableRes icon: Int, label: String, onClick: () -> Unit, iconSize: Int = 20) {
    IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
        Icon(painterResource(icon), label, Modifier.size(iconSize.dp), tint = Mine.colors.textSecondary)
    }
}

@Composable
private fun Gallery(images: List<String>, discount: Double, new: Boolean, compact: Boolean = false) {
    val m = Mine.colors
    Box(Modifier.fillMaxWidth().height(if (compact) 150.dp else 250.dp).background(if (compact) m.surface else m.card)) {
        if (images.isEmpty()) {
            Icon(painterResource(R.drawable.ic_redesign_image), null, Modifier.align(Alignment.Center).size(28.dp), tint = m.textTertiary)
        } else {
            val pager = rememberPagerState { images.size }
            var fullScreenPage by rememberSaveable { mutableStateOf<Int?>(null) }
            fullScreenPage?.takeIf { it in images.indices }?.let { index ->
                PhotoViewer(images, index) { fullScreenPage = null }
            }
            HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
                Box(Modifier.fillMaxSize().clickable(onClickLabel = "Відкрити фото") { fullScreenPage = page }, contentAlignment = Alignment.Center) {
                    ProductImage(images[page], Modifier.size(if (compact) 120.dp else 230.dp))
                }
            }
            if (images.size > 1) {
                Row(
                    Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    repeat(images.size) { i ->
                        Box(Modifier.size(6.dp).background(if (i == pager.currentPage) m.text else m.outline, CircleShape))
                    }
                }
            }
        }
        Row(Modifier.padding(horizontal = 12.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (discount >= 1) ProductBadge("−${discount.toInt()}%", m.danger, m.onDanger)
            if (new) ProductBadge("Новинка", m.infoTint, m.info)
        }
    }
    if (!compact) HorizontalDivider(color = m.line)
}

@Composable
private fun ProductBadge(text: String, background: Color, color: Color) {
    Box(
        Modifier.height(22.dp).background(background, RoundedCornerShape(4.dp)).padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = color) }
}

@Composable
private fun Availability(stock: StockStatus) {
    val m = Mine.colors
    val label = when (stock) {
        StockStatus.IN_STOCK -> "В наявності"
        StockStatus.OUT_OF_STOCK -> "Немає в наявності"
        StockStatus.UNKNOWN -> "Наявність уточнюйте"
    }
    Column(Modifier.fillMaxWidth().background(m.card).padding(horizontal = H, vertical = 14.dp)) {
        Text("За даними сайту", fontSize = 13.sp, color = m.textTertiary)
        Text(label, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = m.text)
    }
    HorizontalDivider(color = m.line)
}

private fun productPrice(price: Double): String =
    NumberFormat.getCurrencyInstance(Locale.forLanguageTag("uk-UA")).apply {
        minimumFractionDigits = if ((price * 100).roundToLong() % 100 == 0L) 0 else 2
    }.format(price)

@Composable
private fun PriceBox(p: ProductDetails) {
    val m = Mine.colors
    val price = p.price
    if (price == null) {
        Text("Ціна недоступна", Modifier.padding(top = 14.dp), fontSize = 16.sp, color = m.textSecondary)
        return
    }
    Row(
        Modifier.fillMaxWidth().padding(top = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(productPrice(price.finalPrice), Modifier.alignByBaseline(), style = PriceStyle, color = m.text)
        if (price.regularPrice > price.finalPrice) {
            Text(productPrice(price.regularPrice), Modifier.alignByBaseline(), fontSize = 16.sp, color = m.textTertiary, textDecoration = TextDecoration.LineThrough)
            Text("−${productPrice(price.regularPrice - price.finalPrice)}", Modifier.alignByBaseline(), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = m.onDanger)
        }
    }
}

@Composable
internal fun ProductActions(onPrint: () -> Unit, onCart: (() -> Unit)?) {
    val m = Mine.colors
    Surface(color = m.card, contentColor = m.text) {
        Column(Modifier.navigationBarsPadding()) {
            HorizontalDivider(color = m.line)
            Row(Modifier.fillMaxWidth().padding(start = H, end = H, top = 10.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(
                    onClick = { onCart?.invoke() }, enabled = onCart != null,
                    modifier = Modifier.size(width = 48.dp, height = 44.dp), shape = RoundedCornerShape(6.dp),
                    border = BorderStroke(1.dp, m.line), color = m.card, contentColor = m.text,
                ) {
                    Box(contentAlignment = Alignment.Center) { Icon(painterResource(R.drawable.ic_redesign_shopping_cart), "Додати до кошика", Modifier.size(20.dp), tint = if (onCart != null) m.text else m.textTertiary) }
                }
                Surface(onClick = onPrint, modifier = Modifier.weight(1f).height(44.dp), shape = RoundedCornerShape(6.dp), color = m.accent, contentColor = m.onAccent) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(R.drawable.ic_redesign_printer), null, Modifier.size(20.dp))
                        Text("Друкувати цінник", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}

@Composable
private fun OwnStock(quantity: Double) {
    val m = Mine.colors
    Row(
        Modifier.fillMaxWidth().background(m.card).padding(horizontal = H, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(qtyText(quantity), Modifier.alignByBaseline(), style = PriceStyle.copy(fontSize = 34.sp, lineHeight = 34.sp), color = if (quantity > 0) m.text else m.textTertiary)
            Text("шт", Modifier.alignByBaseline(), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = m.textSecondary)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("У вашому магазині", fontSize = 13.sp, color = m.textTertiary)
            Text(
                when {
                    quantity <= 0 -> "Немає — запропонуйте інший магазин"
                    quantity <= 2 -> "Мало — перевірте на полиці"
                    else -> "Є на складі магазину"
                },
                fontSize = 15.sp, fontWeight = FontWeight.Medium, color = m.text,
            )
        }
    }
    HorizontalDivider(color = m.line)
}

@Composable
private fun NetworkStores(net: NetAvailability, myShop: String) {
    val m = Mine.colors
    val total = qtyText(net.item.q.values.sum())
    var all by rememberSaveable(net.barcode) { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(start = H, end = H, top = 20.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(net.cityName?.let { shopsOfCity(it) } ?: "Залишки мережі", Modifier.weight(1f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = m.textSecondary)
        Text(
            "Усі $total шт у мережі",
            Modifier.then(if (net.item.q.isNotEmpty()) Modifier.clickable { all = true } else Modifier),
            fontSize = 14.sp, fontWeight = FontWeight.Medium, color = m.link,
        )
    }
    if (all) ua.chudo.scanner.ui.stock.NetworkAvailabilityDialog(
        net.item, net.ownCode, myShop, net.item.name.ifBlank { net.barcode }, net.updatedAt,
    ) { all = false }
    val visible = net.city.take(3)
    if (visible.isNotEmpty()) HorizontalDivider(color = m.line)
    visible.forEach { shop ->
        Row(
            Modifier.fillMaxWidth().height(48.dp).background(m.card).padding(horizontal = H),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(shop.name, Modifier.weight(1f), fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, color = m.text)
            Text(
                if (shop.qty > 0) "${qtyText(shop.qty)} шт" else "немає",
                fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                color = when {
                    shop.qty <= 0 -> m.textTertiary
                    shop.qty <= 2 -> m.warningText
                    else -> m.success
                },
            )
        }
        HorizontalDivider(color = m.line)
    }
}

@Composable
private fun PhotoViewer(images: List<String>, index: Int, onClose: () -> Unit) {
    val pager = rememberPagerState(initialPage = index) { images.size }
    val scope = rememberCoroutineScope()
    var zoom by rememberSaveable(pager.currentPage) { mutableStateOf(false) }
    val background = Color(0xFF0C0D0F)
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(background).statusBarsPadding().navigationBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GalleryButton(R.drawable.ic_redesign_x, "Закрити фото", onClose)
                Text("${pager.currentPage + 1} з ${images.size}", color = Color.White, fontSize = 14.sp)
                GalleryButton(R.drawable.ic_redesign_zoom_in, if (zoom) "Зменшити" else "Збільшити", { zoom = !zoom }, zoom)
            }
            HorizontalPager(pager, Modifier.fillMaxWidth().weight(1f)) { page ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    ProductImage(images[page], Modifier.size(if (zoom) 380.dp else 300.dp))
                }
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 34.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            ) {
                images.forEachIndexed { page, url ->
                    val shape = RoundedCornerShape(6.dp)
                    Box(
                        Modifier.size(56.dp).clip(shape).clickable(onClickLabel = "Фото ${page + 1}") {
                            scope.launch { pager.animateScrollToPage(page) }
                        }.border(2.dp, if (page == pager.currentPage) Color.White else Color.Transparent, shape),
                    ) { ProductImage(url, Modifier.fillMaxSize()) }
                }
            }
        }
    }
}

@Composable
private fun GalleryButton(@DrawableRes icon: Int, label: String, onClick: () -> Unit, active: Boolean = false) {
    Surface(
        onClick = onClick, modifier = Modifier.size(44.dp), shape = CircleShape,
        color = if (active) Color.White else Color(0x9914161A),
        contentColor = if (active) Color(0xFF111111) else Color.White,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.14f)),
    ) { Box(contentAlignment = Alignment.Center) { Icon(painterResource(icon), label, Modifier.size(20.dp)) } }
}

@Composable
private fun CityField(city: String, onClick: () -> Unit) {
    val m = Mine.colors
    Row(
        Modifier.clickable(onClickLabel = "Змінити місто", onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(city, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = m.link)
        Icon(painterResource(R.drawable.ic_redesign_chevron_down), null, Modifier.size(16.dp), tint = m.link)
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
    HorizontalDivider(color = m.line)
    Row(
        Modifier.fillMaxWidth().background(m.card).clickable(onClick = onClick).padding(horizontal = H, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(shop.address, fontSize = 15.sp, color = m.text)
            if (isMine) Text("Ваш магазин", fontSize = 13.sp, color = m.textTertiary)
        }
        val available = mineQty?.let { it > 0 } ?: shop.available
        Text(
            mineQty?.let { "$it шт" } ?: if (shop.available) "Є" else "Немає",
            fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (available) m.success else m.textTertiary,
        )
    }
}

@Composable
private fun Description(text: String) {
    val m = Mine.colors
    Text(
        "Опис", Modifier.padding(start = H, top = 20.dp, bottom = 8.dp),
        fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = m.textSecondary,
    )
    Text(
        text, Modifier.fillMaxWidth().background(m.card).padding(horizontal = H, vertical = 14.dp),
        fontSize = 15.sp, lineHeight = 23.25.sp, color = m.textSecondary,
    )
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
        Modifier.padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        values.forEach { (label, value) ->
            Text(
                if (label == "Штрихкод") value else "$label $value",
                Modifier.clickable(onClickLabel = "Скопіювати $label") {
                    scope.launch { clipboard.setClipEntry(ClipData.newPlainText(label, value).toClipEntry()) }
                },
                style = if (label == "Штрихкод") CodeStyle.copy(fontSize = 12.sp) else androidx.compose.material3.MaterialTheme.typography.bodySmall,
                color = Mine.colors.textTertiary,
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
