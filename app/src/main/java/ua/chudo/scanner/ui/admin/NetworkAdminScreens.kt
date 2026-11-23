package ua.chudo.scanner.ui.admin

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ua.chudo.scanner.chudoApp
import ua.chudo.scanner.data.AdminLogEntry
import ua.chudo.scanner.data.Employee
import ua.chudo.scanner.data.NetworkAdmin
import ua.chudo.scanner.data.NetworkAdminRepository
import ua.chudo.scanner.data.NetworkStockInfo
import ua.chudo.scanner.data.NetworkStockRepository
import ua.chudo.scanner.data.ShopDoc
import ua.chudo.scanner.data.ShopEntry
import ua.chudo.scanner.data.StaffAction
import ua.chudo.scanner.data.StaffMember
import ua.chudo.scanner.data.StoreDoc
import ua.chudo.scanner.ui.auth.authMessage
import ua.chudo.scanner.ui.common.MineButton
import ua.chudo.scanner.ui.common.MineCard
import ua.chudo.scanner.ui.common.MineChip
import ua.chudo.scanner.ui.common.MineHeader
import ua.chudo.scanner.ui.common.MineSearchField
import ua.chudo.scanner.ui.common.MineTone
import ua.chudo.scanner.ui.theme.Mine
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Усе для екранів адміністрування: один завантажений знімок мережі. */
data class AdminData(
    val entries: List<ShopEntry> = emptyList(),
    val shops: List<ShopDoc> = emptyList(),
    val stores: Map<String, StoreDoc> = emptyMap(),
    val staff: List<StaffMember> = emptyList(),
    val history: List<AdminLogEntry> = emptyList(),
    val stockInfo: NetworkStockInfo? = null,
)

