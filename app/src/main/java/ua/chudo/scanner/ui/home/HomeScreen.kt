package ua.chudo.scanner.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material.icons.outlined.ShoppingBag
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ua.chudo.scanner.R
import ua.chudo.scanner.chudoApp
import ua.chudo.scanner.data.Employee
import ua.chudo.scanner.data.HistoryItem
import ua.chudo.scanner.data.OrderStage
import ua.chudo.scanner.ui.common.Avatar
import ua.chudo.scanner.ui.common.MineListRow
import ua.chudo.scanner.ui.common.MinePlacePill
import ua.chudo.scanner.ui.common.MineSearchField
import ua.chudo.scanner.ui.common.MineTile
import ua.chudo.scanner.ui.common.ProductImage
import ua.chudo.scanner.ui.common.RegionPickerSheet
import ua.chudo.scanner.ui.common.StatusDot
import ua.chudo.scanner.ui.theme.Mine
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Главный: город, «Що шукаємо?», поиск, ярлыки «Друк цінників» и «Замовлення», недавние товары. */
@Composable
fun HomeScreen(
    employee: Employee,
    onSearch: (String) -> Unit,
    onOpenProduct: (String) -> Unit,
    onProfile: () -> Unit,
    onLabels: () -> Unit,
    onOrders: () -> Unit,
) {
    val app = LocalContext.current.chudoApp
    val prefs = app.prefs
    val region by prefs.region.collectAsStateWithLifecycle()
    val history by prefs.history.collectAsStateWithLifecycle()
    val orders by app.orders.orders.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var showRegions by rememberSaveable { mutableStateOf(false) }
    var stockAt by remember { mutableStateOf<Date?>(null) }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(employee.shopId) {
        stockAt = runCatching { app.shopStock.info(employee.shopId)?.updatedAt }.getOrNull()
    }

    HomeContent(
        HomeUi(
            region = region.name, employee = employee.name, stockAt = stockAt, query = query, history = history,
            newOrders = orders.count { it.stage == OrderStage.NEW },
        ),
        onQuery = { query = it },
        onSubmit = { if (query.isNotBlank()) { keyboard?.hide(); onSearch(query.trim()) } },
        onRegion = { showRegions = true },
        onProfile = onProfile, onLabels = onLabels, onOrders = onOrders,
        onOpenProduct = onOpenProduct, onClearHistory = prefs::clearHistory,
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
    val employee: String,
    val stockAt: Date?,
    val query: String,
    val history: List<HistoryItem>,
    val newOrders: Int,
)

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
    onProfile: () -> Unit = {},
    onLabels: () -> Unit = {},
    onOrders: () -> Unit = {},
    onOpenProduct: (String) -> Unit = {},
    onClearHistory: () -> Unit = {},
) {
    val m = Mine.colors
    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 40.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Логотип — прежний, без изменений.
                Image(painterResource(R.drawable.logo_wordmark), "Чудо Острів", Modifier.height(34.dp))
                Spacer(Modifier.weight(1f))
                Avatar(ui.employee, size = 44.dp, onClick = onProfile)
            }
            Row(Modifier.padding(top = 18.dp)) { MinePlacePill(ui.region, onRegion) }
            Text("Що шукаємо?", Modifier.padding(top = 18.dp), fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.Black, color = m.text)
            updatedText(ui.stockAt)?.let {
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(m.success)
                    Spacer(Modifier.width(8.dp))
                    Text(it, fontSize = 15.sp, color = m.textSecondary)
                }
            }
            MineSearchField(ui.query, onQuery, "Штрихкод, артикул або назва", onSubmit, Modifier.padding(top = 18.dp))
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MineTile(Icons.Outlined.Print, "Друк цінників", "Цінники, етикетки", accent = false, onClick = onLabels, modifier = Modifier.weight(1f))
                MineTile(
                    Icons.Outlined.ShoppingBag, "Замовлення",
                    if (ui.newOrders > 0) "${ui.newOrders} нові до збирання" else "Нових немає",
                    accent = ui.newOrders > 0, onClick = onOrders, modifier = Modifier.weight(1f),
                )
            }
        }
        if (ui.history.isEmpty()) {
            item {
                Surface(Modifier.padding(top = 28.dp).fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = m.surface, contentColor = m.text) {
                    Column(Modifier.padding(18.dp)) {
                        Text("Тут з'являться товари, які ти відкривав", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text(
                            "Натисни помаранчеву кнопку внизу й наведи камеру на штрихкод або введи артикул з цінника.",
                            Modifier.padding(top = 6.dp), fontSize = 15.sp, lineHeight = 21.sp, color = m.textSecondary,
                        )
                    }
                }
            }
        } else {
            item {
                Row(Modifier.padding(top = 26.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Нещодавні", Modifier.weight(1f), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = m.text)
                    TextButton(onClick = onClearHistory) { Text("Очистити", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = m.link) }
                }
            }
            items(ui.history, key = { it.urlKey }) { h ->
                MineListRow(onClick = { onOpenProduct(h.urlKey) }, divider = h != ui.history.last()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ProductImage(h.image, Modifier.size(52.dp))
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(h.name, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = m.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            h.article?.let { Text("Арт. $it", fontSize = 14.sp, color = m.textSecondary) }
                        }
                    }
                }
            }
        }
    }
}
