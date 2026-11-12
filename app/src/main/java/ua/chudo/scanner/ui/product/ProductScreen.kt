package ua.chudo.scanner.ui.product

import android.content.ClipData
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.OpenInBrowser
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Storefront
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import ua.chudo.scanner.chudoApp
import ua.chudo.scanner.ui.common.ErrorState
import ua.chudo.scanner.ui.common.PriceLine
import ua.chudo.scanner.ui.common.ProductImage
import ua.chudo.scanner.ui.common.RegionPickerSheet
import ua.chudo.scanner.ui.common.SectionTitle
import ua.chudo.scanner.ui.common.Shimmer
import ua.chudo.scanner.ui.common.StockBadge
import ua.chudo.scanner.ui.common.openMap
import ua.chudo.scanner.ui.common.openUrl
import ua.chudo.scanner.ui.common.shareText
import ua.chudo.scanner.ui.theme.InStockGreen
import ua.chudo.scanner.ui.theme.OutOfStockGrey
import uniffi.chudo_core.Lang
import uniffi.chudo_core.ProductDetails
import uniffi.chudo_core.ShopAvailability
import uniffi.chudo_core.productUrl

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.chudoApp
    val vm: ProductViewModel = viewModel(factory = viewModelFactory {
        initializer { ProductViewModel(app.repository, app.prefs, createSavedStateHandle()) }
    })
    val s by vm.state.collectAsStateWithLifecycle()
    val region by app.prefs.region.collectAsStateWithLifecycle()
    var showRegions by rememberSaveable { mutableStateOf(false) }

    val p = s.product
    val url = productUrl(Lang.UA, p?.urlKey ?: vm.urlKey, p?.urlSuffix ?: ".html")

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад") }
                },
                actions = {
                    IconButton(onClick = { ctx.shareText(listOfNotNull(p?.name, url).joinToString("\n")) }) {
                        Icon(Icons.Rounded.Share, "Поділитися")
                    }
                    IconButton(onClick = { ctx.openUrl(url) }) { Icon(Icons.Rounded.OpenInBrowser, "Відкрити на сайті") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = s.refreshing,
            onRefresh = { vm.load(refresh = true) },
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            when {
                s.loading -> ProductSkeleton()
                p == null -> s.error?.let { ErrorState(it, url, { vm.load() }, Modifier.align(Alignment.Center)) }
                else -> LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 40.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    item { Gallery(p.images) }
                    item { Header(p) }
                    item {
                        Button(onClick = { ctx.openUrl(url) }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                            Icon(Icons.Rounded.OpenInBrowser, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Відкрити на сайті")
                        }
                    }
                    s.barcodeToRemember?.let { bc ->
                        item { RememberCard(bc, vm::rememberBarcode, vm::dismissRemember) }
                    }
                    item {
                        SectionTitle("Наявність у магазинах") {
                            AssistChip(
                                onClick = { showRegions = true },
                                label = { Text(region.name) },
                                leadingIcon = { Icon(Icons.Rounded.LocationOn, null, Modifier.size(18.dp)) },
                                trailingIcon = { Icon(Icons.Rounded.ExpandMore, null, Modifier.size(18.dp)) },
                            )
                        }
                    }
                    shopsSection(s.shops, onRetry = vm::loadShops, onMap = ctx::openMap)
                    if (p.description.isNotBlank()) item { Description(p.description) }
                }
            }
        }
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
private fun Gallery(images: List<String>) {
    if (images.isEmpty()) {
        ProductImage(null, Modifier.fillMaxWidth().aspectRatio(1.3f))
        return
    }
    val pager = rememberPagerState { images.size }
    Box {
        HorizontalPager(pager, pageSpacing = 12.dp) { page ->
            ProductImage(images[page], Modifier.fillMaxWidth().aspectRatio(1.1f))
        }
        if (images.size > 1) {
            Row(
                Modifier.align(Alignment.BottomCenter).padding(10.dp)
                    .clip(CircleShape).background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.35f))
                    .padding(horizontal = 8.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                repeat(images.size) { i ->
                    val active = i == pager.currentPage
                    Box(
                        Modifier.size(if (active) 8.dp else 6.dp).clip(CircleShape)
                            .background(if (active) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surface.copy(alpha = 0.7f))
                    )
                }
            }
        }
    }
}

