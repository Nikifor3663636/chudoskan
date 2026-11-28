package ua.chudo.scanner.ui.stock

import androidx.annotation.DrawableRes
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import ua.chudo.scanner.ui.theme.Mine
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ua.chudo.scanner.R
import ua.chudo.scanner.chudoApp
import ua.chudo.scanner.data.HighlightKind
import ua.chudo.scanner.data.HighlightReport
import ua.chudo.scanner.data.LookupResult
import ua.chudo.scanner.data.ProductHighlight
import ua.chudo.scanner.data.ProductHighlights
import ua.chudo.scanner.data.PromoCampaign
import java.text.NumberFormat
import java.util.Locale

@Composable
fun HighlightsScreen(
    canUpload: Boolean,
    onBack: () -> Unit,
    onProduct: (String, String?) -> Unit,
    onSearch: (String) -> Unit,
    onBrowseCatalog: (List<String>, String) -> Unit = { _, _ -> },
    initialKind: HighlightKind = HighlightKind.NEW,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val kind = initialKind
    val promo = kind == HighlightKind.PROMO
    val sheet = ctx.chudoApp.promoSheet
    // «Новинки» — товари; «Акції» — лише текстові кампанії (товарів в акціях немає).
    val showProducts = !promo
    var campaigns by remember { mutableStateOf<List<PromoCampaign>?>(null) }
    var campaignError by remember { mutableStateOf(false) }
    var currentCampaign by remember { mutableStateOf<PromoCampaign?>(null) }
    var campaignRefresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(promo, campaignRefresh) {
        if (!promo) return@LaunchedEffect
        var force = campaignRefresh > 0
        while (true) {
            try {
                campaigns = sheet.read(force)
                campaignError = false
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                campaignError = true
            }
            force = false
            delay(ua.chudo.scanner.data.PromoSheet.REFRESH_MS)
        }
    }
    var productQuery by rememberSaveable { mutableStateOf("") }
    var campaignQuery by rememberSaveable { mutableStateOf("") }
    val query = if (showProducts) productQuery else campaignQuery
    var rows by remember { mutableStateOf(emptyList<ProductHighlight>()) }
    var selected by remember { mutableStateOf<ProductHighlight?>(null) }
    var preview by remember { mutableStateOf<Pair<HighlightKind, List<ProductHighlight>>?>(null) }
    var loading by remember { mutableStateOf(true) }
    var more by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var listError by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val category = kind
            working = true
            note = "Перевіряю файл…"
            scope.launch {
                try {
                    val report = withContext(Dispatchers.IO) {
                        HighlightReport.parse(category) {
                            checkNotNull(ctx.contentResolver.openInputStream(uri)) { "Не вдалося відкрити файл." }
                        }
                    }
                    preview = category to report
                    note = ""
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    note = e.message ?: "Не вдалося прочитати звіт .xlsx."
                } finally {
                    working = false
                }
            }
        }
    }

    LaunchedEffect(kind, showProducts, productQuery, refresh) {
        if (!showProducts) return@LaunchedEffect
        rows = emptyList()
        more = false
        listError = false
        loading = true
        try {
            if (query.isNotBlank()) delay(250)
            val page = ProductHighlights.page(kind, query.trim(), 0)
            rows = page
            more = page.size == ProductHighlights.PAGE_SIZE
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            listError = true
        } finally {
            loading = false
        }
    }

    LazyColumn(Modifier.fillMaxSize().background(Mine.colors.bg).statusBarsPadding()) {
        item {
            HighlightsHeader(kind, onBack)
        }
        item {
            ua.chudo.scanner.ui.common.MineSearchField(
                query, { if (showProducts) productQuery = it else campaignQuery = it },
                if (showProducts) "Назва, штрихкод або артикул" else "Назва акції, ТМ або постачальник", onSubmit = {},
                modifier = Modifier.padding(start = 16.dp, top = 4.dp, end = 16.dp, bottom = 12.dp),
            )
        }
        if (!showProducts) {
            if (campaignError) item {
                HighlightState(if (campaigns == null) "Не вдалося завантажити акції." else "Не вдалося оновити акції.") {
                    TextButton({ campaignRefresh++ }) { Text("Оновити") }
                }
            } else if (campaigns == null) item { HighlightLoading() }
            val term = query.trim()
            val visible = campaigns.orEmpty().filter { campaign ->
                listOf(campaign.title, campaign.brand, campaign.supplier).any { it.contains(term, ignoreCase = true) }
            }
            if (campaigns != null && visible.isEmpty()) item {
                HighlightState(if (term.isEmpty()) "Активних акцій поки немає." else "Акцій не знайдено.")
            }
            items(visible) { campaign ->
                PromoCampaignRow(campaign, Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) { currentCampaign = campaign }
            }
        }
        if (showProducts) {
            if (note.isNotEmpty()) item { Text(note, Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
            if (listError) item {
                HighlightState("Не вдалося завантажити список.") {
                    TextButton({ refresh++ }) { Text("Повторити") }
                }
            } else if (!loading && rows.isEmpty()) item {
                HighlightState(when {
                    query.isNotBlank() -> "За цим запитом товарів немає."
                    promo -> "Акційні товари ще не опубліковано."
                    else -> "Активних новинок поки немає."
                })
            }
            if (rows.isNotEmpty()) item { HorizontalDivider(color = Mine.colors.line) }
            items(rows) { row ->
                HighlightProductRow(row, kind) { selected = row }
            }
            if (loading) item { HighlightLoading() }
            if (more && !loading) item {
                TextButton({
                    val category = kind
                    val search = query
                    val offset = rows.size
                    val revision = refresh
                    loading = true
                    scope.launch {
                        try {
                            val page = ProductHighlights.page(category, search.trim(), offset)
                            if (category == kind && search == productQuery && revision == refresh) {
                                rows = (rows + page).distinct()
                                more = page.size == ProductHighlights.PAGE_SIZE
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            if (category == kind && search == productQuery) note = "Не вдалося завантажити наступні товари."
                        } finally {
                            if (category == kind && search == productQuery && revision == refresh) loading = false
                        }
                    }
                }, modifier = Modifier.padding(horizontal = 8.dp)) { Text("Показати ще") }
            }
            if (canUpload) item {
                HighlightUpload(kind, enabled = !working, onClick = {
                    picker.launch(arrayOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/octet-stream"))
                })
            }
        }
    }

    currentCampaign?.let { campaign ->
        AlertDialog(
            onDismissRequest = { currentCampaign = null },
            title = { Text(campaign.title) },
            text = {
                val labels = listOf("ТМ" to campaign.brand, "Постачальник" to campaign.supplier,
                    "Початок" to campaign.startsOn, "Кінець" to campaign.endsOn, "Статус" to campaign.status)
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items((labels + campaign.details).filter { it.second.isNotBlank() }) { (label, value) ->
                        Text("$label · $value")
                    }
                }
            },
            confirmButton = { TextButton({ currentCampaign = null }) { Text("Закрити") } },
        )
    }

    selected?.let { row ->
        AlertDialog(
            onDismissRequest = { if (!working) selected = null },
            title = { Text(row.name) },
            text = {
                val dates = listOfNotNull(
                    row.startsOn?.let { "Початок $it" }, row.endsOn?.let { "До $it включно" },
                    row.promoPrice?.let(::highlightPrice),
                )
                if (dates.isNotEmpty()) Text(dates.joinToString("\n"))
            },
            confirmButton = {
                TextButton(enabled = !working, onClick = {
                    val code = row.article.ifBlank { row.barcode }
                    working = true
                    scope.launch {
                        try {
                            val result = ctx.chudoApp.repository.lookup(code)
                            selected = null
                            onBrowseCatalog(rows.map { it.article.ifBlank { it.barcode } }.distinct(), code)
                            if (result is LookupResult.Single) onProduct(result.urlKey, row.barcode.ifBlank { null })
                            else onSearch(code)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            selected = null
                            note = "Не вдалося відкрити картку з каталогу."
                        } finally {
                            working = false
                        }
                    }
                }) { Text(if (working) "Шукаю…" else "Картка товару") }
            },
            dismissButton = { TextButton({ selected = null }, enabled = !working) { Text("Закрити") } },
        )
    }

    preview?.let { (category, report) ->
        AlertDialog(
            onDismissRequest = { preview = null },
            title = { Text("Опублікувати «${category.title}»?") },
            text = { Text("${report.size} товарів. Звіт замінить поточний список цієї категорії для всієї мережі.") },
            confirmButton = {
                TextButton({
                    preview = null
                    working = true
                    note = "Публікую…"
                    scope.launch {
                        try {
                            val count = ProductHighlights.publish(category, report)
                            note = "Опубліковано товарів — $count."
                            refresh++
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            note = "Не вдалося завершити публікацію. Онови список і перевір результат."
                        } finally {
                            working = false
                        }
                    }
                }) { Text("Опублікувати") }
            },
            dismissButton = { TextButton({ preview = null }) { Text("Скасувати") } },
        )
    }
}

/** Заголовок як M3 Top App Bar: «Назад» 48 dp і назва — «Новинки» або «Акції», без перемикача між ними. */
@Composable
internal fun HighlightsHeader(kind: HighlightKind, onBack: () -> Unit) {
    val m = Mine.colors
    Row(
        Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
            Icon(painterResource(R.drawable.ic_redesign_chevron_left), "Назад", Modifier.size(24.dp), tint = m.text)
        }
        Text(kind.title, Modifier.padding(start = 4.dp), style = androidx.compose.material3.MaterialTheme.typography.titleLarge, color = m.text)
    }
}

@Composable
internal fun HighlightProductRow(product: ProductHighlight, kind: HighlightKind, onClick: () -> Unit) {
    val m = Mine.colors
    val codes = listOfNotNull(
        product.article.takeIf { it.isNotBlank() }?.let { "арт. $it" },
        product.barcode.takeIf { it.isNotBlank() },
    ).joinToString(" · ")
    HighlightListRow(product.name, codes, R.drawable.ic_redesign_package, onClick) {
        if (kind == HighlightKind.NEW) HighlightBadge(kind.badge, m.infoTint, m.info)
        else Column(horizontalAlignment = Alignment.End) {
            product.promoPrice?.let {
                Text(
                    highlightPrice(it), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = m.text,
                )
            }
            product.endsOn?.takeIf(String::isNotBlank)?.let {
                Text("До $it", fontSize = 13.sp, color = m.textTertiary)
            }
            if (product.promoPrice == null && product.endsOn.isNullOrBlank()) HighlightBadge(kind.badge, m.danger, m.onDanger)
        }
    }
}

/** Акція — текстова картка: назва, ТМ / постачальник, дати, статус і перші умови; натискання — повний текст. */
@Composable
internal fun PromoCampaignRow(campaign: PromoCampaign, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val m = Mine.colors
    val dates = listOf(campaign.startsOn, campaign.endsOn).filter(String::isNotBlank).joinToString(" – ")
    val who = listOf(campaign.brand, campaign.supplier).filter(String::isNotBlank).distinct().joinToString(" · ")
    Surface(
        onClick = onClick, modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
        color = m.card, contentColor = m.text,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                Box(Modifier.size(40.dp).background(m.accentTone, androidx.compose.foundation.shape.CircleShape), contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.ic_redesign_badge_percent), null, Modifier.size(22.dp), tint = m.link)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(campaign.title, style = androidx.compose.material3.MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    if (who.isNotBlank()) Text(who, fontSize = 14.sp, color = m.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            campaign.details.filter { it.second.isNotBlank() }.take(2).forEach { (label, value) ->
                Text("$label: $value", fontSize = 14.sp, lineHeight = 20.sp, color = m.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (dates.isNotBlank()) Text(dates, Modifier.weight(1f), fontSize = 13.sp, color = m.textTertiary)
                else Box(Modifier.weight(1f))
                if (campaign.status.isNotBlank()) {
                    val active = campaign.isActive()
                    HighlightBadge(
                        campaign.status, if (active) m.successTint else m.surface,
                        if (active) m.success else m.textSecondary,
                    )
                }
            }
        }
    }
}

@Composable
private fun HighlightListRow(
    title: String,
    detail: String,
    @DrawableRes icon: Int,
    onClick: () -> Unit,
    trailing: @Composable () -> Unit,
) {
    val m = Mine.colors
    Row(
        Modifier.fillMaxWidth().background(m.card).clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val shape = RoundedCornerShape(12.dp)
        Box(
            Modifier.size(48.dp).background(m.surface, shape).border(1.dp, m.line, shape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(icon), null, Modifier.size(22.dp), tint = m.textTertiary)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title, fontSize = 15.sp, lineHeight = 19.5.sp, fontWeight = FontWeight.Medium,
                maxLines = 2, overflow = TextOverflow.Ellipsis, color = m.text,
            )
            if (detail.isNotBlank()) Text(
                detail, fontSize = 13.sp, color = m.textTertiary,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        trailing()
    }
    HorizontalDivider(color = m.line)
}

@Composable
private fun HighlightBadge(label: String, background: Color, color: Color) {
    Box(
        Modifier.height(22.dp).background(background, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun HighlightLoading() {
    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(24.dp), color = Mine.colors.accent, strokeWidth = 2.dp)
    }
}

@Composable
private fun HighlightState(message: String, action: @Composable () -> Unit = {}) {
    val m = Mine.colors
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val shape = CircleShape
        Box(
            Modifier.size(48.dp).background(m.surface, shape).border(1.dp, m.line, shape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.ic_redesign_package_search), null, Modifier.size(20.dp), tint = m.textSecondary)
        }
        Text(message, fontSize = 14.sp, color = m.textSecondary, textAlign = TextAlign.Center)
        action()
    }
}

@Composable
private fun HighlightUpload(kind: HighlightKind, enabled: Boolean, onClick: () -> Unit) {
    val m = Mine.colors
    Text(
        "Для адміна залишків", Modifier.padding(start = 16.dp, top = 20.dp, bottom = 8.dp),
        fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = m.textSecondary,
    )
    HorizontalDivider(color = m.line)
    Row(
        Modifier.fillMaxWidth().background(m.card).clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(R.drawable.ic_redesign_file_spreadsheet), null, Modifier.size(20.dp), tint = m.textSecondary)
        Column(Modifier.weight(1f)) {
            Text("Завантажити звіт «${kind.title}»", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = m.text)
            Text(if (kind == HighlightKind.PROMO) ".xlsx · назва, код, дати й акційна ціна" else ".xlsx · назва, штрихкод або артикул", fontSize = 13.sp, color = m.textTertiary)
        }
        Icon(painterResource(R.drawable.ic_redesign_chevron_right), null, Modifier.size(18.dp), tint = m.textTertiary)
    }
    HorizontalDivider(color = m.line)
}

internal fun highlightPrice(price: Double): String =
    NumberFormat.getCurrencyInstance(Locale.forLanguageTag("uk-UA")).format(price)