class NetworkAdminViewModel(
    private val employee: Employee,
    private val repo: NetworkAdminRepository,
    private val stock: NetworkStockRepository,
    private val regionName: (String) -> String,
    /** Лише свій магазин («Мій магазин» адміна магазину). */
    private val ownShopOnly: Boolean,
) : ViewModel() {
    private val _data = MutableStateFlow<AdminData?>(null)
    val data = _data.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()

    init { load() }

    fun load() = viewModelScope.launch {
        _error.value = null
        try {
            _data.value = coroutineScope {
                if (ownShopOnly) {
                    AdminData(staff = repo.staff(employee.homeShopId))
                } else {
                    val shops = async { repo.shops() }
                    val stores = async { runCatching { stock.stores() }.getOrDefault(emptyMap()) }
                    val staff = async { repo.staff() }
                    val agents = async { runCatching { repo.agents() }.getOrDefault(emptyList()) }
                    val history = async { runCatching { repo.history() }.getOrDefault(emptyList()) }
                    val info = async { runCatching { stock.info() }.getOrNull() }
                    val entries = NetworkAdmin.directory(shops.await(), stores.await(), staff.await(), agents.await(), regionName)
                    val codeOf = entries.filter { it.shopId != null }.associate { it.shopId!! to it.code }
                    AdminData(entries, shops.await(), stores.await(), staff.await().map { it.copy(code = codeOf[it.shopId]) }, history.await(), info.await())
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _error.value = e.authMessage()
        }
    }

    fun apply(m: StaffMember, action: StaffAction, target: ShopDoc? = null, onDone: (String) -> Unit) = act(onDone, "Готово: ${action.title.lowercase()}") {
        repo.apply(employee.uid, m, action, target)
    }

    /** Склад 1С ↔ магазин: stores/{код}.shopId і shops/{shopId}.code1C. */
    fun link(code: String, shopId: String, onDone: (String) -> Unit) = act(onDone, "ЧО $code прив'язано") {
        stock.link(code, shopId)
        repo.log(employee.uid, "linkStore", code, shopId)
    }

    fun logActAs(shopId: String, code: String?) = viewModelScope.launch {
        runCatching { repo.log(employee.uid, "actAs", code ?: shopId, shopId) }
    }

    private fun act(onDone: (String) -> Unit, ok: String, block: suspend () -> Unit) = viewModelScope.launch {
        if (_busy.value) return@launch
        _busy.value = true
        try {
            block()
            onDone(ok)
            load()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onDone(e.authMessage())
        } finally {
            _busy.value = false
        }
    }
}

@Composable
private fun adminVm(employee: Employee, ownShopOnly: Boolean = false): NetworkAdminViewModel {
    val app = LocalContext.current.chudoApp
    return viewModel(
        key = "admin-${employee.uid}-$ownShopOnly",
        factory = viewModelFactory {
            initializer {
                NetworkAdminViewModel(employee, NetworkAdminRepository(com.google.firebase.firestore.FirebaseFirestore.getInstance()), app.networkStock,
                    { code -> app.prefs.allRegions.firstOrNull { it.code == code }?.name ?: code }, ownShopOnly)
            }
        },
    )
}

private val DATE = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.forLanguageTag("uk"))
private fun date(d: Date?) = d?.let { DATE.format(it) } ?: "—"

// ---------- Меню ----------

/** «Профіль → Адміністрування мережі» (лише superAdmin). */
@Composable
fun NetworkAdminScreen(
    employee: Employee, onBack: () -> Unit,
    onShops: () -> Unit, onStaff: () -> Unit, onNetworkStock: () -> Unit, onHistory: () -> Unit,
) {
    val vm = adminVm(employee)
    val data by vm.data.collectAsStateWithLifecycle()
    val m = Mine.colors
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { MineHeader("Адміністрування мережі", onBack) }
        item {
            AdminRow("Магазини", data?.let { d -> "${d.entries.count { it.shopId != null }} магазинів · складів 1С: ${d.stores.size}" } ?: "Завантажую…", onShops)
        }
        item {
            val pending = data?.staff?.count { it.pending } ?: 0
            AdminRow("Співробітники", data?.let { "${it.staff.count { s -> s.approved }} підтверджених" + if (pending > 0) " · очікують: $pending" else "" } ?: "Завантажую…", onStaff, accent = pending > 0)
        }
        item { AdminRow("Залишки мережі", data?.stockInfo?.let { "Оновлено ${date(it.updatedAt)} · ${it.items} товарів" } ?: "Завантажити звіт 1С", onNetworkStock) }
        item { AdminRow("Історія завантажень", "Залишки мережі й дії адмінів", onHistory) }
        item {
            Text(
                "Тут — усі магазини мережі. Звичайний адмін магазину бачить лише «Мій магазин».",
                Modifier.padding(horizontal = 4.dp), fontSize = 13.sp, lineHeight = 18.sp, color = m.textSecondary,
            )
        }
    }
}

@Composable
private fun AdminRow(title: String, note: String, onClick: () -> Unit, accent: Boolean = false) {
    val m = Mine.colors
    MineCard(Modifier.clickable(onClick = onClick), padding = 18.dp) {
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Text(note, Modifier.padding(top = 4.dp), fontSize = 14.sp, lineHeight = 19.sp, color = if (accent) m.accent else m.textSecondary)
    }
}

// ---------- Магазини ----------

