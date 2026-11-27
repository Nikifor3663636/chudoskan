package ua.chudo.scanner.ui.auth

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ua.chudo.scanner.R
import ua.chudo.scanner.ui.theme.Mine

/** Сторінка входу й станів акаунта: біла поверхня, відступи 28/20/20. */
@Composable
fun AuthPage(content: @Composable ColumnScope.() -> Unit) {
    val m = Mine.colors
    BoxWithConstraints(Modifier.fillMaxSize().background(m.card)) {
        val minHeight = maxHeight
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .statusBarsPadding().navigationBarsPadding().imePadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                Modifier.widthIn(max = 460.dp).fillMaxWidth().heightIn(min = minHeight)
                    .padding(start = 20.dp, top = 28.dp, end = 20.dp, bottom = 20.dp),
                content = content,
            )
        }
    }
}

/** Екран із верхньою панеллю на поверхні: «Відновлення пароля», «Анкета», «Ваш магазин». */
@Composable
fun AuthNavPage(
    title: String,
    onBack: () -> Unit,
    sub: String? = null,
    header: @Composable ColumnScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val m = Mine.colors
    Column(Modifier.fillMaxSize().background(m.card).statusBarsPadding().navigationBarsPadding().imePadding()) {
        Row(
            Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                Icon(painterResource(R.drawable.ic_redesign_chevron_left), "Назад", Modifier.size(24.dp), tint = m.textSecondary)
            }
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = m.text, maxLines = 1)
                sub?.let { Text(it, fontSize = 12.sp, lineHeight = 14.sp, color = m.textTertiary) }
            }
        }
        HorizontalDivider(color = m.line)
        header()
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()), content = content)
    }
}

@Composable
fun AuthLogo(width: Dp, modifier: Modifier = Modifier) {
    Image(
        painterResource(R.drawable.logo_wordmark), "Чудо Острів",
        modifier.width(width), contentScale = ContentScale.FillWidth,
    )
}

/** Поле як `.field`: підпис 13 sp, поле 44 dp, підказка або помилка під ним. */
@Composable
fun AuthField(label: String, hint: String? = null, error: String? = null, content: @Composable () -> Unit) {
    val m = Mine.colors
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = m.textSecondary)
        content()
        when {
            error != null -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_redesign_circle_alert), null, Modifier.size(16.dp), tint = m.danger)
                Text(error, fontSize = 13.sp, lineHeight = 17.sp, color = m.danger)
            }
            hint != null -> Text(hint, fontSize = 13.sp, color = m.textTertiary)
        }
    }
}

