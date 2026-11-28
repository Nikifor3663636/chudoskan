package ua.chudo.scanner.ui.profile

import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.Icons
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import ua.chudo.scanner.R
import ua.chudo.scanner.chudoApp
import ua.chudo.scanner.ui.common.initials
import ua.chudo.scanner.data.Employee
import ua.chudo.scanner.ui.auth.PasswordDialog
import ua.chudo.scanner.ui.auth.authMessage
import ua.chudo.scanner.ui.common.formatPhone
import ua.chudo.scanner.ui.common.maskPhone
import ua.chudo.scanner.ui.theme.Mine
import ua.chudo.scanner.ui.theme.ThemeMode
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp

@Composable
fun ProfileScreen(
    employee: Employee, onBack: () -> Unit, onPrinters: (() -> Unit)?,
    /** «Адміністрування мережі» — лише superAdmin. */
    onNetworkAdmin: (() -> Unit)? = null,
    /** «Мій магазин» — адмін магазину (співробітники свого магазину). */
    onMyShop: (() -> Unit)? = null,
    onScannerSettings: () -> Unit = {},
    onOperations: () -> Unit = {},
) {
    val app = LocalContext.current.chudoApp
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var editName by rememberSaveable { mutableStateOf(false) }
    var confirmLogout by rememberSaveable { mutableStateOf(false) }
    var changePassword by rememberSaveable { mutableStateOf(false) }
    val region = app.prefs.allRegions.firstOrNull { it.code == employee.region }
    val role = when {
        employee.superAdmin -> "Адмін мережі"
        employee.admin -> "Адмін магазину"
        employee.stockAdmin -> "Адмін залишків"
        else -> "Співробітник"
    }
    BackHandler(onBack = onBack)

    var checking by remember { mutableStateOf(false) }

    val theme by app.prefs.themeMode.collectAsStateWithLifecycle()
    ProfileContent(
        ProfileUi(
            name = employee.name, phone = maskPhone(formatPhone(employee.phone)), city = region?.name ?: employee.region,
            shop = employee.actingCode?.let { "ЧО $it" } ?: employee.shop, role = role,
            theme = theme, admin = onPrinters != null, message = message,
            networkAdmin = onNetworkAdmin != null, myShop = onMyShop != null,
            version = app.updater.currentVersion, checking = checking,
        ),
        onScannerSettings = onScannerSettings, onOperations = onOperations,
        onEditName = { editName = true }, onTheme = app.prefs::setThemeMode,
        onPrinters = { onPrinters?.invoke() },
        onNetworkAdmin = { onNetworkAdmin?.invoke() }, onMyShop = { onMyShop?.invoke() }, onPassword = { changePassword = true }, onLogout = { confirmLogout = true },
        onCheckUpdate = {
            if (!checking) scope.launch {
                checking = true
                message = null
                val checked = try {
                    app.updater.check(force = true)
                } finally {
                    checking = false
                }
                message = when (val s = app.updater.state.value) {
                    is ua.chudo.scanner.data.UpdateState.Available -> {
                        launch { app.updater.download() }
                        "Є версія ${s.info.version} — завантажую. Далі натисніть «Встановити» на головному екрані."
                    }
                    is ua.chudo.scanner.data.UpdateState.Ready -> "Версія ${s.info.version} уже завантажена — «Встановити» на головному екрані."
                    is ua.chudo.scanner.data.UpdateState.Downloading -> "Оновлення завантажується."
                    is ua.chudo.scanner.data.UpdateState.NeedPermission -> "Дозвольте встановлення застосунків у налаштуваннях Android."
                    is ua.chudo.scanner.data.UpdateState.Failed -> s.message
                    ua.chudo.scanner.data.UpdateState.None -> if (checked) "У вас остання версія."
                        else "Не вдалося перевірити оновлення. Спробуйте ще раз."
                }
            }
        },
    )

    if (changePassword) {
        PasswordDialog(
            onDismiss = { changePassword = false },
            onDone = { changePassword = false; message = "Пароль змінено." },
        )
    }

    if (editName) {
        var name by rememberSaveable { mutableStateOf(employee.name) }
        var error by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { editName = false },
            title = { Text("Ім'я та прізвище") },
            text = {
                Column {
                    OutlinedTextField(name, { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (name.isBlank()) return@TextButton
                    scope.launch {
                        try {
                            app.auth.updateName(name); editName = false
                        } catch (e: Exception) {
                            error = e.authMessage()
                        }
                    }
                }) { Text("Зберегти") }
            },
            dismissButton = { TextButton(onClick = { editName = false }) { Text("Скасувати") } },
        )
    }
    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("Вийти з акаунта?") },
            text = { Text(formatPhone(employee.phone)) },
            confirmButton = { TextButton(onClick = { confirmLogout = false; app.auth.signOut() }) { Text("Вийти") } },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("Скасувати") } },
        )
    }
}

