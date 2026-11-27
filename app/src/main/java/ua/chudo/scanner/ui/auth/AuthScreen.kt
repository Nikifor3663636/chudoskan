package ua.chudo.scanner.ui.auth

import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.ui.semantics.Role
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
import androidx.compose.ui.unit.sp
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
import ua.chudo.scanner.ui.common.MinePrimaryButton
import ua.chudo.scanner.ui.theme.Mine
import ua.chudo.scanner.ui.theme.ThemeMode
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
        // Supabase: 42501 — RLS / права; 401 — токен без role або чужого проєкту.
        is io.github.jan.supabase.postgrest.exception.PostgrestRestException ->
            if (e.code == "42501" || e.statusCode == 401 || e.statusCode == 403) "Немає доступу (перевір права в Supabase)." else "Помилка бази: ${e.message}"
        is io.github.jan.supabase.exceptions.HttpRequestException -> "Немає інтернету."
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
    when (state) {
        AuthState.Loading, is AuthState.Ready -> AuthSplash(app.updater.currentVersion)
        AuthState.SignedOut -> SignedOutFlow(vm)
        is AuthState.NoProfile -> ProfileFlow(vm, needsAccount = false, sub = formatPhone(state.phone), onBack = app.auth::signOut)
        is AuthState.Pending -> PendingPage(state.employee.shop, app.auth::signOut)
        AuthState.NotConfigured -> NoticePage(
            R.drawable.ic_redesign_circle_alert, "Вхід не підключено",
            "У цій збірці немає налаштувань входу. Додайте файл конфігурації в папку app/ і зберіть застосунок заново.",
            null,
        )
        AuthState.DevicePending -> NoticePage(
            R.drawable.ic_redesign_clock, "Пристрій очікує підтвердження",
            "Адмін мережі має підтвердити цей пристрій. Стан оновлюється автоматично.", app.auth::signOut,
        )
        AuthState.DeviceBlocked -> NoticePage(
            R.drawable.ic_redesign_triangle_alert, "Пристрій заблоковано", "Зверніться до адміна мережі.", app.auth::signOut,
        )
        is AuthState.Failed -> NoticePage(
            R.drawable.ic_redesign_triangle_alert, "Не вдалося завантажити профіль", state.message, app.auth::signOut,
        )
    }
}

@Composable
private fun NoticePage(@androidx.annotation.DrawableRes icon: Int, title: String, text: String, onSignOut: (() -> Unit)?) {
    AuthPage {
        Spacer(Modifier.height(12.dp))
        AuthStateBlock(icon, title, text)
        Spacer(Modifier.weight(1f))
        onSignOut?.let { AuthGhostButton("Вийти", onClick = it) }
    }
}

/** «Анкету надіслано»: кроки й автоматичний вхід після підтвердження. */
@Composable
internal fun PendingPage(shop: String, onSignOut: () -> Unit) {
    AuthPage {
        Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            AuthStateBlock(
                R.drawable.ic_redesign_clock, "Анкету надіслано",
                "Адмін вашого магазину підтвердить її в застосунку. Ви увійдете автоматично — нічого не треба оновлювати.",
            )
            AuthSteps(
                listOf(
                    Triple(StepState.DONE, "Анкету отримано", shop.ifBlank { null }),
                    Triple(StepState.ACTIVE, "Чекаємо на адміна магазину", "Зазвичай до 1 години в робочий час"),
                    Triple(StepState.TODO, "Вхід у застосунок", null),
                ),
            )
        }
        Spacer(Modifier.weight(1f))
        AuthGhostButton("Вийти", Modifier.padding(top = 18.dp), onClick = onSignOut)
    }
}

private enum class SignedOutPage { LOGIN, FORGOT, REGISTER }

