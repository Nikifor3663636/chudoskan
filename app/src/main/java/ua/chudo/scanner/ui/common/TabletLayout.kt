package ua.chudo.scanner.ui.common

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ua.chudo.scanner.R
import ua.chudo.scanner.ui.theme.Mine

internal val TabletMinWidth = 840.dp

@Composable
internal fun TabletRail(
    selected: MainTab?,
    printing: Boolean,
    onTab: (MainTab) -> Unit,
    onScan: () -> Unit,
    onPrint: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val m = Mine.colors
    Row(modifier.background(m.card)) {
        Column(
            Modifier.width(71.dp).fillMaxHeight().systemBarsPadding().padding(vertical = 18.dp).selectableGroup(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            RailTab(R.drawable.ic_redesign_search, "Пошук", selected == MainTab.HOME) { onTab(MainTab.HOME) }
            RailTab(R.drawable.ic_redesign_clipboard_list, "Замовлення", selected == MainTab.ORDERS) { onTab(MainTab.ORDERS) }
            Box(
                Modifier.padding(top = 6.dp, bottom = 10.dp).size(width = 66.dp, height = 52.dp)
                    .clip(RoundedCornerShape(16.dp)).background(m.accent)
                    .clickable(onClickLabel = "Сканувати", role = Role.Button, onClick = onScan),
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(R.drawable.ic_redesign_scan_barcode), "Скан", Modifier.size(20.dp), tint = m.onAccent)
            }
            RailTab(R.drawable.ic_redesign_boxes, "Залишки", selected == MainTab.STOCK) { onTab(MainTab.STOCK) }
            RailTab(R.drawable.ic_redesign_printer, "Друк", printing, onPrint)
            Box(Modifier.weight(1f))
            RailTab(R.drawable.ic_redesign_user, "Профіль", selected == MainTab.PROFILE) { onTab(MainTab.PROFILE) }
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(m.line))
    }
}

@Composable
private fun RailTab(@DrawableRes icon: Int, label: String, active: Boolean, onClick: () -> Unit) {
    val m = Mine.colors
    val color = if (active) m.text else m.textTertiary
    Column(
        Modifier.size(width = 66.dp, height = 52.dp).clip(RoundedCornerShape(16.dp))
            .background(if (active) m.hover else Color.Transparent)
            .selectable(active, role = Role.Tab, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
    ) {
        Icon(painterResource(icon), null, Modifier.size(20.dp), tint = color)
        Text(label, fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.Medium, color = color, textAlign = TextAlign.Center)
    }
}

@Composable
internal fun TabletPanes(list: @Composable () -> Unit, detail: @Composable () -> Unit) {
    val m = Mine.colors
    Row(Modifier.fillMaxSize()) {
        Box(Modifier.width(379.dp).fillMaxHeight().background(m.card)) { list() }
        Box(Modifier.width(1.dp).fillMaxHeight().background(m.line))
        Box(Modifier.weight(1f).fillMaxHeight().background(m.bg)) { detail() }
    }
}
