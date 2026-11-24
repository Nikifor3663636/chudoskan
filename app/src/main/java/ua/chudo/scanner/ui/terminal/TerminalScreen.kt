package ua.chudo.scanner.ui.terminal

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ua.chudo.scanner.chudoApp
import ua.chudo.scanner.data.Employee
import ua.chudo.scanner.data.Stock1C
import ua.chudo.scanner.payment.PayOutcome
import ua.chudo.scanner.payment.PaymentService
import ua.chudo.scanner.terminal.Cart
import ua.chudo.scanner.terminal.SaleStatus
import ua.chudo.scanner.terminal.CartItem
import ua.chudo.scanner.terminal.Discount
import ua.chudo.scanner.terminal.Found
import ua.chudo.scanner.terminal.Money
import ua.chudo.scanner.terminal.Sale
import ua.chudo.scanner.terminal.SaleCode
import ua.chudo.scanner.terminal.SalesRepository
import ua.chudo.scanner.terminal.SiteItem
import ua.chudo.scanner.terminal.TerminalProducts
import ua.chudo.scanner.ui.auth.authMessage
import ua.chudo.scanner.ui.common.BarcodeScanIcon
import ua.chudo.scanner.ui.common.MineButton
import ua.chudo.scanner.ui.common.MineCard
import ua.chudo.scanner.ui.common.MineChip
import ua.chudo.scanner.ui.common.MinePrimaryButton
import ua.chudo.scanner.ui.common.MineSearchField
import ua.chudo.scanner.ui.theme.Mine
import uniffi.chudo_core.ProductSummary

/** Що зараз показує «Термінал» поверх кошика. */
sealed interface TerminalState {
    data object Idle : TerminalState
    data class Loading(val step: String) : TerminalState
    data class Choose(val found: Found.Choose) : TerminalState
    /** Ціна вручну для товару без ціни на сайті. */
    data class AskPrice(val item: CartItem) : TerminalState
    data class Message(val text: String) : TerminalState
    data object Sending : TerminalState
    data class Sent(val sale: Sale) : TerminalState
    /** Застосунок банку відкрито — кошик зафіксовано. */
    data object Paying : TerminalState
    data class Paid(val totalKop: Long) : TerminalState
}