@Composable
private fun Header(p: ProductDetails) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        p.categories.lastOrNull()?.let {
            Text(it.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
        Text(p.name, style = MaterialTheme.typography.titleLarge)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            p.article?.let { art ->
                AssistChip(
                    onClick = { scope.launch { clipboard.setClipEntry(ClipData.newPlainText("Артикул", art).toClipEntry()) } },
                    label = { Text("Арт. $art") },
                    trailingIcon = { Icon(Icons.Rounded.ContentCopy, "Копіювати", Modifier.size(16.dp)) },
                )
            }
            StockBadge(p.stock)
        }
        PriceLine(p.price)
        p.onlyXLeft?.takeIf { it > 0 }?.let {
            Text("Залишилось ${it.toInt()} шт.", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
        }
    }
}

@Composable
private fun RememberCard(barcode: String, onYes: () -> Unit, onNo: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.tertiaryContainer) {
        Column(Modifier.padding(16.dp)) {
            Text("Це твій товар?", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
            Text(
                "Штрихкод $barcode знайшли через відкриту базу. Запам'ятати — наступного разу відкриємо одразу.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onYes) { Text("Так, запам'ятати") }
                TextButton(onClick = onNo) { Text("Ні") }
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
        ShopsState.Loading -> items(3) { Shimmer(Modifier.fillMaxWidth().height(64.dp), 18.dp) }
        is ShopsState.Failed -> item {
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(state.error.title, style = MaterialTheme.typography.titleSmall)
                    Text(state.error.hint, style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = onRetry, Modifier.padding(top = 8.dp)) { Text("Оновити наявність") }
                }
            }
        }
        is ShopsState.Loaded -> {
            val shops = state.shops.sortedByDescending { it.available }
            if (shops.isEmpty()) {
                item {
                    Text(
                        "У цьому місті немає даних про магазини.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                item { AvailabilitySummary(shops) }
                items(shops, key = { it.address }) { ShopRow(it) { onMap(it.address) } }
            }
        }
    }
}

@Composable
private fun AvailabilitySummary(shops: List<ShopAvailability>) {
    val n = shops.count { it.available }
    val (text, color) = when (n) {
        0 -> "Немає в жодному магазині міста" to OutOfStockGrey
        shops.size -> "Є в усіх магазинах міста (${shops.size})" to InStockGreen
        else -> "Є в $n з ${shops.size} магазинів" to InStockGreen
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.Storefront, null, tint = color)
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.titleSmall, color = color)
    }
}

@Composable
private fun ShopRow(shop: ShopAvailability, onClick: () -> Unit) {
    val color = if (shop.available) InStockGreen else OutOfStockGrey
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
    ) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (shop.available) Icons.Rounded.CheckCircle else Icons.Rounded.Cancel, null, tint = color)
            Spacer(Modifier.width(12.dp))
            Text(
                shop.address,
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = if (shop.available) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            Text(if (shop.available) "Є" else "Немає", style = MaterialTheme.typography.labelLarge, color = color)
        }
    }
}

@Composable
private fun Description(text: String) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.animateContentSize()) {
        SectionTitle("Опис")
        Spacer(Modifier.height(6.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = if (expanded) Int.MAX_VALUE else 6,
            overflow = TextOverflow.Ellipsis,
        )
        TextButton(onClick = { expanded = !expanded }) {
            Text(if (expanded) "Згорнути" else "Читати повністю")
            Icon(Icons.Rounded.ExpandMore, null, Modifier.rotate(if (expanded) 180f else 0f))
        }
    }
}

@Composable
private fun ProductSkeleton() {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Shimmer(Modifier.fillMaxWidth().aspectRatio(1.1f), 18.dp)
        Shimmer(Modifier.fillMaxWidth(0.4f).height(14.dp))
        Shimmer(Modifier.fillMaxWidth().height(26.dp))
        Shimmer(Modifier.fillMaxWidth(0.7f).height(26.dp))
        Shimmer(Modifier.fillMaxWidth(0.5f).height(36.dp))
        Shimmer(Modifier.fillMaxWidth().height(52.dp), 26.dp)
    }
}
