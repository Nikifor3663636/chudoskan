package ua.chudo.scanner.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ua.chudo.scanner.R
import ua.chudo.scanner.print.PriceFormat
import ua.chudo.scanner.print.PROMO_PAPER
import ua.chudo.scanner.ui.theme.Mine

/** Montserrat — тот же шрифт цены, что на печатных ценниках. */
val PriceFont = FontFamily(Font(R.font.montserrat_extrabold, FontWeight.ExtraBold), Font(R.font.montserrat_bold, FontWeight.Bold))

/** Пошук за текстом або штрихкодом. */
@Composable
fun MineSearchField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
    onScan: (() -> Unit)? = null,
) {
    val m = Mine.colors
    Row(
        modifier.fillMaxWidth().height(56.dp).clip(Mine.fieldShape).background(m.card)
            .border(1.dp, m.outline, Mine.fieldShape).padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Search, null, tint = m.textSecondary)
        Spacer(Modifier.width(12.dp))
        BasicTextField(
            value = value, onValueChange = onChange, singleLine = true,
            textStyle = TextStyle(fontSize = 17.sp, color = m.text),
            cursorBrush = SolidColor(m.accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                if (value.isEmpty()) Text(placeholder, fontSize = 17.sp, color = m.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                inner()
            },
        )
        if (value.isNotEmpty()) IconButton(onClick = { onChange("") }) { Icon(Icons.Rounded.Close, "Очистити", tint = m.textSecondary) }
        onScan?.let { open -> IconButton(onClick = open) { Icon(BarcodeScanIcon, "Сканувати штрихкод", tint = m.link) } }
    }
}

/** Пилюля города: значок места акцентом + название. */
@Composable
fun MinePlacePill(text: String, onClick: () -> Unit) {
    val m = Mine.colors
    Surface(onClick = onClick, shape = RoundedCornerShape(999.dp), color = m.card, contentColor = m.text, border = BorderStroke(1.dp, m.line)) {
        Row(Modifier.height(44.dp).padding(start = 14.dp, end = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.LocationOn, null, Modifier.size(20.dp), tint = m.accent)
            Spacer(Modifier.width(8.dp))
            Text(text, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

/** Количество в магазине: зелёная пилюля «9 шт», «0 шт» — красноватая. */
@Composable
fun QtyPill(qty: Int, modifier: Modifier = Modifier) {
    val m = Mine.colors
    val color = if (qty > 0) m.success else m.logout
    Row(
        modifier.clip(RoundedCornerShape(10.dp)).background(color.copy(alpha = 0.14f)).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text("$qty", fontFamily = PriceFont, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp, color = color)
        Text(" шт", Modifier.padding(bottom = 2.dp), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = color)
    }
}

/**
 * Цена как настоящий ценник на полке: гривны крупно Montserrat, копейки сверху, «грн» под ними.
 * Акция — жёлтая бумага (как печатаем) и перечёркнутая старая цена. Слева пунктир линии отреза.
 */
@Composable
fun ShelfTagPrice(price: Double, oldPrice: Double?, modifier: Modifier = Modifier) {
    val promo = oldPrice != null && oldPrice > price + 0.001
    val paper = if (promo) Color(PROMO_PAPER) else Color.White
    val ink = Color(0xFF1C1D22)
    val hole = Mine.colors.bg
    val (whole, kop) = PriceFormat.tag(price)
    Box(modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(paper)) {
        // Пунктир отреза и дырочка, как у ценника на кронштейне.
        Canvas(Modifier.matchParentSize()) {
            val x = 22.dp.toPx()
            drawLine(
                ink.copy(alpha = 0.35f), Offset(x, 0f), Offset(x, size.height), strokeWidth = 2.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx())),
            )
            // Отверстие под кронштейн — цвета фона экрана.
            drawCircle(hole, radius = 6.dp.toPx(), center = Offset(x, size.height / 2))
        }
        Column(Modifier.padding(start = 40.dp, end = 20.dp, top = 14.dp, bottom = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (promo) "Акційна ціна" else "Ціна", Modifier.weight(1f), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = ink.copy(alpha = 0.75f))
                if (promo) {
                    Text(
                        PriceFormat.tag(oldPrice!!).first, fontFamily = PriceFont, fontWeight = FontWeight.Bold, fontSize = 26.sp,
                        color = Color(0xFF2A2B10), textDecoration = TextDecoration.LineThrough,
                    )
                }
            }
            Row(verticalAlignment = Alignment.Top) {
                Text(whole, fontFamily = PriceFont, fontWeight = FontWeight.ExtraBold, fontSize = 72.sp, lineHeight = 76.sp, letterSpacing = (-2).sp, color = ink)
                Column(Modifier.padding(start = 6.dp, top = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(kop, fontFamily = PriceFont, fontWeight = FontWeight.ExtraBold, fontSize = 26.sp, lineHeight = 28.sp, color = ink)
                    Text("грн", fontFamily = PriceFont, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 24.sp, color = ink)
                }
            }
        }
    }
}

/** Строка списка: содержимое + разделитель снизу (списки — строками, не карточками). */
@Composable
fun MineListRow(onClick: (() -> Unit)?, divider: Boolean = true, content: @Composable () -> Unit) {
    val m = Mine.colors
    Column {
        Box(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(vertical = 12.dp)) { content() }
        if (divider) Box(Modifier.fillMaxWidth().height(1.dp).background(m.line))
    }
}
