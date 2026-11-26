package ua.chudo.scanner.ui.auth

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ua.chudo.scanner.R
import ua.chudo.scanner.ui.common.MinePrimaryButton
import ua.chudo.scanner.ui.theme.Mine

/** Спільний екран входу, реєстрації та станів акаунта. */
@Composable
fun LoginMineShell(onToggleTheme: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val m = Mine.colors
    Box(Modifier.fillMaxSize().background(m.bg), contentAlignment = Alignment.TopCenter) {
        Box(
            Modifier.align(Alignment.TopEnd).offset(x = 72.dp, y = (-100).dp).size(250.dp)
                .alpha(if (m.dark) 0.25f else 0.15f).border(24.dp, Color(0xFF3F6FE0), CircleShape),
        )
        Column(
            Modifier.widthIn(max = 460.dp).fillMaxSize().verticalScroll(rememberScrollState())
                .statusBarsPadding().navigationBarsPadding().imePadding().padding(horizontal = 24.dp),
        ) {
            Row(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalArrangement = Arrangement.End) {
                Surface(
                    onClick = onToggleTheme, modifier = Modifier.size(48.dp), shape = CircleShape,
                    color = m.surface, contentColor = m.text, border = BorderStroke(1.dp, m.line),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(if (m.dark) Icons.Rounded.LightMode else Icons.Rounded.DarkMode, "Змінити тему", Modifier.size(20.dp))
                    }
                }
            }
            Image(
                painterResource(R.drawable.logo_wordmark), "Чудо Острів",
                Modifier.padding(top = 18.dp).width(234.dp).align(Alignment.CenterHorizontally),
                contentScale = ContentScale.FillWidth,
            )
            Text(
                "ЧудоСкан для співробітників", Modifier.padding(top = 12.dp).align(Alignment.CenterHorizontally),
                fontSize = 14.sp, color = m.textSecondary,
            )
            content()
        }
    }
}

/** Форма входа: телефон и пароль (как работает вход в приложении), кнопка «Увійти». */
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
    Text("Вхід", Modifier.padding(top = 28.dp), fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold, color = m.text)
    FieldLabel("Номер телефону", top = 24.dp)
    MinePhoneField(digits, !busy, onDigits)
    FieldLabel("Пароль", top = 14.dp)
    MinePasswordField(password, !busy, onPassword)
    error?.let { Text(it, Modifier.padding(top = 10.dp), fontSize = 14.sp, lineHeight = 20.sp, color = m.warningText) }
    MinePrimaryButton("Увійти", onSubmit, Modifier.padding(top = 16.dp), height = 58.dp, busy = busy)
    TextButton(onClick = onForgot, Modifier.align(Alignment.CenterHorizontally).padding(top = 4.dp)) {
        Text("Забув пароль", fontSize = 15.sp, color = m.textSecondary)
    }
    Spacer(Modifier.height(12.dp))
    RegisterLink("Немає акаунта? ", "Зареєструватися", onRegister)
}

@Composable
fun ColumnScope.RegisterLink(prefix: String, link: String, onClick: () -> Unit) {
    val m = Mine.colors
    TextButton(onClick = onClick, Modifier.align(Alignment.CenterHorizontally).padding(bottom = 24.dp)) {
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = m.textSecondary)) { append(prefix) }
                withStyle(SpanStyle(color = m.link, fontWeight = FontWeight.Bold)) { append(link) }
            },
            fontSize = 15.sp,
        )
    }
}

@Composable
private fun FieldLabel(text: String, top: androidx.compose.ui.unit.Dp) {
    Text(text, Modifier.padding(top = top, bottom = 8.dp), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Mine.colors.textSecondary)
}

/** Поле 60 dp, скругление 18; в фокусе — рамка 2 dp акцентом, иначе линия. */
@Composable
private fun MineField(focused: Boolean, content: @Composable RowScope.() -> Unit) {
    val m = Mine.colors
    Row(
        Modifier.fillMaxWidth().height(60.dp).clip(Mine.fieldShape).background(m.surface)
            .border(if (focused) 2.dp else 1.dp, if (focused) m.accent else m.line, Mine.fieldShape)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) { content() }
}

/** «+380» в плашке и 9 цифр с маской XX XXX XX XX. */
@Composable
fun MinePhoneField(digits: String, enabled: Boolean, onChange: (String) -> Unit) {
    val m = Mine.colors
    var focused by remember { mutableStateOf(false) }
    MineField(focused) {
        Box(Modifier.height(42.dp).clip(RoundedCornerShape(12.dp)).background(m.field).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
            Text("+380", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = m.text)
        }
        BasicTextField(
            value = digits,
            onValueChange = { raw ->
                var d = raw.filter(Char::isDigit)
                if (d.length > 9 && d.startsWith("380")) d = d.drop(3)
                onChange(d.trimStart('0').take(9))
            },
            enabled = enabled, singleLine = true,
            textStyle = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.5.sp, color = m.text),
            cursorBrush = SolidColor(m.accent),
            visualTransformation = PhoneMask,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            modifier = Modifier.weight(1f).onFocusChanged { focused = it.isFocused },
            decorationBox = { inner ->
                if (digits.isEmpty()) Text("XX XXX XX XX", fontSize = 19.sp, color = m.textSecondary.copy(alpha = 0.6f))
                inner()
            },
        )
    }
}

@Composable
fun MinePasswordField(value: String, enabled: Boolean, onChange: (String) -> Unit) {
    val m = Mine.colors
    var focused by remember { mutableStateOf(false) }
    var visible by rememberSaveable { mutableStateOf(false) }
    MineField(focused) {
        BasicTextField(
            value = value, onValueChange = onChange, enabled = enabled, singleLine = true,
            textStyle = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.Medium, color = m.text),
            cursorBrush = SolidColor(m.accent),
            visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.weight(1f).padding(start = 8.dp).onFocusChanged { focused = it.isFocused },
        )
        IconButton(onClick = { visible = !visible }) {
            Icon(if (visible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, if (visible) "Сховати пароль" else "Показати пароль", tint = m.textSecondary)
        }
    }
}
