package ua.chudo.scanner.ui.stock

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ua.chudo.scanner.chudoApp
import ua.chudo.scanner.data.Employee
import ua.chudo.scanner.data.NetStore
import ua.chudo.scanner.data.NetworkImportResult
import ua.chudo.scanner.data.NetworkStock
import ua.chudo.scanner.data.NetworkStockInfo
import ua.chudo.scanner.data.NetworkStockParser
import ua.chudo.scanner.data.NetworkStockRepository
import ua.chudo.scanner.data.ShopRef
import ua.chudo.scanner.data.SiteShop
import ua.chudo.scanner.data.StoreMatcher
import ua.chudo.scanner.data.StockDiff
import ua.chudo.scanner.data.StockFileException
import ua.chudo.scanner.data.StockImportWarnings
import ua.chudo.scanner.data.StockPlan
import ua.chudo.scanner.data.StoreDoc
import ua.chudo.scanner.ui.auth.authMessage
import ua.chudo.scanner.ui.common.MineCard
import ua.chudo.scanner.ui.common.MineHeader
import ua.chudo.scanner.ui.common.MinePrimaryButton
import ua.chudo.scanner.ui.theme.Mine
import java.text.SimpleDateFormat
import java.util.Locale

/** Розібраний файл, що чекає на відповіді адміна перед записом. */
data class NetPending(
    val fresh: NetworkStock, val plan: StockPlan, val known: Map<String, StoreDoc>, val startedAt: Long,
    /** Скільки складів прив'язано до магазинів автоматично. */
    val autoLinked: Int = 0,
)

sealed interface NetState {
    data object Idle : NetState
    data class Parsing(val progress: Float) : NetState
    /** Звіряю склади 1С з магазинами сайту. */
    data object Matching : NetState
    /** «Який це магазин?» — склади без прив'язки, по одному. */
    /** [candidates] — для кожного складу магазини сайту, найкращі першими; [shops] — усі (запасний список). */
    data class Linking(
        val pending: NetPending, val unknown: List<NetStore>, val shops: List<ShopRef>, val busy: Boolean = false,
        val candidates: Map<String, List<ShopRef>> = emptyMap(),
    ) : NetState
    data class Confirm(val pending: NetPending, val text: String) : NetState
    data class Uploading(val done: Int, val total: Int) : NetState
    data class Done(val result: NetworkImportResult, val autoLinked: Int = 0) : NetState
    data class Failed(val message: String) : NetState
}

