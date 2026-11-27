package ua.chudo.scanner.ui.scan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import ua.chudo.scanner.R
import ua.chudo.scanner.ui.theme.CodeStyle
import ua.chudo.scanner.ui.theme.Mine

@Composable
internal fun ManualScanInput(onClose: () -> Unit, onSubmit: (String) -> Unit) {
    val m = Mine.colors
    var text by rememberSaveable { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    fun submit() {
        val value = text.trim()
        if (value.isNotEmpty()) {
            keyboard?.hide()
            onSubmit(value)
        }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(m.bg).statusBarsPadding().navigationBarsPadding().imePadding()) {
            Row(
                Modifier.fillMaxWidth().height(52.dp).background(m.card).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose, modifier = Modifier.size(40.dp)) {
                    Icon(painterResource(R.drawable.ic_redesign_chevron_left), "Назад", tint = m.textSecondary)
                }
                Text("Ввести код", Modifier.padding(start = 4.dp), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            }
            HorizontalDivider(color = m.line)
            val bad = text.length == 13 && text.all(Char::isDigit) && !ean13Valid(text)
            Column(Modifier.fillMaxWidth().background(m.card).padding(16.dp)) {
                Row(
                    Modifier.fillMaxWidth().height(60.dp).border(1.dp, if (bad) m.danger else m.outline, RoundedCornerShape(6.dp)).padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val style = CodeStyle.copy(fontSize = 26.sp, letterSpacing = 1.56.sp)
                    BasicTextField(
                        text, { text = it }, Modifier.weight(1f).semantics { contentDescription = "Штрихкод або артикул" },
                        singleLine = true, textStyle = style.copy(color = m.text), cursorBrush = SolidColor(m.accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { submit() }),
                        decorationBox = { field ->
                            if (text.isEmpty()) Text("0000000000000", style = style, color = m.textTertiary, maxLines = 1)
                            field()
                        },
                    )
                    if (text.all(Char::isDigit)) Text("${text.length}/13", fontSize = 13.sp, color = m.textTertiary)
                }
                if (bad) Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(R.drawable.ic_redesign_circle_alert), null, Modifier.size(16.dp), tint = m.danger)
                    Text("Контрольна цифра не збігається — перевірте останню цифру", fontSize = 13.sp, color = m.danger)
                } else Text(
                    "Штрихкод з 13 цифр, артикул або код 1С.",
                    Modifier.padding(top = 6.dp), fontSize = 13.sp, color = m.textTertiary,
                )
            }
            HorizontalDivider(color = m.line)
            Spacer(Modifier.weight(1f))
            HorizontalDivider(color = m.line)
            val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "Стерти", "0", "Знайти")
            keys.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth()) {
                    row.forEachIndexed { index, key ->
                        val submitKey = key == "Знайти"
                        val deleteKey = key == "Стерти"
                        val enabled = !submitKey || text.isNotBlank()
                        Box(
                            Modifier.weight(1f).height(62.dp)
                                .background(when {
                                    submitKey && enabled -> m.accent
                                    submitKey || deleteKey -> m.surface
                                    else -> m.card
                                }).clickable(enabled = enabled, role = Role.Button) {
                                    when {
                                        submitKey -> submit()
                                        deleteKey -> text = text.dropLast(1)
                                        else -> text += key
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (deleteKey) Icon(painterResource(R.drawable.ic_redesign_arrow_left), "Стерти", tint = m.text)
                            else Text(
                                key, fontSize = if (submitKey) 16.sp else 24.sp,
                                fontWeight = if (submitKey) FontWeight.SemiBold else FontWeight.Medium,
                                color = if (submitKey && enabled) m.onAccent else m.text,
                            )
                        }
                        if (index < row.lastIndex) Box(Modifier.size(width = 1.dp, height = 62.dp).background(m.line))
                    }
                }
                HorizontalDivider(color = m.line)
            }
            Spacer(Modifier.fillMaxWidth().height(22.dp).background(m.card))
        }
    }
}

/** EAN-13: 13 цифр і правильна контрольна цифра. */
internal fun ean13Valid(code: String): Boolean {
    if (code.length != 13 || !code.all(Char::isDigit)) return false
    val sum = code.take(12).mapIndexed { i, c -> (c - '0') * if (i % 2 == 0) 1 else 3 }.sum()
    return (10 - sum % 10) % 10 == code.last() - '0'
}
