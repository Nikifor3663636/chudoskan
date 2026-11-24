package ua.chudo.scanner.ui.product

import android.content.ClipData
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import ua.chudo.scanner.data.Employee
import ua.chudo.scanner.data.NetAvailability
import ua.chudo.scanner.data.StockItem
import ua.chudo.scanner.ui.stock.NetworkStockCard
import ua.chudo.scanner.print.PriceFormat
import ua.chudo.scanner.print.TagData
import ua.chudo.scanner.ui.common.BarcodeScanIcon
import ua.chudo.scanner.ui.common.ErrorState
import ua.chudo.scanner.ui.common.MineButton
import ua.chudo.scanner.ui.common.MineTone
import ua.chudo.scanner.ui.common.ProductImage
import ua.chudo.scanner.ui.common.MinePrimaryButton
import ua.chudo.scanner.terminal.CartHub
import ua.chudo.scanner.terminal.Money
import ua.chudo.scanner.terminal.cartItemOf
import androidx.compose.material.icons.rounded.AddShoppingCart
import ua.chudo.scanner.ui.common.StockBox
import ua.chudo.scanner.ui.theme.PriceStyle
import androidx.compose.material.icons.rounded.Print
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

/**
 * Картка товару в точності як сторінка товару на сайті «Чудо Острів» (мобільна версія):
 * хлібні крихти, назва, артикул, фото зі знижкою, «В наявності», плашка ціни з кнопкою,
 * «Наявність в магазинах» з містом, «Опис», «Характеристики». Замість «Купити» — «Друкувати цінник».
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductScreen(employee: Employee, onBack: () -> Unit, onScan: () -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.chudoApp
    val vm: ProductViewModel = viewModel(factory = viewModelFactory {
        initializer { ProductViewModel(app.repository, app.prefs, createSavedStateHandle()) }
    })
    val s by vm.state.collectAsStateWithLifecycle()
    val region by app.prefs.region.collectAsStateWithLifecycle()
    var showRegions by rememberSaveable { mutableStateOf(false) }
    var printSheet by rememberSaveable { mutableStateOf(false) }
    var own by remember { mutableStateOf<StockItem?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val p = s.product
    val url = productUrl(Lang.UA, p?.urlKey ?: vm.urlKey, p?.urlSuffix ?: ".html")

    var net by remember { mutableStateOf<NetAvailability?>(null) }
    // Залишки мережі: ОДНЕ читання stock/{штрихкод} (без штрихкоду — за артикулом).
    // Немає даних — як раніше: залишок свого магазину з shops/{shopId}/stock у списку наявності сайту.
    LaunchedEffect(p?.id) {
        if (p == null) return@LaunchedEffect
        net = runCatching {
            val bc = vm.barcode
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
                        onPrint = { printSheet = true }, onScan = onScan, onCity = { showRegions = true },
                        onRetryShops = vm::loadShops, onMap = ctx::openMap, onSite = { ctx.openUrl(url) },
                        onRemember = vm::rememberBarcode, onForget = vm::dismissRemember, net = net,
                        onAddToCart = {
                            val msg = when (val r = app.cart.add(cartItemOf(
                                p.name, net?.item?.article?.ifBlank { null } ?: p.article, p.price?.finalPrice?.let(Money::fromUah),
                                vm.barcode ?: net?.barcode ?: own?.barcode, net?.item?.code, vm.urlKey,
                            ))) {
                                is CartHub.AddResult.Added -> "У кошику: ${r.qty} шт цього товару, усього ${r.count} шт" +
                                    (if (p.price == null) ". Ціну введи в «Терміналі»" else "")
                                CartHub.AddResult.Locked -> "Кошик зайнятий: заверши продаж у «Терміналі»"
                                is CartHub.AddResult.Failed -> r.message
                            }
                            scope.launch { snackbar.showSnackbar(msg) }
                        },
                    )
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp))
    }

    val product = p
    if (printSheet && product != null) {
        val price = product.price
        PrintSheet(
            // Штрихкод, код і артикул — з 1С (stock/{штрихкод}), назва й ціни — з сайту.
            tag = TagData.merge(
                TagData.Site(product.name, product.article, price?.finalPrice, price?.regularPrice),
                net?.let { TagData.OneC(it.barcode, it.item.name, it.item.code, it.item.article) },
                vm.barcode ?: own?.barcode,
            ),
            onDismiss = { printSheet = false },
            onDone = { msg -> scope.launch { snackbar.showSnackbar(msg) } },
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
    onPrint: () -> Unit = {},
    onScan: () -> Unit = {},
    onCity: () -> Unit = {},
    onRetryShops: () -> Unit = {},
    onMap: (String) -> Unit = {},
    onSite: () -> Unit = {},
    onRemember: () -> Unit = {},
    onForget: () -> Unit = {},
    /** «Додати в кошик» «Термінала»; `null` — кнопки немає. */
    onAddToCart: (() -> Unit)? = null,
    /** Залишки мережі з stock/{штрихкод}; `null` — даних немає, лише наявність із сайту. */
    net: NetAvailability? = null,
) {
    Column(Modifier.fillMaxSize().background(pageBg())) {
        // Ціна й залишок — у першій картці, без прокрутки (дизайн-система → 06 Товар).
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
            item { SummaryCard(p, net, own, Modifier.padding(horizontal = H).padding(top = 4.dp)) }
            toRemember?.let { bc -> item { RememberCard(bc, onRemember, onForget) } }
            item { SectionTitle("Наявність в місті $city") }
            item { CityField(city, onCity) }
            shopsSection(shops, myShop, own, onRetry = onRetryShops, onMap = onMap)
            net?.takeIf { it.city.isNotEmpty() }?.let { av ->
                item { SectionDivider() }
                item { SectionTitle("Залишки за даними 1С") }
                item { NetworkStockCard(av, Modifier.padding(horizontal = H)) }
            }
            if (p.images.size > 1) {
                item { SectionDivider() }
                item { SectionTitle("Фото") }
                item { Gallery(p.images, p.price?.discountPercent ?: 0.0) }
            }
            if (p.description.isNotBlank()) {
                item { SectionDivider() }
                item { Description(p.description, onMore = onSite) }
            }
            item { SectionDivider() }
            item { Characteristics(p, barcode ?: own?.barcode) }
            item { Breadcrumbs(p.categories) }
        }
        // Головна дія — внизу, у зоні великого пальця.
        Column(Modifier.fillMaxWidth().background(Mine.colors.surface).navigationBarsPadding().padding(16.dp)) {
            MinePrimaryButton("Друкувати цінник", onPrint, icon = Icons.Rounded.Print)
            onAddToCart?.let {
                MineButton("Додати в кошик", it, Modifier.padding(top = 8.dp).fillMaxWidth(), icon = Icons.Rounded.AddShoppingCart)
            }
        }
    }
}

