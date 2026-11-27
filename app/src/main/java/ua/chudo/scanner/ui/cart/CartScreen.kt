package ua.chudo.scanner.ui.cart

import android.view.WindowManager
import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ua.chudo.scanner.R
import ua.chudo.scanner.chudoApp
import ua.chudo.scanner.data.Cart
import ua.chudo.scanner.data.TerminalPaymentDraft
import ua.chudo.scanner.data.CartLine
import ua.chudo.scanner.print.Ean13
import ua.chudo.scanner.ui.common.ProductImage
import ua.chudo.scanner.ui.theme.Mine
import ua.chudo.scanner.ui.theme.PriceStyle
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

    Column(Modifier.fillMaxSize().background(m.bg).statusBarsPadding()) {
        CartHeader(Cart.units(lines), onBack, if (lines.isNotEmpty()) ({ confirmClear = true }) else null)
        if (lines.isEmpty()) {
            EmptyCart(onScan)
        } else {
            LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 16.dp),
            ) {
                item { HorizontalDivider(color = m.line) }
                items(lines, key = { it.barcode }) { line ->
                    CartProductRow(line, onQty, onRemove, onRetry)
                    HorizontalDivider(color = m.line)
                }
                item { CartTotals(lines) }
            }
            CartActionBar(
                onScan, onCashier = { onShowCashier(); cashier = true },
                cashierReady = lines.any { !it.loading }, onTerminal = onTerminal, terminalReady = terminalReady,
            )
        }
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
private fun CartHeader(units: Int, onBack: () -> Unit, onClear: (() -> Unit)?) {
    val m = Mine.colors
    Row(
        Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
            Icon(painterResource(R.drawable.ic_redesign_chevron_left), "Назад", Modifier.size(24.dp), tint = m.textSecondary)
        }
        Column(Modifier.weight(1f).padding(start = 4.dp)) {
            Text("Кошик покупця", fontSize = 17.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, color = m.text)
            if (units > 0) Text("$units шт", fontSize = 12.sp, lineHeight = 14.4.sp, color = m.textTertiary)
        }
        if (onClear != null) {
            Box(Modifier.height(36.dp).clickable(onClick = onClear).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                Text("Очистити", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = m.link)
            }
        }
    }
}

@Composable
private fun EmptyCart(onScan: () -> Unit) {
    val m = Mine.colors
    Column(
        Modifier.fillMaxWidth().padding(start = 20.dp, top = 80.dp, end = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(48.dp).background(m.surface, CircleShape).border(1.dp, m.line, CircleShape), contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_redesign_shopping_cart), null, Modifier.size(20.dp), tint = m.textSecondary)
        }
        Text("Кошик порожній", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = m.text)
        Text(
            "Скануйте товари підряд — сума порахується сама, ще до каси.",
            Modifier.widthIn(max = 240.dp), textAlign = TextAlign.Center, fontSize = 14.sp, color = m.textSecondary,
        )
        CartButton("Сканувати товари", onScan, icon = R.drawable.ic_redesign_scan_barcode, primary = true)
    }
}

