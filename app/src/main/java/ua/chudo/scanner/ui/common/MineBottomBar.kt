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
import androidx.compose.foundation.layout.fillMaxHeight
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

/** Вкладки: на телефоні в панелі — Головна · Каталог · Скан · Залишки · Профіль; Друк — у профілі, Замовлення — плиткою на головній (обидва є в панелі планшета, Каталог там — чипом у пошуку). */
enum class MainTab { HOME, CATALOG, ORDERS, PRINT, STOCK, PROFILE }

/** Вкладки нижньої панелі телефона (кнопка сканера — між другою і третьою). */
internal val PhoneBarTabs = listOf(MainTab.HOME, MainTab.CATALOG, MainTab.STOCK, MainTab.PROFILE)

/** Яку вкладку підсвічувати в нижній панелі: друк відкривається з профілю. */
internal fun phoneBarSelection(tab: MainTab?): MainTab? = if (tab == MainTab.PRINT) MainTab.PROFILE else tab

/** Штрихкод в рамке — иконка сканера из макета (24×24, обводка). */
val BarcodeScanIcon: ImageVector by lazy {
    ImageVector.Builder("BarcodeScan", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(
            PathParser().parsePathString("M3 7V4h3M21 7V4h-3M3 17v3h3M21 17v3h-3M7 8v8M10 8v8M13 8v8M16 8v8").toNodes(),
            stroke = SolidColor(Color.Black), strokeLineWidth = 2.3f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
        )
    }.build()
}

/** Підпис вкладки в нижній панелі телефона. */
internal fun phoneTabLabel(tab: MainTab): String = when (tab) {
    MainTab.HOME -> "Головна"
    MainTab.CATALOG -> "Каталог"
    MainTab.STOCK -> "Залишки"
    MainTab.PROFILE -> "Профіль"
    MainTab.ORDERS -> "Замовлення"
    MainTab.PRINT -> "Друк"
}

/** Висота нижньої панелі: великі вкладки під великий палець (було 80 dp). */
internal val PhoneBarHeight = 92.dp

/** Нижня навігація: великі вкладки з пігулкою-індикатором, по центру — кнопка сканера (фірмовий колір). */
@Composable
fun MineBottomBar(
    tab: MainTab?,
    onTab: (MainTab) -> Unit,
    onScan: () -> Unit,
    scanLabel: String = "Сканувати товар",
) {
    val selected = phoneBarSelection(tab)
    androidx.compose.material3.Surface(color = Mine.colors.card) {
        Row(
            Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars).height(PhoneBarHeight)
                .padding(horizontal = 4.dp).selectableGroup(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TabItem(MainTab.HOME, selected, R.drawable.ic_redesign_home, onTab)
            TabItem(MainTab.CATALOG, selected, R.drawable.ic_redesign_layout_grid, onTab)
            ScanTab(scanLabel, onScan)
            TabItem(MainTab.STOCK, selected, R.drawable.ic_redesign_boxes, onTab)
            TabItem(MainTab.PROFILE, selected, R.drawable.ic_redesign_user, onTab)
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
        Modifier.weight(1f).fillMaxHeight()
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onScan)
            .semantics { contentDescription = label },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.scale(scale).size(width = 72.dp, height = 60.dp).clip(RoundedCornerShape(20.dp)).background(m.accent),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.ic_redesign_scan_barcode), null, Modifier.size(32.dp), tint = m.onAccent)
        }
    }
}

@Composable
private fun RowScope.TabItem(
    tab: MainTab,
    selected: MainTab?,
    @DrawableRes icon: Int,
    onTab: (MainTab) -> Unit,
) {
    val m = Mine.colors
    val active = tab == selected
    Column(
        Modifier.weight(1f).fillMaxHeight()
            .selectable(active, role = Role.Tab, onClick = { onTab(tab) }),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
    ) {
        Box(
            Modifier.size(width = 64.dp, height = 40.dp).clip(RoundedCornerShape(50))
                .background(if (active) m.accentTone else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(icon), null, Modifier.size(28.dp), tint = if (active) m.link else m.textSecondary)
        }
        Text(
            phoneTabLabel(tab), maxLines = 1, softWrap = false,
            fontSize = 14.sp, lineHeight = 16.sp, letterSpacing = 0.sp,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
            color = if (active) m.text else m.textSecondary,
        )
    }
}
