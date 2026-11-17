package ua.chudo.scanner.ui.product

import android.content.ClipData
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
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
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.text.font.FontWeight
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
import ua.chudo.scanner.data.StockItem
import ua.chudo.scanner.print.PriceTag
import ua.chudo.scanner.print.formatPrice
import ua.chudo.scanner.ui.common.ErrorState
import ua.chudo.scanner.ui.common.MineButton
import ua.chudo.scanner.ui.common.MinePrimaryButton
import ua.chudo.scanner.ui.common.MineTone
import ua.chudo.scanner.ui.common.PriceFont
import ua.chudo.scanner.ui.common.ProductImage
import ua.chudo.scanner.ui.common.RegionPickerSheet
import ua.chudo.scanner.ui.common.ShelfTagPrice
import ua.chudo.scanner.ui.common.Shimmer
import ua.chudo.scanner.ui.common.StatusDot
import ua.chudo.scanner.ui.common.openMap
import ua.chudo.scanner.ui.common.openUrl
import ua.chudo.scanner.ui.common.shareText
import ua.chudo.scanner.ui.print.PrintSheet
import ua.chudo.scanner.ui.theme.Mine
import uniffi.chudo_core.Lang
import uniffi.chudo_core.ProductDetails
import uniffi.chudo_core.ShopAvailability
import uniffi.chudo_core.productUrl