@Composable
private fun SignedOutFlow(vm: AuthViewModel) {
    var page by rememberSaveable { mutableStateOf(SignedOutPage.LOGIN) }
    val open = { p: SignedOutPage -> vm.clearError(); page = p }
    if (page != SignedOutPage.LOGIN) androidx.activity.compose.BackHandler { open(SignedOutPage.LOGIN) }
    when (page) {
        SignedOutPage.LOGIN -> {
            var digits by rememberSaveable { mutableStateOf("") }
            var password by rememberSaveable { mutableStateOf("") }
            val busy by vm.busy.collectAsStateWithLifecycle()
            val error by vm.error.collectAsStateWithLifecycle()
            AuthPage {
                LoginMineForm(
                    digits, password, error, busy,
                    onDigits = { digits = it }, onPassword = { password = it },
                    onSubmit = { vm.signIn(digits, password) },
                    onForgot = { open(SignedOutPage.FORGOT) }, onRegister = { open(SignedOutPage.REGISTER) },
                )
            }
        }
        SignedOutPage.FORGOT -> ForgotPage { open(SignedOutPage.LOGIN) }
        SignedOutPage.REGISTER -> ProfileFlow(vm, needsAccount = true, sub = "Крок 1 з 2", onBack = { open(SignedOutPage.LOGIN) })
    }
}

/** Відновлення пароля: пароль скидає адмін магазину, SMS не потрібні. */
@Composable
private fun ForgotPage(onBack: () -> Unit) {
    val m = Mine.colors
    AuthNavPage("Відновлення пароля", onBack) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                "Пароль скидає адмін вашого магазину — SMS не потрібні. Зверніться до адміна, і він задасть тимчасовий пароль у «Мій магазин».",
                fontSize = 15.sp, lineHeight = 22.sp, color = m.text,
            )
            AuthPrimaryButton("Зрозуміло", busy = false, onClick = onBack)
            Text(
                "Після скидання адмін передасть тимчасовий пароль особисто. Змінити його можна в «Профіль → Змінити пароль».",
                fontSize = 13.sp, lineHeight = 18.sp, color = m.textTertiary,
            )
        }
    }
}

/** Анкета співробітника (реєстрація) або дозаповнення профілю; вибір магазину — окремим екраном. */
@Composable
private fun ProfileFlow(vm: AuthViewModel, needsAccount: Boolean, sub: String?, onBack: () -> Unit) {
    val app = LocalContext.current.chudoApp
    val m = Mine.colors
    val currentRegion by app.prefs.region.collectAsStateWithLifecycle()
    var name by rememberSaveable { mutableStateOf("") }
    var digits by rememberSaveable { mutableStateOf("") }
    var region by rememberSaveable { mutableStateOf(currentRegion.code) }
    var shop by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var manualShop by rememberSaveable { mutableStateOf(false) }
    var picking by rememberSaveable { mutableStateOf(false) }
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()

    LaunchedEffect(region) { vm.loadShops(region) }

    if (picking) {
        androidx.activity.compose.BackHandler { picking = false }
        ShopPickerPage(
            vm, region, shop,
            onRegion = { region = it; shop = "" },
            onPick = { shop = it; manualShop = false; picking = false },
            onManual = { manualShop = true; shop = ""; picking = false },
            onBack = { picking = false },
        )
        return
    }
    AuthNavPage("Анкета співробітника", onBack, sub = sub) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            AuthField("Ім’я та прізвище") { AuthTextInput(name, { name = it }, enabled = !busy) }
            if (needsAccount) {
                AuthField("Телефон", hint = "Він же буде логіном") { MinePhoneField(digits, !busy) { digits = it } }
                AuthField("Пароль", hint = "Щонайменше $MIN_PASSWORD символів") { MinePasswordField(password, !busy) { password = it } }
            }
            AuthField("Магазин") {
                if (manualShop) {
                    AuthTextInput(shop, { shop = it }, enabled = !busy, placeholder = "Адреса магазину", leading = R.drawable.ic_redesign_store)
                } else {
                    AuthInputBox(focused = false, modifier = Modifier.clickable(enabled = !busy) { picking = true }) {
                        Icon(painterResource(R.drawable.ic_redesign_store), null, Modifier.size(18.dp), tint = m.textTertiary)
                        Text(
                            shop.ifBlank { "Оберіть магазин" }, Modifier.weight(1f), fontSize = 16.sp,
                            color = if (shop.isBlank()) m.textTertiary else m.text, maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                        Icon(painterResource(R.drawable.ic_redesign_chevron_right), null, Modifier.size(18.dp), tint = m.textTertiary)
                    }
                }
            }
            error?.let { Text(it, fontSize = 13.sp, lineHeight = 18.sp, color = m.danger) }
            AuthPrimaryButton(if (needsAccount) "Надіслати анкету" else "Зберегти", busy, Modifier.padding(top = 8.dp)) {
                if (needsAccount) vm.register(digits, Passwords(password, password), NewProfile(name, region, shop))
                else vm.completeProfile(name, region, shop)
            }
        }
    }
}

