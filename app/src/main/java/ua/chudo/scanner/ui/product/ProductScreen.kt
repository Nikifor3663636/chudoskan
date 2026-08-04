package ua.chudo.scanner.ui.product

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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import uniffi.chudo_core.ProductDetails
import uniffi.chudo_core.ShopAvailability
import uniffi.chudo_core.productUrl
import ua.chudo.scanner.i18n.tr
import ua.chudo.scanner.i18n.title

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
    val lang by app.prefs.lang.collectAsStateWithLifecycle()
    var showRegions by rememberSaveable { mutableStateOf(false) }

    val p = s.product
    val url = productUrl(lang, p?.urlKey ?: vm.urlKey, p?.urlSuffix ?: ".html")

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, tr("Назад", "Назад")) }
                },
                actions = {
                    IconButton(onClick = { ctx.shareText(listOfNotNull(p?.name, url).joinToString("\n")) }) {
                        Icon(Icons.Rounded.Share, tr("Поділитися", "Поделиться"))
                    }
                    IconButton(onClick = { ctx.openUrl(url) }) { Icon(Icons.Rounded.OpenInBrowser, tr("Відкрити на сайті", "Открыть на сайте")) }
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
                            Text(tr("Відкрити на сайті", "Открыть на сайте"))
                        }
                    }
                    s.barcodeToRemember?.let { bc ->
                        item { RememberCard(bc, vm::rememberBarcode, vm::dismissRemember) }
                    }
                    item {
                        SectionTitle(tr("Наявність у магазинах", "Наличие в магазинах")) {
                            AssistChip(
                                onClick = { showRegions = true },
                                label = { Text(region.title()) },
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
    val clipboard = LocalClipboardManager.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        p.categories.lastOrNull()?.let {
            Text(it.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
        Text(p.name, style = MaterialTheme.typography.titleLarge)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            p.article?.let { art ->
                AssistChip(
                    onClick = { clipboard.setText(AnnotatedString(art)) },
                    label = { Text(tr("Арт. $art", "Арт. $art")) },
                    trailingIcon = { Icon(Icons.Rounded.ContentCopy, tr("Копіювати", "Копировать"), Modifier.size(16.dp)) },
                )
            }
            StockBadge(p.stock)
        }
        PriceLine(p.price)
        p.onlyXLeft?.takeIf { it > 0 }?.let {
            Text(tr("Залишилось ${it.toInt()} шт.", "Осталось ${it.toInt()} шт."), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
        }
    }
}

@Composable
private fun RememberCard(barcode: String, onYes: () -> Unit, onNo: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.tertiaryContainer) {
        Column(Modifier.padding(16.dp)) {
            Text(tr("Це твій товар?", "Это твой товар?"), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
            Text(
                tr("Штрихкод $barcode знайшли через відкриту базу. Запам'ятати — наступного разу відкриємо одразу.", "Штрихкод $barcode нашли через открытую базу. Запомнить — в следующий раз откроем сразу."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onYes) { Text(tr("Так, запам'ятати", "Да, запомнить")) }
                TextButton(onClick = onNo) { Text(tr("Ні", "Нет")) }
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
                    OutlinedButton(onClick = onRetry, Modifier.padding(top = 8.dp)) { Text(tr("Оновити наявність", "Обновить наличие")) }
                }
            }
        }
        is ShopsState.Loaded -> {
            val shops = state.shops.sortedByDescending { it.available }
            if (shops.isEmpty()) {
                item {
                    Text(
                        tr("У цьому місті немає даних про магазини.", "В этом городе нет данных о магазинах."),
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
        0 -> tr("Немає в жодному магазині міста", "Нет ни в одном магазине города") to OutOfStockGrey
        shops.size -> tr("Є в усіх магазинах міста (${shops.size})", "Есть во всех магазинах города (${shops.size})") to InStockGreen
        else -> tr("Є в $n з ${shops.size} магазинів", "Есть в $n из ${shops.size} магазинов") to InStockGreen
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
            Text(if (shop.available) tr("Є", "Есть") else tr("Немає", "Нет"), style = MaterialTheme.typography.labelLarge, color = color)
        }
    }
}

@Composable
private fun Description(text: String) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.animateContentSize()) {
        SectionTitle(tr("Опис", "Описание"))
        Spacer(Modifier.height(6.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = if (expanded) Int.MAX_VALUE else 6,
            overflow = TextOverflow.Ellipsis,
        )
        TextButton(onClick = { expanded = !expanded }) {
            Text(if (expanded) tr("Згорнути", "Свернуть") else tr("Читати повністю", "Читать полностью"))
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