@Composable
fun ShopsAdminScreen(employee: Employee, onBack: () -> Unit, onActAs: (ShopEntry) -> Unit, onStaffOf: (String) -> Unit) {
    val vm = adminVm(employee)
    val data by vm.data.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var linking by remember { mutableStateOf<ShopEntry?>(null) }
    var actAs by remember { mutableStateOf<ShopEntry?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val m = Mine.colors

    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { MineHeader("Магазини", onBack) }
        item { MineSearchField(query, { query = it }, "Місто або номер (ЧО 62)", {}) }
        message?.let { item { Text(it, fontSize = 15.sp, color = m.success) } }
        error?.let { item { Text(it, fontSize = 15.sp, color = m.warningText) } }
        val d = data
        if (d == null) item { CircularProgressIndicator(color = m.accent) }
        else items(NetworkAdmin.search(d.entries, query), key = { it.shopId ?: "store-${it.code}" }) { e ->
            MineCard(padding = 16.dp) {
                Text("${e.title} · ${e.city}", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                if (e.address.isNotBlank()) Text(e.address, fontSize = 14.sp, lineHeight = 19.sp, color = m.textSecondary)
                if (e.shopId != null) {
                    Text(
                        "Співробітників: ${e.staff}" + (if (e.pending > 0) " · очікують: ${e.pending}" else "") +
                            " · ПК з Godex на зв'язку: ${e.pcsOnline}",
                        Modifier.padding(top = 6.dp), fontSize = 14.sp, lineHeight = 19.sp,
                    )
                    Text("Залишки оновлено: ${date(e.stockUpdatedAt)}", fontSize = 14.sp, color = m.textSecondary)
                } else {
                    Text("Склад 1С ще не прив'язано до магазину застосунку.", Modifier.padding(top = 6.dp), fontSize = 14.sp, color = m.warningText)
                }
                if (e.shopId != null) {
                    Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MineButton("Працювати тут", { actAs = e }, Modifier.weight(1f), tone = MineTone.ACCENT)
                        MineButton("Співробітники", { onStaffOf(e.shopId) }, Modifier.weight(1f))
                    }
                    MineButton(if (e.code == null) "Прив'язати склад 1С" else "Змінити склад 1С", { linking = e }, Modifier.padding(top = 8.dp).fillMaxWidth(), enabled = !busy)
                }
            }
        }
        item { Spacer(Modifier.padding(12.dp)) }
    }

    actAs?.let { e ->
        AlertDialog(
            onDismissRequest = { actAs = null },
            title = { Text("Працювати як у ${e.title}?") },
            text = { Text("«Принтери», «Залишки», «Замовлення» і друк перемкнуться на цей магазин до кнопки «Повернутися». Твоя анкета не зміниться.") },
            confirmButton = { TextButton(onClick = { vm.logActAs(e.shopId!!, e.code); actAs = null; onActAs(e) }) { Text("Перемкнути") } },
            dismissButton = { TextButton(onClick = { actAs = null }) { Text("Скасувати") } },
        )
    }
    linking?.let { e ->
        val stores = data?.stores.orEmpty().toSortedMap(compareBy({ it.toIntOrNull() ?: Int.MAX_VALUE }, { it }))
        AlertDialog(
            onDismissRequest = { linking = null },
            title = { Text("Склад 1С для «${e.address}»") },
            text = {
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    items(stores.entries.toList(), key = { it.key }) { (code, st) ->
                        Column(Modifier.fillMaxWidth().clickable(enabled = !busy) {
                            vm.link(code, e.shopId!!) { message = it }
                            linking = null
                        }.padding(vertical = 10.dp)) {
                            Text("ЧО $code · ${st.city}", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            Text(st.name, fontSize = 13.sp, color = m.textSecondary)
                        }
                        HorizontalDivider(color = m.line)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { linking = null }) { Text("Закрити") } },
        )
    }
}

// ---------- Співробітники ----------

/**
 * «Співробітники»: superAdmin — усі магазини, фільтри «Очікують», магазин, місто;
 * адмін магазину («Мій магазин», [ownShopOnly]) — лише свій магазин.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StaffAdminScreen(employee: Employee, onBack: () -> Unit, shopFilter: String? = null, ownShopOnly: Boolean = false) {
    val vm = adminVm(employee, ownShopOnly)
    val data by vm.data.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var pendingOnly by rememberSaveable { mutableStateOf(false) }
    var city by rememberSaveable { mutableStateOf<String?>(null) }
    var shop by rememberSaveable { mutableStateOf(shopFilter) }
    var query by rememberSaveable { mutableStateOf("") }
    var chosen by remember { mutableStateOf<StaffMember?>(null) }
    var confirm by remember { mutableStateOf<Triple<StaffMember, StaffAction, ShopDoc?>?>(null) }
    var transfer by remember { mutableStateOf<StaffMember?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val m = Mine.colors
    val d = data
    val cityOfShop = d?.entries.orEmpty().filter { it.shopId != null }.associate { it.shopId!! to it.city }
    fun cityOf(s: StaffMember) = cityOfShop[s.shopId] ?: s.region

    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { MineHeader(if (ownShopOnly) "Мій магазин" else "Співробітники", onBack) }
        if (ownShopOnly) item { Text(employee.shop, Modifier.padding(horizontal = 4.dp), fontSize = 15.sp, color = m.textSecondary) }
        item { MineSearchField(query, { query = it }, "Ім'я, телефон або магазин", {}) }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MineChip("Очікують" + (d?.staff?.count { it.pending }?.takeIf { it > 0 }?.let { " · $it" } ?: ""), pendingOnly, { pendingOnly = !pendingOnly })
                if (!ownShopOnly) {
                    shop?.let { id -> MineChip("Магазин: ${d?.entries?.firstOrNull { it.shopId == id }?.title ?: "обраний"} ✕", true, { shop = null }) }
                    d?.entries?.map { it.city }?.filter { it.isNotBlank() }?.distinct()?.sorted()?.forEach { c ->
                        MineChip(c, city == c, { city = if (city == c) null else c })
                    }
                }
            }
        }
        message?.let { item { Text(it, fontSize = 15.sp, color = m.success) } }
        error?.let { item { Text(it, fontSize = 15.sp, color = m.warningText) } }
        if (d == null) item { CircularProgressIndicator(color = m.accent) }
        else {
            val list = NetworkAdmin.filterStaff(d.staff, pendingOnly, shop, city, ::cityOf, query)
            if (list.isEmpty()) item { Text("Нікого не знайдено.", fontSize = 15.sp, color = m.textSecondary) }
            items(list, key = { it.uid }) { s ->
                MineCard(Modifier.clickable { chosen = s }, padding = 16.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(s.name.ifBlank { s.phone }, Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            when { s.superAdmin -> "Адмін мережі"; s.pending -> "Очікує"; !s.approved -> "Вимкнено"; s.admin -> "Адмін"; else -> "" },
                            fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (s.pending) m.accent else m.textSecondary,
                        )
                    }
                    Text(listOfNotNull(s.code?.let { "ЧО $it" }, cityOf(s).ifBlank { null }, s.shop.ifBlank { null }).joinToString(" · "), fontSize = 14.sp, color = m.textSecondary)
                    Text(s.phone, fontSize = 14.sp, color = m.textSecondary)
                }
            }
        }
        item { Spacer(Modifier.padding(12.dp)) }
    }

    chosen?.let { s ->
        val actions = NetworkAdmin.actions(s, employee.uid, employee.superAdmin && !ownShopOnly)
        AlertDialog(
            onDismissRequest = { chosen = null },
            title = { Text(s.name.ifBlank { s.phone }) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(s.shop.ifBlank { "Без магазину" }, fontSize = 14.sp, color = m.textSecondary)
                    if (actions.isEmpty()) Text("Змінити цю анкету з телефону не можна.", fontSize = 14.sp)
                    actions.forEach { a ->
                        MineButton(a.title, {
                            chosen = null
                            if (a == StaffAction.TRANSFER) transfer = s else confirm = Triple(s, a, null)
                        }, Modifier.fillMaxWidth(), tone = if (a == StaffAction.DISABLE || a == StaffAction.REJECT) MineTone.DANGER else MineTone.OUTLINE, enabled = !busy)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { chosen = null }) { Text("Закрити") } },
        )
    }
    transfer?.let { s ->
        AlertDialog(
            onDismissRequest = { transfer = null },
            title = { Text("У який магазин?") },
            text = {
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    items(d?.shops.orEmpty().filter { it.id != s.shopId }.sortedWith(compareBy({ it.region }, { it.address })), key = { it.id }) { target ->
                        val e = d?.entries?.firstOrNull { it.shopId == target.id }
                        Column(Modifier.fillMaxWidth().clickable { transfer = null; confirm = Triple(s, StaffAction.TRANSFER, target) }.padding(vertical = 10.dp)) {
                            Text(listOfNotNull(e?.code?.let { "ЧО $it" }, e?.city).joinToString(" · ").ifBlank { target.region }, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            Text(target.address, fontSize = 13.sp, color = m.textSecondary)
                        }
                        HorizontalDivider(color = m.line)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { transfer = null }) { Text("Скасувати") } },
        )
    }
    confirm?.let { (s, a, target) ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(a.title + "?") },
            text = { Text(NetworkAdmin.confirmText(a, s, target)) },
            confirmButton = { TextButton(onClick = { confirm = null; vm.apply(s, a, target) { message = it } }) { Text(a.title) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Скасувати") } },
        )
    }
}

// ---------- Історія ----------

/** Історія завантажень залишків мережі (meta/stock і adminLog) та останні дії адмінів. */
@Composable
fun AdminHistoryScreen(employee: Employee, onBack: () -> Unit) {
    val vm = adminVm(employee)
    val data by vm.data.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val m = Mine.colors
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { MineHeader("Історія завантажень", onBack) }
        error?.let { item { Text(it, fontSize = 15.sp, color = m.warningText) } }
        val d = data
        if (d == null) item { CircularProgressIndicator(color = m.accent) }
        else {
            val names = d.staff.associate { it.uid to it.name.ifBlank { it.phone } }
            item {
                MineCard(padding = 16.dp) {
                    Text("Останнє завантаження", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = m.textSecondary)
                    Text(
                        d.stockInfo?.let { "${date(it.updatedAt)} · ${it.updatedBy.orEmpty()} · ${it.items} товарів · ${it.stores} магазинів" }
                            ?: "Залишки мережі ще не завантажували.",
                        Modifier.padding(top = 4.dp), fontSize = 15.sp, lineHeight = 21.sp,
                    )
                }
            }
            val imports = d.history.filter { it.action == NetworkStockRepository.ACTION }
            item { Text("Завантаження залишків", Modifier.padding(start = 4.dp, top = 8.dp), fontSize = 18.sp, fontWeight = FontWeight.Bold) }
            if (imports.isEmpty()) item { Text("Записів ще немає.", fontSize = 15.sp, color = m.textSecondary) }
            items(imports, key = { "i-" + it.id }) { h -> LogRow(h, names) }
            val actions = d.history.filter { it.action != NetworkStockRepository.ACTION }.take(30)
            item { Text("Дії адмінів", Modifier.padding(start = 4.dp, top = 8.dp), fontSize = 18.sp, fontWeight = FontWeight.Bold) }
            if (actions.isEmpty()) item { Text("Записів ще немає.", fontSize = 15.sp, color = m.textSecondary) }
            items(actions, key = { "a-" + it.id }) { h -> LogRow(h, names) }
        }
        item { Spacer(Modifier.padding(12.dp)) }
    }
}

private val ACTION_TITLES = StaffAction.entries.associate { it.log to it.title } + mapOf(
    NetworkStockRepository.ACTION to "Завантаження залишків мережі",
    "actAs" to "Працювати як у магазині",
    "linkStore" to "Склад 1С ↔ магазин",
)

@Composable
private fun LogRow(h: AdminLogEntry, names: Map<String, String>) {
    val m = Mine.colors
    MineCard(padding = 14.dp) {
        Row {
            Text(ACTION_TITLES[h.action] ?: h.action, Modifier.weight(1f), fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
            Text(date(h.at), fontSize = 13.sp, color = m.textSecondary)
        }
        Text(
            listOfNotNull(names[h.by] ?: h.by, h.target.takeIf { it.isNotBlank() }?.let { names[it] ?: it }, h.details.ifBlank { null }).joinToString(" · "),
            Modifier.padding(top = 2.dp), fontSize = 14.sp, lineHeight = 19.sp, color = m.textSecondary,
        )
    }
}
