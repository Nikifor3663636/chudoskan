package ua.chudo.scanner.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ua.chudo.scanner.ui.theme.Mine
import ua.chudo.scanner.ui.theme.MineColors
import ua.chudo.scanner.ui.theme.QtyStyle

/** Залишок: «є», «мало» (1–2 шт), «немає». Колір ніколи не єдиний сигнал — є іконка й число. */
enum class StockLevel(val word: String, val icon: ImageVector) {
    IN("В наявності", Icons.Rounded.CheckCircle),
    LOW("Мало", Icons.Rounded.Warning),
    OUT("Немає", Icons.Rounded.Block),
}

/** Поріг дизайн-системи: 0 — немає, до 2 шт включно — мало. */
fun stockLevel(qty: Double): StockLevel = when {
    qty <= 0.0 -> StockLevel.OUT
    qty <= 2.0 -> StockLevel.LOW
    else -> StockLevel.IN
}

/** Колір числа й підкладки для рівня. */
fun stockPaint(level: StockLevel, m: MineColors): Pair<Color, Color> = when (level) {
    StockLevel.IN -> m.stockIn to m.stockInBg
    StockLevel.LOW -> m.stockLow to m.stockLowBg
    StockLevel.OUT -> m.stockOut to m.stockOutBg
}

/** «3 шт», «2,5 шт». */
fun pcs(qty: Double): String = (if (qty == Math.floor(qty)) qty.toLong().toString() else qty.toString().replace('.', ',')) + " шт"

/**
 * Блок залишку в картці товару: свій магазин (28 sp, колір рівня) і місто справа.
 * [own] null — свій склад 1С невідомий; [city] null — міста не показуємо.
 */
@Composable
fun StockBox(own: Double?, cityLabel: String?, city: Double?, modifier: Modifier = Modifier) {
    val m = Mine.colors
    val level = own?.let(::stockLevel)
    val (fg, bg) = level?.let { stockPaint(it, m) } ?: (m.textSecondary to m.field)
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(bg).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Твій магазин", fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, color = m.textSecondary)
            if (own == null) {
                Text("Склад 1С не прив'язано", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = m.warningText)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(level!!.icon, level.word, Modifier.size(22.dp), tint = fg)
                    Text(pcs(own), style = QtyStyle, color = fg)
                }
            }
        }
        if (cityLabel != null && city != null) {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(cityLabel, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, color = m.textSecondary, textAlign = TextAlign.End)
                Text(pcs(city), fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold, color = m.text)
            }
        }
    }
}

/** Кількість у рядку списку: число на підкладці рівня. */
@Composable
fun QtyPill(qty: Double, modifier: Modifier = Modifier) {
    val (fg, bg) = stockPaint(stockLevel(qty), Mine.colors)
    Text(
        pcs(qty), modifier.clip(RoundedCornerShape(8.dp)).background(bg).padding(horizontal = 8.dp, vertical = 4.dp),
        fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold, color = fg,
    )
}
