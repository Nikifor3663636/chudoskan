package ua.chudo.scanner.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.LocalOffer
import androidx.compose.material.icons.outlined.ShoppingBag
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import ua.chudo.scanner.R
import ua.chudo.scanner.chudoApp
import ua.chudo.scanner.data.Cart
import ua.chudo.scanner.data.Employee
import ua.chudo.scanner.data.HighlightKind
import ua.chudo.scanner.data.HistoryItem
import ua.chudo.scanner.data.OrderStage
import ua.chudo.scanner.ui.common.MineHeader
import ua.chudo.scanner.ui.common.MineListRow
import ua.chudo.scanner.ui.common.MineSearchField
import ua.chudo.scanner.ui.common.ProductImage
import ua.chudo.scanner.ui.common.RegionPickerSheet
import ua.chudo.scanner.ui.common.StatusDot
import ua.chudo.scanner.ui.theme.Mine
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(
    employee: Employee,
    onSearch: (String) -> Unit,
    onOpenProduct: (String) -> Unit,
    onHistory: () -> Unit,
    onNotifications: () -> Unit,
    onOrders: () -> Unit,
    onCart: () -> Unit,
    onHighlights: ((HighlightKind) -> Unit)?,
) {
    val app = LocalContext.current.chudoApp
    val prefs = app.prefs
    val cart by app.cart.lines.collectAsStateWithLifecycle()
    val region by prefs.region.collectAsStateWithLifecycle()
    val history by prefs.history.collectAsStateWithLifecycle()
    val orders by app.orders.orders.collectAsStateWithLifecycle()
    var newCount by remember(employee.uid) { mutableStateOf<Long?>(null) }
    var promoCount by remember(employee.uid) { mutableStateOf<Long?>(null) }
    LaunchedEffect(employee.uid, onHighlights != null) {
        if (onHighlights != null) kotlinx.coroutines.supervisorScope {
            launch {
                newCount = highlightCount(HighlightKind.NEW)
            }
            launch {
                promoCount = highlightCount(HighlightKind.PROMO)
            }
        }
    }
    var query by rememberSaveable { mutableStateOf("") }
    var showRegions by rememberSaveable { mutableStateOf(false) }
    var stockAt by remember(employee.shopId) { mutableStateOf<Date?>(null) }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(employee.shopId) {
        stockAt = runCatching { app.networkLookup.lastUpdated() }.getOrNull()
            ?: runCatching { app.shopStock.info(employee.shopId)?.updatedAt }.getOrNull()
        stockAt?.let { app.prefs.addActivity(employee, ua.chudo.scanner.data.ActivityKind.STOCK, "Залишки оновлено", employee.shop, it.time) }
    }

    HomeContent(
        HomeUi(region.name, employee.shop, stockAt, query, history, orders.count { it.stage == OrderStage.NEW }, Cart.units(cart), newCount, promoCount, app.orders.demo),
        onQuery = { query = it },
        onSubmit = {
            if (query.isNotBlank()) {
                keyboard?.hide()
                onSearch(query.trim())
            }
        },
        onRegion = { showRegions = true },
        onHistory = onHistory,
        onNotifications = onNotifications,
        onOrders = onOrders,
        onCart = onCart,
        onHighlights = onHighlights,
        onOpenProduct = onOpenProduct,
        banner = {
            val update by app.updater.state.collectAsStateWithLifecycle()
            val scope = rememberCoroutineScope()
            UpdateBanner(update) { scope.launch { app.updater.download() } }
        },
    )

    if (showRegions) {
        RegionPickerSheet(
            regions = prefs.allRegions,
            selected = region.code,
            onPick = { prefs.setRegion(it.code); showRegions = false },
            onDismiss = { showRegions = false },
        )
    }
}

