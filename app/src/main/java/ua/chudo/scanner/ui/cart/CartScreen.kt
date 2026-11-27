package ua.chudo.scanner.ui.cart

import android.view.WindowManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ShoppingCart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ua.chudo.scanner.chudoApp
import ua.chudo.scanner.data.Cart
import ua.chudo.scanner.data.TerminalPaymentDraft
import ua.chudo.scanner.data.CartLine
import ua.chudo.scanner.print.Ean13
import ua.chudo.scanner.ui.common.MessageState
import ua.chudo.scanner.ui.common.MineButton
import ua.chudo.scanner.ui.common.MineCard
import ua.chudo.scanner.ui.common.MineHeader
import ua.chudo.scanner.ui.common.MinePrimaryButton
import ua.chudo.scanner.ui.common.MineStepper
import ua.chudo.scanner.ui.common.ProductImage
import ua.chudo.scanner.ui.theme.Mine
import java.text.DecimalFormat

/**
 * «Кошик»: продавець сканує товари покупця підряд → список, кількість, ціни з сайту й сума.
 * Покупець платить на касі; «Показати касиру» — штрихкоди по одному, щоб касир не шукав товари.
 * Далі тут з'явиться «Оплатити» (ПРРО) — суми вже в копійках.
 */
@Composable
fun CartScreen(employee: ua.chudo.scanner.data.Employee, onBack: () -> Unit, onScan: () -> Unit) {
    val app = LocalContext.current.chudoApp
    val cart = app.cart
    val lines by cart.lines.collectAsStateWithLifecycle()
    var payment by remember { mutableStateOf<TerminalPaymentDraft?>(null) }
    val available = remember(lines) { TerminalPaymentDraft.fromCart(lines) }
    CartContent(
        lines, onBack = onBack, onScan = onScan,
        onQty = cart::setQty, onRemove = cart::remove, onRetry = cart::retry, onClear = cart::clear,
        onTerminal = { payment = available }, terminalReady = available != null,
        onShowCashier = {
            app.prefs.addActivity(employee, ua.chudo.scanner.data.ActivityKind.CART, "Кошик показано касиру",
                "${Cart.units(lines)} шт · ${Cart.money(Cart.total(lines))}")
        },
    )
    payment?.let { draft ->
        AlertDialog(
            onDismissRequest = { payment = null },
            title = { Text("Сума для термінала") },
            text = { Text("${Cart.money(draft.amountKop)} · очікує оплати\nПроведіть оплату на терміналі магазину.") },
            confirmButton = { TextButton(onClick = { payment = null }) { Text("Закрити") } },
        )
    }
}

@Composable
fun CartContent(
    lines: List<CartLine>,
    onBack: () -> Unit = {},
    onScan: () -> Unit = {},
    onQty: (String, Int) -> Unit = { _, _ -> },
    onRemove: (String) -> Unit = {},
    onRetry: (String) -> Unit = {},
    onClear: () -> Unit = {},
    onTerminal: (() -> Unit)? = null,
    terminalReady: Boolean = false,
    onShowCashier: () -> Unit = {},
) {
    val m = Mine.colors
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    var cashier by rememberSaveable { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Box(Modifier.padding(horizontal = 8.dp)) {
            MineHeader("Кошик", onBack) {
                if (lines.isNotEmpty()) TextButton(onClick = { confirmClear = true }) { Text("Очистити", color = m.link, fontSize = 15.sp) }
            }
        }
        if (lines.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                MessageState(
                    Icons.Rounded.ShoppingCart, "Кошик порожній",
                    "Скануй товари покупця підряд — тут буде список і сума до оплати.",
                    primary = "Сканувати товари" to onScan,
                )
            }
            return@Column
        }
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(lines, key = { it.barcode }) { l -> LineCard(l, onQty, onRemove, onRetry) }
        }
        Footer(lines, onScan, onCashier = { onShowCashier(); cashier = true }, onTerminal = onTerminal, terminalReady = terminalReady)
    }

    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Очистити кошик?") },
        text = { Text("Усі ${lines.size} товарів буде прибрано.") },
        confirmButton = { TextButton(onClick = { confirmClear = false; onClear() }) { Text("Очистити") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Скасувати") } },
    )
    if (cashier && lines.isNotEmpty()) CashierView(lines.filter { !it.loading }, onClose = { cashier = false })
}

