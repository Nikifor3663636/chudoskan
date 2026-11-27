package ua.chudo.scanner.ui.orders

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
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
import ua.chudo.scanner.data.OrderLine
import ua.chudo.scanner.data.OrderStage
import ua.chudo.scanner.data.PickupOrder
import ua.chudo.scanner.print.PriceFormat
import ua.chudo.scanner.ui.theme.Mine
import kotlin.math.roundToInt

/** Збирання замовлення: позиції, прогрес і дія внизу залежно від етапу. */
@Composable
fun OrderPickContent(
    order: PickupOrder,
    onBack: () -> Unit,
    onStart: () -> Unit,
    onScan: () -> Unit,
    onManual: () -> Unit,
    onToggle: (Int) -> Unit,
    onIssue: () -> Unit,
) {
    val m = Mine.colors
    Column(Modifier.fillMaxSize().background(m.bg).statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                Icon(painterResource(R.drawable.ic_redesign_chevron_left), "Назад", Modifier.size(24.dp), tint = m.textSecondary)
            }
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                Text("№${order.number}", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = m.text, maxLines = 1)
                Text(
                    "${order.customer} · ${PriceFormat.tag(order.total).first} ₴ · ${if (order.paid) "оплачено" else "оплата при отриманні"}",
                    fontSize = 12.sp, lineHeight = 14.sp, color = m.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            item {
                if (order.stage == OrderStage.NEW) {
                    Row(
                        Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp).fillMaxWidth()
                            .background(m.infoTint, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(painterResource(R.drawable.ic_redesign_circle_alert), null, Modifier.padding(top = 1.dp).size(18.dp), tint = m.info)
                        Text(
                            "Натисніть «Почати збирати», щоб колеги бачили, що замовлення у роботі.",
                            fontSize = 14.sp, lineHeight = 19.6.sp, color = m.info,
                        )
                    }
                } else {
                    val share = if (order.items == 0) 0f else order.picked.toFloat() / order.items
                    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp)) {
                        Row(Modifier.padding(bottom = 6.dp)) {
                            Text("${order.picked} з ${order.items} зібрано", Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = m.text)
                            Text("${(share * 100).roundToInt()}%", fontSize = 14.sp, color = m.textTertiary)
                        }
                        Box(Modifier.fillMaxWidth().height(4.dp).background(m.line, RoundedCornerShape(2.dp))) {
                            Box(Modifier.fillMaxWidth(share).height(4.dp).background(m.success, RoundedCornerShape(2.dp)))
                        }
                    }
                }
                HorizontalDivider(color = m.line)
            }
            itemsIndexed(order.lines) { index, line ->
                PickRow(line, enabled = order.stage == OrderStage.PICKING) { onToggle(index) }
            }
        }
        Column(Modifier.fillMaxWidth().background(m.card).navigationBarsPadding()) {
            HorizontalDivider(color = m.line)
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, top = 10.dp, end = 16.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when (order.stage) {
                    OrderStage.NEW -> PickButton("Почати збирати", null, Modifier.weight(1f), onClick = onStart)
                    OrderStage.PICKING -> {
                        Surface(
                            onClick = onManual, modifier = Modifier.size(width = 48.dp, height = 52.dp),
                            shape = RoundedCornerShape(6.dp), color = m.card, contentColor = m.text, border = BorderStroke(1.dp, m.outline),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(painterResource(R.drawable.ic_redesign_keyboard), "Ввести код", Modifier.size(20.dp))
                            }
                        }
                        PickButton("Сканувати товар", R.drawable.ic_redesign_scan_barcode, Modifier.weight(1f), onClick = onScan)
                    }
                    OrderStage.READY -> PickButton("Видати клієнту", null, Modifier.weight(1f), onClick = onIssue)
                }
            }
        }
    }
}

@Composable
private fun PickButton(text: String, @DrawableRes icon: Int?, modifier: Modifier, onClick: () -> Unit) {
    val m = Mine.colors
    Surface(onClick = onClick, modifier = modifier.height(52.dp), shape = RoundedCornerShape(6.dp), color = m.accent, contentColor = m.onAccent) {
        Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            icon?.let {
                Icon(painterResource(it), null, Modifier.size(20.dp))
                Box(Modifier.width(8.dp))
            }
            Text(text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun PickRow(line: OrderLine, enabled: Boolean, onToggle: () -> Unit) {
    val m = Mine.colors
    val done = line.done
    val background = if (done) m.success.copy(alpha = 0.06f).compositeOver(m.card) else m.card
    Row(
        Modifier.fillMaxWidth().background(background).padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(28.dp).background(if (done) m.success else Color.Transparent, CircleShape)
                .border(1.5.dp, if (done) m.success else m.outline, CircleShape)
                .toggleable(done, enabled = enabled, role = Role.Checkbox, onValueChange = { onToggle() }),
            contentAlignment = Alignment.Center,
        ) {
            if (done) Icon(painterResource(R.drawable.ic_redesign_check), "Зібрано", Modifier.size(16.dp), tint = Color.White)
        }
        Box(
            Modifier.size(48.dp).background(m.surface, RoundedCornerShape(6.dp)).border(1.dp, m.line, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) { Icon(painterResource(R.drawable.ic_redesign_package), null, Modifier.size(22.dp), tint = m.textTertiary) }
        Column(Modifier.weight(1f)) {
            Text(line.name, fontSize = 15.sp, lineHeight = 19.5.sp, fontWeight = FontWeight.Medium, color = m.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                buildAnnotatedString {
                    append(line.barcode)
                    line.inStock?.let { stock ->
                        append(" · у магазині ")
                        if (stock < line.qty) withStyle(SpanStyle(color = m.warningText, fontWeight = FontWeight.SemiBold)) { append("$stock шт") }
                        else append("$stock шт")
                    }
                },
                fontSize = 13.sp, color = m.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Text("${minOf(line.picked, line.qty)}/${line.qty}", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = m.text)
    }
    HorizontalDivider(color = m.line)
}
