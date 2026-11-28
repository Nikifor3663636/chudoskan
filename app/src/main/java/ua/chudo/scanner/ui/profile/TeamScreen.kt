package ua.chudo.scanner.ui.profile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import ua.chudo.scanner.R
import ua.chudo.scanner.data.Employee
import ua.chudo.scanner.data.Team
import ua.chudo.scanner.data.TeamGroup
import ua.chudo.scanner.data.TeamMember
import ua.chudo.scanner.ui.auth.authMessage
import ua.chudo.scanner.ui.common.initials
import ua.chudo.scanner.ui.theme.Mine

/** Стан екрана «Колектив»: завантаження, помилка або люди за посадами. */
sealed interface TeamUi {
    data object Loading : TeamUi
    data class Failed(val message: String) : TeamUi
    data class Ready(val groups: List<TeamGroup>, val count: Int) : TeamUi
}

/** «Профіль → Колектив магазину»: хто керуючий, хто старший продавець, хто продавці. */
@Composable
fun TeamScreen(employee: Employee, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    var ui by remember { mutableStateOf<TeamUi>(TeamUi.Loading) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(employee.shopId, attempt) {
        ui = TeamUi.Loading
        ui = try {
            val members = Team.load()
            TeamUi.Ready(Team.groups(members), members.size)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TeamUi.Failed(e.authMessage())
        }
    }
    TeamContent(
        shop = employee.actingCode?.let { "ЧО $it · ${employee.shop}" } ?: employee.shop,
        meUid = employee.uid, ui = ui, onBack = onBack, onRetry = { attempt++ },
    )
}

@Composable
fun TeamContent(shop: String, meUid: String, ui: TeamUi, onBack: () -> Unit = {}, onRetry: () -> Unit = {}) {
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
                    Text(
                        "Колектив" + ((ui as? TeamUi.Ready)?.count?.takeIf { it > 0 }?.let { " · $it" } ?: ""),
                        fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = m.text,
                    )
                    if (shop.isNotBlank()) Text(shop, fontSize = 12.sp, lineHeight = 14.sp, color = m.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        when (ui) {
            TeamUi.Loading -> item {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = m.accent)
                }
            }
            is TeamUi.Failed -> item {
                Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(ui.message, fontSize = 15.sp, color = m.warningText)
                    Surface(onClick = onRetry, modifier = Modifier.height(48.dp), shape = RoundedCornerShape(50), color = m.accent, contentColor = m.onAccent) {
                        Box(Modifier.padding(horizontal = 24.dp), contentAlignment = Alignment.Center) { Text("Повторити", fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
                    }
                }
            }
            is TeamUi.Ready -> {
                if (ui.groups.isEmpty()) item {
                    Text("У магазині ще немає підтверджених співробітників.", Modifier.padding(24.dp), fontSize = 15.sp, color = m.textSecondary)
                }
                ui.groups.forEach { g ->
                    item(key = "g-" + g.title) { Section(g.title + if (g.members.size > 1) " · ${g.members.size}" else "") }
                    items(g.members, key = { "m-" + it.uid }) { p -> MemberRow(p, me = p.uid == meUid) }
                }
            }
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
private fun MemberRow(p: TeamMember, me: Boolean) {
    val m = Mine.colors
    Row(
        Modifier.fillMaxWidth().background(m.card).heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        val lead = p.job == ua.chudo.scanner.data.JobTitle.MANAGER || p.job == ua.chudo.scanner.data.JobTitle.SENIOR
        Box(
            Modifier.size(40.dp).background(if (lead) m.accentTone else m.surface, CircleShape)
                .border(1.dp, if (lead) m.accentTone else m.line, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(initials(p.name), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (lead) m.link else m.textSecondary)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                p.name.ifBlank { "Без імені" } + if (me) " (ви)" else "",
                fontSize = 15.sp, fontWeight = FontWeight.Medium, color = m.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(Team.subtitle(p.job, p.admin), fontSize = 13.sp, color = if (lead) m.link else m.textTertiary, maxLines = 1)
        }
    }
    HorizontalDivider(color = m.line)
}
