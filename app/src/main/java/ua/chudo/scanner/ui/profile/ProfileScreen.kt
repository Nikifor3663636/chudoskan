package ua.chudo.scanner.ui.profile

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
    Column(Modifier.fillMaxSize().background(m.bg).statusBarsPadding().verticalScroll(rememberScrollState())) {
        Text(
            "Профіль", Modifier.padding(start = 16.dp, top = 4.dp, end = 16.dp, bottom = 12.dp),
            fontSize = 26.sp, lineHeight = 29.9.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.39).sp, color = m.text,
        )
        HorizontalDivider(color = m.line)
        Row(
            Modifier.fillMaxWidth().heightIn(min = 72.dp).background(m.card)
                .clickable(onClickLabel = "Змінити ім’я", onClick = onEditName)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(44.dp).background(m.surface, CircleShape).border(1.dp, m.line, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(initials(ui.name), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = m.textSecondary)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(ui.name, fontSize = 15.sp, lineHeight = 19.5.sp, fontWeight = FontWeight.Medium, color = m.text)
                val suffix = listOf(ui.role, ui.shop.ifBlank { ui.city }, ui.phone).filter(String::isNotBlank).joinToString(" · ")
                Text(suffix, fontSize = 13.sp, color = m.textTertiary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        HorizontalDivider(color = m.line)

        ProfileSection("Робота")
        HorizontalDivider(color = m.line)
        if (ui.networkAdmin) {
            ProfileRow(R.drawable.ic_redesign_users, "Адміністрування мережі", "Магазини, співробітники", onClick = onNetworkAdmin)
        }
        if (ui.myShop) {
            ProfileRow(R.drawable.ic_redesign_users, "Мій магазин", "Співробітники", onClick = onMyShop)
        }
        if (ui.admin) {
            ProfileRow(R.drawable.ic_redesign_printer, "Принтери", "Налаштування", onClick = onPrinters)
        }
        ProfileRow(R.drawable.ic_redesign_history, "Історія операцій", onClick = onOperations)

        ProfileSection("Тема")
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).background(m.surface, Mine.fieldShape)
                .border(1.dp, m.line, Mine.fieldShape).selectableGroup().padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            listOf(ThemeMode.SYSTEM, ThemeMode.LIGHT, ThemeMode.DARK).forEach { mode ->
                val active = mode == ui.theme
                Surface(
                    Modifier.weight(1f).height(34.dp).selectable(active, role = Role.Tab, onClick = { onTheme(mode) }),
                    shape = RoundedCornerShape(6.dp),
                    color = if (active) m.card else Color.Transparent,
                    border = if (active) BorderStroke(1.dp, m.line) else null,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(mode.title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = if (active) m.text else m.textSecondary)
                    }
                }
            }
        }

        ProfileSection("Застосунок")
        HorizontalDivider(color = m.line)
        ProfileRow(R.drawable.ic_redesign_settings, "Налаштування сканера", "Звук, вібрація, безперервний скан", onClick = onScannerSettings)
        ProfileRow(R.drawable.ic_redesign_lock, "Змінити пароль", onClick = onPassword)
        ProfileRow(
            R.drawable.ic_redesign_download, "Перевірити оновлення",
            if (ui.checking) "Перевіряємо…" else ui.version.takeIf(String::isNotEmpty)?.let { "Версія $it" },
            chevron = false, onClick = onCheckUpdate,
        )
        ProfileRow(R.drawable.ic_redesign_log_out, "Вийти", danger = true, chevron = false, onClick = onLogout)
        Text(
            "Магазин і місто змінює адміністратор.", Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp),
            fontSize = 13.sp, color = m.textTertiary,
        )
        ui.message?.let { Text(it, Modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp), fontSize = 14.sp, color = m.textSecondary) }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
internal fun ProfileSection(label: String) {
    Text(
        label, Modifier.padding(start = 16.dp, top = 20.dp, bottom = 8.dp),
        fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Mine.colors.textSecondary,
    )
}

@Composable
private fun ProfileRow(
    @DrawableRes icon: Int,
    label: String,
    value: String? = null,
    danger: Boolean = false,
    chevron: Boolean = true,
    onClick: () -> Unit,
) {
    val m = Mine.colors
    val color = if (danger) m.logout else m.text
    Row(
        Modifier.fillMaxWidth().background(m.card).clickable(onClick = onClick)
            .heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(icon), null, Modifier.size(20.dp), tint = color)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, fontSize = 15.sp, lineHeight = 19.5.sp, fontWeight = FontWeight.Medium, color = color)
            value?.let { Text(it, fontSize = 13.sp, color = m.textTertiary) }
        }
        if (chevron) Icon(
            painterResource(R.drawable.ic_redesign_chevron_right), null, Modifier.size(18.dp), tint = m.textTertiary,
        )
    }
    HorizontalDivider(color = m.line)
}
