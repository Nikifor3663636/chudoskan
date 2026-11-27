package ua.chudo.scanner.ui.orders

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.QrCodeScanner
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
import ua.chudo.scanner.ui.common.MineHeader
import ua.chudo.scanner.ui.common.TabletPanes
import ua.chudo.scanner.ui.common.MinePrimaryButton
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

    // Код со сканера: +1 к строке собираемого заказа.
    LaunchedEffect(scanned) {
        val code = scanned ?: return@LaunchedEffect
        onScannedConsumed()
        val msg = when (val r = repo.scan(code)) {
            is ScanResult.Added -> "+1 · ${r.name} (${r.order.picked} з ${r.order.items})"
            is ScanResult.Completed -> "Замовлення № ${r.order.number} зібрано".also { message ->
                if (!repo.demo) app.prefs.addActivity(employee, ua.chudo.scanner.data.ActivityKind.ORDER, message, goods(r.order.items))
            }
            ScanResult.NotInOrder -> "Цього товару немає в замовленні"
            ScanResult.AlreadyEnough -> "Цей товар уже зібрано повністю"
            ScanResult.NothingPicking -> "Спочатку обери замовлення в «Нові»"
        }
        snackbar.showSnackbar(msg)
    }

    val ui = OrdersUi(shop = employee.shop, orders = orders, tab = tab, demo = repo.demo, activeNumber = activeNumber)
    val openOrder: (PickupOrder) -> Unit = { order ->
        when (order.stage) {
            OrderStage.NEW, OrderStage.PICKING -> { repo.start(order.number); tab = OrderStage.PICKING }
            OrderStage.READY -> issue = order
        }
    }
    Box(Modifier.fillMaxSize()) {
        if (tablet) TabletOrders(ui, { tab = it }, openOrder, onScan)
        else OrdersContent(ui, onBack, { tab = it }, openOrder, onScan)
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp))
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

private fun money(v: Double) = "${PriceFormat.tag(v).first} ₴"

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
    val current = ui.orders.firstOrNull { it.stage == OrderStage.PICKING && it.number == ui.activeNumber }
    val list = ui.orders.filter { it.stage == ui.tab && it != current }
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 20.dp)) {
        item {
            MineHeader("Замовлення", onBack)
            Text(ui.shop.ifEmpty { "Мій магазин" }, Modifier.padding(top = 4.dp), fontSize = 15.sp, color = m.textSecondary)
            if (ui.demo) {
                Surface(Modifier.padding(top = 12.dp), shape = RoundedCornerShape(12.dp), color = m.accentTone, contentColor = m.accent) {
                    Text(
                        "Демо-дані: замовлення з 1С ще не підключені. Можна спробувати збирання сканером.",
                        Modifier.padding(horizontal = 12.dp, vertical = 8.dp), fontSize = 13.sp, lineHeight = 18.sp,
                    )
                }
            }
            Segments(ui, onTab)
        }
        current?.let { o -> item { PickingCard(o, onScan) } }
        items(list, key = { it.number }) { o ->
            OrderRow(o, onClick = { onOrder(o) })
            HorizontalDivider(color = m.line)
        }
        if (list.isEmpty() && current == null) item {
            Text(
                when (ui.tab) {
                    OrderStage.NEW -> "Нових замовлень немає."
                    OrderStage.PICKING -> "Обери замовлення у вкладці «Нові»."
                    OrderStage.READY -> "Готових до видачі замовлень немає."
                },
                Modifier.padding(vertical = 16.dp), fontSize = 15.sp, color = m.textSecondary,
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
                        shape = RoundedCornerShape(6.dp), color = m.accent, contentColor = m.onAccent,
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
        Modifier.padding(top = 20.dp, bottom = 8.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(track(m)).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        OrderStage.entries.forEach { s ->
            val selected = s == ui.tab
            val count = ui.orders.count { it.stage == s }
            Box(
                Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(12.dp))
                    .background(if (selected) m.card else Color.Transparent).clickable { onTab(s) },
                contentAlignment = Alignment.Center,
            ) {
                Text("${s.title} $count", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = if (selected) m.text else m.textSecondary)
            }
        }
    }
}

@Composable
private fun OrderRow(o: PickupOrder, onClick: () -> Unit) {
    val m = Mine.colors
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("№ ${o.number}", Modifier.weight(1f), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = m.text)
            Text(money(o.total), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = m.text)
        }
        Text("${o.customer}, оформлено о ${o.createdAt}", Modifier.padding(top = 6.dp), fontSize = 15.sp, color = m.textSecondary)
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = if (o.paid) m.success.copy(alpha = 0.16f) else m.field,
                contentColor = if (o.paid) m.success else m.text,
            ) {
                Text(if (o.paid) "Оплачено" else "Оплата при отриманні", Modifier.padding(horizontal = 10.dp, vertical = 4.dp), fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(8.dp))
            when {
                o.stage == OrderStage.READY -> Text("Готове до видачі", fontSize = 14.sp, color = m.textSecondary)
                o.missing > 0 -> Text(
                    "${goods(o.missing)} немає в магазині", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = m.warningText,
                )
                o.lines.all { it.inStock != null } -> Text("${goods(o.items)}, усі є в магазині", fontSize = 14.sp, color = m.textSecondary)
            }
        }
    }
}

/** «Збираю зараз»: прогресс и сканер. */
@Composable
private fun PickingCard(o: PickupOrder, onScan: () -> Unit) {
    val m = Mine.colors
    Surface(
        Modifier.padding(top = 16.dp).fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = m.card, contentColor = m.text,
        border = androidx.compose.foundation.BorderStroke(1.dp, m.line),
    ) {
        Column(Modifier.padding(18.dp)) {
            Row {
                Text("Збираю зараз", Modifier.weight(1f), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = m.textSecondary)
                Text("№ ${o.number}", fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.Bottom) {
                Text("${o.picked}", fontSize = 40.sp, lineHeight = 44.sp, fontWeight = FontWeight.Black)
                Text(" з ${goods(o.items)}", Modifier.padding(bottom = 6.dp), fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            Box(Modifier.padding(top = 10.dp).fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(track(m))) {
                Box(
                    Modifier.fillMaxWidth(if (o.items == 0) 0f else o.picked.toFloat() / o.items).height(8.dp)
                        .clip(RoundedCornerShape(4.dp)).background(m.accent),
                )
            }
            MinePrimaryButton("Сканувати наступний товар", onScan, Modifier.padding(top = 16.dp), icon = Icons.Rounded.QrCodeScanner)
            Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                o.lines.forEach { l ->
                    Row {
                        Text(l.name, Modifier.weight(1f), fontSize = 14.sp, maxLines = 1, color = if (l.done) m.textSecondary else m.text)
                        Text("${minOf(l.picked, l.qty)}/${l.qty}", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = if (l.done) m.success else m.text)
                    }
                }
            }
        }
    }
}

/** Доріжка перемикача й прогресу: у світлій темі сіра (білий вибраний сегмент на ній видно), у темній — поверхня. */
private fun track(m: ua.chudo.scanner.ui.theme.MineColors) = if (m.dark) m.surface else Color(0xFFECEEF1)