@Composable
private fun CartProductRow(line: CartLine, onQty: (String, Int) -> Unit, onRemove: (String) -> Unit, onRetry: (String) -> Unit) {
    val m = Mine.colors
    Row(
        Modifier.fillMaxWidth().background(m.card).padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        val shape = RoundedCornerShape(6.dp)
        Surface(Modifier.size(48.dp), shape = shape, color = m.surface, border = BorderStroke(1.dp, m.line)) {
            if (!line.image.isNullOrBlank()) ProductImage(line.image, Modifier.fillMaxSize())
            else Box(contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_redesign_package), null, Modifier.size(22.dp), tint = m.textTertiary)
            }
        }
        Box(Modifier.weight(1f)) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (line.loading) {
                    Row(Modifier.padding(end = 28.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = m.accent)
                        Text("Шукаю ${line.barcode}…", fontSize = 15.sp, color = m.textSecondary, maxLines = 2)
                    }
                } else {
                    Text(
                        line.name, Modifier.padding(end = 28.dp), fontSize = 15.sp, lineHeight = 19.5.sp,
                        fontWeight = FontWeight.Medium, color = m.text, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        listOf(ids(line), line.priceKop?.let { "${cartPrice(it)} / шт" }.orEmpty())
                            .filter(String::isNotBlank).joinToString(" · "),
                        fontSize = 13.sp, lineHeight = 19.5.sp, color = m.textTertiary,
                    )
                    if (Cart.overStock(line)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(painterResource(R.drawable.ic_redesign_triangle_alert), null, Modifier.size(16.dp), tint = m.warningText)
                            Text("За 1С у магазині ${QTY.format(line.inShop ?: 0.0)} шт", fontSize = 12.sp, color = m.warningText)
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CartQuantity(line.qty) { onQty(line.barcode, it) }
                        val sum = Cart.lineSum(line)
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                            if (sum != null) {
                                Text(cartPrice(sum), style = PriceStyle.copy(fontSize = 16.sp), color = m.text, textAlign = TextAlign.End)
                                line.regularKop?.takeIf { it > (line.priceKop ?: Long.MAX_VALUE) }?.let {
                                    Text(cartPrice(it * line.qty), fontSize = 13.sp, color = m.textTertiary, textDecoration = TextDecoration.LineThrough)
                                }
                            } else if (!line.failed) Text("Немає ціни на сайті", fontSize = 13.sp, color = m.warningText, textAlign = TextAlign.End)
                        }
                    }
                    if (line.failed) Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Не вдалося завантажити товар", Modifier.weight(1f), fontSize = 13.sp, color = m.warningText)
                        TextButton(onClick = { onRetry(line.barcode) }) { Text("Повторити", color = m.link) }
                    }
                }
            }
            IconButton(onClick = { onRemove(line.barcode) }, modifier = Modifier.align(Alignment.TopEnd).size(28.dp)) {
                Icon(painterResource(R.drawable.ic_redesign_x), "Прибрати", Modifier.size(18.dp), tint = m.textSecondary)
            }
        }
    }
}

@Composable
private fun CartQuantity(quantity: Int, onChange: (Int) -> Unit) {
    val m = Mine.colors
    Row(
        Modifier.height(36.dp).border(1.dp, m.outline, RoundedCornerShape(6.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        QuantityButton(R.drawable.ic_redesign_minus, "Менше", quantity > 1) { onChange(quantity - 1) }
        Text(quantity.toString(), Modifier.widthIn(min = 36.dp), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = m.text, textAlign = TextAlign.Center)
        QuantityButton(R.drawable.ic_redesign_plus, "Більше", quantity < Cart.MAX_QTY) { onChange(quantity + 1) }
    }
}

@Composable
private fun QuantityButton(@DrawableRes icon: Int, label: String, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(width = 36.dp, height = 34.dp)) {
        val m = Mine.colors
        Icon(painterResource(icon), label, Modifier.size(16.dp), tint = if (enabled) m.text else m.textTertiary)
    }
}

private val QTY = DecimalFormat("#,##0.###")

private fun ids(line: CartLine) = listOfNotNull(
    line.article.ifBlank { null }?.let { "арт. $it" }, line.code1C.ifBlank { null }, line.barcode,
).joinToString(" · ")

private fun cartPrice(kop: Long): String = Cart.money(kop)

@Composable
private fun CartTotals(lines: List<CartLine>) {
    val m = Mine.colors
    val total = Cart.total(lines)
    val savings = Cart.savings(lines)
    val unpriced = Cart.unpriced(lines)
    val loading = lines.count { it.loading }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Без знижок", fontSize = 15.sp, color = m.textSecondary)
            Text(cartPrice(total + savings), fontSize = 15.sp, color = m.text)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Знижка", fontSize = 15.sp, color = m.textSecondary)
            Text("−${cartPrice(savings)}", fontSize = 15.sp, color = m.onDanger)
        }
        HorizontalDivider(Modifier.padding(top = 4.dp), color = m.line)
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Разом", Modifier.alignByBaseline(), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = m.text)
            Text(Cart.money(total), Modifier.alignByBaseline(), style = PriceStyle.copy(fontSize = 26.sp), color = m.text)
        }
        Text("Ціни з сайту мережі. Остаточна сума — на касі.", fontSize = 13.sp, color = m.textTertiary)
        when {
            loading > 0 -> Text("Шукаю ціну ще для $loading товар(ів)…", fontSize = 13.sp, color = m.textSecondary)
            unpriced > 0 -> Text("У сумі не враховано товари без ціни: $unpriced", fontSize = 13.sp, color = m.warningText)
        }
    }
}

