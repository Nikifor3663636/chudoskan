package ua.chudo.scanner.ui.orders

import kotlinx.coroutines.launch
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import ua.chudo.scanner.R
import kotlin.math.roundToInt
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ua.chudo.scanner.chudoApp
import ua.chudo.scanner.data.Employee
import ua.chudo.scanner.data.OrderStage
import ua.chudo.scanner.data.PickupOrder
import ua.chudo.scanner.data.ScanResult
import ua.chudo.scanner.print.PriceFormat
import ua.chudo.scanner.ui.common.TabletPanes
import ua.chudo.scanner.ui.theme.Mine

/** «Замовлення»: интернет-заказы на самовывоз, сборка сканером. */
@Composable
fun OrdersScreen(
    employee: Employee, onBack: () -> Unit, onScan: () -> Unit, scanned: String?, onScannedConsumed: () -> Unit,
    tablet: Boolean = false,
) {
    val app = LocalContext.current.chudoApp
    val repo = app.orders
    val orders by repo.orders.collectAsStateWithLifecycle()
    val activeNumber by repo.activeNumber.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(OrderStage.NEW) }
    var issue by remember { mutableStateOf<PickupOrder?>(null) }
    val snackbar = remember { SnackbarHostState() }

    var openNumber by rememberSaveable { mutableStateOf<String?>(null) }
    var manual by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    fun scanMessage(code: String): String = when (val r = repo.scan(code)) {
        is ScanResult.Added -> "+1 · ${r.name} (${r.order.picked} з ${r.order.items})"
        is ScanResult.Completed -> "Замовлення № ${r.order.number} зібрано".also { message ->
            if (!repo.demo) app.prefs.addActivity(employee, ua.chudo.scanner.data.ActivityKind.ORDER, message, goods(r.order.items))
        }
        ScanResult.NotInOrder -> "Цього товару немає в замовленні"
        ScanResult.AlreadyEnough -> "Цей товар уже зібрано повністю"
        ScanResult.NothingPicking -> "Спочатку обери замовлення в «Нові»"
    }

    // Код со сканера: +1 к строке собираемого заказа.
    LaunchedEffect(scanned) {
        val code = scanned ?: return@LaunchedEffect
        onScannedConsumed()
        snackbar.showSnackbar(scanMessage(code))
    }
    val ui = OrdersUi(shop = employee.shop, orders = orders, tab = tab, demo = repo.demo, activeNumber = activeNumber)
    val opened = orders.firstOrNull { it.number == openNumber }
    val openOrder: (PickupOrder) -> Unit = { order ->
        when (order.stage) {
            OrderStage.NEW, OrderStage.PICKING -> { repo.start(order.number); tab = OrderStage.PICKING }
            OrderStage.READY -> issue = order
        }
    }
    Box(Modifier.fillMaxSize()) {
        if (tablet) TabletOrders(ui, { tab = it }, openOrder, onScan)
        else if (opened != null) {
            androidx.activity.compose.BackHandler { openNumber = null }
            OrderPickContent(
                opened,
                onBack = { openNumber = null },
                onStart = { repo.start(opened.number); tab = OrderStage.PICKING },
                onScan = { repo.start(opened.number); onScan() },
                onManual = { repo.start(opened.number); manual = true },
                onToggle = { index ->
                    repo.togglePicked(opened.number, index)?.takeIf { it.stage == OrderStage.READY }?.let { done ->
                        tab = OrderStage.READY
                        scope.launch { snackbar.showSnackbar("Замовлення № ${done.number} зібрано") }
                    }
                },
                onIssue = { issue = opened },
            )
        } else OrdersContent(ui, onBack, { tab = it }, { openNumber = it.number }, onScan)
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 80.dp, start = 16.dp, end = 16.dp))
    }
    if (manual) ua.chudo.scanner.ui.scan.ManualScanInput(onClose = { manual = false }) { code ->
        manual = false
        scope.launch { snackbar.showSnackbar(scanMessage(code)) }
    }

    issue?.let { o ->
        AlertDialog(
            onDismissRequest = { issue = null },
            title = { Text("Видати № ${o.number}?") },
            text = { Text("${o.customer} · ${money(o.total)}" + if (o.paid) "" else "\nОплата при отриманні: візьми ${money(o.total)}.") },
            confirmButton = { TextButton(onClick = { repo.issue(o.number, paymentReceived = !o.paid); issue = null }) { Text(if (o.paid) "Видано" else "Оплату отримано · видати") } },
            dismissButton = { TextButton(onClick = { issue = null }) { Text("Скасувати") } },
        )
    }
}

data class OrdersUi(
    val shop: String,
    val orders: List<PickupOrder>,
    val tab: OrderStage,
    val demo: Boolean,
    val activeNumber: String? = orders.singleOrNull { it.stage == OrderStage.PICKING }?.number,
)