@Composable
private fun LineCard(l: CartLine, onQty: (String, Int) -> Unit, onRemove: (String) -> Unit, onRetry: (String) -> Unit) {
    val m = Mine.colors
    MineCard(padding = 14.dp) {
        Row(verticalAlignment = Alignment.Top) {
            ProductImage(l.image, Modifier.size(56.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                if (l.loading) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = m.accent)
                        Spacer(Modifier.width(8.dp))
                        Text("Шукаю ${l.barcode}…", fontSize = 15.sp, color = m.textSecondary)
                    }
                } else {
                    Text(l.name, fontSize = 16.sp, lineHeight = 21.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(ids(l), fontSize = 13.sp, color = m.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            IconButton(onClick = { onRemove(l.barcode) }) { Icon(Icons.Rounded.DeleteOutline, "Прибрати", tint = m.textSecondary) }
        }
        if (!l.loading) {
            Row(Modifier.padding(top = 10.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                MineStepper(l.qty, { onQty(l.barcode, it) }, range = 1..Cart.MAX_QTY)
                Spacer(Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.End) {
                    val sum = Cart.lineSum(l)
                    if (sum != null) {
                        Text(Cart.money(sum), fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            l.regularKop?.let {
                                Text(Cart.money(it), fontSize = 13.sp, color = m.textSecondary, textDecoration = TextDecoration.LineThrough)
                                Spacer(Modifier.width(6.dp))
                            }
                            if (l.qty > 1) Text("${Cart.money(l.priceKop!!)} / шт", fontSize = 13.sp, color = m.textSecondary)
                        }
                    } else if (!l.failed) Text("Немає ціни на сайті", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = m.warningText)
                }
            }
            when {
                l.failed -> Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Не вдалося завантажити товар", Modifier.weight(1f), fontSize = 14.sp, color = m.warningText)
                    TextButton(onClick = { onRetry(l.barcode) }) { Text("Повторити", color = m.link) }
                }
                Cart.overStock(l) -> Text(
                    "За 1С у магазині лише ${QTY.format(l.inShop ?: 0.0)} шт",
                    Modifier.padding(top = 6.dp), fontSize = 14.sp, color = m.warningText,
                )
            }
        }
    }
}

private val QTY = DecimalFormat("#,##0.###")

private fun ids(l: CartLine) = listOfNotNull(
    l.article.ifBlank { null }?.let { "Арт. $it" }, l.code1C.ifBlank { null }, l.barcode,
).joinToString(" · ")

@Composable
private fun Footer(lines: List<CartLine>, onScan: () -> Unit, onCashier: () -> Unit, onTerminal: (() -> Unit)?, terminalReady: Boolean) {
    val m = Mine.colors
    val unpriced = Cart.unpriced(lines)
    val loading = lines.count { it.loading }
    val savings = Cart.savings(lines)
    Surface(color = m.card, contentColor = m.text, shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp), border = BorderStroke(1.dp, m.line)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 18.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text("Разом · ${Cart.units(lines)} шт", fontSize = 15.sp, color = m.textSecondary)
                    if (savings > 0) Text("Знижка ${Cart.money(savings)}", fontSize = 14.sp, color = m.success, fontWeight = FontWeight.Bold)
                }
                Text(Cart.money(Cart.total(lines)), fontSize = 30.sp, fontWeight = FontWeight.Black)
            }
            when {
                loading > 0 -> Text("Шукаю ціну ще для $loading товар(ів)…", Modifier.padding(top = 4.dp), fontSize = 14.sp, color = m.textSecondary)
                unpriced > 0 -> Text("У сумі не враховано товари без ціни: $unpriced", Modifier.padding(top = 4.dp), fontSize = 14.sp, color = m.warningText)
            }
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MineButton("Показати касиру", onCashier, Modifier.weight(1f), enabled = lines.any { !it.loading })
                MinePrimaryButton("Сканувати ще", onScan, Modifier.weight(1f), height = 48.dp)
            }
            onTerminal?.let { MineButton("Сума для термінала", it, Modifier.padding(top = 8.dp), enabled = terminalReady) }
        }
    }
}