class NetworkStockViewModel(
    private val employee: Employee,
    private val repo: NetworkStockRepository,
    /** Після завантаження: показ залишків перечитає склади й дату. */
    private val onImported: suspend () -> Unit = {},
    /** Магазини з сайту (той самий список, що при реєстрації) — для автоприв'язки складів 1С. */
    private val siteShops: suspend () -> List<SiteShop> = { emptyList() },
    /** Видалення старих stock/{штрихкод}; `null` — не показуємо. */
    private val cleanup: ua.chudo.scanner.data.LegacyStockCleanup? = null,
) : ViewModel() {
    private val _state = MutableStateFlow<NetState>(NetState.Idle)
    val state: StateFlow<NetState> = _state.asStateFlow()
    private val _info = MutableStateFlow<NetworkStockInfo?>(null)
    val info: StateFlow<NetworkStockInfo?> = _info.asStateFlow()

    private val _cleanup = MutableStateFlow<ua.chudo.scanner.data.CleanupState?>(null)
    val cleanupState: StateFlow<ua.chudo.scanner.data.CleanupState?> = _cleanup.asStateFlow()
    private val _cleaning = MutableStateFlow(false)
    val cleaning: StateFlow<Boolean> = _cleaning.asStateFlow()
    private val _cleanupError = MutableStateFlow<String?>(null)
    val cleanupError: StateFlow<String?> = _cleanupError.asStateFlow()

    init {
        refreshInfo()
        cleanup?.let { c -> viewModelScope.launch { _cleanup.value = runCatching { c.state() }.getOrNull() } }
    }

    /** До 5 000 на добу; прогрес — після кожної пачки. */
    fun deleteLegacy() {
        val c = cleanup ?: return
        if (_cleaning.value) return
        _cleaning.value = true
        _cleanupError.value = null
        viewModelScope.launch {
            try {
                _cleanup.value = c.run(employee.name) { _cleanup.value = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _cleanupError.value = e.authMessage()
            } finally {
                _cleaning.value = false
            }
        }
    }

    private fun refreshInfo() = viewModelScope.launch { _info.value = runCatching { repo.info() }.getOrNull() }

    fun pick(ctx: Context, uri: Uri) {
        val startedAt = System.currentTimeMillis()
        _state.value = NetState.Parsing(0f)
        val resolver = ctx.contentResolver
        guard {
            val (fresh, plan) = withContext(Dispatchers.Default) {
                val size = runCatching { resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } }.getOrNull() ?: -1L
                val fresh = NetworkStockParser.parse(
                    { resolver.openInputStream(uri) ?: throw StockFileException("Не вдалося відкрити файл.") }, size,
                ) { p -> _state.value = NetState.Parsing(p) }
                fresh to StockDiff.plan(repo.snapshot.read(), fresh)
            }
            val known = repo.stores()
            val pending = NetPending(fresh, plan, known, startedAt)
            val unknown = fresh.stores.filter { known[it.code]?.shopId == null }
            if (unknown.isEmpty()) { confirmOrUpload(pending); return@guard }
            // Автоматично: склад 1С ↔ магазин сайту за назвою ТЦ і містом; питаємо лише про сумнівні.
            _state.value = NetState.Matching
            val shops = runCatching { siteShops() }.getOrDefault(emptyList())
            val matches = StoreMatcher.match(unknown, shops, alreadyLinked = known.values.mapNotNull { it.shopId }.toSet())
            var auto = 0
            for (m in matches) {
                val id = m.shopId ?: continue
                runCatching { repo.link(m.code, id) }.onSuccess { auto++ }
            }
            val autoCodes = matches.filter { it.shopId != null }.map { it.code }.toSet()
            val withAuto = pending.copy(
                known = pending.known + matches.filter { it.shopId != null }.associate { m ->
                    m.code to (known[m.code] ?: unknown.first { it.code == m.code }.let { StoreDoc(it.name, it.city, null) }).copy(shopId = m.shopId)
                },
                autoLinked = auto,
            )
            val rest = unknown.filter { it.code !in autoCodes }
            if (rest.isEmpty()) { confirmOrUpload(withAuto); return@guard }
            fun ref(s: SiteShop) = ShopRef(s.shopId, s.address, s.regionName)
            _state.value = NetState.Linking(
                withAuto, rest,
                shops.map(::ref).ifEmpty { repo.shops() },
                candidates = matches.associate { m -> m.code to m.candidates.map(::ref) },
            )
        }
    }

    /** [shopId] = null — пропустити склад (залишки все одно завантажаться за кодом). */
    fun link(shopId: String?) {
        val s = _state.value as? NetState.Linking ?: return
        val store = s.unknown.first()
        _state.value = s.copy(busy = true)
        guard {
            if (shopId != null) repo.link(store.code, shopId)
            val rest = s.unknown.drop(1)
            if (rest.isNotEmpty()) _state.value = s.copy(unknown = rest, busy = false) else confirmOrUpload(s.pending)
        }
    }

    private fun confirmOrUpload(p: NetPending) {
        val warnings = StockImportWarnings.of(p.fresh, p.plan)
        if (warnings.isEmpty()) upload(p) else _state.value = NetState.Confirm(p, warnings.joinToString("\n\n") + "\n\nЗавантажити?")
    }

    fun confirm() {
        (_state.value as? NetState.Confirm)?.let { upload(it.pending) }
    }

    private fun upload(p: NetPending) {
        _state.value = NetState.Uploading(0, 1)
        guard {
            val r = repo.upload(employee, p.fresh, p.plan, p.known, p.startedAt) { done, total ->
                _state.value = NetState.Uploading(done, total)
            }
            _state.value = NetState.Done(r, p.autoLinked)
            runCatching { onImported() }
            refreshInfo()
        }
    }

    fun reset() { _state.value = NetState.Idle }

    private fun guard(block: suspend () -> Unit) = viewModelScope.launch {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: StockFileException) {
            _state.value = NetState.Failed(e.message.orEmpty())
        } catch (e: OutOfMemoryError) {
            _state.value = NetState.Failed("Забракло пам'яті для розбору файлу.")
        } catch (e: Exception) {
            _state.value = NetState.Failed(e.authMessage())
        }
    }

    companion object {
        fun fmt(n: Int): String = "%,d".format(Locale.forLanguageTag("uk"), n).replace(',', ' ')
    }
}

