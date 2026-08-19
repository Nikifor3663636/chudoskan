package ua.chudo.scanner.ui.auth

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.auth.PhoneAuthCredential
import com.google.firebase.auth.PhoneAuthProvider
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ua.chudo.scanner.R
import ua.chudo.scanner.chudoApp
import ua.chudo.scanner.data.AppPrefs
import ua.chudo.scanner.data.AuthRepository
import ua.chudo.scanner.data.AuthState
import ua.chudo.scanner.data.ChudoRepository
import ua.chudo.scanner.data.Employee
import ua.chudo.scanner.data.NewProfile
import ua.chudo.scanner.data.PhoneEvent
import ua.chudo.scanner.i18n.title
import ua.chudo.scanner.i18n.tr
import ua.chudo.scanner.ui.common.RegionPickerSheet
import uniffi.chudo_core.Lang

/** Список магазинов города не зависит от товара: сайт отдаёт все магазины для любого id. */
private const val ANY_PRODUCT_ID = 1L

/** Повторная отправка SMS — не раньше чем через 60 с. */
private const val RESEND_MS = 60_000L

sealed interface ShopsState {
    data object Loading : ShopsState
    data class Loaded(val shops: List<String>) : ShopsState
    data object Failed : ShopsState
}

class AuthViewModel(
    private val auth: AuthRepository,
    private val repo: ChudoRepository,
    private val prefs: AppPrefs,
) : ViewModel() {
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private val _info = MutableStateFlow<String?>(null)
    val info = _info.asStateFlow()
    private val _shops = MutableStateFlow<ShopsState>(ShopsState.Loading)
    val shops = _shops.asStateFlow()
    private var shopsJob: Job? = null
    private val lang get() = prefs.lang.value

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

    // --- Вход, регистрация и привязка по телефону ---
    private val _codeSent = MutableStateFlow(false)
    val codeSent = _codeSent.asStateFlow()
    private val _code = MutableStateFlow("")
    val code = _code.asStateFlow()
    /** SystemClock.elapsedRealtime(), с которого можно отправить код повторно. */
    private val _resendAt = MutableStateFlow(0L)
    val resendAt = _resendAt.asStateFlow()
    /** Телефон привязан к старому аккаунту. */
    private val _linked = MutableStateFlow(false)
    val linked = _linked.asStateFlow()
    private var verificationId: String? = null
    private var resendToken: PhoneAuthProvider.ForceResendingToken? = null
    private var lastRequest: (Activity, Boolean) -> Unit = { _, _ -> }

    fun setCode(value: String) { _code.value = value.filter(Char::isDigit).take(6) }

    /** Отправить SMS. [profile] — для регистрации, [link] — привязка к текущему (email) аккаунту. */
    fun requestCode(activity: Activity, digits: String, profile: NewProfile?, link: Boolean, resend: Boolean = false) {
        if (_busy.value) return
        clearMessages()
        profile?.let { p -> check(p.name, p.shop)?.let { return fail(it) } }
        if (digits.length != 9) return fail(lang.tr("Введи номер повністю: +380 XX XXX XX XX.", "Введи номер полностью: +380 XX XXX XX XX."))
        lastRequest = { a, r -> requestCode(a, digits, profile, link, r) }
        _busy.value = true
        auth.sendCode(activity, "+380$digits", if (resend) resendToken else null) { ev ->
            when (ev) {
                is PhoneEvent.CodeSent -> {
                    verificationId = ev.verificationId
                    resendToken = ev.token
                    _codeSent.value = true
                    _resendAt.value = SystemClock.elapsedRealtime() + RESEND_MS
                    _busy.value = false
                }
                is PhoneEvent.Verified -> {
                    ev.credential.smsCode?.let { _code.value = it }
                    _codeSent.value = true
                    _busy.value = false
                    finish(ev.credential, profile, link)
                }
                is PhoneEvent.Failed -> {
                    _busy.value = false
                    _error.value = ev.error.authMessage(lang)
                }
            }
        }
    }

    fun resend(activity: Activity) = lastRequest(activity, true)

    fun verify(profile: NewProfile?, link: Boolean) {
        val id = verificationId ?: return
        if (_code.value.length != 6) return fail(lang.tr("Введи 6 цифр із SMS.", "Введи 6 цифр из SMS."))
        finish(auth.credential(id, _code.value), profile, link)
    }

    private fun finish(credential: PhoneAuthCredential, profile: NewProfile?, link: Boolean) = submit {
        if (link) {
            auth.linkPhone(credential)
            _linked.value = true
        } else {
            auth.signInWithPhone(credential, profile)
        }
    }

    fun resetPhone() {
        clearMessages()
        _codeSent.value = false
        _code.value = ""
        verificationId = null
    }

    /** Вход в старый аккаунт по email — только чтобы потом привязать телефон. */
    fun signIn(email: String, password: String) = submit {
        if (email.isBlank() || password.isEmpty()) return@submit fail(lang.tr("Введи email і пароль.", "Введи email и пароль."))
        auth.signIn(email, password)
    }

    fun completeProfile(name: String, region: String, shop: String) = submit {
        check(name, shop)?.let { return@submit fail(it) }
        auth.completeProfile(name, region, shop)
    }

    fun resetPassword(email: String) = submit {
        if (email.isBlank()) return@submit fail(lang.tr("Спочатку введи email.", "Сначала введи email."))
        auth.resetPassword(email)
        _info.value = lang.tr("Лист для зміни пароля надіслано на $email.", "Письмо для смены пароля отправлено на $email.")
    }

    fun clearMessages() { _error.value = null; _info.value = null }

    private fun check(name: String, shop: String): String? = when {
        name.isBlank() -> lang.tr("Введи ім'я.", "Введи имя.")
        shop.isBlank() -> lang.tr("Обери свій магазин.", "Выбери свой магазин.")
        else -> null
    }

    private fun fail(msg: String) { _error.value = msg }

    private fun submit(block: suspend () -> Unit) {
        if (_busy.value) return
        clearMessages()
        viewModelScope.launch {
            _busy.value = true
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = e.authMessage(lang)
            } finally {
                _busy.value = false
            }
        }
    }
}

