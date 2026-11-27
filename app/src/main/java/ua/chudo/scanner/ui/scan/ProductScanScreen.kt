package ua.chudo.scanner.ui.scan

import androidx.compose.ui.text.withStyle
import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import ua.chudo.scanner.R
import ua.chudo.scanner.ui.common.ProductImage
import ua.chudo.scanner.ui.theme.CodeStyle
import ua.chudo.scanner.ui.theme.Mine
import ua.chudo.scanner.ui.theme.PriceStyle
import kotlinx.coroutines.CancellationException
import ua.chudo.scanner.chudoApp
import ua.chudo.scanner.data.Backend
import ua.chudo.scanner.data.Employee
import ua.chudo.scanner.data.HighlightKind
import ua.chudo.scanner.data.LookupResult
import ua.chudo.scanner.data.ProductHighlights
import ua.chudo.scanner.ui.home.updatedText
import ua.chudo.scanner.ui.stock.highlightPrice
import java.text.NumberFormat
import java.util.Locale

private data class ScanSummary(
    val title: String,
    val urlKey: String?,
    val price: Double?,
    val oldPrice: Double?,
    val article: String?,
    val image: String?,
    val own: Double?,
    val otherStore: String?,
    val stockTime: String?,
    val flags: List<String>,
)

@Composable
fun ProductScanScreen(
    employee: Employee,
    onClose: () -> Unit,
    onSearch: (String) -> Unit,
    onProduct: (String, String) -> Unit,
    onPrint: (String, String) -> Unit,
    onCart: (String?) -> Unit,
    onCartScan: () -> Unit,
    onOpenSearch: () -> Unit,
    initialPriceMode: Boolean = false,
) {
    val app = LocalContext.current.chudoApp
    val options by app.prefs.scanner.collectAsStateWithLifecycle()
    var code by rememberSaveable { mutableStateOf<String?>(null) }
    var summary by remember(code) { mutableStateOf<ScanSummary?>(null) }
    var loading by remember(code) { mutableStateOf(code != null) }
    var manual by rememberSaveable { mutableStateOf(false) }
    var priceMode by rememberSaveable { mutableStateOf(initialPriceMode) }

    LaunchedEffect(code, employee.shopId) {
        val barcode = code ?: return@LaunchedEffect
        summary = run {
            val cached = optional { app.offlineStock.byBarcode(employee, barcode) }
            val live = if (cached == null) optional { app.networkLookup.byBarcode(employee.shopId, barcode) }?.let { it to true }
                else optional {
                    kotlinx.coroutines.withTimeoutOrNull(2500) { app.networkLookup.byBarcode(employee.shopId, barcode) to true }
                }
            val offline = cached != null && live == null
            val net = if (offline) cached else live?.first
            val lookup = if (offline) null else optional { app.repository.lookup(barcode, net) } as? LookupResult.Single
            val product = lookup?.let { optional { app.repository.details(it.urlKey) } }
            val flags = if (Backend.isSupabase && !offline) {
                optional { ProductHighlights.forProduct(barcode, product?.article ?: net?.item?.article) }.orEmpty()
            } else emptyList()
            ScanSummary(
                title = product?.name ?: net?.item?.name ?: flags.firstOrNull()?.name ?: "Товар за кодом $barcode",
                urlKey = lookup?.urlKey,
                price = flags.firstNotNullOfOrNull { it.promoPrice } ?: product?.price?.finalPrice,
                oldPrice = product?.price?.regularPrice,
                article = product?.article ?: net?.item?.article,
                image = product?.images?.firstOrNull(),
                own = net?.own,
                otherStore = net?.city?.firstOrNull()?.let { store -> "${store.name} · ${formatQuantity(store.qty)} шт" },
                stockTime = updatedText(net?.updatedAt)?.let { if (offline) "Офлайн · $it" else it },
                flags = flags.mapNotNull { flag -> HighlightKind.entries.firstOrNull { it.key == flag.kind }?.badge },
            )
        }
        loading = false
    }

    val barcode = code
    val result: (@Composable () -> Unit)? = if (barcode == null) null else {
        {
            val m = Mine.colors
            Surface(
                shape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp),
                modifier = Modifier.fillMaxWidth(), color = m.card, contentColor = m.text, shadowElevation = 10.dp,
            ) {
                Column(Modifier.navigationBarsPadding().padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 28.dp)) {
                    Box(
                        Modifier.align(Alignment.CenterHorizontally).padding(bottom = 12.dp)
                            .size(width = 36.dp, height = 4.dp).background(m.outline, Mine.pillShape),
                    )
                    if (loading) {
                        Text("Шукаємо товар…", style = MaterialTheme.typography.titleMedium)
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                    } else {
                        val found = summary
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Surface(
                                modifier = Modifier.size(64.dp), shape = RoundedCornerShape(6.dp),
                                color = m.surface, border = BorderStroke(1.dp, m.line),
                            ) {
                                if (found?.image != null) ProductImage(found.image, Modifier.fillMaxWidth())
                                else Box(contentAlignment = Alignment.Center) {
                                    Icon(painterResource(R.drawable.ic_redesign_package), null, Modifier.size(22.dp), tint = m.textTertiary)
                                }
                            }
                            Column(Modifier.weight(1f)) {
                                Text(found?.title ?: barcode, fontSize = 15.sp, lineHeight = 19.5.sp, fontWeight = FontWeight.Medium)
                                val article = found?.article?.takeIf { it.isNotBlank() }?.let { " · арт. $it" }.orEmpty()
                                Text(barcode + article, style = CodeStyle, color = m.textTertiary)
                                found?.flags?.takeIf { it.isNotEmpty() }?.let {
                                    Text(it.joinToString(" · "), fontSize = 13.sp, color = m.link)
                                }
                            }
                        }
                        Row(
                            Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.Bottom,
                        ) {
                            Column(Modifier.weight(1f)) {
                                found?.price?.let { price ->
                                    Text(highlightPrice(price), style = PriceStyle.copy(fontSize = 26.sp))
                                    found.oldPrice?.takeIf { it > price + 0.001 }?.let {
                                        Text(highlightPrice(it), fontSize = 13.sp, color = m.textTertiary, textDecoration = TextDecoration.LineThrough)
                                    }
                                }
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                val own = found?.own
                                if (own == null) Text("Залишок невідомий", fontSize = 13.sp, color = m.textSecondary)
                                else Text(
                                    androidx.compose.ui.text.buildAnnotatedString {
                                        append("У магазині ")
                                        val qtyColor = when {
                                            own <= 0.0 -> m.textTertiary
                                            own <= 2.0 -> m.warningText
                                            else -> m.success
                                        }
                                        withStyle(androidx.compose.ui.text.SpanStyle(color = qtyColor, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)) {
                                            append(formatQuantity(own))
                                        }
                                        append(" шт")
                                    },
                                    fontSize = 13.sp, color = m.textSecondary,
                                )
                                if (own == 0.0) found.otherStore?.let { Text(it, fontSize = 13.sp, color = m.textSecondary) }
                            }
                        }
                        val key = found?.urlKey
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (key == null || priceMode) {
                                ScanSheetButton("Далі", R.drawable.ic_redesign_scan_line, Modifier.weight(1f), onClick = { code = null })
                            }
                            if (key == null) {
                                ScanSheetButton("Пошук", R.drawable.ic_redesign_search, Modifier.weight(1f), primary = true) { onSearch(barcode) }
                            } else {
                                ScanSheetButton("Цінник", R.drawable.ic_redesign_printer, Modifier.weight(1f), primary = priceMode) { onPrint(key, barcode) }
                                if (!priceMode) {
                                    ScanSheetButton("Кошик", R.drawable.ic_redesign_shopping_cart, Modifier.weight(1f)) { onCart(barcode) }
                                    ScanSheetButton("Картка", null, Modifier.weight(1f), primary = true) { onProduct(key, barcode) }
                                }
                            }
                        }
                        if (!priceMode && key != null && !options.continuous) {
                            Spacer(Modifier.height(8.dp))
                            ScanSheetButton("Далі", R.drawable.ic_redesign_scan_line, Modifier.fillMaxWidth()) { code = null }
                        }
                        val note = found?.stockTime ?: if (options.continuous) "Наведіть на наступний код — картка оновиться" else null
                        if (note != null) Text(
                            note, Modifier.fillMaxWidth().padding(top = 10.dp),
                            fontSize = 13.sp, color = m.textTertiary, textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
    ScannerScreen(
        onBarcode = {
            if (!manual) {
                app.prefs.addActivity(employee, ua.chudo.scanner.data.ActivityKind.SCAN, "Штрихкод відскановано", it)
                if (options.continuous || priceMode) code = it else onSearch(it)
            }
        },
        onClose = onClose,
        continuous = options.continuous,
        result = result,
        onManual = { manual = true },
        acceptingScans = !manual,
        onCartMode = onCartScan,
        onSearch = onOpenSearch,
        priceMode = priceMode,
        onPriceMode = { priceMode = it },
    )
    if (manual) ManualScanInput(
        onClose = { manual = false },
        onSubmit = {
            manual = false
            if (priceMode) code = it else onSearch(it)
        },
    )
}

@Composable
private fun ScanSheetButton(
    label: String,
    @DrawableRes icon: Int?,
    modifier: Modifier,
    primary: Boolean = false,
    onClick: () -> Unit,
) {
    val m = Mine.colors
    Surface(
        onClick = onClick, modifier = modifier.height(44.dp), shape = RoundedCornerShape(6.dp),
        color = if (primary) m.accent else m.card,
        contentColor = if (primary) m.onAccent else m.text,
        border = if (primary) null else BorderStroke(1.dp, m.outline),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) Icon(painterResource(icon), null, Modifier.size(18.dp))
            Text(label, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        }
    }
}

private fun formatQuantity(quantity: Double): String =
    NumberFormat.getNumberInstance(Locale.forLanguageTag("uk")).format(quantity)

private suspend fun <T> optional(read: suspend () -> T): T? = try {
    read()
} catch (error: CancellationException) {
    throw error
} catch (_: Exception) {
    null
}
