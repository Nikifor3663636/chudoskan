package ua.chudo.scanner.ui.home

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
    onSearch: () -> Unit,
    onScan: () -> Unit,
    onLabels: () -> Unit,
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
                while (true) {
                    try {
                        promoCount = app.promoSheet.read().count { it.isActive() }.toLong()
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        if (promoCount == null) promoCount = highlightCount(HighlightKind.PROMO)
                    }
                    kotlinx.coroutines.delay(ua.chudo.scanner.data.PromoSheet.REFRESH_MS)
                }
            }
        }
    }
    var showRegions by rememberSaveable { mutableStateOf(false) }
    var stockAt by remember(employee.shopId) { mutableStateOf<Date?>(null) }

    LaunchedEffect(employee.shopId) {
        stockAt = runCatching { app.networkLookup.lastUpdated() }.getOrNull()
            ?: runCatching { app.shopStock.info(employee.shopId)?.updatedAt }.getOrNull()
        stockAt?.let { app.prefs.addActivity(employee, ua.chudo.scanner.data.ActivityKind.STOCK, "Залишки оновлено", employee.shop, it.time) }
    }

    HomeContent(
        HomeUi(
            region = region.name,
            shop = employee.shop,
            stockAt = stockAt,
            query = "",
            history = history,
            newOrders = orders.count { it.stage == OrderStage.NEW },
            cartUnits = Cart.units(cart),
            newCount = newCount,
            promoCount = promoCount,
            ordersDemo = app.orders.demo,
            cartTotal = Cart.total(cart).takeIf { cart.all { line -> line.priceKop != null } },
            greeting = remember(employee.name) {
                greeting(employee.name, java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY))
            },
        ),
        onRegion = { showRegions = true },
        onScan = onScan,
        onOpenSearch = onSearch,
        onLabels = onLabels,
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
    val cartTotal: Long? = null,
    /** «Доброго ранку, Олено» — вітання продавця на головній. */
    val greeting: String = "",
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
    onScan: () -> Unit = {},
    onOpenSearch: (() -> Unit)? = null,
    onLabels: () -> Unit = {},
    onHistory: () -> Unit = {},
    onNotifications: () -> Unit = {},
    onOrders: () -> Unit = {},
    onCart: () -> Unit = {},
    onHighlights: ((HighlightKind) -> Unit)? = null,
    onOpenProduct: (String) -> Unit = {},
    banner: @Composable () -> Unit = {},
) {
    val m = Mine.colors
    // Пошук — унизу, над навігацією: туди дістає великий палець однією рукою.
    Column(Modifier.fillMaxSize().background(m.bg).statusBarsPadding().imePadding()) {
    LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = Mine.spacing16)) {
        item {
            if (ui.greeting.isNotBlank()) Text(
                ui.greeting, Modifier.padding(start = Mine.spacing16, end = Mine.spacing16, top = Mine.spacing16),
                style = MaterialTheme.typography.headlineSmall, color = m.text, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Row(
                Modifier.fillMaxWidth().padding(start = Mine.spacing16, end = Mine.spacing8, top = Mine.spacing4),
                horizontalArrangement = Arrangement.spacedBy(Mine.spacing8),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier.weight(1f).heightIn(min = 48.dp).clickable(onClickLabel = "Змінити місто пошуку", onClick = onRegion),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    HomeIcon(R.drawable.ic_redesign_store, 20)
                    Text(
                        ui.shop.ifBlank { ui.region }, Modifier.weight(1f, fill = false),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    HomeIcon(R.drawable.ic_redesign_chevron_down, 18)
                }
                IconButton(onClick = onNotifications, modifier = Modifier.size(48.dp)) {
                    Icon(painterResource(R.drawable.ic_redesign_bell), "Сповіщення", Modifier.size(24.dp), tint = m.textSecondary)
                }
            }
            Column(Modifier.padding(horizontal = Mine.spacing16)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusDot(if (ui.stockAt == null) m.outline else m.success, Modifier.size(8.dp))
                    Text(
                        updatedText(ui.stockAt) ?: "Час оновлення залишків невідомий",
                        fontSize = 13.sp, color = m.textTertiary,
                    )
                }
                banner()
            }
        }
        item {
            val cartDetail = if (ui.cartUnits == 0) "Порожній" else buildString {
                append("${ui.cartUnits} шт")
                ui.cartTotal?.let { append(" · ${Cart.money(it)}") }
            }
            Column(
                Modifier.padding(start = Mine.spacing16, end = Mine.spacing16, top = Mine.spacing16),
                verticalArrangement = Arrangement.spacedBy(Mine.spacing12),
            ) {
                Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(Mine.spacing12)) {
                    QuickAction(
                        R.drawable.ic_redesign_clipboard_list, "Замовлення", orderDetail(ui), onOrders,
                        Modifier.weight(1f), accent = ui.newOrders > 0, badge = ui.newOrders,
                    )
                    QuickAction(R.drawable.ic_redesign_shopping_cart, "Кошик", cartDetail, onCart, Modifier.weight(1f))
                }
                onHighlights?.let { open ->
                    Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(Mine.spacing12)) {
                        QuickAction(
                            R.drawable.ic_redesign_sparkles, "Новинки", ui.newCount?.let { "$it товарів" },
                            { open(HighlightKind.NEW) }, Modifier.weight(1f),
                        )
                        QuickAction(
                            R.drawable.ic_redesign_badge_percent, "Акції", ui.promoCount?.let { "$it акцій" },
                            { open(HighlightKind.PROMO) }, Modifier.weight(1f),
                        )
                    }
                }
            }
        }
        if (ui.history.isNotEmpty()) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(start = Mine.spacing16, end = Mine.spacing4, top = Mine.spacing20),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Нещодавні", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = m.textSecondary)
                    androidx.compose.material3.TextButton(onClick = onHistory) {
                        Text("Історія", style = MaterialTheme.typography.labelLarge, color = m.link)
                    }
                }
                HorizontalDivider(color = m.line)
            }
            itemsIndexed(ui.history.take(4), key = { _, product -> product.urlKey }) { _, product ->
                RecentProductRow(product, onOpenProduct)
                HorizontalDivider(color = m.line)
            }
        }
    }
        Box(Modifier.fillMaxWidth().background(m.bg).padding(start = Mine.spacing16, end = Mine.spacing16, top = Mine.spacing8, bottom = Mine.spacing12)) {
            MineSearchField(
                ui.query, onQuery, "Штрихкод, артикул або назва", onSubmit,
                onScan = onScan, homeStyle = true, onOpenSearch = onOpenSearch,
            )
        }
    }
}