/** Рамка `.input`: 44 dp, радіус 6, у фокусі — синя, з помилкою — червона. */
@Composable
fun AuthInputBox(
    focused: Boolean,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    val m = Mine.colors
    val shape = RoundedCornerShape(6.dp)
    val ring = when {
        isError -> m.danger
        focused -> m.info
        else -> null
    }
    Row(
        modifier.fillMaxWidth()
            .height(44.dp).background(m.card, shape).border(1.dp, ring ?: m.outline, shape).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@Composable
fun AuthTextInput(
    value: String,
    onChange: (String) -> Unit,
    enabled: Boolean = true,
    placeholder: String = "",
    keyboard: KeyboardType = KeyboardType.Text,
    isError: Boolean = false,
    @DrawableRes leading: Int? = null,
) {
    val m = Mine.colors
    var focused by remember { mutableStateOf(false) }
    AuthInputBox(focused, isError = isError) {
        leading?.let { Icon(painterResource(it), null, Modifier.size(18.dp), tint = m.textTertiary) }
        BasicTextField(
            value = value, onValueChange = onChange, enabled = enabled, singleLine = true,
            textStyle = TextStyle(fontSize = 16.sp, color = m.text),
            cursorBrush = SolidColor(m.info),
            keyboardOptions = KeyboardOptions(keyboardType = keyboard),
            modifier = Modifier.weight(1f).onFocusChanged { focused = it.isFocused },
            decorationBox = { inner ->
                if (value.isEmpty() && placeholder.isNotEmpty()) Text(placeholder, fontSize = 16.sp, color = m.textTertiary)
                inner()
            },
        )
    }
}

/** «+380» сірим і 9 цифр з маскою XX XXX XX XX. */
@Composable
fun MinePhoneField(digits: String, enabled: Boolean, isError: Boolean = false, onChange: (String) -> Unit) {
    val m = Mine.colors
    var focused by remember { mutableStateOf(false) }
    AuthInputBox(focused, isError = isError) {
        Text("+380", fontSize = 14.sp, color = m.textTertiary)
        BasicTextField(
            value = digits,
            onValueChange = { raw ->
                var d = raw.filter(Char::isDigit)
                if (d.length > 9 && d.startsWith("380")) d = d.drop(3)
                onChange(d.trimStart('0').take(9))
            },
            enabled = enabled, singleLine = true,
            textStyle = TextStyle(fontSize = 16.sp, color = m.text),
            cursorBrush = SolidColor(m.info),
            visualTransformation = PhoneMask,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            modifier = Modifier.weight(1f).onFocusChanged { focused = it.isFocused },
            decorationBox = { inner ->
                if (digits.isEmpty()) Text("XX XXX XX XX", fontSize = 16.sp, color = m.textTertiary)
                inner()
            },
        )
    }
}

@Composable
fun MinePasswordField(value: String, enabled: Boolean, isError: Boolean = false, onChange: (String) -> Unit) {
    val m = Mine.colors
    var focused by remember { mutableStateOf(false) }
    var visible by rememberSaveable { mutableStateOf(false) }
    AuthInputBox(focused, isError = isError) {
        BasicTextField(
            value = value, onValueChange = onChange, enabled = enabled, singleLine = true,
            textStyle = TextStyle(fontSize = 16.sp, color = m.text),
            cursorBrush = SolidColor(m.info),
            visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.weight(1f).onFocusChanged { focused = it.isFocused },
        )
        IconButton(onClick = { visible = !visible }, modifier = Modifier.size(36.dp)) {
            Icon(
                painterResource(if (visible) R.drawable.ic_redesign_eye_off else R.drawable.ic_redesign_eye),
                if (visible) "Сховати пароль" else "Показати пароль", Modifier.size(18.dp), tint = m.textSecondary,
            )
        }
    }
}

/** `.btn.primary.lg.block`: 52 dp, акцент. */
@Composable
fun AuthPrimaryButton(text: String, busy: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val m = Mine.colors
    Surface(
        onClick = onClick, enabled = !busy, modifier = modifier.fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(6.dp), color = m.accent, contentColor = m.onAccent,
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = m.onAccent)
            else Text(text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        }
    }
}

/** `.btn.ghost.block`: 44 dp, без фону. */
@Composable
fun AuthGhostButton(text: String, modifier: Modifier = Modifier, color: Color = Mine.colors.text, onClick: () -> Unit) {
    Box(
        modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(6.dp)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = color)
    }
}

