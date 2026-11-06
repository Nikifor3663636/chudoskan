package ua.chudo.scanner.ui.auth

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ua.chudo.scanner.R
import ua.chudo.scanner.chudoApp
import ua.chudo.scanner.data.AuthRepository
import ua.chudo.scanner.data.AuthState
import ua.chudo.scanner.data.ChudoRepository
import ua.chudo.scanner.data.Employee
import ua.chudo.scanner.data.NewProfile
import ua.chudo.scanner.ui.common.RegionPickerSheet
import ua.chudo.scanner.ui.common.formatPhone

/** Список магазинов города не зависит от товара: сайт отдаёт все магазины для любого id. */
private const val ANY_PRODUCT_ID = 1L

sealed interface ShopsState {
    data object Loading : ShopsState
    data class Loaded(val shops: List<String>) : ShopsState
    data object Failed : ShopsState
}

class AuthViewModel(
    private val auth: AuthRepository,
    private val repo: ChudoRepository,
) : ViewModel() {
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private val _shops = MutableStateFlow<ShopsState>(ShopsState.Loading)
    val shops = _shops.asStateFlow()
    private var shopsJob: Job? = null

    fun loadShops(region: String) {
        shopsJob?.cancel()
        _shops.value = ShopsState.Loading
        shopsJob = viewModelScope.launch {
            _shops.value = try {
                ShopsState.Loaded(repo.shops(ANY_PRODUCT_ID, region).map { it.address }.distinct())
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                ShopsState.Failed
            }
        }
    }

    fun signIn(digits: String, password: String) = submit {
        if (digits.length != 9) return@submit fail("Введи номер повністю: +380 XX XXX XX XX.")
        if (password.isEmpty()) return@submit fail("Введи пароль.")
        auth.signIn(digits, password)
    }

    fun register(digits: String, passwords: Passwords, profile: NewProfile) = submit {
        check(profile.name, profile.shop)?.let { return@submit fail(it) }
        if (digits.length != 9) return@submit fail("Введи номер повністю: +380 XX XXX XX XX.")
        passwords.problem()?.let { return@submit fail(it) }
        auth.register(digits, passwords.new, profile)
    }

    fun completeProfile(name: String, region: String, shop: String) = submit {
        check(name, shop)?.let { return@submit fail(it) }
        auth.completeProfile(name, region, shop)
    }

    fun clearError() { _error.value = null }

    private fun check(name: String, shop: String): String? = when {
        name.isBlank() -> "Введи ім'я."
        shop.isBlank() -> "Обери свій магазин."
        else -> null
    }

    private fun fail(msg: String) { _error.value = msg }

    private fun submit(block: suspend () -> Unit) {
        if (_busy.value) return
        clearError()
        viewModelScope.launch {
            _busy.value = true
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = e.authMessage()
            } finally {
                _busy.value = false
            }
        }
    }
}

/** Новый пароль и повтор. */
data class Passwords(val new: String, val repeat: String) {
    fun problem(): String? = when {
        new.length < MIN_PASSWORD -> "Пароль — щонайменше $MIN_PASSWORD символів."
        new != repeat -> "Паролі не збігаються."
        else -> null
    }
}

const val MIN_PASSWORD = 6

fun Throwable.authMessage(): String = run {
    when (val e = this@authMessage) {
        is FirebaseNetworkException -> "Немає інтернету."
        is FirebaseTooManyRequestsException -> "Забагато спроб. Зачекай кілька хвилин."
        is FirebaseAuthRecentLoginRequiredException -> "Вийди й увійди знову, потім зміни пароль."
        is FirebaseAuthWeakPasswordException -> "Пароль закороткий — щонайменше 6 символів."
        is FirebaseAuthUserCollisionException -> "Цей номер уже зареєстровано. Увійди з паролем або попроси адміністратора скинути пароль."
        is FirebaseAuthInvalidUserException -> "Акаунта з таким номером немає або його вимкнено."
        is FirebaseAuthInvalidCredentialsException -> "Невірний номер або пароль."
        is FirebaseFirestoreException -> if (e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
            "Немає доступу. Перевір правила Firestore (firestore.rules)."
        } else {
            "Помилка бази: ${e.message}"
        }
        else -> e.message ?: e.javaClass.simpleName
    }
}