private fun orderDetail(ui: HomeUi): String {
    if (ui.ordersDemo) return "Демо · ${ui.newOrders}"
    val count = ui.newOrders
    if (count == 0) return "Немає нових"
    val word = when {
        count % 100 in 11..14 -> "нових"
        count % 10 == 1 -> "нове"
        count % 10 in 2..4 -> "нові"
        else -> "нових"
    }
    return "$count $word на збір"
}

@Composable
private fun HomeIcon(@DrawableRes icon: Int, size: Int, tint: Color = Mine.colors.text) {
    Icon(painterResource(icon), null, Modifier.size(size.dp), tint = tint)
}

/** Плитка швидкої дії: картка 16 dp, іконка в тональному колі, назва й підпис; уся площа — для натискання. */
@Composable
private fun QuickAction(
    @DrawableRes icon: Int,
    title: String,
    detail: String?,
    onClick: () -> Unit,
    modifier: Modifier,
    accent: Boolean = false,
    badge: Int = 0,
) {
    val m = Mine.colors
    Surface(
        onClick = onClick, modifier = modifier.fillMaxHeight().heightIn(min = 104.dp),
        shape = RoundedCornerShape(16.dp), color = m.card, contentColor = m.text,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box {
                Box(Modifier.size(44.dp).background(m.accentTone, CircleShape), contentAlignment = Alignment.Center) {
                    HomeIcon(icon, 24, m.link)
                }
                if (badge > 0) androidx.compose.material3.Badge(Modifier.align(Alignment.TopEnd)) { Text("$badge") }
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (detail != null) Text(detail, fontSize = 13.sp, color = if (accent) m.link else m.textTertiary, maxLines = 2)
            }
        }
    }
}

@Composable
private fun RecentProductRow(product: HistoryItem, onOpenProduct: (String) -> Unit) {
    val m = Mine.colors
    Row(
        Modifier.fillMaxWidth().background(m.card).clickable { onOpenProduct(product.urlKey) }
            .padding(horizontal = Mine.spacing16, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(Mine.spacing12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            Modifier.size(48.dp), shape = RoundedCornerShape(12.dp), color = m.surface,
            border = BorderStroke(1.dp, m.line),
        ) {
            if (product.image.isNullOrBlank()) {
                Box(contentAlignment = Alignment.Center) { HomeIcon(R.drawable.ic_redesign_package, 22, m.textTertiary) }
            } else {
                ProductImage(product.image, Modifier.fillMaxSize())
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(product.name, fontSize = 15.sp, lineHeight = 19.5.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            product.article?.let { Text("арт. $it", fontSize = 13.sp, color = m.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        product.priceKop?.let {
            Text(Cart.money(it), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.16).sp)
        }
    }
}

@Composable
fun HistoryScreen(onBack: () -> Unit, onOpenProduct: (String) -> Unit) {
    val prefs = LocalContext.current.chudoApp.prefs
    val history by prefs.history.collectAsStateWithLifecycle()
    HistoryContent(history, onBack, prefs::clearHistory, onOpenProduct)
}

@Composable
internal fun HistoryContent(
    history: List<HistoryItem>,
    onBack: () -> Unit,
    onClear: () -> Unit,
    onOpenProduct: (String) -> Unit,
) {
    val m = Mine.colors
    Column(Modifier.fillMaxSize().background(m.bg).statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().height(64.dp).padding(horizontal = Mine.spacing4),
            horizontalArrangement = Arrangement.spacedBy(Mine.spacing4),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(
                    painterResource(R.drawable.ic_redesign_chevron_left), "Назад",
                    Modifier.size(24.dp), tint = m.textSecondary,
                )
            }
            Text(
                "Нещодавні", Modifier.weight(1f).padding(start = Mine.spacing4),
                fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = m.text,
            )
            if (history.isNotEmpty()) {
                Box(
                    Modifier.height(44.dp).clickable(onClick = onClear).padding(horizontal = Mine.spacing8),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Очистити", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = m.link)
                }
            }
        }
        if (history.isEmpty()) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = Mine.spacing20, vertical = Mine.spacing32),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Surface(
                    modifier = Modifier.size(48.dp), shape = CircleShape, color = m.surface,
                    border = BorderStroke(1.dp, m.line),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        HomeIcon(R.drawable.ic_redesign_history, 20, m.textSecondary)
                    }
                }
                Text(
                    "Тут з’являться переглянуті товари",
                    fontSize = 14.sp, color = m.textSecondary, textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(bottom = Mine.spacing16)) {
                item { HorizontalDivider(color = m.line) }
                items(history, key = { it.urlKey }) { product ->
                    RecentProductRow(product, onOpenProduct)
                    HorizontalDivider(color = m.line)
                }
            }
        }
    }
}