/** `.btn.secondary.sm`: 36 dp, рамка. */
@Composable
fun AuthSecondarySmall(text: String, onClick: () -> Unit) {
    val m = Mine.colors
    Surface(
        onClick = onClick, modifier = Modifier.height(36.dp), shape = RoundedCornerShape(6.dp),
        color = m.card, contentColor = m.text, border = BorderStroke(1.dp, m.outline),
    ) {
        Box(Modifier.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
            Text(text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
    }
}


/** Форма входу як у дизайні: телефон, пароль, «Увійти», «Забули пароль?», анкета внизу. */
@Composable
fun ColumnScope.LoginMineForm(
    digits: String,
    password: String,
    error: String?,
    busy: Boolean,
    onDigits: (String) -> Unit,
    onPassword: (String) -> Unit,
    onSubmit: () -> Unit,
    onForgot: () -> Unit,
    onRegister: () -> Unit,
) {
    val m = Mine.colors
    Column(Modifier.fillMaxWidth()) {
        AuthLogo(132.dp, Modifier.padding(top = 12.dp, bottom = 28.dp))
        Text("Вхід для співробітників", fontSize = 26.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.39).sp, color = m.text)
        Text("Номер телефону й пароль, які ви вказали в анкеті.", Modifier.padding(top = 6.dp), fontSize = 15.sp, lineHeight = 22.sp, color = m.textSecondary)
        Column(Modifier.padding(top = 26.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            AuthField("Телефон") { MinePhoneField(digits, !busy, onChange = onDigits) }
            AuthField("Пароль", error = error) { MinePasswordField(password, !busy, isError = error != null, onChange = onPassword) }
        }
        AuthPrimaryButton("Увійти", busy, Modifier.padding(top = 24.dp), onSubmit)
        AuthGhostButton("Забули пароль?", Modifier.padding(top = 6.dp), onClick = onForgot)
    }
    Spacer(Modifier.weight(1f))
    Column(Modifier.fillMaxWidth().padding(top = 24.dp)) {
        HorizontalDivider(color = m.line)
        Row(
            Modifier.fillMaxWidth().padding(top = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Ще немає акаунта?", Modifier.weight(1f), fontSize = 14.sp, color = m.textSecondary)
            AuthSecondarySmall("Заповнити анкету", onRegister)
        }
    }
}

/** `.state`: кругла іконка, заголовок, текст. */
@Composable
fun AuthStateBlock(@DrawableRes icon: Int, title: String, text: String, titleSize: Int = 20) {
    val m = Mine.colors
    Column(
        Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.size(48.dp).background(m.surface, CircleShape).border(1.dp, m.line, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(icon), null, Modifier.size(24.dp), tint = m.textSecondary)
        }
        Text(title, fontSize = titleSize.sp, fontWeight = FontWeight.SemiBold, color = m.text, textAlign = TextAlign.Center)
        Text(text, Modifier.widthIn(max = 300.dp), fontSize = 15.sp, lineHeight = 22.sp, color = m.textSecondary, textAlign = TextAlign.Center)
    }
}

/** Заставка: логотип, «Перевіряємо вхід…», версія внизу. */
@Composable
fun AuthSplash(version: String) {
    val m = Mine.colors
    Box(Modifier.fillMaxSize().background(m.card).navigationBarsPadding()) {
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(22.dp)) {
            AuthLogo(180.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = m.textTertiary)
                Text("Перевіряємо вхід…", fontSize = 14.sp, color = m.textTertiary)
            }
        }
        if (version.isNotBlank()) Text(
            "ЧудоСкан $version", Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp), fontSize = 12.sp, color = m.textTertiary,
        )
    }
}

/** Крок очікування у списку «Анкету отримано → Чекаємо → Вхід». */
enum class StepState { DONE, ACTIVE, TODO }

@Composable
fun AuthSteps(steps: List<Triple<StepState, String, String?>>) {
    val m = Mine.colors
    val shape = RoundedCornerShape(8.dp)
    Column(Modifier.fillMaxWidth().background(m.card, shape).border(1.dp, m.line, shape)) {
        steps.forEachIndexed { i, (state, title, sub) ->
            if (i > 0) HorizontalDivider(color = m.line)
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                when (state) {
                    StepState.DONE -> Icon(painterResource(R.drawable.ic_redesign_circle_check), null, Modifier.size(18.dp), tint = m.text)
                    StepState.ACTIVE -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = m.text)
                    StepState.TODO -> Icon(painterResource(R.drawable.ic_redesign_circle_dot), null, Modifier.size(18.dp), tint = m.textTertiary)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = if (state == StepState.TODO) m.textTertiary else m.text)
                    sub?.let { Text(it, fontSize = 13.sp, color = m.textTertiary) }
                }
            }
        }
    }
}
