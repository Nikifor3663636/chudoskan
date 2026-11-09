package ua.chudo.shared.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.Store
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import ua.chudo.shared.BarcodeScannerView
import ua.chudo.shared.auth.Employee
import ua.chudo.shared.auth.authMessage
import ua.chudo.shared.core.Core
import ua.chudo.shared.core.Price
import ua.chudo.shared.core.ProductDetails
import ua.chudo.shared.core.ProductSummary
import ua.chudo.shared.core.ShopAvailability
import ua.chudo.shared.core.StockStatus
import ua.chudo.shared.data.BlockedException
import ua.chudo.shared.data.HistoryItem
import ua.chudo.shared.data.HttpException
import ua.chudo.shared.data.LookupResult
import ua.chudo.shared.data.Via
import ua.chudo.shared.openUrl
import ua.chudo.shared.res.Res
import ua.chudo.shared.res.logo_wordmark

// ---------- Общие элементы ----------

@Composable
private fun TopBar(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад") }
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        actions()
    }
}

private fun money(v: Double): String {
    val whole = v.toLong()
    val frac = ((v - whole) * 100 + 0.5).toInt()
    val grouped = whole.toString().reversed().chunked(3).joinToString(" ").reversed()
    return if (frac == 0) "$grouped ₴" else "$grouped,${frac.toString().padStart(2, '0')} ₴"
}

@Composable
private fun PriceLine(price: Price?, big: Boolean = false) {
    if (price == null) { Text("Ціна недоступна", color = OutOfStockGrey); return }
    Row(verticalAlignment = Alignment.Bottom) {
        Text(money(price.finalPrice), style = if (big) PriceStyle else MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        if (price.regularPrice > price.finalPrice + 0.001) {
            Spacer(Modifier.width(8.dp))
            Text(money(price.regularPrice), color = OutOfStockGrey, textDecoration = TextDecoration.LineThrough)
        }
    }
}

@Composable
private fun StockBadge(stock: StockStatus) {
    val (text, color) = when (stock) {
        StockStatus.IN_STOCK -> "Є на сайті" to InStockGreen
        StockStatus.OUT_OF_STOCK -> "Немає онлайн" to OutOfStockGrey
        StockStatus.UNKNOWN -> "Невідомо" to OutOfStockGrey
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = color)
    }
}

@Composable
private fun ProductCard(p: ProductSummary, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(p.image, null, Modifier.size(72.dp).clip(RoundedCornerShape(10.dp)).background(Color.White), contentScale = ContentScale.Fit)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(p.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                p.article?.let { Text("Арт. $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                PriceLine(p.price)
                StockBadge(p.stock)
            }
        }
    }
}

fun Throwable.uiMessage(): String = when (this) {
    is BlockedException -> "Сайт тимчасово не пускає застосунок. Спробуй пізніше або відкрий товар у браузері."
    is HttpException -> "Помилка сайту ($code). Спробуй ще раз пізніше."
    else -> authMessage()
}

