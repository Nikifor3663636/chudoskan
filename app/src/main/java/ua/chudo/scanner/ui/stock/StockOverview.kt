package ua.chudo.scanner.ui.stock

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ua.chudo.scanner.R
import ua.chudo.scanner.data.SbStockRow
import ua.chudo.scanner.data.ShopStockInfo
import ua.chudo.scanner.data.StoreDoc
import ua.chudo.scanner.ui.theme.Mine
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Вкладки огляду залишків. */
enum class StockTab(val title: String) { PROMO("Акції"), NEW("Новинки"), LOW("Закінчуються") }

/** Товар, що закінчується: у своєму магазині ≤ 1 шт, а в місті ще є. */
data class LowItem(val row: SbStockRow, val own: Double, val city: Double)

/** Відбір «Закінчуються»: свій магазин ≤ 1, інші магазини міста > 0; спершу «немає», далі за назвою. */
fun lowStock(rows: List<SbStockRow>, ownCode: String, stores: Map<String, StoreDoc>): List<LowItem> {
    val ownCity = stores[ownCode]?.city?.trim()?.lowercase()
    return rows.mapNotNull { r ->
        val own = r.q[ownCode] ?: 0.0
        if (own > 1.0) return@mapNotNull null
        val city = r.q.filter { (code, qty) ->
            code != ownCode && qty > 0 && (ownCity == null || stores[code]?.city?.trim()?.lowercase() == ownCity)
        }.values.sum()
        if (city <= 0.0) null else LowItem(r, own, city)
    }.sortedWith(compareBy<LowItem> { it.own }.thenBy { it.row.name })
}

/** «Оновлено сьогодні о 09:30» / «Оновлено 25.11 о 09:30». */
fun updatedTitle(at: Date?): String {
    at ?: return "Залишки ще не завантажено"
    val day = Calendar.getInstance().apply { time = at }
    val now = Calendar.getInstance()
    val today = day.get(Calendar.YEAR) == now.get(Calendar.YEAR) && day.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)
    val time = SimpleDateFormat("HH:mm", Locale.forLanguageTag("uk")).format(at)
    return if (today) "Оновлено сьогодні о $time" else "Оновлено ${SimpleDateFormat("dd.MM", Locale.forLanguageTag("uk")).format(at)} о $time"
}

/** «21 870» з нерозривним пробілом. */
fun groupThousands(n: Int): String = String.format(Locale.forLanguageTag("uk"), "%,d", n).replace(',', ' ').replace(' ', ' ')

@Composable
fun StockTitle(shop: String) {
    val m = Mine.colors
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp)) {
        Text("Залишки", fontSize = 26.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.39).sp, color = m.text)
        if (shop.isNotBlank()) Text(shop, Modifier.padding(top = 2.dp), fontSize = 14.sp, color = m.textSecondary)
    }
}

/** `.banner.neutral`: коли оновлено, скільки товарів, хто завантажив. */
@Composable
fun StockBanner(info: ShopStockInfo?, stores: Int, modifier: Modifier = Modifier) {
    val m = Mine.colors
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier.fillMaxWidth().background(m.surface, shape).border(1.dp, m.line, shape).padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(painterResource(R.drawable.ic_redesign_database), null, Modifier.padding(top = 1.dp).size(18.dp), tint = m.textSecondary)
        val details = listOfNotNull(
            info?.rows?.takeIf { it > 0 }?.let { "${groupThousands(it)} товарів" },
            stores.takeIf { it > 0 }?.let { "$it магазинів" },
            info?.updatedBy?.takeIf { it.isNotBlank() }?.let { "завантажив(ла) $it" },
        ).joinToString(" · ")
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = m.text, fontWeight = FontWeight.SemiBold)) { append(updatedTitle(info?.updatedAt)) }
                if (details.isNotEmpty()) append("\n$details")
            },
            fontSize = 14.sp, lineHeight = 19.6.sp, color = m.textSecondary,
        )
    }
}

@Composable
fun StockSegments(tab: StockTab, counts: Map<StockTab, Long?>, onTab: (StockTab) -> Unit, modifier: Modifier = Modifier) {
    val m = Mine.colors
    Row(
        modifier.fillMaxWidth().background(m.surface, RoundedCornerShape(8.dp))
            .border(1.dp, m.line, RoundedCornerShape(8.dp)).selectableGroup().padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        StockTab.entries.forEach { t ->
            val selected = t == tab
            Surface(
                Modifier.weight(1f).height(34.dp).selectable(selected, role = Role.Tab, onClick = { onTab(t) }),
                shape = RoundedCornerShape(6.dp), border = if (selected) BorderStroke(1.dp, m.line) else null,
                color = if (selected) m.card else Color.Transparent,
            ) {
                Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Text(t.title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = if (selected) m.text else m.textSecondary, maxLines = 1)
                    counts[t]?.let {
                        Spacer(Modifier.width(6.dp))
                        Text("$it", fontSize = 12.sp, color = m.textTertiary)
                    }
                }
            }
        }
    }
}

/** Рядок товару, що закінчується: «1 шт» жовтим або «немає», під ним — скільки в місті. */
@Composable
fun LowStockRow(item: LowItem, onClick: () -> Unit) {
    val m = Mine.colors
    Row(
        Modifier.fillMaxWidth().background(m.card).clickable(onClick = onClick).heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(48.dp).background(m.surface, RoundedCornerShape(6.dp)).border(1.dp, m.line, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) { Icon(painterResource(R.drawable.ic_redesign_package), null, Modifier.size(22.dp), tint = m.textTertiary) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(item.row.name.ifBlank { item.row.barcode }, fontSize = 15.sp, lineHeight = 19.5.sp, fontWeight = FontWeight.Medium, color = m.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                listOf(item.row.article.takeIf(String::isNotBlank)?.let { "арт. $it" }, item.row.barcode.takeIf(String::isNotBlank))
                    .filterNotNull().joinToString(" · "),
                fontSize = 13.sp, color = m.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                if (item.own > 0) "${item.own.toLong()} шт" else "немає", fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                color = if (item.own > 0) m.warningText else m.textTertiary,
            )
            Text("у місті ${item.city.toLong()}", fontSize = 13.sp, color = m.textTertiary)
        }
    }
    HorizontalDivider(color = m.line)
}

@Composable
fun StockListMessage(text: String, loading: Boolean = false) {
    val m = Mine.colors
    Column(Modifier.fillMaxWidth().background(m.card)) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp), contentAlignment = Alignment.CenterStart) {
            if (loading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = m.accent)
            else Text(text, fontSize = 14.sp, color = m.textSecondary)
        }
        HorizontalDivider(color = m.line)
    }
}

/** Заголовок розділу `.sec-h` з лінією під ним. */
@Composable
fun StockSection(text: String) {
    val m = Mine.colors
    Column {
        Text(text, Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = m.textSecondary)
        HorizontalDivider(color = m.line)
    }
}

@Composable
fun StockLinkRow(@DrawableRes icon: Int, title: String, sub: String, onClick: () -> Unit) {
    val m = Mine.colors
    Row(
        Modifier.fillMaxWidth().background(m.card).clickable(onClick = onClick).heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(icon), null, Modifier.size(20.dp), tint = m.textSecondary)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = m.text)
            Text(sub, fontSize = 13.sp, color = m.textTertiary)
        }
        Icon(painterResource(R.drawable.ic_redesign_chevron_right), null, Modifier.size(18.dp), tint = m.textTertiary)
    }
    HorizontalDivider(color = m.line)
}
