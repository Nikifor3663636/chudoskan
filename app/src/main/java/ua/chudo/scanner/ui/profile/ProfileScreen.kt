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
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Store
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
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
import ua.chudo.scanner.i18n.LocalLang
import ua.chudo.scanner.i18n.title
import ua.chudo.scanner.i18n.tr
import ua.chudo.scanner.ui.auth.authMessage
import ua.chudo.scanner.ui.common.Avatar
import ua.chudo.scanner.ui.theme.InStockGreen
import uniffi.chudo_core.Lang
import java.text.DateFormat

@Composable
fun ProfileScreen(employee: Employee, onBack: () -> Unit, onPrinters: () -> Unit) {
    val app = LocalContext.current.chudoApp
    val lang = LocalLang.current
    val scope = rememberCoroutineScope()
    var info by remember { mutableStateOf<ShopStockInfo?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var editName by rememberSaveable { mutableStateOf(false) }
    var confirmLogout by rememberSaveable { mutableStateOf(false) }
    val region = app.prefs.allRegions.firstOrNull { it.code == employee.region }
    val currentLang by app.prefs.lang.collectAsStateWithLifecycle()

    LaunchedEffect(employee.shopId) { info = runCatching { app.shopStock.info(employee.shopId) }.getOrNull() }

    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, tr("Назад", "Назад")) }
            Text(tr("Профіль", "Профиль"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(16.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(employee.name, size = 64.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(employee.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Verified, null, Modifier.size(16.dp), tint = InStockGreen)
                    Spacer(Modifier.width(4.dp))
                    Text(tr("Співробітник підтверджений", "Сотрудник подтверждён"), style = MaterialTheme.typography.bodySmall, color = InStockGreen)
                }
            }
            IconButton(onClick = { editName = true }) { Icon(Icons.Rounded.Edit, tr("Змінити ім'я", "Изменить имя")) }
        }
        Spacer(Modifier.height(20.dp))

        Card {
            if (employee.phone.isNotEmpty()) InfoRow(Icons.Rounded.Phone, tr("Телефон", "Телефон"), formatPhone(employee.phone))
            else InfoRow(Icons.Rounded.Email, "Email", employee.email)
            HorizontalDivider()
            InfoRow(Icons.Rounded.LocationOn, tr("Місто", "Город"), region?.title() ?: employee.region)
            HorizontalDivider()
            InfoRow(Icons.Rounded.Store, tr("Магазин", "Магазин"), employee.shop)
            HorizontalDivider()
            InfoRow(
                Icons.Rounded.Inventory2,
                tr("Залишки 1С", "Остатки 1С"),
                info?.let { i ->
                    val date = i.updatedAt?.let { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(it) } ?: "—"
                    tr("${i.rows} позицій · $date", "${i.rows} позиций · $date")
                } ?: tr("ще не завантажено", "ещё не загружены"),
            )
        }
        Text(
            tr("Магазин змінює адміністратор.", "Магазин меняет администратор."),
            Modifier.padding(start = 4.dp, top = 6.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))

        Card {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Translate, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(14.dp))
                Text(tr("Мова", "Язык"), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                FilterChip(selected = currentLang == Lang.UA, onClick = { app.prefs.setLang(Lang.UA) }, label = { Text("UA") })
                Spacer(Modifier.width(8.dp))
                FilterChip(selected = currentLang == Lang.RU, onClick = { app.prefs.setLang(Lang.RU) }, label = { Text("RU") })
            }
            HorizontalDivider()
            ActionRow(Icons.Rounded.Print, tr("Принтери", "Принтеры"), onClick = onPrinters)
            HorizontalDivider()
            ActionRow(Icons.AutoMirrored.Rounded.Logout, tr("Вийти", "Выйти"), danger = true) { confirmLogout = true }
        }
        message?.let {
            Text(it, Modifier.padding(top = 10.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(32.dp))
    }

    if (editName) {
        var name by rememberSaveable { mutableStateOf(employee.name) }
        var error by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { editName = false },
            title = { Text(tr("Ім'я та прізвище", "Имя и фамилия")) },
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
                            error = e.authMessage(lang)
                        }
                    }
                }) { Text(tr("Зберегти", "Сохранить")) }
            },
            dismissButton = { TextButton(onClick = { editName = false }) { Text(tr("Скасувати", "Отмена")) } },
        )
    }
    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text(tr("Вийти з акаунта?", "Выйти из аккаунта?")) },
            text = { Text(employee.phone.ifEmpty { employee.email }) },
            confirmButton = { TextButton(onClick = { confirmLogout = false; app.auth.signOut() }) { Text(tr("Вийти", "Выйти")) } },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text(tr("Скасувати", "Отмена")) } },
        )
    }
}

/** +380671234567 → +380 67 123 45 67 */
private fun formatPhone(p: String): String =
    if (p.length == 13 && p.startsWith("+380")) "+380 ${p.substring(4, 6)} ${p.substring(6, 9)} ${p.substring(9, 11)} ${p.substring(11)}" else p

@Composable
private fun Card(content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Column { content() }
    }
}

@Composable
private fun InfoRow(icon: ImageVector, label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(14.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun ActionRow(icon: ImageVector, label: String, danger: Boolean = false, onClick: () -> Unit) {
    val color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = if (danger) color else MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(14.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = color)
    }
}