data class HomeUi(
    val region: String,
    val shop: String,
    val stockAt: Date?,
    val query: String,
    val history: List<HistoryItem>,
    val newOrders: Int,
    val cartUnits: Int = 0,
    val newCount: Long? = null,
    val promoCount: Long? = null,
    val ordersDemo: Boolean = false,
)
/** «Доступна нова версія 3.2.0 · Оновити» → завантаження з прогресом → системне встановлення. */
@Composable
fun UpdateBanner(state: ua.chudo.scanner.data.UpdateState, onUpdate: () -> Unit) {
    val m = Mine.colors
    val (title, note, button, progress) = when (state) {
        is ua.chudo.scanner.data.UpdateState.Available -> Quad("Доступна нова версія ${state.info.version}", "Оновлення займе хвилину", "Оновити", null)
        is ua.chudo.scanner.data.UpdateState.Downloading -> Quad("Завантажую ${state.info.version}…", "${(state.progress * 100).toInt()}%", null, state.progress)
        is ua.chudo.scanner.data.UpdateState.NeedPermission -> Quad(
            "Дозволь встановлення", "У налаштуваннях увімкни «Дозволити з цього джерела» для ЧудоСкан і повернись", "Встановити", null,
        )
        is ua.chudo.scanner.data.UpdateState.Ready -> Quad("Версія ${state.info.version} завантажена", "Натисни «Встановити» у вікні Android", "Встановити", null)
        is ua.chudo.scanner.data.UpdateState.Failed -> Quad("Оновлення не завантажилось", state.message, "Ще раз", null)
        ua.chudo.scanner.data.UpdateState.None -> return
    }
    Surface(Modifier.padding(top = 14.dp).fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = m.accentTone, contentColor = m.text) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Text(note, fontSize = 14.sp, lineHeight = 19.sp, color = m.textSecondary)
                }
                if (button != null) {
                    Spacer(Modifier.width(10.dp))
                    ua.chudo.scanner.ui.common.MineButton(button, onUpdate, tone = ua.chudo.scanner.ui.common.MineTone.ACCENT)
                }
            }
            if (progress != null) androidx.compose.material3.LinearProgressIndicator(
                progress = { progress }, modifier = Modifier.padding(top = 10.dp).fillMaxWidth(), color = m.accent, trackColor = m.line,
            )
        }
    }
}

private suspend fun highlightCount(kind: HighlightKind): Long? = try {
    ua.chudo.scanner.data.ProductHighlights.count(kind)
} catch (e: kotlinx.coroutines.CancellationException) {
    throw e
} catch (_: Exception) {
    null
}

private data class Quad(val a: String, val b: String, val c: String?, val d: Float?)

/** «оновлено о 12:20» сегодня, иначе «оновлено 14.11». */
fun updatedText(at: Date?): String? {
    at ?: return null
    val day = Calendar.getInstance().apply { time = at }
    val now = Calendar.getInstance()
    val today = day.get(Calendar.YEAR) == now.get(Calendar.YEAR) && day.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)
    return if (today) "Залишки з 1С оновлено о ${SimpleDateFormat("HH:mm", Locale.forLanguageTag("uk")).format(at)}"
    else "Залишки з 1С оновлено ${SimpleDateFormat("dd.MM", Locale.forLanguageTag("uk")).format(at)}"
}

