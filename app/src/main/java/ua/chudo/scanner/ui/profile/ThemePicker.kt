package ua.chudo.scanner.ui.profile

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ua.chudo.scanner.ui.theme.Mine
import ua.chudo.scanner.ui.theme.MineColors
import ua.chudo.scanner.ui.theme.MineDark
import ua.chudo.scanner.ui.theme.MineLight
import ua.chudo.scanner.ui.theme.ThemeMode

/** «Тема оформлення» (ThemeMine): Темна · Світла · Як у системі, с мини-экранами. */
@Composable
fun ThemePicker(mode: ThemeMode, onPick: (ThemeMode) -> Unit) {
    val m = Mine.colors
    Column {
        Text("Тема оформлення", Modifier.padding(horizontal = 4.dp), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = m.text)
        Row(Modifier.padding(top = 12.dp).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ThemeMode.entries.forEach { t ->
                val selected = t == mode
                Surface(
                    Modifier.weight(1f).selectable(selected, role = Role.RadioButton, onClick = { onPick(t) }),
                    shape = RoundedCornerShape(18.dp), color = m.surface, contentColor = m.text,
                    border = if (selected) BorderStroke(2.dp, m.accent) else BorderStroke(1.dp, m.line),
                ) {
                    Column(Modifier.padding(start = 8.dp, end = 8.dp, top = 10.dp, bottom = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        when (t) {
                            ThemeMode.DARK -> MiniScreen(MineDark)
                            ThemeMode.LIGHT -> MiniScreen(MineLight)
                            ThemeMode.SYSTEM -> SplitScreen()
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(t.title, fontSize = 14.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, maxLines = 1)
                    }
                }
            }
        }
        Text(
            "Світла тема краще видно при яскравому світлі в торговому залі. «Як у системі» перемикається разом із телефоном.",
            Modifier.padding(start = 4.dp, end = 4.dp, top = 12.dp), fontSize = 14.sp, lineHeight = 20.sp, color = m.textSecondary,
        )
    }
}

/** Мини-экран 64×104: заголовок, плашка, поле, кнопка. */
@Composable
private fun MiniScreen(c: MineColors) {
    val tone = if (c.dark) c.danger else c.accentTone
    Column(
        Modifier.size(64.dp, 104.dp).clip(RoundedCornerShape(12.dp)).background(c.bg)
            .then(if (c.dark) Modifier.border(1.dp, c.line, RoundedCornerShape(12.dp)) else Modifier)
            .padding(horizontal = 6.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(Modifier.fillMaxWidth(0.7f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(c.text))
        Box(Modifier.fillMaxWidth().height(22.dp).clip(RoundedCornerShape(6.dp)).background(tone))
        Box(Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp)).background(if (c.dark) Color.Transparent else c.card).border(1.dp, c.outline, RoundedCornerShape(6.dp)))
        Spacer(Modifier.weight(1f))
        Box(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp)).background(c.accent))
    }
}

@Composable
private fun SplitScreen() {
    Row(Modifier.size(64.dp, 104.dp).clip(RoundedCornerShape(12.dp)).border(1.dp, MineDark.line, RoundedCornerShape(12.dp))) {
        Box(Modifier.weight(1f).fillMaxHeight().background(MineLight.bg))
        Box(Modifier.weight(1f).fillMaxHeight().background(MineDark.bg))
    }
}
