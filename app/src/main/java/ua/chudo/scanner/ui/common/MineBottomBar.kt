package ua.chudo.scanner.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PointOfSale
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.ShoppingBag
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ua.chudo.scanner.ui.theme.Manrope
import ua.chudo.scanner.ui.theme.Mine

/** Вкладки нижней панели (макет Orders): Пошук · Замовлення · [сканер] · Друк · Профіль. */
enum class MainTab { SEARCH, ORDERS, STOCK, TERMINAL, PROFILE }

/** Штрихкод в рамке — иконка сканера из макета (24×24, обводка). */
val BarcodeScanIcon: ImageVector by lazy {
    ImageVector.Builder("BarcodeScan", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(
            PathParser().parsePathString("M3 7V4h3M21 7V4h-3M3 17v3h3M21 17v3h-3M7 8v8M10 8v8M13 8v8M16 8v8").toNodes(),
            stroke = SolidColor(Color.Black), strokeLineWidth = 2.3f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
        )
    }.build()
}

/** Висота панелі без системної навігації, діаметр FAB і на скільки він виступає над панеллю. */
val BottomBarHeight = 80.dp
private val FabSize = 72.dp
private val FabRise = 30.dp

/**
 * Нижня панель (дизайн-система → MineBottomBar): 80 dp на поверхні, вкладки 64 dp з індикатором-«пігулкою»
 * 56×32 на активній, кнопка сканера 72 dp по центру виступає над панеллю на 30 dp.
 */
@Composable
fun MineBottomBar(tab: MainTab?, newOrders: Int, onTab: (MainTab) -> Unit, onScan: () -> Unit) {
    val m = Mine.colors
    Box(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().background(m.surface).navigationBarsPadding()) {
            HorizontalDivider(color = m.line)
            Row(
                Modifier.fillMaxWidth().height(BottomBarHeight - 1.dp).padding(bottom = 8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                // Дві половини однакової ширини: місце під FAB — рівно по центру (2 вкладки зліва, 3 справа).
                Row(Modifier.weight(1f)) {
                    TabItem(MainTab.SEARCH, tab, Icons.Rounded.Search, "Пошук", 0, onTab)
                    TabItem(MainTab.ORDERS, tab, Icons.Outlined.ShoppingBag, "Замовлення", newOrders, onTab)
                }
                Spacer(Modifier.width(FabSize + 8.dp))
                Row(Modifier.weight(1f)) {
                    TabItem(MainTab.STOCK, tab, Icons.Outlined.Inventory2, "Залишки", 0, onTab)
                    TabItem(MainTab.TERMINAL, tab, Icons.Outlined.PointOfSale, "Термінал", 0, onTab)
                    TabItem(MainTab.PROFILE, tab, Icons.Outlined.Person, "Профіль", 0, onTab)
                }
            }
        }
        // FAB «Скан»: працює з будь-якої вкладки; тінь — лише в листа, FAB і діалогу.
        Box(
            Modifier.align(Alignment.TopCenter).offset(y = -FabRise).size(FabSize)
                .shadow(8.dp, CircleShape)
                .clip(CircleShape).background(m.accent)
                .clickable(onClick = onScan)
                .semantics { contentDescription = "Сканувати"; role = Role.Button },
            contentAlignment = Alignment.Center,
        ) {
            Icon(BarcodeScanIcon, null, Modifier.size(32.dp), tint = m.onAccent)
        }
    }
}

@Composable
private fun RowScope.TabItem(t: MainTab, selected: MainTab?, icon: ImageVector, label: String, badge: Int, onTab: (MainTab) -> Unit) {
    val m = Mine.colors
    val on = t == selected
    Column(
        Modifier.weight(1f).height(64.dp).clickable { onTab(t) }
            .semantics { role = Role.Tab; this.selected = on },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        Box(
            // «Пігулка» 56×32, але не ширша за саму вкладку (5 вкладок на вузькому екрані).
            Modifier.padding(horizontal = 4.dp).widthIn(max = 56.dp).fillMaxWidth().height(32.dp)
                .clip(CircleShape).background(if (on) m.accentTone else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, Modifier.size(24.dp), tint = if (on) m.accentText else m.textSecondary)
            if (badge > 0) {
                Box(
                    Modifier.align(Alignment.TopEnd).offset(x = (-4).dp, y = (-2).dp).size(20.dp)
                        .clip(CircleShape).background(m.accent).border(2.dp, m.surface, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(if (badge > 9) "9+" else "$badge", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = m.onAccent)
                }
            }
        }
        // П'ять вкладок на вузькому екрані: «Замовлення» зменшується від 12 до 9 sp, а не обрізається.
        BasicText(
            label, Modifier.padding(horizontal = 2.dp),
            style = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.SemiBold, color = if (on) m.text else m.textSecondary, textAlign = TextAlign.Center),
            maxLines = 1, softWrap = false,
            autoSize = TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = 12.sp, stepSize = 0.5.sp),
        )
    }
}