private val H = 16.dp

@Composable
private fun pageBg(): Color = Mine.colors.bg

/** «1 магазин», «3 магазини», «5 магазинів». */
internal fun shopsWord(n: Int): String {
    val d = n % 10
    val dd = n % 100
    return "$n " + when {
        d == 1 && dd != 11 -> "магазин"
        d in 2..4 && dd !in 12..14 -> "магазини"
        else -> "магазинів"
    }
}

/** Картка товару: фото 88, назва, артикул, ціна 40 sp (зі знижкою — стара закреслена), блок залишку. */
@Composable
private fun SummaryCard(p: ProductDetails, net: NetAvailability?, own: StockItem?, modifier: Modifier = Modifier) {
    val m = Mine.colors
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = m.card, contentColor = m.text) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ProductImage(p.images.firstOrNull(), Modifier.size(88.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(p.name, fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    p.article?.let { art ->
                        Text(
                            "Арт. $art", Modifier.clickable { scope.launch { clipboard.setClipEntry(ClipData.newPlainText("Артикул", art).toClipEntry()) } },
                            fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, color = m.textSecondary,
                        )
                    }
                }
            }
            val price = p.price
            if (price == null) {
                Text("Ціна недоступна", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = m.textSecondary)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("${PriceFormat.tag(price.finalPrice).first} ₴", style = PriceStyle, color = m.text)
                    if (price.regularPrice > price.finalPrice + 0.001) {
                        Text(
                            "${PriceFormat.tag(price.regularPrice).first} ₴", fontSize = 16.sp, color = m.textSecondary,
                            textDecoration = TextDecoration.LineThrough,
                        )
                        if (price.discountPercent >= 1) {
                            Text(
                                "−${price.discountPercent.toInt()}%",
                                Modifier.clip(RoundedCornerShape(8.dp)).background(m.danger).padding(horizontal = 8.dp, vertical = 2.dp),
                                fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = m.onDanger,
                            )
                        }
                    }
                }
            }
            val ownQty = net?.own ?: own?.qty
            if (net != null || own != null) {
                StockBox(
                    own = ownQty,
                    cityLabel = net?.cityName?.let { "$it, ${shopsWord(net.city.size)}" },
                    city = net?.takeIf { it.cityName != null }?.city?.sumOf { it.qty },
                )
            } else {
                Availability(p.stock)
            }
        }
    }
}

