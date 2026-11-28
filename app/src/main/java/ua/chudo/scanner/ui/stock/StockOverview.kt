package ua.chudo.scanner.ui.stock

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import ua.chudo.scanner.data.StoreItem
import ua.chudo.scanner.ui.theme.Mine
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Порядок магазинів у «Залишки по магазинах»: свій першим, далі магазини свого міста,
 * далі решта — за містом; усередині — за номером ЧО.
 */
fun storeOrder(stores: Map<String, StoreDoc>, own: String?): List<Pair<String, StoreDoc>> {
    val ownCity = own?.let { stores[it]?.city?.trim()?.lowercase() }
    fun num(code: String) = code.toIntOrNull() ?: Int.MAX_VALUE
    return stores.entries.sortedWith(
        compareBy<Map.Entry<String, StoreDoc>> { it.key != own }
            .thenBy { ownCity == null || it.value.city.trim().lowercase() != ownCity }
            .thenBy { it.value.city.trim().lowercase() }
            .thenBy { num(it.key) }.thenBy { it.key },
    ).map { it.key to it.value }
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
    val shape = RoundedCornerShape(16.dp)
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

/** Магазини мережі великими пігулками: свій — «Мій · ЧО 22». Гортаються вбік. */
@Composable
fun StoreChips(stores: List<Pair<String, StoreDoc>>, own: String?, selected: String?, onPick: (String) -> Unit, modifier: Modifier = Modifier) {
    val m = Mine.colors
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        stores.forEach { (code, store) ->
            val active = code == selected
            Surface(
                Modifier.height(52.dp).selectable(active, role = Role.Tab, onClick = { onPick(code) }),
                shape = RoundedCornerShape(16.dp), color = if (active) m.accentTone else m.card,
                border = BorderStroke(1.dp, if (active) m.accentTone else m.line),
            ) {
                Column(Modifier.padding(horizontal = 14.dp), verticalArrangement = Arrangement.Center) {
                    Text(
                        (if (code == own) "Мій · " else "") + "ЧО $code", fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                        color = if (active) m.link else m.text, maxLines = 1,
                    )
                    if (store.city.isNotBlank()) Text(store.city, fontSize = 12.sp, color = m.textTertiary, maxLines = 1)
                }
            }
        }
    }
}

/** Товар у вибраному магазині: назва, артикул і кількість великим праворуч. */
@Composable
fun StoreItemRow(item: StoreItem, onClick: () -> Unit) {
    val m = Mine.colors
    Row(
        Modifier.fillMaxWidth().background(m.card).clickable(onClick = onClick).heightIn(min = 64.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(48.dp).background(m.surface, RoundedCornerShape(12.dp)).border(1.dp, m.line, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) { Icon(painterResource(R.drawable.ic_redesign_package), null, Modifier.size(22.dp), tint = m.textTertiary) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(item.item.name.ifBlank { item.barcode }, fontSize = 15.sp, lineHeight = 19.5.sp, fontWeight = FontWeight.Medium, color = m.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                listOf(item.item.article.takeIf(String::isNotBlank)?.let { "арт. $it" }, item.item.code.takeIf(String::isNotBlank))
                    .filterNotNull().joinToString(" · "),
                fontSize = 13.sp, color = m.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Text("${qtyText(item.qty)} шт", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = m.text)
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