/**
 * Пускает в приложение только подтверждённого сотрудника.
 * [content] получает анкету, чтобы экраны знали магазин сотрудника.
 */
@Composable
fun AuthGate(content: @Composable (Employee) -> Unit) {
    val app = LocalContext.current.chudoApp
    val state by app.auth.state.collectAsStateWithLifecycle()
    when (val s = state) {
        is AuthState.Ready -> {
            // Остальные экраны (поиск, наличие) открываются сразу в городе сотрудника.
            LaunchedEffect(s.employee.uid) {
                if (s.employee.region.isNotEmpty() && app.prefs.region.value.code != s.employee.region) {
                    app.prefs.setRegion(s.employee.region)
                }
            }
            content(s.employee)
        }
        else -> AuthScreen(s)
    }
}

/** Смена пароля (профиль): текущий + новый дважды. */
@Composable
fun PasswordDialog(onDismiss: () -> Unit, onDone: () -> Unit) {
    val app = LocalContext.current.chudoApp
    val scope = rememberCoroutineScope()
    var current by rememberSaveable { mutableStateOf("") }
    var new by rememberSaveable { mutableStateOf("") }
    var repeat by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Змінити пароль") },
        text = {
            Column {
                PasswordField(current, "Поточний пароль") { current = it }
                Spacer(Modifier.height(10.dp))
                PasswordField(new, "Новий пароль") { new = it }
                Spacer(Modifier.height(10.dp))
                PasswordField(repeat, "Повтори пароль") { repeat = it }
                error?.let { Text(it, Modifier.padding(top = 10.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                Text(
                    "Забув поточний пароль — попроси адміністратора скинути його.",
                    Modifier.padding(top = 10.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                error = if (current.isEmpty()) "Введи поточний пароль." else Passwords(new, repeat).problem()
                if (error != null) return@TextButton
                busy = true
                scope.launch {
                    try {
                        app.auth.changePassword(current, new)
                        onDone()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        error = if (e is FirebaseAuthInvalidCredentialsException) "Поточний пароль невірний." else e.authMessage()
                    } finally {
                        busy = false
                    }
                }
            }) { if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Зберегти") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Скасувати") } },
    )
}

@Composable
private fun AuthScreen(state: AuthState) {
    val app = LocalContext.current.chudoApp
    val vm: AuthViewModel = viewModel(factory = viewModelFactory { initializer { AuthViewModel(app.auth, app.repository) } })
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painterResource(R.drawable.logo_wordmark), null,
            Modifier.fillMaxWidth(0.7f).height(72.dp),
            tint = androidx.compose.ui.graphics.Color.Unspecified,
        )
        Spacer(Modifier.height(20.dp))
        when (state) {
            AuthState.NotConfigured -> Notice(
                Icons.Rounded.CloudOff,
                "Вхід не підключено",
                "У цій збірці немає google-services.json. Додай файл з консолі Firebase у папку app/ і збери APK заново.",
            )
            AuthState.Loading, is AuthState.Ready -> CircularProgressIndicator(Modifier.padding(top = 48.dp))
            AuthState.SignedOut -> SignedOutForm(vm)
            is AuthState.NoProfile -> {
                Text("Заповни дані співробітника", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(formatPhone(state.phone), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(16.dp))
                ProfileFields(vm, needsAccount = false)
                TextButton(onClick = app.auth::signOut) { Text("Вийти") }
            }
            is AuthState.Pending -> {
                Notice(
                    Icons.Rounded.HourglassTop,
                    "Заявку надіслано",
                    "${state.employee.name}, адміністратор має підтвердити, що ти працюєш у магазині:\n${state.employee.shop}\n\nЩойно підтвердять — застосунок відкриється сам.",
                )
                TextButton(onClick = app.auth::signOut) { Text("Вийти") }
            }
            is AuthState.Failed -> {
                Notice(Icons.Rounded.WarningAmber, "Не вдалося завантажити профіль", state.message)
                TextButton(onClick = app.auth::signOut) { Text("Вийти") }
            }
        }
    }
}

@Composable
private fun SignedOutForm(vm: AuthViewModel) {
    // 0 — вход, 1 — регистрация.
    var mode by rememberSaveable { mutableIntStateOf(0) }
    var forgot by rememberSaveable { mutableStateOf(false) }
    val switchTo = { m: Int -> vm.clearError(); mode = m }
    Text(
        if (mode == 1) "Реєстрація співробітника" else "Вхід для співробітників",
        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
    )
    Spacer(Modifier.height(16.dp))
    if (mode == 1) {
        ProfileFields(vm, needsAccount = true)
        TextButton(onClick = { switchTo(0) }) { Text("Вже є акаунт? Увійти") }
    } else {
        LoginFields(vm)
        TextButton(onClick = { forgot = true }) { Text("Забув пароль") }
        TextButton(onClick = { switchTo(1) }) { Text("Немає акаунта? Зареєструватися") }
    }
    if (forgot) {
        AlertDialog(
            onDismissRequest = { forgot = false },
            title = { Text("Забув пароль") },
            text = {
                Text(
                    "Попроси адміністратора скинути пароль: він задасть тобі новий, і ти зможеш змінити його в Профілі.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = { TextButton(onClick = { forgot = false }) { Text("Зрозуміло") } },
        )
    }
}

@Composable
private fun LoginFields(vm: AuthViewModel) {
    var digits by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    val busy by vm.busy.collectAsStateWithLifecycle()
    PhoneField(digits, !busy) { digits = it }
    Spacer(Modifier.height(10.dp))
    PasswordField(password, "Пароль") { password = it }
    Messages(vm)
    Spacer(Modifier.height(16.dp))
    SubmitButton("Увійти", busy) { vm.signIn(digits, password) }
}

/** Номер после +380: 9 цифр, маска XX XXX XX XX. Принимает и вставку «0671234567» / «+380…». */
@Composable
private fun PhoneField(digits: String, enabled: Boolean, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = digits,
        onValueChange = { raw ->
            var d = raw.filter(Char::isDigit)
            if (d.length > 9 && d.startsWith("380")) d = d.drop(3)
            onChange(d.trimStart('0').take(9))
        },
        enabled = enabled, modifier = Modifier.fillMaxWidth(), singleLine = true,
        label = { Text("Номер телефону") },
        prefix = { Text("+380 ") },
        placeholder = { Text("XX XXX XX XX") },
        leadingIcon = { Icon(Icons.Rounded.Phone, null) },
        visualTransformation = PhoneMask,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
        shape = RoundedCornerShape(14.dp),
    )
}

private object PhoneMask : VisualTransformation {
    /** Пробел перед цифрой с этим индексом: 67 123 45 67. */
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

/** Регистрация (имя + телефон + пароль + город + магазин) или дозаполнение анкеты. */
@Composable
private fun ProfileFields(vm: AuthViewModel, needsAccount: Boolean) {
    val app = LocalContext.current.chudoApp
    var name by rememberSaveable { mutableStateOf("") }
    var digits by rememberSaveable { mutableStateOf("") }
    var region by rememberSaveable { mutableStateOf(app.prefs.region.value.code) }
    var shop by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var repeat by rememberSaveable { mutableStateOf("") }
    var manualShop by rememberSaveable { mutableStateOf(false) }
    var showRegions by rememberSaveable { mutableStateOf(false) }
    val busy by vm.busy.collectAsStateWithLifecycle()
    val shops by vm.shops.collectAsStateWithLifecycle()
    val regionObj = app.prefs.allRegions.firstOrNull { it.code == region } ?: app.prefs.region.value

    LaunchedEffect(region) { vm.loadShops(region) }

    OutlinedTextField(
        value = name, onValueChange = { name = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
        label = { Text("Ім'я та прізвище") }, shape = RoundedCornerShape(14.dp),
    )
    if (needsAccount) {
        Spacer(Modifier.height(10.dp))
        PhoneField(digits, !busy) { digits = it }
        Spacer(Modifier.height(10.dp))
        PasswordField(password, "Пароль (щонайменше $MIN_PASSWORD символів)") { password = it }
        Spacer(Modifier.height(10.dp))
        PasswordField(repeat, "Повтори пароль") { repeat = it }
    }
    Spacer(Modifier.height(16.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Твій магазин", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        AssistChip(
            onClick = { showRegions = true },
            label = { Text(regionObj.name) },
            leadingIcon = { Icon(Icons.Rounded.LocationOn, null, Modifier.size(18.dp)) },
        )
    }
    Spacer(Modifier.height(8.dp))
    when (val s = shops) {
        ShopsState.Loading -> CircularProgressIndicator(Modifier.padding(12.dp).size(28.dp))
        is ShopsState.Loaded -> if (!manualShop && s.shops.isNotEmpty()) {
            s.shops.forEach { address ->
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    tonalElevation = if (shop == address) 3.dp else 0.dp,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp).clickable { shop = address },
                ) {
                    Row(Modifier.padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = shop == address, onClick = { shop = address })
                        Text(address, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            TextButton(onClick = { manualShop = true; shop = "" }) { Text("Мого магазину немає в списку") }
        } else {
            ManualShop(shop) { shop = it }
        }
        ShopsState.Failed -> {
            Text(
                "Не вдалося завантажити магазини з сайту — впиши адресу вручну.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ManualShop(shop) { shop = it }
        }
    }
    Messages(vm)
    Spacer(Modifier.height(16.dp))
    SubmitButton(if (needsAccount) "Зареєструватися" else "Зберегти", busy) {
        if (needsAccount) vm.register(digits, Passwords(password, repeat), NewProfile(name, region, shop))
        else vm.completeProfile(name, region, shop)
    }
    Text(
        "Магазин змінити потім може лише адміністратор. Після реєстрації адміністратор підтверджує акаунт.",
        Modifier.padding(top = 8.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )

    if (showRegions) {
        RegionPickerSheet(
            regions = app.prefs.allRegions,
            selected = region,
            onPick = { region = it.code; shop = ""; manualShop = false; showRegions = false },
            onDismiss = { showRegions = false },
        )
    }
}

@Composable
private fun ManualShop(value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, modifier = Modifier.fillMaxWidth(),
        label = { Text("Адреса магазину") },
        leadingIcon = { Icon(Icons.Rounded.Store, null) },
        shape = RoundedCornerShape(14.dp),
    )
}

@Composable
private fun PasswordField(value: String, label: String, onChange: (String) -> Unit) {
    var visible by rememberSaveable { mutableStateOf(false) }
    OutlinedTextField(
        value = value, onValueChange = onChange, modifier = Modifier.fillMaxWidth(), singleLine = true,
        label = { Text(label) }, shape = RoundedCornerShape(14.dp),
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(if (visible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, null)
            }
        },
    )
}

@Composable
private fun Messages(vm: AuthViewModel) {
    val error by vm.error.collectAsStateWithLifecycle()
    error?.let {
        Text(it, Modifier.fillMaxWidth().padding(top = 10.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SubmitButton(text: String, busy: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = !busy, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp)) {
        if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) else Text(text)
    }
}

@Composable
private fun Notice(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, text: String) {
    Icon(icon, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(12.dp))
    Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    Spacer(Modifier.height(8.dp))
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    Spacer(Modifier.height(16.dp))
}