/** «Профіль → Залишки мережі»: звіт 1С з усіма складами → `stock/{штрихкод}`. Лише для stockAdmin. */
@Composable
fun NetworkStockScreen(employee: Employee, onBack: () -> Unit) {
    val app = LocalContext.current.chudoApp
    val vm: NetworkStockViewModel = viewModel(
        key = "network-stock-${employee.uid}",
        factory = viewModelFactory { initializer { NetworkStockViewModel(
                    employee, app.networkStock, onImported = { app.networkLookup.invalidate() },
                    siteShops = { loadSiteShops(app.repository, app.prefs.allRegions.map { it.code to it.name }) },
                    cleanup = app.legacyCleanup,
                ) } },
    )
    val s by vm.state.collectAsStateWithLifecycle()
    val info by vm.info.collectAsStateWithLifecycle()
    val cleanup by vm.cleanupState.collectAsStateWithLifecycle()
    val cleaning by vm.cleaning.collectAsStateWithLifecycle()
    val cleanupError by vm.cleanupError.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.pick(ctx, it) } }
    val m = Mine.colors
    val busy = s is NetState.Parsing || s is NetState.Matching || s is NetState.Uploading || s is NetState.Linking || s is NetState.Confirm

    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        MineHeader("Залишки мережі", onBack)
        MineCard {
            Text("Звіт 1С «Ведомость по партиям товаров на складах» (.xlsx) з усіма складами.", fontSize = 15.sp, lineHeight = 21.sp)
            Spacer(Modifier.height(6.dp))
            Text(
                info?.let { i ->
                    "Останнє завантаження: ${i.updatedAt?.let { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.forLanguageTag("uk")).format(it) } ?: "—"}" +
                        (i.updatedBy?.let { " · $it" } ?: "") + " · ${NetworkStockViewModel.fmt(i.items)} товарів · ${i.stores} магазинів"
                } ?: "Залишки мережі ще не завантажували.",
                fontSize = 14.sp, lineHeight = 19.sp, color = m.textSecondary,
            )
        }
        when (val st = s) {
            is NetState.Parsing -> MineCard {
                Text("Розбираю файл… ${(st.progress * 100).toInt()}%", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator({ st.progress }, Modifier.fillMaxWidth())
            }
            NetState.Matching -> MineCard {
                Text("Звіряю склади 1С з магазинами мережі…", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            is NetState.Uploading -> MineCard {
                Text("Записую: ${NetworkStockViewModel.fmt(st.done)} з ${NetworkStockViewModel.fmt(st.total)}", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(10.dp))
                if (st.total > 0) LinearProgressIndicator({ st.done.toFloat() / st.total }, Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            is NetState.Done -> MineCard {
                val r = st.result
                Text("Готово", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = m.success)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Магазинів: ${r.stores} · Товарів: ${NetworkStockViewModel.fmt(r.items)} · Змінено: ${NetworkStockViewModel.fmt(r.changed)} · " +
                        "Видалено: ${NetworkStockViewModel.fmt(r.removed)} · Час: ${r.seconds} с",
                    fontSize = 15.sp, lineHeight = 21.sp,
                )
                if (st.autoLinked > 0) {
                    Spacer(Modifier.height(6.dp))
                    Text("Склади 1С автоматично прив'язано до магазинів: ${st.autoLinked}. Кожен магазин бачить свої залишки.", fontSize = 14.sp, lineHeight = 19.sp, color = m.success)
                }
                if (r.keptStores.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "У файлі немає товарів магазинів ${r.keptStores.joinToString { "ЧО $it" }} — їхні залишки не змінено.",
                        fontSize = 14.sp, lineHeight = 19.sp, color = m.warningText,
                    )
                }
            }
            is NetState.Failed -> Text(st.message, fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium, color = m.warningText)
            else -> Unit
        }
        MinePrimaryButton(if (s is NetState.Done || s is NetState.Failed) "Вибрати інший файл" else "Вибрати файл xlsx", {
            vm.reset()
            picker.launch(arrayOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/octet-stream", "*/*"))
        }, height = 58.dp, enabled = !busy)
        cleanup?.let { c -> LegacyCleanupCard(c, cleaning, cleanupError, vm::deleteLegacy) }
        Spacer(Modifier.height(24.dp))
    }

    when (val st = s) {
        is NetState.Linking -> LinkDialog(st, onPick = vm::link, onCancel = vm::reset)
        is NetState.Confirm -> AlertDialog(
            onDismissRequest = vm::reset,
            title = { Text("Перевір перед завантаженням") },
            text = { Text(st.text) },
            confirmButton = { TextButton(onClick = vm::confirm) { Text("Завантажити") } },
            dismissButton = { TextButton(onClick = vm::reset) { Text("Скасувати") } },
        )
        else -> Unit
    }
}

/** «Який це магазин?» для складу 1С без прив'язки. */
@Composable
private fun LinkDialog(s: NetState.Linking, onPick: (String?) -> Unit, onCancel: () -> Unit) {
    val store = s.unknown.first()
    val m = Mine.colors
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Який це магазин?") },
        text = {
            Column {
                Text(store.name, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
                Text("Складів без прив'язки: ${s.unknown.size}", fontSize = 13.sp, color = m.textSecondary)
                Spacer(Modifier.height(10.dp))
                val list = (s.candidates[store.code].orEmpty() + s.shops).distinctBy { it.id }
                if (list.isEmpty()) {
                    Text("У базі ще немає магазинів застосунку. Можна пропустити — залишки все одно завантажаться.", fontSize = 14.sp, color = m.textSecondary)
                } else {
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(list, key = { it.id }) { shop ->
                            Column(Modifier.fillMaxWidth().clickable(enabled = !s.busy) { onPick(shop.id) }.padding(vertical = 10.dp)) {
                                Text(shop.address.ifBlank { shop.id }, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                                if (shop.region.isNotBlank()) Text(shop.region, fontSize = 13.sp, color = m.textSecondary)
                            }
                            HorizontalDivider(color = m.line)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(null) }, enabled = !s.busy) { Text("Пропустити") } },
        dismissButton = { TextButton(onClick = onCancel, enabled = !s.busy) { Text("Скасувати") } },
    )
}

/** Усі магазини мережі з сайту (по 4 міста одночасно); id — як у анкеті сотрудника (AuthRepository.shopId). */
suspend fun loadSiteShops(repo: ua.chudo.scanner.data.ChudoRepository, regions: List<Pair<String, String>>): List<SiteShop> =
    kotlinx.coroutines.coroutineScope {
        regions.chunked(4).flatMap { chunk ->
            chunk.map { (code, name) ->
                async {
                    runCatching { repo.shops(SITE_SHOPS_PRODUCT_ID, code) }.getOrDefault(emptyList())
                        .map { it.address }.distinct()
                        .map { a -> SiteShop(ua.chudo.scanner.data.AuthRepository.shopId(code, a), code, name, a) }
                }
            }.awaitAll().flatten()
        }
    }

/** Будь-який товар: сайт віддає список магазинів міста разом із наявністю (як при реєстрації). */
private const val SITE_SHOPS_PRODUCT_ID = 1L

/** Старий формат `stock/{штрихкод}`: видалення пачками, до 5 000 на добу. */
@Composable
private fun LegacyCleanupCard(s: ua.chudo.scanner.data.CleanupState, busy: Boolean, error: String?, onDelete: () -> Unit) {
    val m = Mine.colors
    val daily = ua.chudo.scanner.data.LegacyStockCleanup.DAILY
    MineCard {
        Text("Старі залишки (stock/…)", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        val left = s.remaining
        Text(
            when {
                left == 0L -> "Старих залишків немає — усе видалено."
                left == null -> "Сьогодні видалено ${NetworkStockViewModel.fmt(s.deletedToday)} з ${NetworkStockViewModel.fmt(daily)}."
                else -> "Лишилося: ${NetworkStockViewModel.fmt(left.toInt())} · сьогодні видалено ${NetworkStockViewModel.fmt(s.deletedToday)} з ${NetworkStockViewModel.fmt(daily)}"
            },
            fontSize = 14.sp, lineHeight = 19.sp, color = m.textSecondary,
        )
        if (busy) {
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator({ s.deletedToday.toFloat() / daily }, Modifier.fillMaxWidth())
        }
        if (left != 0L && s.leftToday == 0 && !busy) {
            Spacer(Modifier.height(6.dp))
            Text("Ліміт на сьогодні вичерпано — продовжте завтра.", fontSize = 14.sp, color = m.warningText)
        }
        error?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, fontSize = 14.sp, color = m.warningText)
        }
        if (left != 0L) {
            Spacer(Modifier.height(10.dp))
            ua.chudo.scanner.ui.common.MineButton(
                "Видалити старі залишки (stock/…)", onDelete, Modifier.fillMaxWidth(),
                enabled = !busy && s.leftToday > 0,
            )
        }
    }
}
