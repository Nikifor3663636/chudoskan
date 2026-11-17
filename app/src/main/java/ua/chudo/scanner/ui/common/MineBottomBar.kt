package ua.chudo.scanner.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Print
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ua.chudo.scanner.ui.theme.Mine

/** Вкладки нижней панели (макет Orders): Пошук · Замовлення · [сканер] · Друк · Профіль. */
enum class MainTab { SEARCH, ORDERS, PRINT, PROFILE }

/** Штрихкод в рамке — иконка сканера из макета (24×24, обводка). */
val BarcodeScanIcon: ImageVector by lazy {
    ImageVector.Builder("BarcodeScan", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(
            PathParser().parsePathString("M3 7V4h3M21 7V4h-3M3 17v3h3M21 17v3h-3M7 8v8M10 8v8M13 8v8M16 8v8").toNodes(),
            stroke = SolidColor(Color.Black), strokeLineWidth = 2.3f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
        )
    }.build()
}

/**
 * Нижняя панель стиля Mine: четыре вкладки и большая круглая кнопка сканера по центру,
 * приподнятая над панелью и обведённая кольцом цвета фона.
 */
@Composable
fun MineBottomBar(tab: MainTab?, newOrders: Int, onTab: (MainTab) -> Unit, onScan: () -> Unit) {
    val m = Mine.colors
    Box(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().background(m.card).navigationBarsPadding()) {
            HorizontalDivider(color = m.line)
            Row(Modifier.fillMaxWidth().height(72.dp), verticalAlignment = Alignment.CenterVertically) {
                TabItem(MainTab.SEARCH, tab, Icons.Rounded.Search, "Пошук", 0, onTab)
                TabItem(MainTab.ORDERS, tab, Icons.Outlined.ShoppingBag, "Замовлення", newOrders, onTab)
                // Место под круглую кнопку: фиксированное, чтобы подписи вкладок («Замовлення») не обрезались.
                Spacer(Modifier.width(84.dp))
                TabItem(MainTab.PRINT, tab, Icons.Outlined.Print, "Друк", 0, onTab)
                TabItem(MainTab.PROFILE, tab, Icons.Outlined.Person, "Профіль", 0, onTab)
            }
        }
        // Кнопка сканера: 76 dp, кольцо 6 dp цвета фона, мягкая тень — как в макете.
        Box(
            Modifier.align(Alignment.TopCenter).offset(y = (-30).dp).size(88.dp)
                .shadow(10.dp, CircleShape, ambientColor = m.accent, spotColor = m.accent)
                .clip(CircleShape).background(m.card)
                .clickable(onClick = onScan)
                .semantics { contentDescription = "Сканувати"; role = Role.Button },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(76.dp).clip(CircleShape).background(m.accent), contentAlignment = Alignment.Center) {
                Icon(BarcodeScanIcon, null, Modifier.size(32.dp), tint = m.onAccent)
            }
        }
    }
}

@Composable
private fun RowScope.TabItem(t: MainTab, selected: MainTab?, icon: ImageVector, label: String, badge: Int, onTab: (MainTab) -> Unit) {
    val m = Mine.colors
    val on = t == selected
    Column(
        Modifier.weight(1f).clickable { onTab(t) }.padding(vertical = 6.dp)
            .semantics { role = Role.Tab },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            Icon(icon, null, Modifier.size(26.dp), tint = if (on) m.text else m.textSecondary)
            if (badge > 0) {
                Box(
                    Modifier.align(Alignment.TopEnd).offset(x = 10.dp, y = (-6).dp).size(20.dp)
                        .clip(CircleShape).background(m.accent).border(2.dp, m.card, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(if (badge > 9) "9+" else "$badge", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = m.onAccent)
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(label, fontSize = 12.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium, color = if (on) m.text else m.textSecondary, maxLines = 1, softWrap = false)
    }
}