@Composable
fun HomeContent(
    ui: HomeUi,
    onQuery: (String) -> Unit = {},
    onSubmit: () -> Unit = {},
    onRegion: () -> Unit = {},
    onHistory: () -> Unit = {},
    onNotifications: () -> Unit = {},
    onOrders: () -> Unit = {},
    onCart: () -> Unit = {},
    onHighlights: ((HighlightKind) -> Unit)? = null,
    onOpenProduct: (String) -> Unit = {},
    banner: @Composable () -> Unit = {},
) {
    val m = Mine.colors
    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
        ) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Image(painterResource(R.drawable.logo_wordmark), "Чудо Острів", Modifier.padding(top = 8.dp, bottom = 10.dp).height(30.dp))
                    Spacer(Modifier.weight(1f))
                    androidx.compose.material3.IconButton(onClick = onNotifications) {
                        Icon(Icons.Outlined.Notifications, "Сповіщення", tint = m.text)
                    }
                }
            }
            item {
                Surface(onClick = onRegion, color = m.bg, contentColor = m.text, shape = Mine.fieldShape) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            if (ui.shop.isNotBlank()) Text(ui.shop, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Text("Місто пошуку · ${ui.region}", fontSize = 13.sp, color = m.textSecondary)
                        }
                        Icon(Icons.Rounded.KeyboardArrowDown, "Змінити місто пошуку", tint = m.textSecondary)
                    }
                }
                Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(if (ui.stockAt == null) m.textSecondary else m.success)
                    Spacer(Modifier.width(8.dp))
                    Text(updatedText(ui.stockAt) ?: "Час оновлення залишків невідомий", fontSize = 13.sp, color = m.textSecondary)
                }
                banner()
            }
            if (ui.history.isNotEmpty()) {
                item {
                    Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Нещодавні", Modifier.weight(1f), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = m.text)
                        TextButton(onClick = onHistory) { Text("Історія", color = m.link) }
                    }
                    Surface(shape = Mine.cardShape, color = m.card, border = BorderStroke(1.dp, m.line)) {
                        Column(Modifier.padding(horizontal = 10.dp)) {
                            val recent = ui.history.take(4)
                            recent.forEachIndexed { index, product ->
                                HistoryRow(product, index < recent.lastIndex, onOpenProduct)
                            }
                        }
                    }
                }
            }
            item {
                Text("Швидкі дії", Modifier.padding(top = 20.dp, bottom = 10.dp), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = m.text)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuickAction(Icons.Outlined.ShoppingCart, "Кошик", "${ui.cartUnits} шт", onCart, Modifier.weight(1f))
                    QuickAction(
                        Icons.Outlined.ShoppingBag, "Замовлення",
                        if (ui.ordersDemo) "Демо · ${ui.newOrders}" else "Нові: ${ui.newOrders}",
                        onOrders, Modifier.weight(1f), accent = ui.newOrders > 0,
                    )
                }
                onHighlights?.let { open ->
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        QuickAction(Icons.Rounded.AutoAwesome, "Новинки", ui.newCount?.let { "$it шт" }, { open(HighlightKind.NEW) }, Modifier.weight(1f))
                        QuickAction(Icons.Outlined.LocalOffer, "Акції", ui.promoCount?.let { "$it шт" }, { open(HighlightKind.PROMO) }, Modifier.weight(1f))
                    }
                }
            }
        }
        MineSearchField(
            ui.query, onQuery, "Штрихкод, артикул або назва", onSubmit,
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun QuickAction(
    icon: ImageVector,
    title: String,
    detail: String?,
    onClick: () -> Unit,
    modifier: Modifier,
    accent: Boolean = false,
) {
    val m = Mine.colors
    Surface(
        onClick = onClick, modifier = modifier, shape = Mine.fieldShape,
        color = m.card, contentColor = m.text, border = BorderStroke(1.dp, m.line),
    ) {
        Row(Modifier.heightIn(min = 60.dp).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(22.dp), tint = m.textSecondary)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                if (detail != null) Text(detail, fontSize = 12.sp, color = if (accent) m.link else m.textSecondary)
            }
        }
    }
}

@Composable
fun HistoryScreen(onBack: () -> Unit, onOpenProduct: (String) -> Unit) {
    val prefs = LocalContext.current.chudoApp.prefs
    val history by prefs.history.collectAsStateWithLifecycle()
    val m = Mine.colors
    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
        MineHeader("Історія", onBack) {
            if (history.isNotEmpty()) {
                TextButton(onClick = prefs::clearHistory) { Text("Очистити", color = m.link) }
            }
        }
        LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
            if (history.isEmpty()) item { Text("Тут з’являться переглянуті товари", color = m.textSecondary) }
            itemsIndexed(history, key = { _, item -> item.urlKey }) { index, item ->
                HistoryRow(item, index < history.lastIndex, onOpenProduct)
            }
        }
    }
}

@Composable
private fun HistoryRow(product: HistoryItem, divider: Boolean, onOpenProduct: (String) -> Unit) {
    val m = Mine.colors
    MineListRow(onClick = { onOpenProduct(product.urlKey) }, divider = divider) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (product.image.isNullOrBlank()) {
                Surface(Modifier.size(40.dp), shape = Mine.fieldShape, color = m.surface) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.Inventory2, null, Modifier.size(20.dp), tint = m.textSecondary)
                    }
                }
            } else {
                ProductImage(product.image, Modifier.size(40.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(product.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = m.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(product.article?.let { "Арт. $it" }.orEmpty(), Modifier.weight(1f), fontSize = 12.sp, color = m.textSecondary)
                    product.priceKop?.let { Text(Cart.money(it), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = m.text) }
                }
            }
        }
    }
}