class TerminalViewModel(
    private val employee: Employee,
    private val products: TerminalProducts,
    private val sales: SalesRepository?,
    private val payments: PaymentService? = null,
) : ViewModel() {
    private val _cart = MutableStateFlow(Cart())
    val cart = _cart.asStateFlow()
    private val _state = MutableStateFlow<TerminalState>(TerminalState.Idle)
    val state = _state.asStateFlow()

    /** Оплата карткою підключена в магазині (shops/{shopId}.payment.provider ≠ "none"). */
    private val _card = MutableStateFlow(false)
    val card = _card.asStateFlow()

    /** Банк не відповів: продаж лишається "paying", кошик не змінюємо, кнопка — «Перевірити оплату». */
    private val _checkBank = MutableStateFlow(false)
    val checkBank = _checkBank.asStateFlow()

    /** Продаж для поточного кошика (створюється під час першої оплати); зміна кошика — новий продаж. */
    private var saleId: String? = null

    /** Під час оплати й поки невідомо, чи списано гроші, склад кошика не змінюється. */
    private val locked get() = _state.value is TerminalState.Paying || _checkBank.value

    init {
        payments?.let { p -> viewModelScope.launch { _card.value = runCatching { p.provider(employee.shopId) }.getOrNull() != null } }
    }

    private fun edit(f: (Cart) -> Cart) {
        if (locked) return
        _cart.value = f(_cart.value)
        saleId = null
    }

    /** «Оплатити карткою» (або «Перевірити оплату» після збою): ключів банку в застосунку немає. */
    fun payCard() {
        val p = payments ?: return
        val repo = sales ?: return
        val cart = _cart.value
        if (cart.items.isEmpty() || _state.value is TerminalState.Paying) return
        if (cart.items.any { it.manualPrice && it.priceKop == 0L }) {
            _state.value = TerminalState.Message("Вкажіть ціну для всіх товарів з позначкою «ціна вручну».")
            return
        }
        _state.value = TerminalState.Paying
        guard {
            val flow = p.flow(employee.shopId) ?: error("Оплату карткою в магазині не підключено")
            val id = saleId ?: repo.send(employee.shopId, employee.uid, cart, SaleStatus.SENT).id.also { saleId = it }
            _state.value = when (val r = flow.pay(id)) {
                is PayOutcome.Paid -> { _checkBank.value = false; TerminalState.Paid(cart.totalKop) }
                is PayOutcome.Declined -> { _checkBank.value = false; TerminalState.Message("Оплату відхилено: ${r.reason}. Можна спробувати ще раз.") }
                PayOutcome.Cancelled -> { _checkBank.value = false; TerminalState.Message("Оплату скасовано. Можна спробувати ще раз.") }
                is PayOutcome.CheckBank -> { _checkBank.value = true; TerminalState.Message(r.message) }
            }
        }
    }

    fun find(query: String) {
        val q = query.trim().ifEmpty { return }
        _state.value = TerminalState.Loading("Шукаємо «$q»…")
        guard { handle(products.find(q)) }
    }

    fun pick(p: ProductSummary, barcode: String?) {
        _state.value = TerminalState.Loading("Відкриваємо товар…")
        guard { handle(products.pick(p.urlKey, barcode)) }
    }

    private fun handle(f: Found) {
        _state.value = when (f) {
            is Found.Ready -> { add(f.item); TerminalState.Idle }
            is Found.NeedPrice -> TerminalState.AskPrice(f.item)
            is Found.Choose -> TerminalState.Choose(f)
            is Found.NotFound -> TerminalState.Message("Не знайшли «${f.query}» ні на сайті, ні в 1С.")
        }
    }

    private fun add(item: CartItem) {
        if (locked) { _state.value = TerminalState.Message("Спершу завершіть оплату."); return }
        runCatching { edit { it.add(item) } }
            .onFailure { _state.value = TerminalState.Message(it.message ?: "Не вдалося додати товар") }
    }

    /** Ціна вручну: для нового товару — додає, для наявного — змінює. */
    fun setPrice(item: CartItem, kop: Long) {
        _state.value = TerminalState.Idle
        if (_cart.value.items.any { it.key == item.key }) edit { it.setPrice(item.key, kop) }
        else add(item.copy(priceKop = kop, manualPrice = true))
    }

    /** Змінити ціну рядка «ціна вручну». */
    fun editPrice(item: CartItem) { if (!locked) _state.value = TerminalState.AskPrice(item) }

    fun inc(key: String) = edit { it.inc(key) }
    fun dec(key: String) = edit { it.dec(key) }
    fun remove(key: String) = edit { it.remove(key) }
    fun discount(d: Discount) = edit { it.withDiscount(d) }
    fun dismiss() { if (_state.value !is TerminalState.Paying) _state.value = TerminalState.Idle }

    /** «Передати на касу»: одне записування `sales/{id}` зі status "draft", QR і короткий код. */
    fun send() {
        val repo = sales ?: run { _state.value = TerminalState.Message("Каса недоступна: застосунок не підключено до бази."); return }
        val cart = _cart.value
        if (cart.items.isEmpty()) return
        if (cart.items.any { it.manualPrice && it.priceKop == 0L }) {
            _state.value = TerminalState.Message("Вкажіть ціну для всіх товарів з позначкою «ціна вручну».")
            return
        }
        _state.value = TerminalState.Sending
        guard { _state.value = TerminalState.Sent(repo.send(employee.shopId, employee.uid, cart)) }
    }

    /** Після передачі на касу — новий кошик. */
    fun newCart() { _cart.value = Cart(); saleId = null; _checkBank.value = false; _state.value = TerminalState.Idle }

    private fun guard(block: suspend () -> Unit) = viewModelScope.launch {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value = TerminalState.Message(e.authMessage())
        }
    }
}