@Composable
private fun CartActionBar(
    onScan: () -> Unit,
    onCashier: () -> Unit,
    cashierReady: Boolean,
    onTerminal: (() -> Unit)?,
    terminalReady: Boolean,
) {
    val m = Mine.colors
    Column(Modifier.fillMaxWidth().background(m.card).navigationBarsPadding()) {
        HorizontalDivider(color = m.line)
        Column(
            Modifier.padding(start = 16.dp, top = 10.dp, end = 16.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CartButton("Сканувати ще", onScan, Modifier.weight(1f), icon = R.drawable.ic_redesign_scan_barcode)
                CartButton("Показати касиру", onCashier, Modifier.weight(1f), primary = true, enabled = cashierReady)
            }
            if (onTerminal != null) CartButton("Сума для термінала", onTerminal, Modifier.fillMaxWidth(), enabled = terminalReady)
        }
    }
}

@Composable
private fun CartButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    primary: Boolean = false,
    enabled: Boolean = true,
) {
    val m = Mine.colors
    Surface(
        onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 44.dp),
        shape = RoundedCornerShape(6.dp), color = if (primary && enabled) m.accent else m.card,
        contentColor = if (!enabled) m.textTertiary else if (primary) m.onAccent else m.text,
        border = if (primary && enabled) null else BorderStroke(1.dp, m.outline),
    ) {
        Row(
            Modifier.heightIn(min = 44.dp).padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) Icon(painterResource(icon), null, Modifier.size(20.dp))
            Text(label, fontSize = 15.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
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
        CashierContent(
            line = l, index = idx, count = lines.size, onClose = onClose,
            onPrevious = if (idx > 0) ({ i = idx - 1 }) else null,
            onNext = if (idx < lines.lastIndex) ({ i = idx + 1 }) else null,
        )
    }
}

@Composable
internal fun CashierContent(
    line: CartLine,
    index: Int,
    count: Int,
    onClose: () -> Unit,
    onPrevious: (() -> Unit)?,
    onNext: (() -> Unit)?,
) {
    val ink = Color(0xFF111111)
    Column(Modifier.fillMaxSize().background(Color.White).statusBarsPadding().navigationBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose, modifier = Modifier.size(40.dp)) {
                Icon(painterResource(R.drawable.ic_redesign_x), "Закрити", Modifier.size(24.dp), tint = ink)
            }
            Text("Для каси · ${index + 1} з $count", Modifier.padding(start = 8.dp), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = ink)
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterVertically),
        ) {
            Text(
                line.name, Modifier.widthIn(max = 260.dp), fontSize = 17.sp, lineHeight = 22.1.sp, fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center, color = ink, maxLines = 3, overflow = TextOverflow.Ellipsis,
            )
            Column(Modifier.widthIn(max = 352.dp).fillMaxWidth().padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                BigBarcode(line.barcode, Modifier.fillMaxWidth().height(130.dp))
                Text(line.barcode, Modifier.padding(top = 4.dp), fontSize = 30.sp, fontWeight = FontWeight.Medium, color = ink, textAlign = TextAlign.Center, letterSpacing = 3.sp, maxLines = 1)
            }
            Text("× ${line.qty} шт", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = ink)
            Text("Яскравість екрана піднято до максимуму", fontSize = 13.sp, color = Color(0xFF555555), textAlign = TextAlign.Center)
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 34.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CashierButton("Назад", Modifier.weight(1f), onPrevious, R.drawable.ic_redesign_chevron_left)
            CashierButton("Далі", Modifier.weight(1f), onNext, R.drawable.ic_redesign_chevron_right, next = true)
        }
    }
}

@Composable
private fun CashierButton(
    label: String,
    modifier: Modifier,
    action: (() -> Unit)?,
    @DrawableRes icon: Int,
    next: Boolean = false,
) {
    val enabled = action != null
    Surface(
        onClick = { action?.invoke() }, enabled = enabled, modifier = modifier.height(52.dp), shape = RoundedCornerShape(6.dp),
        color = if (next && enabled) Color(0xFF111111) else Color(0xFFEEF0F3),
        contentColor = if (!enabled) Color(0xFF666C77) else if (next) Color.White else Color(0xFF111111),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
            if (!next) Icon(painterResource(icon), null, Modifier.size(20.dp))
            Text(label, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            if (next) Icon(painterResource(icon), null, Modifier.size(20.dp))
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