@Composable
private fun TopBar(onBack: () -> Unit, onShare: () -> Unit, onSite: () -> Unit, onScan: () -> Unit) {
    val m = Mine.colors
    Row(Modifier.fillMaxWidth().statusBarsPadding().height(64.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        RoundButton(Icons.AutoMirrored.Rounded.ArrowBack, "Назад", onBack)
        Text("Товар", Modifier.weight(1f).padding(start = 4.dp), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = m.text)
        RoundButton(BarcodeScanIcon, "Сканувати ще", onScan)
        RoundButton(Icons.Rounded.Share, "Поділитися", onShare)
        RoundButton(Icons.Rounded.OpenInBrowser, "На сайті", onSite)
    }
}

@Composable
private fun RoundButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    val m = Mine.colors
    Surface(onClick = onClick, modifier = Modifier.size(48.dp), shape = CircleShape, color = Color.Transparent, contentColor = m.text) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, label, Modifier.size(24.dp)) }
    }
}

/** «Головна • Для малюків • Дитячі іграшки» — серым, одной строкой с прокруткой. */
@Composable
private fun Breadcrumbs(categories: List<String>) {
    val m = Mine.colors
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = H, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        (listOf("Головна") + categories).forEachIndexed { i, c ->
            if (i > 0) Box(Modifier.padding(horizontal = 8.dp).size(4.dp).clip(CircleShape).background(m.outline))
            Text(c, fontSize = 14.sp, color = m.textSecondary, maxLines = 1)
        }
    }
}

/** Фото на белом, слева сверху красная плашка скидки; точки страниц — оранжевая активная. */
@Composable
private fun Gallery(images: List<String>, discount: Double) {
    val m = Mine.colors
    Column(Modifier.padding(top = 16.dp)) {
        Box(Modifier.padding(horizontal = H).clip(RoundedCornerShape(16.dp))) {
            if (images.isEmpty()) {
                ProductImage(null, Modifier.fillMaxWidth().aspectRatio(1.3f))
            } else {
                val pager = rememberPagerState { images.size }
                Column {
                    HorizontalPager(pager, pageSpacing = 12.dp) { page ->
                        ProductImage(images[page], Modifier.fillMaxWidth().aspectRatio(1.3f))
                    }
                    if (images.size > 1) {
                        Row(Modifier.fillMaxWidth().background(m.card).padding(vertical = 12.dp), horizontalArrangement = Arrangement.Center) {
                            repeat(images.size) { i ->
                                Box(
                                    Modifier.padding(horizontal = 4.dp).size(9.dp).clip(CircleShape)
                                        .background(if (i == pager.currentPage) m.accent else m.line),
                                )
                            }
                        }
                    }
                }
            }
            if (discount >= 1) {
                Text(
                    "-${discount.toInt()}%",
                    Modifier.padding(12.dp).clip(RoundedCornerShape(999.dp)).background(m.danger).padding(horizontal = 10.dp, vertical = 3.dp),
                    color = m.onDanger, fontSize = 15.sp, fontWeight = FontWeight.Bold,
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
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(if (ok) Icons.Rounded.CheckCircle else Icons.Rounded.Cancel, null, Modifier.size(24.dp), tint = if (ok) m.stockIn else m.stockOut)
        Spacer(Modifier.width(8.dp))
        Text(text, fontSize = 16.sp, color = m.text)
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
                Text("Місто", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = m.accentText)
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
            Modifier.padding(top = 1.dp).size(22.dp), tint = if (shop.available) m.stockIn else m.stockOut,
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(shop.address, fontSize = 16.sp, lineHeight = 22.sp, color = if (shop.available) m.text else m.textSecondary)
            if (isMine) {
                Text(
                    if (mineQty != null) "Твій магазин, за даними 1С: $mineQty шт" else "Твій магазин",
                    fontSize = 14.sp, fontWeight = FontWeight.Bold, color = m.accentText,
                )
            }
        }
    }
}

/** «Опис» + «Дізнатись більше» (открывает страницу на сайте). */
@Composable
private fun Description(text: String, onMore: () -> Unit) {
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
                fontSize = 16.sp, fontWeight = FontWeight.Bold, color = m.accentText,
            )
            Text("Дізнатись більше", Modifier.clickable(onClick = onMore), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = m.accentText)
        }
    }
}

/** «Характеристики»: подпись слева серым, значение справа жирным — как таблица на сайте. */
@Composable
private fun Characteristics(p: ProductDetails, barcode: String?) {
    val m = Mine.colors
    val rows = listOfNotNull(
        "Найменування товару" to p.name,
        p.article?.let { "Артикул" to it },
        barcode?.takeIf { it.isNotBlank() }?.let { "Штрихкод" to it },
        p.categories.lastOrNull()?.let { "Категорія" to it },
    )
    Column {
        SectionTitle("Характеристики")
        rows.forEach { (k, v) ->
            Row(Modifier.padding(horizontal = H, vertical = 6.dp)) {
                Text(k, Modifier.weight(1f), fontSize = 16.sp, color = m.textSecondary)
                Spacer(Modifier.width(16.dp))
                Text(v, Modifier.weight(1.2f), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = m.text, textAlign = TextAlign.End)
            }
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