private fun money(v: Double) = "${PriceFormat.tag(v).first} ₴"

/** «5 товарів», «1 товар», «2 товари». */
fun goods(n: Int): String {
    val w = when {
        n % 100 in 11..14 -> "товарів"
        n % 10 == 1 -> "товар"
        n % 10 in 2..4 -> "товари"
        else -> "товарів"
    }
    return "$n $w"
}

@Composable
fun OrdersContent(ui: OrdersUi, onBack: () -> Unit = {}, onTab: (OrderStage) -> Unit = {}, onOrder: (PickupOrder) -> Unit = {}, onScan: () -> Unit = {}) {
    val m = Mine.colors
    val list = ui.orders.filter { it.stage == ui.tab }
    LazyColumn(Modifier.fillMaxSize().background(m.bg).statusBarsPadding()) {
        item {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp)) {
                Text("Замовлення", fontSize = 26.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.39).sp, color = m.text)
                Text(
                    "Самовивіз · ${ui.shop.substringBefore(" · ").ifBlank { "Мій магазин" }}, сьогодні",
                    Modifier.padding(top = 2.dp), fontSize = 14.sp, color = m.textSecondary,
                )
            }
            Box(Modifier.padding(horizontal = 16.dp)) { Segments(ui, onTab) }
        }
        if (list.isNotEmpty()) {
            item { Spacer(Modifier.height(12.dp)); HorizontalDivider(color = m.line) }
            items(list, key = { it.number }) { o ->
                OrderRow(o, onClick = { onOrder(o) })
                HorizontalDivider(color = m.line)
            }
        } else item {
            Column(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 56.dp, bottom = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    Modifier.size(48.dp).clip(CircleShape).background(m.surface).border(1.dp, m.line, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(painterResource(R.drawable.ic_redesign_inbox), null, Modifier.size(20.dp), tint = m.textSecondary)
                }
                Text(
                    when (ui.tab) {
                        OrderStage.NEW -> "Нових замовлень немає"
                        OrderStage.PICKING -> "Нічого не збираєте"
                        OrderStage.READY -> "Готових до видачі немає"
                    },
                    fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = m.text,
                )
                Text(
                    "Нові замовлення з сайту з’являються тут автоматично, зі звуком.",
                    Modifier.widthIn(max = 260.dp), fontSize = 14.sp, color = m.textSecondary, textAlign = TextAlign.Center,
                )
            }
        }
        if (ui.demo) item {
            Text(
                "Демо-дані: обмін замовленнями з 1С ще налаштовується.",
                Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp), fontSize = 13.sp, color = m.textTertiary,
            )
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
internal fun TabletOrders(
    ui: OrdersUi, onTab: (OrderStage) -> Unit, onOrder: (PickupOrder) -> Unit, onScan: () -> Unit,
) {
    val m = Mine.colors
    val rows = ui.orders.filter { it.stage == ui.tab }
    var selection by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = rows.firstOrNull { it.number == selection } ?: rows.firstOrNull()
    TabletPanes(list = {
        LazyColumn(Modifier.fillMaxSize().statusBarsPadding()) {
            item {
                Column(Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 10.dp)) {
                    Text("Замовлення", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Text(listOf("Самовивіз", ui.shop).filter(String::isNotBlank).joinToString(" · "), fontSize = 13.sp, color = m.textTertiary)
                    if (ui.demo) Text("Демо-дані", fontSize = 13.sp, color = m.link)
                    Segments(ui, onTab)
                }
                HorizontalDivider(color = m.line)
            }
            items(rows, key = { it.number }) { order ->
                val active = selected?.number == order.number
                Row(
                    Modifier.fillMaxWidth().background(if (active) m.surface else m.card)
                        .clickable { selection = order.number }
                        .then(if (active) Modifier.drawBehind {
                            drawRect(m.accent, size = Size(3.dp.toPx(), size.height))
                        } else Modifier)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("№${order.number}", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        Text("${order.customer} · ${order.createdAt}", fontSize = 13.sp, color = m.textTertiary)
                        Text(
                            listOf(order.stage.title, "Оплачено".takeIf { order.paid }).filterNotNull().joinToString(" · "),
                            fontSize = 12.sp, color = if (order.stage == OrderStage.READY) m.success else m.link,
                        )
                    }
                    Text(money(order.total), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
                HorizontalDivider(color = m.line)
            }
        }
    }, detail = {
        if (selected == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("У цьому розділі немає замовлень", color = m.textSecondary)
            }
        } else LazyColumn(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 24.dp)) {
            item {
                Column(Modifier.padding(top = 24.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("№${selected.number}", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                    Text("${selected.customer} · оформлено о ${selected.createdAt} · ${money(selected.total)}", fontSize = 14.sp, color = m.textSecondary)
                    val active = selected.stage == OrderStage.PICKING && selected.number == ui.activeNumber
                    val label = when {
                        active -> "Сканувати наступний товар"
                        selected.stage == OrderStage.READY -> "Видати замовлення"
                        else -> "Почати збирати"
                    }
                    Surface(
                        onClick = { if (active) onScan() else onOrder(selected) },
                        shape = RoundedCornerShape(50), color = m.accent, contentColor = m.onAccent,
                    ) {
                        Box(Modifier.heightIn(min = 44.dp).padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                            Text(label, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
                TabletOrderTableRow("Товар", "Потрібно", "У магазині", "Зібрано", heading = true)
            }
            items(selected.lines) { line ->
                TabletOrderTableRow(line.name, line.qty.toString(), line.inStock?.toString() ?: "—", line.picked.toString(),
                    low = line.inStock?.let { it < line.qty } == true)
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    })
}

@Composable
private fun TabletOrderTableRow(name: String, qty: String, stock: String, picked: String, heading: Boolean = false, low: Boolean = false) {
    val m = Mine.colors
    val color = if (heading) m.textSecondary else m.text
    Row(
        Modifier.fillMaxWidth().background(if (heading) m.surface else m.card).heightIn(min = 44.dp).padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(name, Modifier.weight(2f), fontSize = 14.sp, color = color)
        Text(qty, Modifier.weight(0.8f), fontSize = 13.sp, color = color)
        Text(stock, Modifier.weight(0.9f), fontSize = 13.sp, color = if (low) m.warningText else color)
        Text(picked, Modifier.weight(0.65f), fontSize = 13.sp, color = color)
    }
    HorizontalDivider(color = m.line)
}

/** Переключатель «Нові 3 · Збираю 1 · Готові 2». */
@Composable
private fun Segments(ui: OrdersUi, onTab: (OrderStage) -> Unit) {
    val m = Mine.colors
    Row(
        Modifier.fillMaxWidth().background(m.surface, RoundedCornerShape(50))
            .border(1.dp, m.line, RoundedCornerShape(50)).selectableGroup().padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        OrderStage.entries.forEach { s ->
            val selected = s == ui.tab
            val count = ui.orders.count { it.stage == s }
            Surface(
                Modifier.weight(1f).height(40.dp).selectable(selected, role = Role.Tab, onClick = { onTab(s) }),
                shape = RoundedCornerShape(50), border = if (selected) BorderStroke(1.dp, m.line) else null,
                color = if (selected) m.card else Color.Transparent,
            ) {
                Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Text(s.title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = if (selected) m.text else m.textSecondary)
                    Spacer(Modifier.width(6.dp))
                    Text("$count", fontSize = 12.sp, color = m.textTertiary)
                }
            }
        }
    }
}

private enum class PillTone { NEUTRAL, OK, WARN }

@Composable
private fun OrderPill(text: String, tone: PillTone = PillTone.NEUTRAL) {
    val m = Mine.colors
    val (bg, fg) = when (tone) {
        PillTone.NEUTRAL -> m.surface to m.textSecondary
        PillTone.OK -> m.successTint to m.success
        PillTone.WARN -> m.warningTint to m.warningText
    }
    Box(
        Modifier.height(22.dp).background(bg, RoundedCornerShape(8.dp))
            .then(if (tone == PillTone.NEUTRAL) Modifier.border(1.dp, m.line, RoundedCornerShape(8.dp)) else Modifier)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 12.sp, lineHeight = 12.sp, fontWeight = FontWeight.Medium, color = fg, maxLines = 1)
    }
}

/** «1 позиції», «5 позицій». */
private fun positions(n: Int) = "$n " + if (n % 10 == 1 && n % 100 != 11) "позиції" else "позицій"

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OrderRow(o: PickupOrder, onClick: () -> Unit) {
    val m = Mine.colors
    Row(
        Modifier.fillMaxWidth().background(m.card).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("№${o.number}", Modifier.weight(1f), fontSize = 15.sp, lineHeight = 19.5.sp, fontWeight = FontWeight.Medium, color = m.text)
                Text(money(o.total), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.15).sp, color = m.text)
            }
            Text(
                "${o.customer} · оформлено о ${o.createdAt} · ${goods(o.items)}",
                fontSize = 13.sp, color = m.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            FlowRow(
                Modifier.padding(top = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (o.paid) OrderPill("Оплачено", PillTone.OK) else OrderPill("Оплата при отриманні")
                if (o.missing > 0) OrderPill("${positions(o.missing)} не вистачає", PillTone.WARN)
                if (o.stage == OrderStage.PICKING) OrderPill("${o.picked} з ${o.items} зібрано")
            }
        }
        Icon(painterResource(R.drawable.ic_redesign_chevron_right), null, Modifier.size(18.dp), tint = m.textTertiary)
    }
}

