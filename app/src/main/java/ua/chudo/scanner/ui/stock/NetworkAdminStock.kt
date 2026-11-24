package ua.chudo.scanner.ui.stock

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ua.chudo.scanner.data.CityStock
import ua.chudo.scanner.data.StoreDoc
import ua.chudo.scanner.data.StoreItem
import ua.chudo.scanner.ui.common.MineChip
import ua.chudo.scanner.chudoApp
import ua.chudo.scanner.ui.theme.Mine

/** Адмін: товар у всіх магазинах мережі — по містах, своє місто першим. */
@Composable
fun NetworkDistributionCard(groups: List<CityStock>, ownCode: String?) {
    val m = Mine.colors
    Surface(shape = RoundedCornerShape(20.dp), color = m.card, contentColor = m.text, border = BorderStroke(1.dp, m.line)) {
        Column(Modifier.fillMaxWidth().padding(18.dp)) {
            Text("По магазинах мережі", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = m.textSecondary)
            if (groups.isEmpty()) {
                Text("Товару немає в жодному магазині.", Modifier.padding(top = 6.dp), fontSize = 15.sp, color = m.textSecondary)
            }
            groups.forEach { g ->
                Row(Modifier.padding(top = 14.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(g.city, Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text("разом ${qtyText(g.total)} шт", fontSize = 14.sp, color = m.textSecondary)
                }
                g.stores.forEachIndexed { i, s ->
                    if (i > 0) HorizontalDivider(color = m.line)
                    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            s.name + if (s.code == ownCode) " · ваш" else "", Modifier.weight(1f), fontSize = 15.sp, lineHeight = 20.sp,
                            fontWeight = if (s.code == ownCode) FontWeight.Bold else FontWeight.Normal,
                            color = if (s.code == ownCode) m.accent else m.text,
                        )
                        Text("${qtyText(s.qty)} шт", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

/** Адмін: «Що в магазині» — вибрати ЧО NN і побачити його товари з кількістю. */
@Composable
fun StoreStockCard(
    stores: List<Pair<String, StoreDoc>>, selected: String?, items: List<StoreItem>?, loading: Boolean,
    onPick: (String) -> Unit, onOpen: (StoreItem) -> Unit,
) {
    val m = Mine.colors
    Surface(shape = RoundedCornerShape(20.dp), color = m.card, contentColor = m.text, border = BorderStroke(1.dp, m.line)) {
        Column(Modifier.fillMaxWidth().padding(18.dp)) {
            Text("Що в магазині", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(
                if (stores.isEmpty()) "Залишки мережі ще не завантажували." else "Обери магазин — покажемо його товари, найбільші залишки першими.",
                Modifier.padding(top = 4.dp), fontSize = 14.sp, lineHeight = 19.sp, color = m.textSecondary,
            )
            Row(Modifier.padding(top = 10.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                stores.forEach { (code, st) -> MineChip("ЧО $code" + st.city.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty(), selected == code, { onPick(code) }) }
            }
            if (selected != null) {
                stores.firstOrNull { it.first == selected }?.second?.name?.let {
                    Text(it, Modifier.padding(top = 10.dp), fontSize = 14.sp, color = m.textSecondary)
                }
                when {
                    loading -> CircularProgressIndicator(Modifier.padding(top = 12.dp), color = m.accent)
                    items.isNullOrEmpty() -> Text("Товарів із залишком немає.", Modifier.padding(top = 10.dp), fontSize = 15.sp, color = m.textSecondary)
                    else -> {
                        Text("Показано: ${items.size}" + if (items.size >= 100) " (найбільші залишки)" else "", Modifier.padding(top = 10.dp), fontSize = 13.sp, color = m.textSecondary)
                        items.forEachIndexed { i, it ->
                            if (i > 0) HorizontalDivider(color = m.line)
                            Row(Modifier.fillMaxWidth().clickable { onOpen(it) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(it.item.name.ifBlank { it.barcode }, fontSize = 15.sp, lineHeight = 20.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(listOf(it.item.code, it.item.article).filter { s -> s.isNotBlank() }.joinToString(" · "), fontSize = 13.sp, color = m.textSecondary)
                                }
                                Text("${qtyText(it.qty)} шт", Modifier.padding(start = 10.dp), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Адмін залишків / мережі на вкладці «Залишки»: один звіт 1С на всю мережу. Застосунок розкладає кількість
 * по складах «ЧО NN», а склади прив'язані до магазинів — кожен магазин бачить свої залишки.
 */
@Composable
fun NetworkUploadCard(onUpload: () -> Unit) {
    val m = Mine.colors
    val app = androidx.compose.ui.platform.LocalContext.current.chudoApp
    var info by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<ua.chudo.scanner.data.NetworkStockInfo?>(null) }
    androidx.compose.runtime.LaunchedEffect(Unit) { info = runCatching { app.networkStock.info() }.getOrNull() }
    Surface(shape = RoundedCornerShape(20.dp), color = m.card, contentColor = m.text, border = BorderStroke(1.dp, m.line)) {
        Column(Modifier.fillMaxWidth().padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ua.chudo.scanner.ui.common.StatusDot(if (info == null) m.warning else m.success)
                Text(
                    info?.let { i -> "  Залишки мережі оновлено " + (i.updatedAt?.let { java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.forLanguageTag("uk")).format(it) } ?: "—") }
                        ?: "  Залишки мережі ще не завантажено",
                    fontSize = 16.sp, fontWeight = FontWeight.Bold,
                )
            }
            info?.let { i ->
                Text(
                    "${i.items} товарів · ${i.stores} магазинів" + (i.updatedBy?.let { ", завантажив(ла) $it" } ?: ""),
                    Modifier.padding(top = 4.dp), fontSize = 14.sp, color = m.textSecondary,
                )
            }
            ua.chudo.scanner.ui.common.MineButton(
                "Завантажити файл 1С на всю мережу", onUpload, Modifier.padding(top = 14.dp).fillMaxWidth(),
                tone = if (info == null) ua.chudo.scanner.ui.common.MineTone.ACCENT else ua.chudo.scanner.ui.common.MineTone.OUTLINE,
            )
            Text(
                "Звіт «Ведомость по партиям товаров на складах» (.xlsx). Кожен магазин побачить свої залишки, ти — усі.",
                Modifier.padding(top = 8.dp), fontSize = 13.sp, lineHeight = 18.sp, color = m.textSecondary,
            )
        }
    }
}