/** Карточка товара: цена как ценник, наличие в своём магазине и в городе, «Друкувати цінник» внизу. */
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
    var ownLoaded by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val p = s.product
    val url = productUrl(Lang.UA, p?.urlKey ?: vm.urlKey, p?.urlSuffix ?: ".html")

    // Остаток своего магазина из выгрузки 1С.
    LaunchedEffect(p?.id) {
        if (p == null) return@LaunchedEffect
        own = runCatching { app.shopStock.find(employee.shopId, p.article, listOfNotNull(vm.barcode)) }.getOrNull()
        ownLoaded = true
    }

    Box(Modifier.fillMaxSize().background(Mine.colors.bg)) {
        Column(Modifier.fillMaxSize()) {
            TopBar(onBack, onShare = { ctx.shareText(listOfNotNull(p?.name, url).joinToString("\n")) }, onSite = { ctx.openUrl(url) })
            PullToRefreshBox(isRefreshing = s.refreshing, onRefresh = { vm.load(refresh = true) }, modifier = Modifier.weight(1f)) {
                when {
                    s.loading -> ProductSkeleton()
                    p == null -> s.error?.let { ErrorState(it, url, { vm.load() }, Modifier.align(Alignment.Center)) }
                    else -> LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
                        item { Gallery(p.images) }
                        item { Header(p, vm.barcode) }
                        p.price?.let { price ->
                            item {
                                ShelfTagPrice(
                                    price.finalPrice, price.regularPrice.takeIf { it > price.finalPrice + 0.001 },
                                    Modifier.padding(top = 18.dp),
                                )
                            }
                        }
                        s.barcodeToRemember?.let { bc -> item { RememberCard(bc, vm::rememberBarcode, vm::dismissRemember) } }
                        item { OwnShop(employee.shop, own, ownLoaded, s.shops) }
                        item {
                            Row(Modifier.padding(top = 24.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("Інші магазини", Modifier.weight(1f), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Mine.colors.text)
                                TextButton(onClick = { showRegions = true }) {
                                    Text(region.name, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Mine.colors.link)
                                    Icon(Icons.Rounded.ExpandMore, null, Modifier.size(18.dp), tint = Mine.colors.link)
                                }
                            }
                        }
                        shopsSection(s.shops.withoutShop(employee.shop), onRetry = vm::loadShops, onMap = ctx::openMap)
                        if (p.description.isNotBlank()) item { Description(p.description) }
                    }
                }
            }
            if (p != null) ActionBar(onPrint = { printSheet = true }, onScan = onScan)
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp))
    }

    val product = p
    if (printSheet && product != null) {
        val price = product.price
        PrintSheet(
            tag = PriceTag(
                name = product.name,
                price = price?.finalPrice?.let(::formatPrice).orEmpty(),
                oldPrice = price?.takeIf { it.regularPrice > it.finalPrice + 0.001 }?.regularPrice?.let(::formatPrice).orEmpty(),
                article = product.article.orEmpty(),
                barcode = vm.barcode ?: own?.barcode.orEmpty(),
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

/** Свой магазин показываем отдельным блоком — из списка «Інші магазини» его убираем. */
private fun ShopsState.withoutShop(shop: String): ShopsState =
    if (this is ShopsState.Loaded) ShopsState.Loaded(shops.filterNot { it.address.equals(shop, ignoreCase = true) }) else this

@Composable
private fun TopBar(onBack: () -> Unit, onShare: () -> Unit, onSite: () -> Unit) {
    val m = Mine.colors
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        RoundButton(Icons.AutoMirrored.Rounded.ArrowBack, "Назад", onBack)
        Spacer(Modifier.weight(1f))
        RoundButton(Icons.Rounded.Share, "Поділитися", onShare)
        Spacer(Modifier.width(8.dp))
        Surface(onClick = onSite, shape = RoundedCornerShape(999.dp), color = m.card, contentColor = m.text, border = BorderStroke(1.dp, m.line)) {
            Row(Modifier.height(44.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("На сайті", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                Icon(Icons.Rounded.OpenInBrowser, null, Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun RoundButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    val m = Mine.colors
    Surface(onClick = onClick, modifier = Modifier.size(44.dp), shape = CircleShape, color = m.card, contentColor = m.text, border = BorderStroke(1.dp, m.line)) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, label, Modifier.size(22.dp)) }
    }
}

/** Внизу: одна главная кнопка и «Сканувати ще» — под большой палец. */
@Composable
private fun ActionBar(onPrint: () -> Unit, onScan: () -> Unit) {
    val m = Mine.colors
    Column(Modifier.fillMaxWidth().background(m.bg).navigationBarsPadding()) {
        HorizontalDivider(color = m.line)
        Row(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MinePrimaryButton("Друкувати цінник", onPrint, Modifier.weight(1f))
            MineButton("Сканувати ще", onScan, Modifier.weight(1f).height(56.dp))
        }
    }
}

@Composable
private fun Gallery(images: List<String>) {
    val m = Mine.colors
    if (images.isEmpty()) {
        ProductImage(null, Modifier.fillMaxWidth().aspectRatio(1.6f).clip(RoundedCornerShape(24.dp)))
        return
    }
    val pager = rememberPagerState { images.size }
    Box(Modifier.clip(RoundedCornerShape(24.dp))) {
        HorizontalPager(pager, pageSpacing = 12.dp) { page ->
            ProductImage(images[page], Modifier.fillMaxWidth().aspectRatio(1.25f))
        }
        if (images.size > 1) {
            Row(
                Modifier.align(Alignment.BottomCenter).padding(10.dp).clip(CircleShape).background(m.bg.copy(alpha = 0.55f))
                    .padding(horizontal = 8.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                repeat(images.size) { i ->
                    val active = i == pager.currentPage
                    Box(Modifier.size(if (active) 8.dp else 6.dp).clip(CircleShape).background(if (active) m.accent else m.textSecondary))
                }
            }
        }
    }
}

@Composable
private fun Header(p: ProductDetails, barcode: String?) {
    val m = Mine.colors
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    Column(Modifier.padding(top = 18.dp)) {
        Text(p.name, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold, color = m.text)
        val meta = listOfNotNull(p.article?.let { "Арт. $it" }, barcode?.let { "штрихкод $it" }).joinToString(", ")
        if (meta.isNotEmpty()) {
            Text(
                meta,
                Modifier.padding(top = 6.dp).clickable {
                    p.article?.let { art -> scope.launch { clipboard.setClipEntry(ClipData.newPlainText("Артикул", art).toClipEntry()) } }
                },
                fontSize = 15.sp, color = m.textSecondary,
            )
        }
    }
}

/** «У твоєму магазині»: количество из 1С крупно; нет выгрузки — по сайту «є / немає». */
@Composable
private fun OwnShop(shop: String, own: StockItem?, loaded: Boolean, shops: ShopsState) {
    val m = Mine.colors
    val site = (shops as? ShopsState.Loaded)?.shops?.firstOrNull { it.address.equals(shop, ignoreCase = true) }
    Surface(Modifier.padding(top = 14.dp).fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = m.card, contentColor = m.text, border = BorderStroke(1.dp, m.line)) {
        Column(Modifier.padding(18.dp)) {
            Text("У твоєму магазині", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = m.textSecondary)
            when {
                own != null -> {
                    val qty = own.qty.toInt()
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("$qty", fontFamily = PriceFont, fontWeight = FontWeight.ExtraBold, fontSize = 48.sp, lineHeight = 52.sp, color = if (qty > 0) m.success else m.logout)
                        Text(" шт", Modifier.padding(bottom = 8.dp), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                    Text("За даними 1С", fontSize = 14.sp, color = m.textSecondary)
                }
                !loaded -> Shimmer(Modifier.padding(top = 8.dp).width(120.dp).height(44.dp))
                site != null -> Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(if (site.available) m.success else m.logout)
                    Spacer(Modifier.width(8.dp))
                    Text(if (site.available) "Є, за даними сайту" else "Немає, за даними сайту", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
                else -> Text("Немає в залишках 1С твого магазину", Modifier.padding(top = 6.dp), fontSize = 16.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun RememberCard(barcode: String, onYes: () -> Unit, onNo: () -> Unit) {
    val m = Mine.colors
    Surface(Modifier.padding(top = 14.dp), shape = RoundedCornerShape(20.dp), color = m.accentTone, contentColor = m.text) {
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

private fun androidx.compose.foundation.lazy.LazyListScope.shopsSection(
    state: ShopsState,
    onRetry: () -> Unit,
    onMap: (String) -> Unit,
) {
    when (state) {
        ShopsState.Loading -> items(3) { Shimmer(Modifier.padding(vertical = 6.dp).fillMaxWidth().height(44.dp)) }
        is ShopsState.Failed -> item {
            val m = Mine.colors
            Column(Modifier.padding(vertical = 8.dp)) {
                Text(state.error.title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = m.text)
                Text(state.error.hint, fontSize = 14.sp, color = m.textSecondary)
                MineButton("Оновити наявність", onRetry, Modifier.padding(top = 8.dp))
            }
        }
        is ShopsState.Loaded -> {
            val shops = state.shops.sortedByDescending { it.available }
            if (shops.isEmpty()) {
                item { Text("Інших магазинів у цьому місті немає.", Modifier.padding(vertical = 8.dp), fontSize = 15.sp, color = Mine.colors.textSecondary) }
            } else {
                items(shops, key = { it.address }) { ShopRow(it, last = it == shops.last()) { onMap(it.address) } }
            }
        }
    }
}

@Composable
private fun ShopRow(shop: ShopAvailability, last: Boolean, onClick: () -> Unit) {
    val m = Mine.colors
    Column(Modifier.clickable(onClick = onClick)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                shop.address, Modifier.weight(1f), fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                color = if (shop.available) m.text else m.textSecondary,
            )
            Spacer(Modifier.width(12.dp))
            StatusDot(if (shop.available) m.success else m.outline)
            Spacer(Modifier.width(6.dp))
            Text(if (shop.available) "є" else "немає", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = if (shop.available) m.text else m.textSecondary)
        }
        if (!last) HorizontalDivider(color = m.line)
    }
}

@Composable
private fun Description(text: String) {
    val m = Mine.colors
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.padding(top = 20.dp).animateContentSize()) {
        Text("Опис", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = m.text)
        Text(
            text, Modifier.padding(top = 6.dp), fontSize = 15.sp, lineHeight = 22.sp, color = m.text,
            maxLines = if (expanded) Int.MAX_VALUE else 5, overflow = TextOverflow.Ellipsis,
        )
        TextButton(onClick = { expanded = !expanded }) {
            Text(if (expanded) "Згорнути" else "Читати повністю", color = m.link)
            Icon(Icons.Rounded.ExpandMore, null, Modifier.rotate(if (expanded) 180f else 0f), tint = m.link)
        }
    }
}

@Composable
private fun ProductSkeleton() {
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Shimmer(Modifier.fillMaxWidth().aspectRatio(1.25f), 24.dp)
        Shimmer(Modifier.fillMaxWidth().height(26.dp))
        Shimmer(Modifier.fillMaxWidth(0.6f).height(18.dp))
        Shimmer(Modifier.fillMaxWidth().height(130.dp), 16.dp)
        Shimmer(Modifier.fillMaxWidth().height(110.dp), 20.dp)
    }
}
