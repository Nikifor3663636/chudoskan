package ua.chudo.scanner.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import ua.chudo.scanner.ui.theme.Mine

enum class MainTab { HOME, LABELS, STOCK, PROFILE }

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
) {
    val m = Mine.colors
    Column(Modifier.fillMaxWidth().background(m.card).navigationBarsPadding()) {
        HorizontalDivider(color = m.line)
        Row(Modifier.fillMaxWidth().heightIn(min = 76.dp), verticalAlignment = Alignment.CenterVertically) {
            TabItem(MainTab.HOME, tab, Icons.Outlined.Home, "Головна", onTab)
            TabItem(MainTab.LABELS, tab, Icons.Rounded.Print, "Друк цінників", onTab)
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Surface(onClick = onScan, shape = Mine.pillShape, color = m.accent, contentColor = m.onAccent) {
                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        Icon(BarcodeScanIcon, scanLabel, Modifier.size(26.dp))
                    }
                }
                Text("Скан", Modifier.padding(top = 2.dp), fontSize = 11.sp, lineHeight = 13.sp, color = m.textSecondary)
            }
            TabItem(MainTab.STOCK, tab, Icons.Outlined.Inventory2, "Залишки", onTab)
            TabItem(MainTab.PROFILE, tab, Icons.Outlined.Person, "Профіль", onTab)
        }
    }
}

@Composable
private fun RowScope.TabItem(tab: MainTab, selected: MainTab?, icon: ImageVector, label: String, onTab: (MainTab) -> Unit) {
    val m = Mine.colors
    val active = tab == selected
    val color = if (active) m.link else m.textSecondary
    Column(
        Modifier.weight(1f).heightIn(min = 56.dp)
            .selectable(selected = active, role = Role.Tab, onClick = { onTab(tab) })
            .padding(horizontal = 2.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(Modifier.width(44.dp).height(30.dp), shape = Mine.pillShape, color = if (active) m.accentTone else Color.Transparent) {
            Box(contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(22.dp), tint = color) }
        }
        Spacer(Modifier.height(3.dp))
        Text(
            label, fontSize = 11.sp, lineHeight = 13.sp,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
            color = color, textAlign = TextAlign.Center,
        )
    }
}