fun Throwable.authMessage(lang: Lang): String = with(lang) {
    when (val e = this@authMessage) {
        is FirebaseNetworkException -> tr("Немає інтернету.", "Нет интернета.")
        is FirebaseTooManyRequestsException -> tr("Забагато спроб. Зачекай кілька хвилин.", "Слишком много попыток. Подожди несколько минут.")
        is FirebaseAuthWeakPasswordException -> tr("Пароль закороткий — щонайменше 6 символів.", "Пароль слишком короткий — минимум 6 символов.")
        is FirebaseAuthUserCollisionException -> tr("Цей номер уже прив'язано до іншого акаунта.", "Этот номер уже привязан к другому аккаунту.")
        is FirebaseAuthInvalidUserException -> tr("Такого акаунта немає або його вимкнено.", "Такого аккаунта нет или он отключён.")
        is FirebaseAuthInvalidCredentialsException -> tr("Невірний код або дані входу.", "Неверный код или данные входа.")
        is FirebaseFirestoreException -> if (e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
            tr("Немає доступу. Перевір правила Firestore (firestore.rules).", "Нет доступа. Проверь правила Firestore (firestore.rules).")
        } else {
            tr("Помилка бази: ${e.message}", "Ошибка базы: ${e.message}")
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
            PhoneLinkOffer(s.employee.uid)
        }
        is AuthState.Pending -> {
            AuthScreen(s)
            PhoneLinkOffer(s.employee.uid)
        }
        else -> AuthScreen(s)
    }
}

