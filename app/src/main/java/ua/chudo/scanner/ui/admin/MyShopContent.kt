package ua.chudo.scanner.ui.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ua.chudo.scanner.R
import ua.chudo.scanner.data.StaffMember
import ua.chudo.scanner.ui.common.formatPhone
import ua.chudo.scanner.ui.common.maskPhone
import androidx.compose.foundation.border
import ua.chudo.scanner.ui.common.initials
import ua.chudo.scanner.ui.theme.Mine

/** Хто чекає підтвердження і хто вже працює: спершу адміни, далі за ім'ям; вимкнені — у кінці. */
fun myShopGroups(staff: List<StaffMember>): Pair<List<StaffMember>, List<StaffMember>> {
    val pending = staff.filter { it.pending }.sortedBy { it.name }
    val team = staff.filterNot { it.pending }
        .sortedWith(compareBy<StaffMember> { !it.approved }.thenByDescending { it.admin }.thenBy { it.name })
    return pending to team
}

fun staffRole(s: StaffMember): String = when {
    s.superAdmin -> "Адмін мережі"
    !s.approved -> "Доступ вимкнено"
    s.admin -> "Адмін магазину"
    else -> "Співробітник"
}

/** «Мій магазин» як у дизайні: анкети на підтвердження і співробітники магазину. */
@Composable
fun MyShopContent(
    shop: String,
    staff: List<StaffMember>?,
    error: String?,
    message: String?,
    busy: Boolean,
    canReject: (StaffMember) -> Boolean,
    onBack: () -> Unit,
    onApprove: (StaffMember) -> Unit,
    onReject: (StaffMember) -> Unit,
    onOpen: (StaffMember) -> Unit,
) {
    val m = Mine.colors
    LazyColumn(Modifier.fillMaxSize().background(m.bg).statusBarsPadding()) {
        item {
            Row(
                Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                    Icon(painterResource(R.drawable.ic_redesign_chevron_left), "Назад", Modifier.size(24.dp), tint = m.textSecondary)
                }
                Column(Modifier.weight(1f).padding(start = 4.dp)) {
                    Text("Мій магазин", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = m.text)
                    if (shop.isNotBlank()) Text(shop, fontSize = 12.sp, lineHeight = 14.sp, color = m.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        message?.let { item { Note(it, m.success) } }
        error?.let { item { Note(it, m.warningText) } }
        if (staff == null) item {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = m.accent)
            }
        } else {
            val (pending, team) = myShopGroups(staff)
            if (pending.isNotEmpty()) {
                item { Section("Чекають підтвердження") }
                items(pending, key = { "p-" + it.uid }) { s ->
                    Row(
                        Modifier.fillMaxWidth().background(m.card).padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Initials(s.name)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(s.name.ifBlank { maskPhone(formatPhone(s.phone)) }, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = m.text)
                                Text(maskPhone(formatPhone(s.phone)), fontSize = 13.sp, color = m.textTertiary)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                SmallAction("Підтвердити", primary = true, enabled = !busy) { onApprove(s) }
                                if (canReject(s)) SmallAction("Відхилити", primary = false, enabled = !busy) { onReject(s) }
                            }
                        }
                    }
                    HorizontalDivider(color = m.line)
                }
            }
            item { Section("Співробітники · ${team.size}") }
            if (team.isEmpty()) item { Note("Підтверджених співробітників ще немає.", m.textSecondary) }
            items(team, key = { "t-" + it.uid }) { s ->
                Row(
                    Modifier.fillMaxWidth().background(m.card).clickable { onOpen(s) }.heightIn(min = 56.dp)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
                ) {
                    Initials(s.name)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(s.name.ifBlank { maskPhone(formatPhone(s.phone)) }, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = m.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${staffRole(s)} · ${maskPhone(formatPhone(s.phone))}", fontSize = 13.sp, color = m.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Icon(painterResource(R.drawable.ic_redesign_chevron_right), null, Modifier.size(18.dp), tint = m.textTertiary)
                }
                HorizontalDivider(color = m.line)
            }
            item { Box(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun Section(text: String) {
    val m = Mine.colors
    Column {
        Text(text, Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = m.textSecondary)
        HorizontalDivider(color = m.line)
    }
}

@Composable
private fun Note(text: String, color: Color) {
    Text(text, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), fontSize = 14.sp, color = color)
}

/** `.ava`: кругла плашка з ініціалами. */
@Composable
private fun Initials(name: String) {
    val m = Mine.colors
    Box(Modifier.size(36.dp).background(m.surface, CircleShape).border(1.dp, m.line, CircleShape), contentAlignment = Alignment.Center) {
        Text(initials(name), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = m.textSecondary)
    }
}

@Composable
private fun SmallAction(text: String, primary: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val m = Mine.colors
    Surface(
        onClick = onClick, enabled = enabled, modifier = Modifier.height(36.dp), shape = RoundedCornerShape(6.dp),
        color = if (primary) m.accent else Color.Transparent, contentColor = if (primary) m.onAccent else m.text,
    ) {
        Box(Modifier.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
            Text(text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
    }
}
