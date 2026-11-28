package ua.chudo.scanner.ui.stock

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import ua.chudo.scanner.R
import ua.chudo.scanner.chudoApp
import ua.chudo.scanner.data.NetItem
import ua.chudo.scanner.data.StoreDoc
import ua.chudo.scanner.ui.theme.Mine
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Рядок «Наявності в мережі»: магазин, місто, кількість; свій — з позначкою «ваш». */
data class NetStoreRow(val code: String, val name: String, val city: String, val qty: Double, val own: Boolean)

/** Місто-чип: назва й сума по місту. */
data class NetCity(val name: String, val total: Double, val rows: List<NetStoreRow>)

/**
 * Міста з магазинами, де товар є (свій магазин — завжди, навіть з нулем).
 * Своє місто першим, далі за кількістю; у місті свій магазин зверху, потім за спаданням.
 */
fun networkCities(item: NetItem, stores: Map<String, StoreDoc>, ownCode: String?, ownName: String): List<NetCity> {
    // Усі склади мережі (як у дизайні: місто з нулем теж видно), плюс коди з товару, яких немає в довіднику.
    val codes = (stores.keys + item.q.filter { (code, qty) -> qty > 0 || code == ownCode }.keys).distinct()
    val rows = codes.map { code -> code to (item.q[code] ?: 0.0) }.map { (code, qty) ->
        val doc = stores[code]
        NetStoreRow(
            code,
            if (code == ownCode && ownName.isNotBlank()) ownName else doc?.name?.ifBlank { null } ?: "ЧО $code",
            doc?.city?.trim()?.ifEmpty { null } ?: "Інше", qty, code == ownCode,
        )
    }.toMutableList()
    if (ownCode != null && rows.none { it.own }) {
        rows += NetStoreRow(ownCode, ownName.ifBlank { "ЧО $ownCode" }, stores[ownCode]?.city?.trim()?.ifEmpty { null } ?: "Інше", 0.0, true)
    }
    return rows.groupBy { it.city.lowercase() }.map { (_, list) ->
        NetCity(
            list.first().city,
            list.sumOf { it.qty },
            list.sortedWith(compareByDescending<NetStoreRow> { it.own }.thenByDescending { it.qty }.thenBy { it.code }),
        )
    }.sortedWith(compareByDescending<NetCity> { c -> c.rows.any { it.own } }.thenByDescending { it.total }.thenBy { it.name })
}

private fun qty(v: Double): String = if (v % 1.0 == 0.0) v.toLong().toString() else String.format(Locale.forLanguageTag("uk"), "%.1f", v)

/** «Дані 1С на сьогодні, 09:30» / «Дані 1С на 25.11, 09:30». */
fun stockStamp(at: Date?): String {
    at ?: return "Дані 1С"
    val day = Calendar.getInstance().apply { time = at }
    val now = Calendar.getInstance()
    val today = day.get(Calendar.YEAR) == now.get(Calendar.YEAR) && day.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)
    val time = SimpleDateFormat("HH:mm", Locale.forLanguageTag("uk")).format(at)
    return if (today) "Дані 1С на сьогодні, $time" else "Дані 1С на ${SimpleDateFormat("dd.MM", Locale.forLanguageTag("uk")).format(at)}, $time"
}

/** «Наявність у мережі»: чипи міст і магазини вибраного міста. Відкривається на весь екран. */
@Composable
fun NetworkAvailabilityDialog(
    item: NetItem,
    ownCode: String?,
    ownName: String,
    title: String,
    updatedAt: Date?,
    onClose: () -> Unit,
) {
    val app = LocalContext.current.chudoApp
    val stores by produceState<Map<String, StoreDoc>?>(null) {
        value = runCatching { app.networkLookup.stores() }.getOrDefault(emptyMap())
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        NetworkAvailabilityContent(
            stores?.let { networkCities(item, it, ownCode, ownName) }, title, updatedAt, onClose,
        )
    }
}

@Composable
fun NetworkAvailabilityContent(cities: List<NetCity>?, title: String, updatedAt: Date?, onBack: () -> Unit) {
    val m = Mine.colors
    var picked by rememberSaveable { mutableStateOf<String?>(null) }
    val city = cities?.firstOrNull { it.name == picked } ?: cities?.firstOrNull()
    Column(Modifier.fillMaxSize().background(m.bg).statusBarsPadding().navigationBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(painterResource(R.drawable.ic_redesign_chevron_left), "Назад", Modifier.size(24.dp), tint = m.textSecondary)
            }
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                Text("Наявність у мережі", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = m.text, maxLines = 1)
                Text(title, fontSize = 12.sp, lineHeight = 14.sp, color = m.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 16.dp, top = 4.dp, end = 16.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            cities.orEmpty().forEach { c -> CityChip(c, c == city) { picked = c.name } }
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            if (cities == null) item {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    androidx.compose.material3.CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = m.accent)
                }
            }
            if (city != null) {
                item { HorizontalDivider(color = m.line) }
                items(city.rows, key = { it.code }) { row -> StoreRow(row) }
            }
            item {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(painterResource(R.drawable.ic_redesign_clock), null, Modifier.padding(top = 1.dp).size(16.dp), tint = m.textTertiary)
                    Text(
                        "${stockStamp(updatedAt)}. Перед тим як направити покупця, зателефонуйте в магазин.",
                        fontSize = 13.sp, lineHeight = 18.sp, color = m.textTertiary,
                    )
                }
            }
        }
    }
}

@Composable
private fun CityChip(city: NetCity, selected: Boolean, onClick: () -> Unit) {
    val m = Mine.colors
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier.height(32.dp).background(if (selected) m.text else m.card, shape)
            .border(1.dp, if (selected) m.text else m.outline, shape)
            .selectable(selected, role = Role.Tab, onClick = onClick).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        val color = if (selected) m.card else m.textSecondary
        Text(city.name, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = color)
        Text(qty(city.total), fontSize = 12.sp, color = color.copy(alpha = 0.75f))
    }
}

@Composable
private fun StoreRow(row: NetStoreRow) {
    val m = Mine.colors
    Row(
        Modifier.fillMaxWidth().background(m.card).heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(row.name, Modifier.weight(1f, fill = false), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = m.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (row.own) Box(
                    Modifier.height(22.dp).background(m.surface, RoundedCornerShape(8.dp)).border(1.dp, m.line, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp),
                    contentAlignment = Alignment.Center,
                ) { Text("ваш", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = m.textSecondary) }
            }
            Text(row.city, fontSize = 13.sp, color = m.textTertiary)
        }
        Text(
            if (row.qty > 0) "${qty(row.qty)} шт" else "немає", fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
            color = when {
                row.qty <= 0 -> m.textTertiary
                row.qty <= 2 -> m.warningText
                else -> m.success
            },
        )
    }
    HorizontalDivider(color = m.line)
}