/** Старому email-аккаунту один раз предлагаем привязать телефон (uid не меняется). */
@Composable
private fun PhoneLinkOffer(uid: String) {
    val app = LocalContext.current.chudoApp
    val activity = LocalContext.current.findActivity()
    var show by remember(uid) { mutableStateOf(app.auth.needsPhoneLink() && !app.prefs.phoneLinkOffered(uid)) }
    if (!show) return
    val vm: AuthViewModel = viewModel(key = "link", factory = viewModelFactory { initializer { AuthViewModel(app.auth, app.repository, app.prefs) } })
    val codeSent by vm.codeSent.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val linked by vm.linked.collectAsStateWithLifecycle()
    var digits by rememberSaveable { mutableStateOf("") }
    val close = { app.prefs.markPhoneLinkOffered(uid); show = false }
    LaunchedEffect(linked) { if (linked) close() }
    AlertDialog(
        onDismissRequest = {},
        title = { Text(tr("Прив'яжи телефон", "Привяжи телефон")) },
        text = {
            Column {
                Text(
                    tr(
                        "Вхід по email більше не підтримується. Прив'яжи номер — далі входитимеш за кодом з SMS. Дані акаунта збережуться.",
                        "Вход по email больше не поддерживается. Привяжи номер — дальше будешь входить по коду из SMS. Данные аккаунта сохранятся.",
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                if (codeSent) CodeFields(vm, digits) else PhoneField(digits, !busy) { digits = it }
                Messages(vm)
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                if (codeSent) vm.verify(null, link = true)
                else activity?.let { vm.requestCode(it, digits, null, link = true) }
            }) { Text(if (codeSent) tr("Підтвердити", "Подтвердить") else tr("Надіслати код", "Отправить код")) }
        },
        dismissButton = { TextButton(onClick = close) { Text(tr("Пізніше", "Позже")) } },
    )
}

@Composable
private fun AuthScreen(state: AuthState) {
    val app = LocalContext.current.chudoApp
    val vm: AuthViewModel = viewModel(factory = viewModelFactory { initializer { AuthViewModel(app.auth, app.repository, app.prefs) } })
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LangSwitch(app.prefs)
        Icon(
            painterResource(R.drawable.logo_wordmark), null,
            Modifier.fillMaxWidth(0.7f).height(72.dp),
            tint = androidx.compose.ui.graphics.Color.Unspecified,
        )
        Spacer(Modifier.height(20.dp))
        when (state) {
            AuthState.NotConfigured -> Notice(
                Icons.Rounded.CloudOff,
                tr("Вхід не підключено", "Вход не подключён"),
                tr(
                    "У цій збірці немає google-services.json. Додай файл з консолі Firebase у папку app/ і збери APK заново.",
                    "В этой сборке нет google-services.json. Добавь файл из консоли Firebase в папку app/ и собери APK заново.",
                ),
            )
            AuthState.Loading, is AuthState.Ready -> CircularProgressIndicator(Modifier.padding(top = 48.dp))
            AuthState.SignedOut -> SignedOutForm(vm)
            is AuthState.NoProfile -> {
                Text(tr("Заповни дані співробітника", "Заполни данные сотрудника"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(state.email, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(16.dp))
                ProfileFields(vm, needsAccount = false)
                TextButton(onClick = app.auth::signOut) { Text(tr("Вийти", "Выйти")) }
            }
            is AuthState.Pending -> {
                Notice(
                    Icons.Rounded.HourglassTop,
                    tr("Заявку надіслано", "Заявка отправлена"),
                    tr(
                        "${state.employee.name}, адміністратор має підтвердити, що ти працюєш у магазині:\n${state.employee.shop}\n\nЩойно підтвердять — застосунок відкриється сам.",
                        "${state.employee.name}, администратор должен подтвердить, что ты работаешь в магазине:\n${state.employee.shop}\n\nКак только подтвердят — приложение откроется само.",
                    ),
                )
                TextButton(onClick = app.auth::signOut) { Text(tr("Вийти", "Выйти")) }
            }
            is AuthState.Failed -> {
                Notice(Icons.Rounded.WarningAmber, tr("Не вдалося завантажити профіль", "Не удалось загрузить профиль"), state.message)
                TextButton(onClick = app.auth::signOut) { Text(tr("Вийти", "Выйти")) }
            }
        }
    }
}

@Composable
private fun LangSwitch(prefs: AppPrefs) {
    val lang by prefs.lang.collectAsStateWithLifecycle()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = { prefs.setLang(if (lang == Lang.RU) Lang.UA else Lang.RU) }) {
            Text(if (lang == Lang.RU) "RU → UA" else "UA → RU")
        }
    }
}

