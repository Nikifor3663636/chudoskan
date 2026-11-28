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
import ua.chudo.scanner.data.PasswordRequest
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
    resets: List<PasswordRequest> = emptyList(),
    onReset: (PasswordRequest) -> Unit = {},
    onDismissReset: (PasswordRequest) -> Unit = {},
    log: List<ua.chudo.scanner.data.AdminLogEntry> = emptyList(),
) {
    val m = Mine.colors
    LazyColumn(Modifier.fillMaxSize().background(m.bg).statusBarsPadding()) {
        item {
            Row(
                Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
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
            if (resets.isNotEmpty()) {
                item { Section("Скидання пароля") }
                items(resets, key = { "r-" + it.id }) { r -> ResetRow(r, busy, onReset, onDismissReset) }
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
            if (log.isNotEmpty()) {
                val names = staff.associate { it.uid to it.name }
                item { Section("Журнал дій") }
                items(log.take(20), key = { "l-" + it.id }) { e -> LogRow(ua.chudo.scanner.data.shopLogLine(e, names), e.at) }
            }
            item { Box(Modifier.height(16.dp)) }
        }
    }
}

/** Запит із «Відновлення пароля»: хто, коли; «Скинути» — лише якщо номер є серед співробітників магазину. */
@Composable
private fun ResetRow(r: PasswordRequest, busy: Boolean, onReset: (PasswordRequest) -> Unit, onDismiss: (PasswordRequest) -> Unit) {
    val m = Mine.colors
    val phone = maskPhone(formatPhone(r.phone))
    Row(
        Modifier.fillMaxWidth().background(m.card).padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(36.dp).background(m.surface, CircleShape).border(1.dp, m.line, CircleShape), contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_redesign_lock), null, Modifier.size(18.dp), tint = m.textSecondary)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(r.name ?: phone, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = m.text)
                Text(
                    listOfNotNull(phone.takeIf { r.name != null }, r.createdAt?.let { resetTime(it) }).joinToString(" · "),
                    fontSize = 13.sp, color = m.textTertiary,
                )
                if (r.uid == null) Text("Цього номера немає серед співробітників магазину", fontSize = 13.sp, color = m.warningText)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (r.uid != null) SmallAction("Скинути", primary = true, enabled = !busy) { onReset(r) }
                SmallAction("Відхилити", primary = false, enabled = !busy) { onDismiss(r) }
            }
        }
    }
    HorizontalDivider(color = m.line)
}

/** Рядок «Журналу дій»: іконка дії · що зроблено · хто → з ким · коли. */
@Composable
private fun LogRow(line: ua.chudo.scanner.data.ShopLogLine, at: java.util.Date?) {
    val m = Mine.colors
    val icon = when (line.action) {
        "passwordReset" -> R.drawable.ic_redesign_lock
        "pairPc", "unpairPc", "devicePair" -> R.drawable.ic_redesign_printer
        else -> R.drawable.ic_redesign_users
    }
    Row(
        Modifier.fillMaxWidth().background(m.card).heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(36.dp).background(m.surface, CircleShape).border(1.dp, m.line, CircleShape), contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), null, Modifier.size(18.dp), tint = m.textSecondary)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(line.title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = m.text)
            Text(line.detail, fontSize = 13.sp, color = m.textTertiary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        at?.let { Text(resetTime(it), fontSize = 13.sp, color = m.textTertiary) }
    }
    HorizontalDivider(color = m.line)
}

/** «сьогодні о 10:42» / «26.11 о 18:20». */
fun resetTime(at: java.util.Date, zone: java.time.ZoneId = java.time.ZoneId.systemDefault(), today: java.time.LocalDate = java.time.LocalDate.now(zone)): String {
    val t = at.toInstant().atZone(zone)
    val hm = t.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
    return when (t.toLocalDate()) {
        today -> "сьогодні о $hm"
        today.minusDays(1) -> "учора о $hm"
        else -> t.format(java.time.format.DateTimeFormatter.ofPattern("dd.MM")) + " о $hm"
    }
}

/** Тимчасовий пароль показуємо адміну один раз; після «Готово» він ніде не зберігається. */
@Composable
fun TempPasswordDialog(r: PasswordRequest, password: String, onDone: () -> Unit) {
    val m = Mine.colors
    androidx.compose.material3.AlertDialog(
        onDismissRequest = {},
        containerColor = m.card,
        shape = RoundedCornerShape(28.dp),
        title = { Text("Тимчасовий пароль", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = m.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Для: ${r.name ?: maskPhone(formatPhone(r.phone))}", fontSize = 14.sp, color = m.textSecondary,
                )
                Box(
                    Modifier.fillMaxWidth().background(m.surface, RoundedCornerShape(12.dp)).border(1.dp, m.line, RoundedCornerShape(12.dp))
                        .padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        password, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = m.text, letterSpacing = 2.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    )
                }
                Text(
                    "Передайте пароль співробітнику особисто. Після закриття вікна його більше не буде видно. При вході застосунок попросить співробітника задати свій пароль.",
                    fontSize = 13.sp, lineHeight = 18.sp, color = m.textTertiary,
                )
            }
        },
        confirmButton = { SmallAction("Готово", primary = true, enabled = true, onClick = onDone) },
    )
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
    // M3: filled (основна дія) або text button, 40 dp, пігулка.
    Surface(
        onClick = onClick, enabled = enabled, modifier = Modifier.height(40.dp), shape = RoundedCornerShape(50),
        color = if (primary) m.accent else Color.Transparent, contentColor = if (primary) m.onAccent else m.link,
    ) {
        Box(Modifier.padding(horizontal = if (primary) 24.dp else 12.dp), contentAlignment = Alignment.Center) {
            Text(text, style = androidx.compose.material3.MaterialTheme.typography.labelLarge)
        }
    }
}
