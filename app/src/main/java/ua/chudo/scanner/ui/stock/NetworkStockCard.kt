package ua.chudo.scanner.ui.stock

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ua.chudo.scanner.data.NetAvailability
import ua.chudo.scanner.ui.theme.Mine
import ua.chudo.scanner.ui.common.PriceFont
import java.text.SimpleDateFormat
import java.util.Locale

/** Кількість без «,0»: 3 → «3», 2.5 → «2,5». */
fun qtyText(v: Double): String = if (v == Math.floor(v)) v.toLong().toString() else v.toString().replace('.', ',')

/** Залишки мережі з 1С: свій магазин великим шрифтом, магазини міста з кількостями, дата оновлення. */
@Composable
fun NetworkStockCard(av: NetAvailability, modifier: Modifier = Modifier) {
    val m = Mine.colors
    Surface(modifier, shape = RoundedCornerShape(20.dp), color = m.card, contentColor = m.text, border = BorderStroke(1.dp, m.line)) {
        Column(Modifier.fillMaxWidth().padding(18.dp)) {
            Text("У вашому магазині", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = m.textSecondary)
            val own = av.own
            if (own != null) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        qtyText(own), fontFamily = PriceFont, fontWeight = FontWeight.ExtraBold, fontSize = 48.sp, lineHeight = 52.sp,
                        color = if (own > 0) m.success else m.logout,
                    )
                    Text(" шт", Modifier.padding(bottom = 8.dp), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            } else {
                Text(
                    "Магазин ще не прив'язано до складу 1С — це робить адмін залишків.",
                    Modifier.padding(top = 6.dp), fontSize = 15.sp, lineHeight = 20.sp, color = m.warningText,
                )
            }

            if (av.cityName != null) {
                Text(
                    "У магазинах міста" + " · ${av.cityName}",
                    Modifier.padding(top = 14.dp, bottom = 4.dp), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = m.textSecondary,
                )
                if (av.city.isEmpty()) {
                    Text("В інших магазинах міста немає.", fontSize = 15.sp, color = m.textSecondary)
                } else {
                    av.city.forEachIndexed { i, s ->
                        if (i > 0) HorizontalDivider(color = m.line)
                        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(s.name, Modifier.weight(1f), fontSize = 15.sp, lineHeight = 20.sp)
                            Text("${qtyText(s.qty)} шт", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            av.updatedAt?.let {
                Text(
                    "Оновлено " + SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.forLanguageTag("uk")).format(it),
                    Modifier.padding(top = 12.dp), fontSize = 13.sp, color = m.textSecondary,
                )
            }
        }
    }
}
