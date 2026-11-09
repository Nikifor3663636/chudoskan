package ua.chudo.shared.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material.icons.rounded.Store
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import ua.chudo.shared.auth.AuthState
import ua.chudo.shared.auth.NewProfile
import ua.chudo.shared.auth.authMessage
import ua.chudo.shared.res.Res
import ua.chudo.shared.res.logo_wordmark

const val MIN_PASSWORD = 6

fun passwordProblem(new: String, repeat: String): String? = when {
    new.length < MIN_PASSWORD -> "Пароль — щонайменше $MIN_PASSWORD символів."
    new != repeat -> "Паролі не збігаються."
    else -> null
}

/** Вход, регистрация, ожидание подтверждения — как в Android-версии. */
@Composable
fun AuthScreen(state: AuthState) {
    val graph = LocalGraph.current
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(painterResource(Res.drawable.logo_wordmark), "Чудо Острів", Modifier.fillMaxWidth(0.7f).height(72.dp))
        Spacer(Modifier.height(20.dp))
        when (state) {
            AuthState.NotConfigured -> Notice(Icons.Rounded.CloudOff, "Вхід не підключено", "У цій збірці немає налаштувань Firebase (app/google-services.json).")
            AuthState.Loading, is AuthState.Ready -> CircularProgressIndicator(Modifier.padding(top = 48.dp))
            AuthState.SignedOut -> SignedOutForm()
            is AuthState.NoProfile -> {
                Text("Заповни дані співробітника", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(formatPhone(state.phone), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(16.dp))
                ProfileFields(needsAccount = false)
                TextButton(onClick = graph.auth::signOut) { Text("Вийти") }
            }
            is AuthState.Pending -> {
                Notice(
                    Icons.Rounded.HourglassTop, "Заявку надіслано",
                    "${state.employee.name}, адміністратор має підтвердити, що ти працюєш у магазині:\n${state.employee.shop}\n\nЩойно підтвердять — застосунок відкриється сам.",
                )
                TextButton(onClick = graph.auth::signOut) { Text("Вийти") }
            }
            is AuthState.Failed -> {
                Notice(Icons.Rounded.WarningAmber, "Не вдалося завантажити профіль", state.message)
                TextButton(onClick = graph.auth::signOut) { Text("Вийти") }
            }
        }
    }
}

/** Общий помощник: выполнить действие с индикатором и текстом ошибки. */
@Composable
private fun rememberSubmitter(): Submitter {
    val scope = rememberCoroutineScope()
    return remember { Submitter(scope) }
}

private class Submitter(private val scope: kotlinx.coroutines.CoroutineScope) {
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    fun run(block: suspend () -> Unit) {
        if (busy) return
        error = null
        busy = true
        scope.launch {
            try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.authMessage() } finally { busy = false }
        }
    }
}

@Composable
private fun SignedOutForm() {
    var register by rememberSaveable { mutableStateOf(false) }
    var forgot by rememberSaveable { mutableStateOf(false) }
    Text(if (register) "Реєстрація співробітника" else "Вхід для співробітників", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(16.dp))
    if (register) {
        ProfileFields(needsAccount = true)
        TextButton(onClick = { register = false }) { Text("Вже є акаунт? Увійти") }
    } else {
        LoginFields()
        TextButton(onClick = { forgot = true }) { Text("Забув пароль") }
        TextButton(onClick = { register = true }) { Text("Немає акаунта? Зареєструватися") }
    }
    if (forgot) {
        AlertDialog(
            onDismissRequest = { forgot = false },
            title = { Text("Забув пароль") },
            text = { Text("Попроси адміністратора скинути пароль: він задасть тобі новий, і ти зможеш змінити його в Профілі.") },
            confirmButton = { TextButton(onClick = { forgot = false }) { Text("Зрозуміло") } },
        )
    }
}

@Composable
private fun LoginFields() {
    val graph = LocalGraph.current
    val sub = rememberSubmitter()
    var digits by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    PhoneField(digits, !sub.busy) { digits = it }
    Spacer(Modifier.height(10.dp))
    PasswordField(password, "Пароль") { password = it }
    sub.error?.let { ErrorText(it) }
    Spacer(Modifier.height(16.dp))
    SubmitButton("Увійти", sub.busy) {
        when {
            digits.length != 9 -> sub.error = "Введи номер повністю: +380 XX XXX XX XX."
            password.isEmpty() -> sub.error = "Введи пароль."
            else -> sub.run { graph.auth.signIn(digits, password) }
        }
    }
}