@Composable
fun RegionPicker(selected: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val graph = LocalGraph.current
    var filter by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Місто для наявності") },
        text = {
            Column {
                OutlinedTextField(filter, { filter = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("Пошук міста") })
                LazyColumn(Modifier.height(360.dp)) {
                    items(graph.prefs.allRegions.filter { it.name.contains(filter.trim(), ignoreCase = true) }, key = { it.code }) { r ->
                        Text(
                            r.name, Modifier.fillMaxWidth().clickable { onPick(r.code) }.padding(vertical = 12.dp, horizontal = 4.dp),
                            fontWeight = if (r.code == selected) FontWeight.Bold else FontWeight.Normal,
                            color = if (r.code == selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрити") } },
    )
}

// ---------- Главная ----------

@Composable
fun HomeScreen(nav: Navigator) {
    val graph = LocalGraph.current
    val region by graph.prefs.region.collectAsStateWithLifecycle()
    val history by graph.prefs.history.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var pickRegion by remember { mutableStateOf(false) }
    fun submit() { if (query.isNotBlank()) nav.go(Screen.Results(query.trim())) }

    LazyColumn(
        Modifier.fillMaxSize().safeDrawingPadding(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Image(painterResource(Res.drawable.logo_wordmark), "Чудо Острів", Modifier.height(44.dp))
                    Text("Наявність іграшок у магазинах", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { openUrl("https://chudo-ostriv.com.ua/ua/") }) { Icon(Icons.Rounded.Store, "Сайт") }
                IconButton(onClick = { nav.go(Screen.Profile) }) { Icon(Icons.Rounded.Person, "Профіль") }
            }
        }
        item {
            AssistChip(onClick = { pickRegion = true }, label = { Text(region.name) }, leadingIcon = { Icon(Icons.Rounded.LocationOn, null, Modifier.size(18.dp)) })
        }
        item {
            Surface(
                onClick = { nav.go(Screen.Scan) }, modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.primary,
            ) {
                Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.QrCodeScanner, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text("Сканувати штрихкод", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimary)
                        Text("Наведи камеру на упаковку", color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f))
                    }
                }
            }
        }
        item {
            OutlinedTextField(
                query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text("Артикул, штрихкод або назва") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, "Очистити") } },
                shape = MaterialTheme.shapes.large,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submit() }),
            )
        }
        if (history.isNotEmpty()) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Нещодавні", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = graph.prefs::clearHistory) { Text("Очистити") }
                }
            }
            items(history, key = { it.urlKey }) { h -> HistoryRow(h) { nav.go(Screen.Product(h.urlKey, null)) } }
        } else {
            item {
                Text(
                    "Порада: на ціннику артикул зазвичай у дужках у назві. Штрихкод сайт не знає — застосунок шукає його у відкритій базі.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    if (pickRegion) RegionPicker(region.code, onPick = { graph.prefs.setRegion(it); pickRegion = false }, onDismiss = { pickRegion = false })
}

@Composable
private fun HistoryRow(h: HistoryItem, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable(onClick = onClick).padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
        AsyncImage(h.image, null, Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).background(Color.White), contentScale = ContentScale.Fit)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(h.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            h.article?.let { Text("Арт. $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

// ---------- Сканер ----------

@Composable
fun ScanScreen(nav: Navigator) {
    var error by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        BarcodeScannerView(
            Modifier.fillMaxSize(),
            onCode = { code -> if (!done) { done = true; nav.replace(Screen.Results(code)) } },
            onUnavailable = { error = it },
        )
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
            IconButton(onClick = { nav.back() }, modifier = Modifier.background(Color.Black.copy(alpha = 0.45f), CircleShape)) {
                Icon(Icons.Rounded.Close, "Закрити", tint = Color.White)
            }
            Spacer(Modifier.weight(1f))
            Text(error ?: "Наведи рамку на штрихкод", color = Color.White, style = MaterialTheme.typography.titleMedium, modifier = Modifier.align(Alignment.CenterHorizontally))
            Spacer(Modifier.height(12.dp))
            Button(onClick = { nav.back() }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Ввести вручну") }
        }
    }
}

// ---------- Результаты ----------

@Composable
fun ResultsScreen(query: String, nav: Navigator) {
    val graph = LocalGraph.current
    var step by remember { mutableStateOf("Шукаємо…") }
    var result by remember { mutableStateOf<LookupResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableStateOf(0) }
    LaunchedEffect(query, attempt) {
        result = null; error = null
        try {
            val r = graph.repo.lookup(query) { step = it }
            if (r is LookupResult.Single) {
                val bc = r.barcode.takeIf { r.via is Via.UpcArticle || r.via is Via.UpcTitle }
                nav.replace(Screen.Product(r.urlKey, bc))
            } else result = r
        } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.uiMessage() }
    }
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        TopBar(query, onBack = { nav.back() })
        when (val r = result) {
            null -> if (error != null) Message(Icons.Rounded.SearchOff, "Не вдалося знайти", error!!, "Спробувати ще" to { attempt++ })
            else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text(step, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            is LookupResult.Many -> LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Text("Знайдено ${r.items.size}", style = MaterialTheme.typography.titleMedium) }
                viaNote(r.via)?.let { note -> item { Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.tertiaryContainer) { Text(note, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall) } } }
                items(r.items, key = { it.id }) { p -> ProductCard(p) { nav.go(Screen.Product(p.urlKey, r.barcode.takeIf { r.via !is Via.Direct })) } }
            }
            is LookupResult.NotFound -> Message(
                Icons.Rounded.SearchOff, "Не знайшли «${r.query}»",
                "Спробуй ввести артикул з цінника — він зазвичай у дужках у назві." +
                    if (r.triedArticles.isNotEmpty()) "\n\nПеревірили артикули: ${r.triedArticles.joinToString()}" else "",
                "Шукати на сайті" to { openUrl(Core.siteSearchUrl(r.query)) },
            )
            is LookupResult.Single -> Unit
        }
    }
}

