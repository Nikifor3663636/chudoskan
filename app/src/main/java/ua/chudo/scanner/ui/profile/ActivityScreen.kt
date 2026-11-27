package ua.chudo.scanner.ui.profile

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ua.chudo.scanner.R
import ua.chudo.scanner.chudoApp
import ua.chudo.scanner.data.ActivityKind
import ua.chudo.scanner.data.Employee
import ua.chudo.scanner.data.ShopActivity
import ua.chudo.scanner.ui.theme.Mine
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun ActivityScreen(employee: Employee, onBack: () -> Unit, notifications: Boolean = false) {
    val prefs = LocalContext.current.chudoApp.prefs
    val revision by prefs.activityRevision.collectAsStateWithLifecycle()
    val events = remember(employee, revision) { prefs.activities(employee) }
    val readAt = remember(employee, revision) { prefs.notificationsReadAt(employee) }
    ActivityContent(events, readAt, notifications, onBack, onReadAll = { prefs.readNotifications(employee, System.currentTimeMillis()) })
}

/** Що показуємо в «Сповіщеннях»: оновлення залишків і стан принтерів. */
fun notificationEvents(events: List<ShopActivity>): List<ShopActivity> =
    events.filter { it.kind == ActivityKind.STOCK || it.kind == ActivityKind.PRINTER }

@DrawableRes
private fun activityIcon(kind: ActivityKind): Int = when (kind) {
    ActivityKind.PRINT, ActivityKind.PRINTER -> R.drawable.ic_redesign_printer
    ActivityKind.CART -> R.drawable.ic_redesign_shopping_cart
    ActivityKind.ORDER -> R.drawable.ic_redesign_clipboard_list
    ActivityKind.SCAN -> R.drawable.ic_redesign_scan_barcode
    ActivityKind.STOCK -> R.drawable.ic_redesign_database
}

@Composable
fun ActivityContent(
    events: List<ShopActivity>,
    readAt: Long,
    notifications: Boolean,
    onBack: () -> Unit,
    onReadAll: () -> Unit,
    zone: ZoneId = ZoneId.systemDefault(),
    today: LocalDate = LocalDate.now(zone),
) {
    var filter by rememberSaveable { mutableStateOf<ActivityKind?>(null) }
    val m = Mine.colors
    val visible = if (notifications) notificationEvents(events) else events.filter { filter == null || it.kind == filter }
    val unread = notifications && visible.any { it.time > readAt }
    LazyColumn(Modifier.fillMaxSize().background(m.bg).statusBarsPadding()) {
        item {
            Row(
                Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                    Icon(painterResource(R.drawable.ic_redesign_chevron_left), "Назад", Modifier.size(24.dp), tint = m.textSecondary)
                }
                Text(
                    if (notifications) "Сповіщення" else "Історія операцій", Modifier.weight(1f).padding(start = 4.dp),
                    fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = m.text, maxLines = 1,
                )
                if (notifications) Box(
                    Modifier.height(36.dp).clickable(enabled = unread, onClick = onReadAll).padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Прочитати всі", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = if (unread) m.text else m.textTertiary)
                }
            }
            if (!notifications) Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 16.dp, top = 4.dp, end = 16.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                listOf(null, ActivityKind.PRINT, ActivityKind.ORDER, ActivityKind.SCAN).forEach { option ->
                    FilterChip(option?.title ?: "Усе", filter == option) { filter = option }
                }
            }
        }
        visible.groupBy { Instant.ofEpochMilli(it.time).atZone(zone).toLocalDate() }.forEach { (day, rows) ->
            item {
                Text(
                    when (day) {
                        today -> "Сьогодні"
                        today.minusDays(1) -> "Учора"
                        else -> day.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))
                    },
                    Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp),
                    color = m.textSecondary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                )
                HorizontalDivider(color = m.line)
            }
            items(rows) { event ->
                val fresh = notifications && event.time > readAt
                Row(
                    Modifier.fillMaxWidth().background(if (fresh) m.info.copy(alpha = 0.05f).compositeOver(m.card) else m.card)
                        .heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = if (notifications) Alignment.Top else Alignment.CenterVertically,
                ) {
                    Icon(painterResource(activityIcon(event.kind)), null, Modifier.size(20.dp), tint = m.text)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(event.title, fontSize = 15.sp, lineHeight = 19.5.sp, fontWeight = FontWeight.Medium, color = m.text)
                        if (event.detail.isNotBlank()) Text(event.detail, fontSize = 13.sp, color = m.textTertiary)
                    }
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(Instant.ofEpochMilli(event.time).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm")), color = m.textTertiary, fontSize = 13.sp)
                        if (fresh) Box(Modifier.size(8.dp).background(m.info, CircleShape))
                    }
                }
                HorizontalDivider(color = m.line)
            }
        }
        if (visible.isEmpty()) item {
            Column(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    Modifier.size(48.dp).background(m.surface, CircleShape).border(1.dp, m.line, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painterResource(if (notifications) R.drawable.ic_redesign_bell else R.drawable.ic_redesign_history), null,
                        Modifier.size(20.dp), tint = m.textSecondary,
                    )
                }
                Text(
                    if (notifications) "Сповіщень поки немає" else "Операцій поки немає",
                    Modifier.widthIn(max = 260.dp), fontSize = 14.sp, color = m.textSecondary, textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val m = Mine.colors
    val shape = RoundedCornerShape(6.dp)
    Box(
        Modifier.height(32.dp).background(if (selected) m.text else m.card, shape)
            .border(1.dp, if (selected) m.text else m.outline, shape)
            .toggleable(selected, role = Role.Tab, onValueChange = { onClick() }).padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = if (selected) m.card else m.textSecondary)
    }
}