/** Регистрация (имя + телефон + пароль + город + магазин) или дозаполнение анкеты. */
@Composable
private fun ProfileFields(needsAccount: Boolean) {
    val graph = LocalGraph.current
    val sub = rememberSubmitter()
    var name by rememberSaveable { mutableStateOf("") }
    var digits by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var repeat by rememberSaveable { mutableStateOf("") }
    var region by rememberSaveable { mutableStateOf(graph.prefs.region.value.code) }
    var shop by rememberSaveable { mutableStateOf("") }
    var manual by rememberSaveable { mutableStateOf(false) }
    var pickRegion by remember { mutableStateOf(false) }
    var shops by remember { mutableStateOf<List<String>?>(null) }
    var shopsFailed by remember { mutableStateOf(false) }
    LaunchedEffect(region) {
        shops = null; shopsFailed = false
        runCatching { graph.repo.shops(1, region).map { it.address }.distinct() }
            .onSuccess { shops = it }.onFailure { shopsFailed = true }
    }

    OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Ім'я та прізвище") }, shape = RoundedCornerShape(14.dp))
    if (needsAccount) {
        Spacer(Modifier.height(10.dp))
        PhoneField(digits, !sub.busy) { digits = it }
        Spacer(Modifier.height(10.dp))
        PasswordField(password, "Пароль (щонайменше $MIN_PASSWORD символів)") { password = it }
        Spacer(Modifier.height(10.dp))
        PasswordField(repeat, "Повтори пароль") { repeat = it }
    }
    Spacer(Modifier.height(16.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Твій магазин", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        AssistChip(
            onClick = { pickRegion = true },
            label = { Text(graph.prefs.allRegions.firstOrNull { it.code == region }?.name ?: region) },
            leadingIcon = { Icon(Icons.Rounded.LocationOn, null, Modifier.size(18.dp)) },
        )
    }
    Spacer(Modifier.height(8.dp))
    val list = shops
    when {
        list == null && !shopsFailed -> CircularProgressIndicator(Modifier.padding(12.dp).size(28.dp))
        list != null && list.isNotEmpty() && !manual -> {
            list.forEach { address ->
                Surface(
                    shape = RoundedCornerShape(14.dp), tonalElevation = if (shop == address) 3.dp else 0.dp,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp).clickable { shop = address },
                ) {
                    Row(Modifier.padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = shop == address, onClick = { shop = address })
                        Text(address, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            TextButton(onClick = { manual = true; shop = "" }) { Text("Мого магазину немає в списку") }
        }
        else -> {
            if (shopsFailed) Text("Не вдалося завантажити магазини з сайту — впиши адресу вручну.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(shop, { shop = it }, Modifier.fillMaxWidth(), label = { Text("Адреса магазину") }, leadingIcon = { Icon(Icons.Rounded.Store, null) }, shape = RoundedCornerShape(14.dp))
        }
    }
    sub.error?.let { ErrorText(it) }
    Spacer(Modifier.height(16.dp))
    SubmitButton(if (needsAccount) "Зареєструватися" else "Зберегти", sub.busy) {
        val problem = when {
            name.isBlank() -> "Введи ім'я."
            shop.isBlank() -> "Обери свій магазин."
            needsAccount && digits.length != 9 -> "Введи номер повністю: +380 XX XXX XX XX."
            needsAccount -> passwordProblem(password, repeat)
            else -> null
        }
        if (problem != null) sub.error = problem
        else sub.run {
            val p = NewProfile(name, region, shop)
            if (needsAccount) graph.auth.register(digits, password, p) else graph.auth.completeProfile(p)
        }
    }
    Text(
        "Магазин змінити потім може лише адміністратор. Після реєстрації адміністратор підтверджує акаунт.",
        Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
    )
    if (pickRegion) RegionPicker(region, onPick = { region = it; shop = ""; manual = false; pickRegion = false }, onDismiss = { pickRegion = false })
}

/** Номер после +380: 9 цифр, маска XX XXX XX XX. Принимает и вставку «0671234567» / «+380…». */
@Composable
fun PhoneField(digits: String, enabled: Boolean, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = digits,
        onValueChange = { raw ->
            var d = raw.filter(Char::isDigit)
            if (d.length > 9 && d.startsWith("380")) d = d.drop(3)
            onChange(d.trimStart('0').take(9))
        },
        enabled = enabled, modifier = Modifier.fillMaxWidth(), singleLine = true,
        label = { Text("Номер телефону") }, prefix = { Text("+380 ") }, placeholder = { Text("XX XXX XX XX") },
        leadingIcon = { Icon(Icons.Rounded.Phone, null) }, visualTransformation = PhoneMask,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), shape = RoundedCornerShape(14.dp),
    )
}

private object PhoneMask : VisualTransformation {
    private val gaps = intArrayOf(2, 5, 7)
    override fun filter(text: AnnotatedString): TransformedText {
        val s = text.text
        val out = buildString { s.forEachIndexed { i, c -> if (i in gaps) append(' '); append(c) } }
        return TransformedText(AnnotatedString(out), object : OffsetMapping {
            override fun originalToTransformed(offset: Int) = offset + gaps.count { it < offset }
            override fun transformedToOriginal(offset: Int) =
                (offset - gaps.withIndex().count { (j, g) -> g < s.length && g + j < offset }).coerceIn(0, s.length)
        })
    }
}

@Composable
fun PasswordField(value: String, label: String, onChange: (String) -> Unit) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value, onChange, Modifier.fillMaxWidth(), singleLine = true, label = { Text(label) }, shape = RoundedCornerShape(14.dp),
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = { IconButton(onClick = { visible = !visible }) { Icon(if (visible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, null) } },
    )
}

@Composable
fun ErrorText(text: String) {
    Text(text, Modifier.fillMaxWidth().padding(top = 10.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
}

@Composable
fun SubmitButton(text: String, busy: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = !busy, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp)) {
        if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) else Text(text)
    }
}

@Composable
private fun Notice(icon: ImageVector, title: String, text: String) {
    Icon(icon, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(12.dp))
    Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    Spacer(Modifier.height(8.dp))
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    Spacer(Modifier.height(16.dp))
}

/** +380671234567 → +380 67 123 45 67 */
fun formatPhone(p: String): String =
    if (p.length == 13 && p.startsWith("+380")) "+380 ${p.substring(4, 6)} ${p.substring(6, 9)} ${p.substring(9, 11)} ${p.substring(11)}" else p