private fun viaNote(via: Via): String? = when (via) {
    Via.Direct, Via.Remembered -> null
    is Via.UpcArticle -> "Штрихкод знайшли у відкритій базі → артикул ${via.article}. Обери свій товар — ми його запам'ятаємо."
    is Via.UpcTitle -> "Шукали за назвою з відкритої бази: «${via.title}». Перевір, що це саме твій товар."
}

@Composable
private fun Message(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, text: String, action: Pair<String, () -> Unit>) {
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(icon, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        Button(onClick = action.second) { Text(action.first) }
    }
}

// ---------- Карточка товара ----------

@Composable
fun ProductScreen(urlKey: String, barcode: String?, nav: Navigator) {
    val graph = LocalGraph.current
    val region by graph.prefs.region.collectAsStateWithLifecycle()
    var product by remember { mutableStateOf<ProductDetails?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var shops by remember { mutableStateOf<List<ShopAvailability>?>(null) }
    var shopsError by remember { mutableStateOf(false) }
    var remembered by remember { mutableStateOf(barcode != null && graph.prefs.rememberedProduct(barcode) == urlKey) }
    var pickRegion by remember { mutableStateOf(false) }
    var attempt by remember { mutableStateOf(0) }

    LaunchedEffect(urlKey, attempt) {
        error = null
        try {
            val p = graph.repo.details(urlKey)
            product = p
            graph.prefs.addToHistory(HistoryItem(p.urlKey, p.name, p.article, p.images.firstOrNull()))
        } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.uiMessage() }
    }
    LaunchedEffect(product?.id, region.code) {
        val p = product ?: return@LaunchedEffect
        shops = null; shopsError = false
        runCatching { graph.repo.shops(p.id, region.code) }.onSuccess { shops = it }.onFailure { shopsError = true }
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        TopBar(product?.name ?: "Товар", onBack = { nav.back() }) {
            product?.let { p -> IconButton(onClick = { openUrl(Core.productUrl(p.urlKey, p.urlSuffix)) }) { Icon(Icons.AutoMirrored.Rounded.OpenInNew, "На сайті") } }
        }
        val p = product
        when {
            error != null -> Message(Icons.Rounded.SearchOff, "Не вдалося завантажити товар", error!!, "Спробувати ще" to { attempt++ })
            p == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else -> LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(p.images.ifEmpty { listOf("") }) { img ->
                            AsyncImage(img, null, Modifier.height(240.dp).aspectRatio(1f).clip(MaterialTheme.shapes.large).background(Color.White), contentScale = ContentScale.Fit)
                        }
                    }
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(p.name, style = MaterialTheme.typography.titleLarge)
                        p.article?.let { Text("Артикул: $it", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        PriceLine(p.price, big = true)
                        StockBadge(p.stock)
                    }
                }
                if (barcode != null) item {
                    if (remembered) Text("Штрихкод $barcode прив'язано до цього товару ✓", color = InStockGreen, style = MaterialTheme.typography.bodySmall)
                    else OutlinedButton(onClick = { graph.prefs.rememberBarcode(barcode, p.urlKey); remembered = true }) { Text("Запам'ятати штрихкод $barcode") }
                }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Наявність у магазинах", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        AssistChip(onClick = { pickRegion = true }, label = { Text(region.name) }, leadingIcon = { Icon(Icons.Rounded.LocationOn, null, Modifier.size(18.dp)) })
                    }
                }
                val list = shops
                when {
                    shopsError -> item { Text("Не вдалося завантажити наявність.", color = MaterialTheme.colorScheme.error) }
                    list == null -> item { CircularProgressIndicator(Modifier.size(28.dp)) }
                    list.isEmpty() -> item { Text("У цьому місті магазинів не знайдено.") }
                    else -> items(list.sortedByDescending { it.available }) { s ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(10.dp).background(if (s.available) InStockGreen else OutOfStockGrey, CircleShape))
                            Spacer(Modifier.width(10.dp))
                            Text(s.address, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            Text(if (s.available) "є" else "немає", color = if (s.available) InStockGreen else OutOfStockGrey, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (p.description.isNotBlank()) item {
                    Text("Опис", style = MaterialTheme.typography.titleMedium)
                    Text(p.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    if (pickRegion) RegionPicker(region.code, onPick = { graph.prefs.setRegion(it); pickRegion = false }, onDismiss = { pickRegion = false })
}

// ---------- Профиль ----------

@Composable
fun ProfileScreen(employee: Employee, nav: Navigator) {
    val graph = LocalGraph.current
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var changePassword by remember { mutableStateOf(false) }
    var confirmLogout by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp)) {
        TopBar("Профіль", onBack = { nav.back() })
        Spacer(Modifier.height(8.dp))
        Text(employee.name, style = MaterialTheme.typography.headlineMedium)
        Text("Співробітник підтверджений", color = InStockGreen, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(16.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                InfoRow("Телефон", formatPhone(employee.phone))
                HorizontalDivider()
                InfoRow("Місто", graph.prefs.allRegions.firstOrNull { it.code == employee.region }?.name ?: employee.region)
                HorizontalDivider()
                InfoRow("Магазин", employee.shop)
            }
        }
        Text("Магазин змінює адміністратор.", Modifier.padding(start = 4.dp, top = 6.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        Card(Modifier.fillMaxWidth()) {
            Column {
                ActionRow(Icons.Rounded.Lock, "Змінити пароль") { changePassword = true }
                HorizontalDivider()
                ActionRow(Icons.AutoMirrored.Rounded.Logout, "Вийти", danger = true) { confirmLogout = true }
            }
        }
        message?.let { Text(it, Modifier.padding(top = 10.dp), color = MaterialTheme.colorScheme.primary) }
    }
    if (changePassword) {
        var current by remember { mutableStateOf("") }
        var new by remember { mutableStateOf("") }
        var repeat by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        var busy by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { if (!busy) changePassword = false },
            title = { Text("Змінити пароль") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    PasswordField(current, "Поточний пароль") { current = it }
                    PasswordField(new, "Новий пароль") { new = it }
                    PasswordField(repeat, "Повтори пароль") { repeat = it }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    error = if (current.isEmpty()) "Введи поточний пароль." else passwordProblem(new, repeat)
                    if (error == null) {
                        busy = true
                        scope.launch {
                            try { graph.auth.changePassword(current, new); changePassword = false; message = "Пароль змінено." }
                            catch (e: CancellationException) { throw e }
                            catch (e: Exception) { error = e.authMessage() }
                            finally { busy = false }
                        }
                    }
                }) { Text("Зберегти") }
            },
            dismissButton = { TextButton(onClick = { changePassword = false }) { Text("Скасувати") } },
        )
    }
    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("Вийти з акаунта?") },
            text = { Text(formatPhone(employee.phone)) },
            confirmButton = { TextButton(onClick = { confirmLogout = false; graph.auth.signOut() }) { Text("Вийти") } },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("Скасувати") } },
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun ActionRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, danger: Boolean = false, onClick: () -> Unit) {
    val color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = color)
        Spacer(Modifier.width(14.dp))
        Text(label, color = color)
    }
}
