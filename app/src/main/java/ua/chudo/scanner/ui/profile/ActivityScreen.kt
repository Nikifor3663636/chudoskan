package ua.chudo.scanner.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ua.chudo.scanner.chudoApp
import ua.chudo.scanner.data.ActivityKind
import ua.chudo.scanner.data.Employee
import ua.chudo.scanner.ui.common.BarcodeScanIcon
import ua.chudo.scanner.ui.common.MineChip
import ua.chudo.scanner.ui.common.MineHeader
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
    var filter by rememberSaveable { mutableStateOf<ActivityKind?>(null) }
    val m = Mine.colors
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val visible = if (notifications) events.filter { it.kind == ActivityKind.STOCK || it.kind == ActivityKind.PRINTER }
        else events.filter { filter == null || it.kind == filter }
    LazyColumn(Modifier.fillMaxSize().background(m.bg).statusBarsPadding()) {
        item {
            Column(Modifier.padding(horizontal = 16.dp)) {
                MineHeader(if (notifications) "Сповіщення" else "Історія операцій", onBack) {
                    if (notifications) TextButton(
                        onClick = { prefs.readNotifications(employee, System.currentTimeMillis()) },
                        enabled = visible.any { it.time > readAt },
                    ) { Text("Прочитати всі") }
                }
                if (!notifications) Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    listOf(null, ActivityKind.PRINT, ActivityKind.ORDER, ActivityKind.SCAN).forEach { option ->
                        MineChip(option?.title ?: "Усе", filter == option, { filter = option })
                    }
                }
            }
        }
        visible.groupBy { Instant.ofEpochMilli(it.time).atZone(zone).toLocalDate() }.forEach { (day, rows) ->
            item {
                Text(when (day) {
                    today -> "Сьогодні"
                    today.minusDays(1) -> "Учора"
                    else -> day.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))
                }, Modifier.padding(horizontal = 16.dp, vertical = 10.dp), color = m.textSecondary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            }
            items(rows) { event ->
                val fresh = notifications && event.time > readAt
                Row(
                    Modifier.fillMaxWidth().background(if (fresh) m.surface else m.card).heightIn(min = 62.dp)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(when (event.kind) {
                        ActivityKind.PRINT -> Icons.Outlined.Print
                        ActivityKind.CART -> Icons.Outlined.ShoppingCart
                        ActivityKind.ORDER -> Icons.AutoMirrored.Outlined.ReceiptLong
                        ActivityKind.SCAN -> BarcodeScanIcon
                        ActivityKind.STOCK -> Icons.Outlined.Inventory2
                        ActivityKind.PRINTER -> Icons.Outlined.Print
                    }, null, tint = m.text)
                    Column(Modifier.weight(1f)) {
                        Text(event.title, color = m.text, fontWeight = FontWeight.SemiBold)
                        if (event.detail.isNotBlank()) Text(event.detail, color = m.textSecondary, fontSize = 13.sp)
                    }
                    Text(Instant.ofEpochMilli(event.time).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm")), color = m.textSecondary, fontSize = 12.sp)
                    if (fresh) Text("•", color = m.link)
                }
                HorizontalDivider(color = m.line)
            }
        }
        if (visible.isEmpty()) item {
            Text(if (notifications) "Сповіщень поки немає." else "Операцій поки немає.", Modifier.padding(16.dp), color = m.textSecondary)
        }
    }
}
