package ua.chudo.scanner.ui.common

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ua.chudo.scanner.R
import ua.chudo.scanner.ui.theme.Mine

enum class MainTab { HOME, ORDERS, STOCK, PROFILE }

/** Штрихкод в рамке — иконка сканера из макета (24×24, обводка). */
val BarcodeScanIcon: ImageVector by lazy {
    ImageVector.Builder("BarcodeScan", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(
            PathParser().parsePathString("M3 7V4h3M21 7V4h-3M3 17v3h3M21 17v3h-3M7 8v8M10 8v8M13 8v8M16 8v8").toNodes(),
            stroke = SolidColor(Color.Black), strokeLineWidth = 2.3f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
        )
    }.build()
}

@Composable
fun MineBottomBar(
    tab: MainTab?,
    onTab: (MainTab) -> Unit,
    onScan: () -> Unit,
    scanLabel: String = "Сканувати товар",
    newOrders: Int = 0,
) {
    val m = Mine.colors
    val bottomInset = WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)
        .union(WindowInsets(bottom = 12.dp))
    Column(Modifier.fillMaxWidth().background(m.card).windowInsetsPadding(bottomInset)) {
        HorizontalDivider(color = m.line)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Mine.spacing4).selectableGroup(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TabItem(MainTab.HOME, tab, R.drawable.ic_redesign_search, "Пошук", onTab)
            TabItem(MainTab.ORDERS, tab, R.drawable.ic_redesign_clipboard_list, "Замовлення", onTab, newOrders)
            ScanTab(scanLabel, onScan)
            TabItem(MainTab.STOCK, tab, R.drawable.ic_redesign_boxes, "Залишки", onTab)
            TabItem(MainTab.PROFILE, tab, R.drawable.ic_redesign_user, "Профіль", onTab)
        }
    }
}

@Composable
private fun RowScope.ScanTab(label: String, onScan: () -> Unit) {
    val m = Mine.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.94f else 1f, tween(120), label = "scanKeyScale")
    Column(
        Modifier.weight(1f).heightIn(min = 63.dp)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onScan)
            .semantics { contentDescription = label }
            .padding(top = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically),
    ) {
        Box(
            Modifier.scale(scale).size(width = 52.dp, height = 40.dp).clip(Mine.cardShape).background(m.accent),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.ic_redesign_scan_barcode), null, Modifier.size(22.dp), tint = m.onAccent)
        }
        TabLabel("Скан", m.text)
    }
}

@Composable
private fun RowScope.TabItem(
    tab: MainTab,
    selected: MainTab?,
    @DrawableRes icon: Int,
    label: String,
    onTab: (MainTab) -> Unit,
    badge: Int = 0,
) {
    val m = Mine.colors
    val active = tab == selected
    val color = if (active) m.text else m.textTertiary
    Box(
        Modifier.weight(1f).heightIn(min = 63.dp)
            .selectable(selected = active, role = Role.Tab, onClick = { onTab(tab) }),
        contentAlignment = Alignment.Center,
    ) {
        if (active) {
            Box(
                Modifier.align(Alignment.TopCenter).fillMaxWidth(0.4f).height(2.dp)
                    .background(m.accent, androidx.compose.foundation.shape.RoundedCornerShape(bottomStart = 2.dp, bottomEnd = 2.dp)),
            )
        }
        Column(
            Modifier.padding(top = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Icon(painterResource(icon), null, Modifier.size(22.dp), tint = color)
            TabLabel(label, color)
        }
        if (badge > 0) {
            Box(
                Modifier.align(Alignment.TopCenter).offset(x = 15.dp, y = 4.dp)
                    .widthIn(min = 18.dp).height(18.dp)
                    .background(m.onDanger, Mine.pillShape).border(2.dp, m.card, Mine.pillShape)
                    .padding(horizontal = 5.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("$badge", color = Color.White, fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun TabLabel(text: String, color: Color) {
    Text(
        text, fontSize = 11.sp, lineHeight = 16.5.sp, fontWeight = FontWeight.Medium,
        color = color, textAlign = TextAlign.Center,
    )
}