@Composable
private fun SignedOutForm(vm: AuthViewModel) {
    // 0 — вход по телефону, 1 — регистрация, 2 — старый аккаунт с email.
    var mode by rememberSaveable { mutableIntStateOf(0) }
    val switchTo = { m: Int -> vm.resetPhone(); mode = m }
    Text(
        when (mode) {
            1 -> tr("Реєстрація співробітника", "Регистрация сотрудника")
            2 -> tr("Старий акаунт з email", "Старый аккаунт с email")
            else -> tr("Вхід для співробітників", "Вход для сотрудников")
        },
        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
    )
    Spacer(Modifier.height(16.dp))
    when (mode) {
        1 -> {
            ProfileFields(vm, needsAccount = true)
            TextButton(onClick = { switchTo(0) }) { Text(tr("Вже є акаунт? Увійти", "Уже есть аккаунт? Войти")) }
        }
        2 -> {
            LoginFields(vm)
            TextButton(onClick = { switchTo(0) }) { Text(tr("Увійти за номером телефону", "Войти по номеру телефона")) }
        }
        else -> {
            PhoneLogin(vm)
            TextButton(onClick = { switchTo(1) }) { Text(tr("Немає акаунта? Зареєструватися", "Нет аккаунта? Зарегистрироваться")) }
            TextButton(onClick = { switchTo(2) }) {
                Text(tr("Раніше входив по email?", "Раньше входил по email?"), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun PhoneLogin(vm: AuthViewModel) {
    val activity = LocalContext.current.findActivity()
    var digits by rememberSaveable { mutableStateOf("") }
    val busy by vm.busy.collectAsStateWithLifecycle()
    val codeSent by vm.codeSent.collectAsStateWithLifecycle()
    if (codeSent) {
        CodeFields(vm, digits)
        Messages(vm)
        Spacer(Modifier.height(16.dp))
        SubmitButton(tr("Увійти", "Войти"), busy) { vm.verify(null, link = false) }
    } else {
        PhoneField(digits, !busy) { digits = it }
        Messages(vm)
        Spacer(Modifier.height(16.dp))
        SubmitButton(tr("Отримати код", "Получить код"), busy) {
            activity?.let { vm.requestCode(it, digits, null, link = false) }
        }
    }
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
        label = { Text(tr("Номер телефону", "Номер телефона")) },
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

/** Поле кода из SMS + повторная отправка через 60 с. Код с SMS подставляется сам (SMS Retriever). */
@Composable
private fun CodeFields(vm: AuthViewModel, digits: String) {
    val activity = LocalContext.current.findActivity()
    val code by vm.code.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val resendAt by vm.resendAt.collectAsStateWithLifecycle()
    var left by remember { mutableIntStateOf(0) }
    LaunchedEffect(resendAt) {
        while (true) {
            left = ((resendAt - SystemClock.elapsedRealtime() + 999) / 1000).toInt().coerceAtLeast(0)
            if (left == 0) break
            delay(1000)
        }
    }
    Text(
        tr("Код надіслано на +380 ${PhoneMask.filter(AnnotatedString(digits)).text}", "Код отправлен на +380 ${PhoneMask.filter(AnnotatedString(digits)).text}"),
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(10.dp))
    OutlinedTextField(
        value = code, onValueChange = vm::setCode, enabled = !busy,
        modifier = Modifier.fillMaxWidth(), singleLine = true,
        label = { Text(tr("Код з SMS", "Код из SMS")) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        shape = RoundedCornerShape(14.dp),
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(onClick = vm::resetPhone, enabled = !busy) { Text(tr("Змінити номер", "Изменить номер")) }
        TextButton(onClick = { activity?.let(vm::resend) }, enabled = left == 0 && !busy) {
            Text(if (left > 0) tr("Ще раз через $left с", "Ещё раз через $left с") else tr("Надіслати ще раз", "Отправить ещё раз"))
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
private fun LoginFields(vm: AuthViewModel) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    val busy by vm.busy.collectAsStateWithLifecycle()
    EmailField(email) { email = it }
    Spacer(Modifier.height(10.dp))
    PasswordField(password) { password = it }
    Messages(vm)
    Spacer(Modifier.height(16.dp))
    SubmitButton(tr("Увійти", "Войти"), busy) { vm.signIn(email, password) }
    TextButton(onClick = { vm.resetPassword(email) }, enabled = !busy) { Text(tr("Забув пароль", "Забыл пароль")) }
}

/** Регистрация (имя + телефон + город + магазин) или дозаполнение анкеты. */
@Composable
private fun ProfileFields(vm: AuthViewModel, needsAccount: Boolean) {
    val app = LocalContext.current.chudoApp
    val activity = LocalContext.current.findActivity()
    var name by rememberSaveable { mutableStateOf("") }
    var digits by rememberSaveable { mutableStateOf("") }
    var region by rememberSaveable { mutableStateOf(app.prefs.region.value.code) }
    var shop by rememberSaveable { mutableStateOf("") }
    var manualShop by rememberSaveable { mutableStateOf(false) }
    var showRegions by rememberSaveable { mutableStateOf(false) }
    val busy by vm.busy.collectAsStateWithLifecycle()
    val codeSent by vm.codeSent.collectAsStateWithLifecycle()
    val shops by vm.shops.collectAsStateWithLifecycle()
    val regionObj = app.prefs.allRegions.firstOrNull { it.code == region } ?: app.prefs.region.value

    LaunchedEffect(region) { vm.loadShops(region) }

    if (needsAccount && codeSent) {
        CodeFields(vm, digits)
        Messages(vm)
        Spacer(Modifier.height(16.dp))
        SubmitButton(tr("Зареєструватися", "Зарегистрироваться"), busy) {
            vm.verify(NewProfile(name, region, shop), link = false)
        }
        return
    }

    OutlinedTextField(
        value = name, onValueChange = { name = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
        label = { Text(tr("Ім'я та прізвище", "Имя и фамилия")) }, shape = RoundedCornerShape(14.dp),
    )
    if (needsAccount) {
        Spacer(Modifier.height(10.dp))
        PhoneField(digits, !busy) { digits = it }
    }
    Spacer(Modifier.height(16.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(tr("Твій магазин", "Твой магазин"), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        AssistChip(
            onClick = { showRegions = true },
            label = { Text(regionObj.title()) },
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
            TextButton(onClick = { manualShop = true; shop = "" }) { Text(tr("Мого магазину немає в списку", "Моего магазина нет в списке")) }
        } else {
            ManualShop(shop) { shop = it }
        }
        ShopsState.Failed -> {
            Text(
                tr("Не вдалося завантажити магазини з сайту — впиши адресу вручну.", "Не удалось загрузить магазины с сайта — впиши адрес вручную."),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ManualShop(shop) { shop = it }
        }
    }
    Messages(vm)
    Spacer(Modifier.height(16.dp))
    SubmitButton(if (needsAccount) tr("Отримати код", "Получить код") else tr("Зберегти", "Сохранить"), busy) {
        if (needsAccount) activity?.let { vm.requestCode(it, digits, NewProfile(name, region, shop), link = false) }
        else vm.completeProfile(name, region, shop)
    }
    Text(
        tr(
            "Магазин змінити потім може лише адміністратор. Після реєстрації адміністратор підтверджує акаунт.",
            "Магазин потом может сменить только администратор. После регистрации администратор подтверждает аккаунт.",
        ),
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
        label = { Text(tr("Адреса магазину", "Адрес магазина")) },
        leadingIcon = { Icon(Icons.Rounded.Store, null) },
        shape = RoundedCornerShape(14.dp),
    )
}

@Composable
private fun EmailField(value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, modifier = Modifier.fillMaxWidth(), singleLine = true,
        label = { Text("Email") }, shape = RoundedCornerShape(14.dp),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
    )
}

@Composable
private fun PasswordField(value: String, onChange: (String) -> Unit) {
    var visible by rememberSaveable { mutableStateOf(false) }
    OutlinedTextField(
        value = value, onValueChange = onChange, modifier = Modifier.fillMaxWidth(), singleLine = true,
        label = { Text(tr("Пароль", "Пароль")) }, shape = RoundedCornerShape(14.dp),
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
    val info by vm.info.collectAsStateWithLifecycle()
    error?.let {
        Text(it, Modifier.fillMaxWidth().padding(top = 10.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
    }
    info?.let {
        Text(it, Modifier.fillMaxWidth().padding(top = 10.dp), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
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