/** Вкладка «Термінал»: кошик для покупця; оплата карткою — після підключення банку. */
@Composable
fun TerminalScreen(employee: Employee, onScan: () -> Unit, scanned: String?, onScannedConsumed: () -> Unit) {
    val app = LocalContext.current.chudoApp
    val vm: TerminalViewModel = viewModel(
        key = "terminal-${employee.uid}-${employee.shopId}",
        factory = viewModelFactory {
            initializer {
                val stock = if (app.auth.configured) Stock1C { bc -> app.networkLookup.byBarcode(employee.shopId, bc) } else null
                TerminalViewModel(
                    employee,
                    TerminalProducts(
                        lookup = { q -> app.repository.lookup(q, stock) },
                        site = { key ->
                            val p = app.repository.details(key)
                            SiteItem(p.name, p.article, p.price?.finalPrice?.let(Money::fromUah))
                        },
                        stock = { bc -> stock?.find(bc) },
                    ),
                    if (app.auth.configured) app.sales else null,
                    if (app.auth.configured) app.payments else null,
                )
            }
        },
    )
    val cart by vm.cart.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val card by vm.card.collectAsStateWithLifecycle()
    val checkBank by vm.checkBank.collectAsStateWithLifecycle()
    val noticesFlow = remember { if (app.auth.configured) app.payments.notices else MutableStateFlow(emptyList<String>()) }
    val notices by noticesFlow.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var received by rememberSaveable { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    val m = Mine.colors

    LaunchedEffect(scanned) { scanned?.let { vm.find(it); onScannedConsumed() } }

    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text("Термінал", Modifier.padding(horizontal = 4.dp, vertical = 8.dp), fontSize = 26.sp, fontWeight = FontWeight.Bold) }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MineSearchField(query, { query = it }, "Штрихкод, артикул або назва", {
                    keyboard?.hide(); vm.find(query); query = ""
                }, Modifier.weight(1f))
                Surface(onClick = onScan, modifier = Modifier.size(60.dp), shape = RoundedCornerShape(18.dp), color = m.accentTone, contentColor = m.accent) {
                    Box(contentAlignment = Alignment.Center) { Icon(BarcodeScanIcon, "Сканувати", Modifier.size(28.dp)) }
                }
            }
        }
        (state as? TerminalState.Loading)?.let { s ->
            item {
                Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = m.accent)
                    Spacer(Modifier.width(10.dp))
                    Text(s.step, fontSize = 15.sp, color = m.textSecondary)
                }
            }
        }
        if (cart.items.isEmpty()) {
            item {
                MineCard {
                    Text("Кошик порожній", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Text("Скануйте товар або введіть штрихкод чи артикул.", fontSize = 14.sp, color = m.textSecondary)
                }
            }
        } else {
            items(cart.items, key = { it.key }) { it ->
                CartRow(it, onInc = { vm.inc(it.key) }, onDec = { vm.dec(it.key) }, onRemove = { vm.remove(it.key) }, onPrice = {
                    vm.editPrice(it)
                })
            }
            item { DiscountCard(cart.discount, vm::discount) }
            item { TotalsCard(cart, received) { received = it } }
            item {
                MinePrimaryButton("Передати на касу", vm::send, height = 58.dp, busy = state is TerminalState.Sending)
            }
            item {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    MineButton(
                        if (checkBank) "Перевірити оплату" else "Оплатити карткою", vm::payCard, Modifier.fillMaxWidth(),
                        enabled = card, busy = state is TerminalState.Paying,
                    )
                    if (!card) Text(
                        "Оплата карткою з'явиться після підключення банку",
                        Modifier.padding(top = 6.dp), fontSize = 13.sp, color = m.textSecondary, textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }

    when (val s = state) {
        is TerminalState.Choose -> AlertDialog(
            onDismissRequest = vm::dismiss,
            title = { Text("Оберіть товар") },
            text = {
                LazyColumn {
                    items(s.found.options, key = { it.id }) { p ->
                        Column(Modifier.fillMaxWidth().clickable { vm.pick(p, s.found.barcode) }.padding(vertical = 10.dp)) {
                            Text(p.name, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            p.price?.let { Text(Money.format(Money.fromUah(it.finalPrice)), fontSize = 14.sp, color = m.textSecondary) }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = vm::dismiss) { Text("Скасувати") } },
        )
        is TerminalState.AskPrice -> PriceDialog(s.item, onDone = { vm.setPrice(s.item, it) }, onCancel = vm::dismiss)
        is TerminalState.Message -> AlertDialog(
            onDismissRequest = vm::dismiss,
            text = { Text(s.text, fontSize = 15.sp) },
            confirmButton = { TextButton(onClick = vm::dismiss) { Text("Зрозуміло") } },
        )
        is TerminalState.Sent -> SentDialog(s.sale, onNew = { received = ""; vm.newCart() })
        is TerminalState.Paid -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Оплачено") },
            text = { Text("Карткою: ${Money.format(s.totalKop)}. Чек сформує каса магазину.", fontSize = 15.sp) },
            confirmButton = { TextButton(onClick = { received = ""; vm.newCart() }) { Text("Новий кошик") } },
        )
        else -> if (notices.isNotEmpty()) AlertDialog(
            onDismissRequest = app.payments::dismissNotices,
            title = { Text("Оплата") },
            text = { Text(notices.joinToString("\n\n"), fontSize = 15.sp) },
            confirmButton = { TextButton(onClick = app.payments::dismissNotices) { Text("Зрозуміло") } },
        )
    }
}

@Composable
private fun CartRow(it: CartItem, onInc: () -> Unit, onDec: () -> Unit, onRemove: () -> Unit, onPrice: () -> Unit) {
    val m = Mine.colors
    MineCard(padding = 14.dp) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(it.name, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold)
                Text(listOf(it.code, it.article, it.barcode).filter { s -> s.isNotBlank() }.joinToString(" · "), fontSize = 13.sp, color = m.textSecondary)
            }
            IconButton(onClick = onRemove) { Icon(Icons.Rounded.Close, "Видалити", tint = m.textSecondary) }
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable(enabled = it.manualPrice, onClick = onPrice)) {
                Text(if (it.manualPrice && it.priceKop == 0L) "Вкажіть ціну" else Money.format(it.priceKop), fontSize = 16.sp, fontWeight = FontWeight.Medium,
                    color = if (it.manualPrice && it.priceKop == 0L) m.warningText else m.text)
                if (it.manualPrice) Text("ціна вручну", fontSize = 12.sp, color = m.warningText)
            }
            ua.chudo.scanner.ui.common.MineStepper(it.qty, { q -> if (q > it.qty) onInc() else onDec() }, 1..999)
        }
        Text("Разом: ${Money.format(it.sumKop)}", Modifier.padding(top = 4.dp), fontSize = 14.sp, color = m.textSecondary)
    }
}

