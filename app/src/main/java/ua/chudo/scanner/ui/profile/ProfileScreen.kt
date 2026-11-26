package ua.chudo.scanner.ui.profile

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Store
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import ua.chudo.scanner.chudoApp
import ua.chudo.scanner.data.Employee
import ua.chudo.scanner.data.ShopStockInfo
import ua.chudo.scanner.ui.auth.PasswordDialog
import ua.chudo.scanner.ui.auth.authMessage
import ua.chudo.scanner.ui.common.formatPhone
import ua.chudo.scanner.ui.common.Avatar
import ua.chudo.scanner.ui.common.MineHeader
import ua.chudo.scanner.ui.theme.Mine
import ua.chudo.scanner.ui.theme.ThemeMode
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp

@Composable
fun ProfileScreen(
    employee: Employee, onBack: () -> Unit, onPrinters: (() -> Unit)?, onStock: () -> Unit = {},
    onNetworkStock: (() -> Unit)? = null,
    /** «Адміністрування мережі» — лише superAdmin. */
    onNetworkAdmin: (() -> Unit)? = null,
    /** «Мій магазин» — адмін магазину (співробітники свого магазину). */
    onMyShop: (() -> Unit)? = null,
) {
    val app = LocalContext.current.chudoApp
    val scope = rememberCoroutineScope()
    var info by remember { mutableStateOf<ShopStockInfo?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var editName by rememberSaveable { mutableStateOf(false) }
    var confirmLogout by rememberSaveable { mutableStateOf(false) }
    var changePassword by rememberSaveable { mutableStateOf(false) }
    val region = app.prefs.allRegions.firstOrNull { it.code == employee.region }

    // Залишки мережі (одне завантаження на всю мережу); старі залишки магазину — запасний варіант.
    LaunchedEffect(employee.shopId) {
        info = runCatching { app.networkLookup.info() }.getOrNull()?.let { ShopStockInfo(it.updatedAt, it.updatedBy, it.items) }
            ?: runCatching { app.shopStock.info(employee.shopId) }.getOrNull()
    }
    var checking by remember { mutableStateOf(false) }

    val theme by app.prefs.themeMode.collectAsStateWithLifecycle()
    ProfileContent(
        ProfileUi(
            name = employee.name, phone = formatPhone(employee.phone), city = region?.name ?: employee.region,
            shop = employee.shop, stock = info?.let { "${it.rows} позицій" } ?: "ще не завантажено",
            theme = theme, admin = onPrinters != null, message = message, networkStock = onNetworkStock != null,
            networkAdmin = onNetworkAdmin != null, myShop = onMyShop != null,
            version = app.updater.currentVersion, checking = checking,
        ),
        onBack = onBack, onEditName = { editName = true }, onTheme = app.prefs::setThemeMode,
        onStock = onStock, onPrinters = { onPrinters?.invoke() }, onNetworkStock = { onNetworkStock?.invoke() },
        onNetworkAdmin = { onNetworkAdmin?.invoke() }, onMyShop = { onMyShop?.invoke() }, onPassword = { changePassword = true }, onLogout = { confirmLogout = true },
        onCheckUpdate = {
            if (!checking) scope.launch {
                checking = true
                message = null
                runCatching { app.updater.check(force = true) }
                checking = false
                message = when (val s = app.updater.state.value) {
                    is ua.chudo.scanner.data.UpdateState.Available -> {
                        launch { app.updater.download() }
                        "Є версія ${s.info.version} — завантажую. Далі натисніть «Встановити» на головному екрані."
                    }
                    is ua.chudo.scanner.data.UpdateState.Ready -> "Версія ${s.info.version} уже завантажена — «Встановити» на головному екрані."
                    else -> "У вас остання версія."
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
    val stock: String,
    val theme: ThemeMode,
    val admin: Boolean,
    val message: String? = null,
    /** «Залишки мережі» — лише stockAdmin / superAdmin. */
    val networkStock: Boolean = false,
    val networkAdmin: Boolean = false,
    val myShop: Boolean = false,
    val version: String = "",
    val checking: Boolean = false,
)

/** Профиль (ThemeMine): кто я, тема, магазин и действия одним списком. */
@Composable
fun ProfileContent(
    ui: ProfileUi,
    onBack: () -> Unit = {},
    onEditName: () -> Unit = {},
    onTheme: (ThemeMode) -> Unit = {},
    onStock: () -> Unit = {},
    onPrinters: () -> Unit = {},
    onNetworkStock: () -> Unit = {},
    onNetworkAdmin: () -> Unit = {},
    onMyShop: () -> Unit = {},
    onPassword: () -> Unit = {},
    onLogout: () -> Unit = {},
    onCheckUpdate: () -> Unit = {},
) {
    val m = Mine.colors
    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        MineHeader("Профіль", onBack)
        Row(Modifier.padding(top = 12.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(ui.name, size = 64.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(ui.name, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = m.text)
                Text("Співробітник підтверджений", Modifier.padding(top = 2.dp), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = m.success)
            }
            IconButton(onClick = onEditName) { Icon(Icons.Rounded.Edit, "Змінити ім'я", tint = m.textSecondary) }
        }
        Spacer(Modifier.height(28.dp))
        ThemePicker(ui.theme, onTheme)
        Spacer(Modifier.height(24.dp))

        Surface(shape = RoundedCornerShape(20.dp), color = m.surface, contentColor = m.text) {
            Column {
                if (ui.networkAdmin) {
                    ProfileRow("Адміністрування мережі", "Магазини, співробітники", onClick = onNetworkAdmin)
                    RowDivider()
                }
                if (ui.myShop) {
                    ProfileRow("Мій магазин", "Співробітники", onClick = onMyShop)
                    RowDivider()
                }
                if (ui.admin) {
                    ProfileRow("Принтери", "Налаштування", onClick = onPrinters)
                    RowDivider()
                }
                ProfileRow("Залишки магазину", ui.stock, onClick = onStock)
                RowDivider()
                if (ui.networkStock) {
                    ProfileRow("Залишки мережі", "Завантажити з 1С", onClick = onNetworkStock)
                    RowDivider()
                }
                ProfileStackedRow("Магазин", ui.shop)
                RowDivider()
                ProfileRow("Місто", ui.city)
                RowDivider()
                ProfileRow("Телефон", ui.phone)
                RowDivider()
                ProfileRow("Змінити пароль", null, onClick = onPassword)
                RowDivider()
                ProfileRow(
                    "Перевірити оновлення",
                    if (ui.checking) "Перевіряю…" else ui.version.takeIf { it.isNotEmpty() }?.let { "Версія $it" },
                    onClick = onCheckUpdate,
                )
                RowDivider()
                ProfileRow("Вийти", null, danger = true, onClick = onLogout)
            }
        }
        Text("Магазин і місто змінює адміністратор.", Modifier.padding(start = 4.dp, top = 8.dp), fontSize = 13.sp, color = m.textSecondary)
        ui.message?.let { Text(it, Modifier.padding(start = 4.dp, top = 10.dp), fontSize = 15.sp, color = m.success) }
        Spacer(Modifier.height(32.dp))
    }
}

/**
 * Довге значення (адреса магазину) — під підписом, до 3 рядків, а не обрізаним в один рядок поруч із підписом.
 */
@Composable
private fun ProfileStackedRow(label: String, value: String) {
    val m = Mine.colors
    Column(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 18.dp, vertical = 12.dp)) {
        Text(label, fontSize = 14.sp, color = m.textSecondary)
        Text(
            value.ifBlank { "—" }, Modifier.padding(top = 2.dp), fontSize = 16.sp, lineHeight = 21.sp,
            color = m.text, maxLines = 3, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Строка списка профиля: название слева, значение справа; нажимаемая — со стрелкой. */
@Composable
private fun ProfileRow(label: String, value: String?, danger: Boolean = false, onClick: (() -> Unit)? = null) {
    val m = Mine.colors
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = 56.dp).padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label, Modifier.weight(1f), fontSize = 16.sp,
            fontWeight = if (danger) FontWeight.Medium else FontWeight.Normal, color = if (danger) m.logout else m.text,
        )
        value?.let { Text(it, Modifier.padding(start = 12.dp), fontSize = 14.sp, color = m.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        if (onClick != null && !danger) Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, Modifier.padding(start = 6.dp).size(20.dp), tint = m.textSecondary)
    }
}

@Composable
private fun RowDivider() = HorizontalDivider(color = Mine.colors.line)
