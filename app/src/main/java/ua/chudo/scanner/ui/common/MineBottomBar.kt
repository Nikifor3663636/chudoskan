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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItem
import androidx.compose.foundation.shape.RoundedCornerShape
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

/** Нижня навігація Material 3: пігулка-індикатор активної вкладки, по центру — кнопка сканера (фірмовий колір). */
@Composable
fun MineBottomBar(
    tab: MainTab?,
    onTab: (MainTab) -> Unit,
    onScan: () -> Unit,
    scanLabel: String = "Сканувати товар",
    newOrders: Int = 0,
) {
    androidx.compose.material3.NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        TabItem(MainTab.HOME, tab, R.drawable.ic_redesign_search, "Пошук", onTab)
        TabItem(MainTab.ORDERS, tab, R.drawable.ic_redesign_clipboard_list, "Замовлення", onTab, newOrders)
        ScanTab(scanLabel, onScan)
        TabItem(MainTab.STOCK, tab, R.drawable.ic_redesign_boxes, "Залишки", onTab)
        TabItem(MainTab.PROFILE, tab, R.drawable.ic_redesign_user, "Профіль", onTab)
    }
}

@Composable
private fun RowScope.ScanTab(label: String, onScan: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.94f else 1f, tween(120), label = "scanKeyScale")
    Column(
        Modifier.weight(1f).height(80.dp)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onScan)
            .semantics { contentDescription = label },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.scale(scale).size(width = 64.dp, height = 48.dp).clip(RoundedCornerShape(16.dp)).background(c.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.ic_redesign_scan_barcode), null, Modifier.size(24.dp), tint = c.onPrimaryContainer)
        }
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
    NavigationBarItem(
        selected = tab == selected,
        onClick = { onTab(tab) },
        icon = {
            androidx.compose.material3.BadgedBox(badge = { if (badge > 0) androidx.compose.material3.Badge { Text("$badge") } }) {
                Icon(painterResource(icon), null, Modifier.size(24.dp))
            }
        },
        label = { Text(label, maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.sp)) },
    )
}