data class ProfileUi(
    val name: String,
    val phone: String,
    val city: String,
    val shop: String,
    val theme: ThemeMode,
    val admin: Boolean,
    val message: String? = null,
    val networkAdmin: Boolean = false,
    val myShop: Boolean = false,
    val version: String = "",
    val checking: Boolean = false,
    val role: String = "",
)

/** Профиль (ThemeMine): кто я, тема, магазин и действия одним списком. */
@Composable
fun ProfileContent(
    ui: ProfileUi,
    onEditName: () -> Unit = {},
    onTheme: (ThemeMode) -> Unit = {},
    onPrinters: () -> Unit = {},
    onNetworkAdmin: () -> Unit = {},
    onMyShop: () -> Unit = {},
    onPassword: () -> Unit = {},
    onLogout: () -> Unit = {},
    onCheckUpdate: () -> Unit = {},
    onScannerSettings: () -> Unit = {},
    onOperations: () -> Unit = {},
) {
    val m = Mine.colors
    Column(
        Modifier.fillMaxSize().background(m.bg).statusBarsPadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Профіль", Modifier.padding(top = 8.dp, bottom = 4.dp),
            style = MaterialTheme.typography.headlineMedium, color = m.text,
        )
        // Хто я: велика картка, натискання — змінити ім’я.
        Surface(
            onClick = onEditName, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
            color = m.card, contentColor = m.text,
        ) {
            Row(
                Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(56.dp).background(m.accentTone, CircleShape), contentAlignment = Alignment.Center) {
                    Text(initials(ui.name), style = MaterialTheme.typography.titleLarge, color = m.link)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(ui.name, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (ui.role.isNotBlank()) Text(ui.role, fontSize = 14.sp, color = m.link, fontWeight = FontWeight.Medium)
                    ui.shop.ifBlank { ui.city }.takeIf(String::isNotBlank)?.let {
                        Text(it, fontSize = 14.sp, color = m.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    if (ui.phone.isNotBlank()) Text(ui.phone, fontSize = 14.sp, color = m.textTertiary)
                }
                Icon(painterResource(R.drawable.ic_redesign_chevron_right), "Змінити ім’я", Modifier.size(20.dp), tint = m.textTertiary)
            }
        }

        ProfileSection("Робота")
        ProfileGroup {
            val rows = buildList<@Composable () -> Unit> {
                if (ui.networkAdmin) add { ProfileRow(R.drawable.ic_redesign_users, "Адміністрування мережі", "Магазини, співробітники", onClick = onNetworkAdmin) }
                if (ui.myShop) add { ProfileRow(R.drawable.ic_redesign_users, "Мій магазин", "Співробітники, запити", onClick = onMyShop) }
                if (ui.admin) add { ProfileRow(R.drawable.ic_redesign_printer, "Принтери", "Налаштування друку", onClick = onPrinters) }
                add { ProfileRow(R.drawable.ic_redesign_history, "Історія операцій", "Друк, замовлення, скани", onClick = onOperations) }
            }
            rows.forEachIndexed { i, row ->
                if (i > 0) ProfileDivider()
                row()
            }
        }

        ProfileSection("Застосунок")
        ProfileGroup {
            ProfileRow(R.drawable.ic_redesign_settings, "Налаштування сканера", "Звук, вібрація, безперервний скан", onClick = onScannerSettings)
            ProfileDivider()
            ProfileRow(R.drawable.ic_redesign_lock, "Змінити пароль", onClick = onPassword)
            ProfileDivider()
            ProfileRow(
                R.drawable.ic_redesign_download, "Перевірити оновлення",
                if (ui.checking) "Перевіряємо…" else ui.version.takeIf(String::isNotEmpty)?.let { "Версія $it" },
                chevron = false, onClick = onCheckUpdate,
            )
        }

        ProfileSection("Тема")
        // Сегментована кнопка M3: 48 dp, пігулка, вибраний сегмент — тональний з галочкою.
        Row(
            Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(50)).border(1.dp, m.outline, RoundedCornerShape(50))
                .selectableGroup(),
        ) {
            listOf(ThemeMode.SYSTEM, ThemeMode.LIGHT, ThemeMode.DARK).forEachIndexed { i, mode ->
                val active = mode == ui.theme
                if (i > 0) Box(Modifier.fillMaxHeight().width(1.dp).background(m.outline))
                Row(
                    Modifier.weight(1f).fillMaxHeight().background(if (active) m.accentTone else Color.Transparent)
                        .selectable(active, role = Role.Tab, onClick = { onTheme(mode) }),
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (active) Icon(Icons.Rounded.Check, null, Modifier.padding(end = 6.dp).size(18.dp), tint = m.link)
                    Text(mode.title, style = MaterialTheme.typography.labelLarge, color = if (active) m.link else m.textSecondary, maxLines = 1)
                }
            }
        }

        ui.message?.let { Text(it, fontSize = 14.sp, color = m.textSecondary) }
        // «Вийти» — окремо й унизу, щоб не натиснути випадково.
        OutlinedButton(
            onClick = onLogout, modifier = Modifier.padding(top = 12.dp).fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(50), border = BorderStroke(1.dp, m.outline),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = m.logout),
        ) {
            Icon(painterResource(R.drawable.ic_redesign_log_out), null, Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Вийти", style = MaterialTheme.typography.titleMedium)
        }
        Text(
            "Магазин і місто змінює адміністратор.", Modifier.padding(bottom = 16.dp),
            fontSize = 13.sp, color = m.textTertiary,
        )
    }
}