@Composable
private fun DiscountCard(d: Discount, onChange: (Discount) -> Unit) {
    var percentMode by rememberSaveable { mutableStateOf(d !is Discount.Amount) }
    var text by rememberSaveable { mutableStateOf("") }
    fun apply(t: String, percent: Boolean) {
        text = t
        onChange(
            when {
                t.isBlank() -> Discount.None
                percent -> t.trim().toIntOrNull()?.takeIf { it in 0..100 }?.let { Discount.Percent(it) } ?: Discount.None
                else -> Money.parse(t)?.let { Discount.Amount(it) } ?: Discount.None
            },
        )
    }
    MineCard(padding = 14.dp) {
        Text("Знижка", fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MineChip("%", percentMode, { percentMode = true; apply(text, true) }, Modifier.width(56.dp))
            MineChip("₴", !percentMode, { percentMode = false; apply(text, false) }, Modifier.width(56.dp))
            OutlinedTextField(
                text, { apply(it, percentMode) }, Modifier.weight(1f), singleLine = true,
                placeholder = { Text(if (percentMode) "0–100" else "сума") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
        }
    }
}

@Composable
private fun TotalsCard(cart: Cart, received: String, onReceived: (String) -> Unit) {
    val m = Mine.colors
    MineCard(padding = 14.dp) {
        Line("Сума", Money.format(cart.subtotalKop))
        if (cart.discountKop > 0) Line("Знижка", "−" + Money.format(cart.discountKop))
        Line("До сплати", Money.format(cart.totalKop), bold = true)
        Spacer(Modifier.height(10.dp))
        Text("Готівка", fontSize = 15.sp, fontWeight = FontWeight.Bold)
        OutlinedTextField(
            received, onReceived, Modifier.fillMaxWidth(), singleLine = true,
            label = { Text("Отримано") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        )
        Money.parse(received)?.let { kop ->
            val change = cart.changeKop(kop)
            Spacer(Modifier.height(6.dp))
            if (change != null) Line("Решта", Money.format(change), bold = true)
            else Text("Отримано менше, ніж до сплати", fontSize = 14.sp, color = m.warningText)
        }
    }
}

@Composable
private fun Line(label: String, value: String, bold: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, Modifier.weight(1f), fontSize = if (bold) 18.sp else 15.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)
        Text(value, fontSize = if (bold) 18.sp else 15.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium)
    }
}

@Composable
private fun PriceDialog(item: CartItem, onDone: (Long) -> Unit, onCancel: () -> Unit) {
    var text by remember(item.key) { mutableStateOf(if (item.priceKop > 0) Money.format(item.priceKop).removeSuffix(" ₴") else "") }
    val kop = Money.parse(text)
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Ціна вручну") },
        text = {
            Column {
                Text(item.name, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text("Ціни на сайті немає — вкажіть ціну з цінника.", fontSize = 13.sp, color = Mine.colors.textSecondary)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    text, { text = it }, singleLine = true, label = { Text("Ціна, ₴") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
            }
        },
        confirmButton = { TextButton(onClick = { kop?.let(onDone) }, enabled = kop != null && kop > 0) { Text("Готово") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Скасувати") } },
    )
}

@Composable
private fun SentDialog(sale: Sale, onNew: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Передано на касу") },
        text = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                QrCode(SaleCode.qr(sale.id, sale.code), Modifier.size(220.dp))
                Spacer(Modifier.height(10.dp))
                Text("Код кошика", fontSize = 13.sp, color = Mine.colors.textSecondary)
                Text(sale.code, fontSize = 32.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp)
                Text("До сплати: ${Money.format(sale.totalKop)}", fontSize = 16.sp, fontWeight = FontWeight.Medium)
            }
        },
        confirmButton = { TextButton(onClick = onNew) { Text("Новий кошик") } },
    )
}

/** QR без мережі: zxing рахує матрицю, Canvas малює модулі. */
@Composable
fun QrCode(content: String, modifier: Modifier = Modifier) {
    val matrix = remember(content) {
        QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 1))
    }
    Canvas(modifier.background(Color.White)) {
        val cell = size.minDimension / matrix.width
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
            if (matrix[x, y]) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
        }
    }
}