/**
 * Для каси: штрихкоди по одному на весь екран (яскравість — на максимум), кількість великим шрифтом.
 * Касир сканує код своїм сканером і вводить кількість — без пошуку товарів.
 */
@Composable
private fun CashierView(lines: List<CartLine>, onClose: () -> Unit) {
    var i by rememberSaveable { mutableIntStateOf(0) }
    if (lines.isEmpty()) { onClose(); return }
    val idx = i.coerceIn(0, lines.lastIndex)
    val l = lines[idx]
    val activity = androidx.activity.compose.LocalActivity.current
    DisposableEffect(activity) {
        val w = activity?.window
        val before = w?.attributes?.screenBrightness
        w?.let { it.attributes = it.attributes.apply { screenBrightness = 1f }; it.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        onDispose {
            w?.let { it.attributes = it.attributes.apply { screenBrightness = before ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }; it.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // Білий фон незалежно від теми — так сканер каси читає надійніше.
        Column(Modifier.fillMaxSize().background(Color.White).statusBarsPadding().navigationBarsPadding().padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Товар ${idx + 1} з ${lines.size}", Modifier.weight(1f), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Закрити", tint = Color.Black) }
            }
            Spacer(Modifier.weight(0.4f))
            BigBarcode(l.barcode, Modifier.fillMaxWidth().height(180.dp))
            Text(l.barcode, Modifier.fillMaxWidth().padding(top = 8.dp), fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.Black, textAlign = TextAlign.Center, letterSpacing = 2.sp)
            Text("× ${l.qty} шт", Modifier.fillMaxWidth().padding(top = 18.dp), fontSize = 56.sp, fontWeight = FontWeight.Black, color = Color.Black, textAlign = TextAlign.Center)
            Text(l.name, Modifier.fillMaxWidth().padding(top = 8.dp), fontSize = 17.sp, color = Color(0xFF444444), textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Cart.lineSum(l)?.let { Text(Cart.money(it), Modifier.fillMaxWidth().padding(top = 4.dp), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.Black, textAlign = TextAlign.Center) }
            Spacer(Modifier.weight(1f))
            Text("Разом: ${Cart.money(Cart.total(lines))}", Modifier.fillMaxWidth(), fontSize = 18.sp, color = Color.Black, textAlign = TextAlign.Center)
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NavButton("Назад", Icons.AutoMirrored.Rounded.ArrowBack, enabled = idx > 0, Modifier.weight(1f)) { i = idx - 1 }
                if (idx < lines.lastIndex) NavButton("Далі", Icons.AutoMirrored.Rounded.ArrowForward, enabled = true, Modifier.weight(1f)) { i = idx + 1 }
                else NavButton("Готово", null, enabled = true, Modifier.weight(1f), onClick = onClose)
            }
        }
    }
}

@Composable
private fun NavButton(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector?, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick, enabled = enabled, modifier = modifier.height(60.dp), shape = RoundedCornerShape(30.dp),
        color = if (enabled) Mine.colors.accent else Color(0xFFE6E6E6), contentColor = if (enabled) Mine.colors.onAccent else Color(0xFF999999),
    ) {
        Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            if (icon != null && text == "Назад") { Icon(icon, null); Spacer(Modifier.width(8.dp)) }
            Text(text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            if (icon != null && text != "Назад") { Spacer(Modifier.width(8.dp)); Icon(icon, null) }
        }
    }
}

/** EAN-13 (та сама розкладка, що на цінниках); інший формат коду — лише цифри під ним. */
@Composable
private fun BigBarcode(raw: String, modifier: Modifier) {
    val code = Ean13.normalize(raw)
    if (code == null) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text("Код не EAN-13 — касир вводить цифри вручну", fontSize = 16.sp, color = Color(0xFF666666), textAlign = TextAlign.Center)
        }
        return
    }
    val bars = Ean13.bars(code, 1)
    val total = Ean13.lengthDots(1).toFloat()
    Canvas(modifier) {
        val k = size.width / total
        for (r in bars) drawRect(Color.Black, Offset(r.first * k, 0f), Size((r.last + 1 - r.first) * k, size.height))
    }
}