@Composable
internal fun ProfileSection(label: String) {
    Text(
        label, Modifier.padding(start = 4.dp, top = 8.dp),
        style = MaterialTheme.typography.titleSmall, color = Mine.colors.textSecondary,
    )
}

/** Група рядків — картка 16 dp. */
@Composable
private fun ProfileGroup(content: @Composable () -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = Mine.colors.card, contentColor = Mine.colors.text) {
        Column { content() }
    }
}

@Composable
private fun ProfileDivider() = HorizontalDivider(Modifier.padding(start = 72.dp), color = Mine.colors.line)

/** Рядок профілю: від 64 dp, іконка в колі 40 dp, назва 16 sp, підпис 14 sp — зручно влучити пальцем. */
@Composable
private fun ProfileRow(
    @DrawableRes icon: Int,
    label: String,
    value: String? = null,
    chevron: Boolean = true,
    onClick: () -> Unit,
) {
    val m = Mine.colors
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).background(m.accentTone, CircleShape), contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), null, Modifier.size(22.dp), tint = m.link)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium, color = m.text)
            value?.let { Text(it, fontSize = 14.sp, color = m.textTertiary) }
        }
        if (chevron) Icon(
            painterResource(R.drawable.ic_redesign_chevron_right), null, Modifier.size(20.dp), tint = m.textTertiary,
        )
    }
}