/** «Ваш магазин»: пошук, магазини міста, позначка вибраного. */
@Composable
private fun ShopPickerPage(
    vm: AuthViewModel,
    region: String,
    selected: String,
    onRegion: (String) -> Unit,
    onPick: (String) -> Unit,
    onManual: () -> Unit,
    onBack: () -> Unit,
) {
    val app = LocalContext.current.chudoApp
    val m = Mine.colors
    val shops by vm.shops.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var showRegions by rememberSaveable { mutableStateOf(false) }
    val regionName = app.prefs.allRegions.firstOrNull { it.code == region }?.name ?: region
    AuthNavPage(
        "Ваш магазин", onBack,
        header = {
            Column(Modifier.background(m.card).padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 12.dp)) {
                AuthTextInput(query, { query = it }, placeholder = "Місто, ТЦ або номер ЧО", leading = R.drawable.ic_redesign_search)
            }
            HorizontalDivider(color = m.line)
        },
    ) {
        Column(Modifier.fillMaxWidth().background(m.bg)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, top = 20.dp, end = 16.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(regionName, Modifier.weight(1f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = m.textSecondary)
                Text("Змінити місто", Modifier.clickable { showRegions = true }, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = m.link)
            }
            HorizontalDivider(color = m.line)
            when (val s = shops) {
                ShopsState.Loading -> Box(Modifier.fillMaxWidth().background(m.card).padding(16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = m.accent)
                }
                ShopsState.Failed -> PickerRow("Не вдалося завантажити магазини — впишіть адресу вручну", selected = false, onClick = onManual)
                is ShopsState.Loaded -> {
                    val q = query.trim()
                    s.shops.filter { q.isEmpty() || it.contains(q, ignoreCase = true) || regionName.contains(q, ignoreCase = true) }
                        .forEach { address -> PickerRow(address, selected = address == selected) { onPick(address) } }
                }
            }
            PickerRow("Мого магазину немає в списку", selected = false, onClick = onManual)
        }
    }
    if (showRegions) {
        RegionPickerSheet(
            regions = app.prefs.allRegions,
            selected = region,
            onPick = { onRegion(it.code); showRegions = false },
            onDismiss = { showRegions = false },
        )
    }
}

@Composable
private fun PickerRow(title: String, selected: Boolean, onClick: () -> Unit) {
    val m = Mine.colors
    Row(
        Modifier.fillMaxWidth().background(m.card).selectable(selected, role = Role.RadioButton, onClick = onClick)
            .heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, Modifier.weight(1f), fontSize = 15.sp, lineHeight = 19.5.sp, fontWeight = FontWeight.Medium, color = m.text)
        if (selected) Icon(painterResource(R.drawable.ic_redesign_check), "Вибрано", Modifier.size(20.dp), tint = m.text)
        else Icon(painterResource(R.drawable.ic_redesign_chevron_right), null, Modifier.size(18.dp), tint = m.textTertiary)
    }
    HorizontalDivider(color = m.line)
}

internal object PhoneMask : VisualTransformation {
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

@Composable
private fun PasswordField(value: String, label: String, onChange: (String) -> Unit) {
    var visible by rememberSaveable { mutableStateOf(false) }
    OutlinedTextField(
        value = value, onValueChange = onChange, modifier = Modifier.fillMaxWidth(), singleLine = true,
        label = { Text(label) }, shape = Mine.fieldShape,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(if (visible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, null)
            }
        },
    )
}

