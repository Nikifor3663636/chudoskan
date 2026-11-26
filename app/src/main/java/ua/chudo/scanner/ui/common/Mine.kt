package ua.chudo.scanner.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ua.chudo.scanner.ui.theme.Mine

/** Основная кнопка: на всю ширину, 56 dp, «таблетка», акцент. */
@Composable
fun MinePrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    height: Dp = 56.dp,
    enabled: Boolean = true,
    busy: Boolean = false,
) {
    val m = Mine.colors
    Button(
        onClick = onClick, enabled = enabled && !busy,
        modifier = modifier.fillMaxWidth().heightIn(min = height),
        shape = Mine.pillShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = m.accent, contentColor = m.onAccent,
            disabledContainerColor = m.accent.copy(alpha = 0.5f), disabledContentColor = m.onAccent.copy(alpha = 0.7f),
        ),
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(22.dp), color = m.onAccent, strokeWidth = 2.dp)
        } else {
            icon?.let { Icon(it, null, Modifier.size(22.dp)); Spacer(Modifier.width(10.dp)) }
            Text(text, fontSize = if (height > 56.dp) 18.sp else 17.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** Кнопка в карточке: 48 dp, скругление 14. [tone] — акцент, ошибка или контур. */
enum class MineTone { ACCENT, DANGER, OUTLINE }

@Composable
fun MineButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, tone: MineTone = MineTone.OUTLINE, enabled: Boolean = true, busy: Boolean = false) {
    val m = Mine.colors
    val shape = Mine.fieldShape
    val pad = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
    val content: @Composable () -> Unit = {
        if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = LocalContentColorOr(tone, m))
        else Text(text, fontSize = 15.sp, fontWeight = if (tone == MineTone.OUTLINE) FontWeight.Medium else FontWeight.Bold, textAlign = TextAlign.Center)
    }
    when (tone) {
        MineTone.OUTLINE -> OutlinedButton(
            onClick, modifier.heightIn(min = 48.dp), enabled = enabled && !busy, shape = shape, contentPadding = pad,
            border = BorderStroke(1.dp, m.outline),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = m.text),
        ) { content() }
        else -> Button(
            onClick, modifier.heightIn(min = 48.dp), enabled = enabled && !busy, shape = shape, contentPadding = pad,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (tone == MineTone.ACCENT) m.accent else m.danger,
                contentColor = if (tone == MineTone.ACCENT) m.onAccent else m.onDanger,
            ),
        ) { content() }
    }
}

@Suppress("FunctionName")
private fun LocalContentColorOr(tone: MineTone, m: ua.chudo.scanner.ui.theme.MineColors): Color = when (tone) {
    MineTone.ACCENT -> m.onAccent
    MineTone.DANGER -> m.onDanger
    MineTone.OUTLINE -> m.text
}

/** Карточка: фон карточки, скругление 20, отступ 18. */
@Composable
fun MineCard(modifier: Modifier = Modifier, padding: Dp = 18.dp, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = Mine.cardShape, color = Mine.colors.card, contentColor = Mine.colors.text) {
        Column(Modifier.padding(padding), content = content)
    }
}

/** Иконка в квадрате 44 dp на тоне акцента. */
@Composable
fun MineIconBox(icon: ImageVector) {
    Box(Modifier.size(44.dp).clip(CircleShape).background(Mine.colors.accentTone), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = Mine.colors.accent)
    }
}

/** Точка статуса 10 dp. */
@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(10.dp).clip(CircleShape).background(color))
}

/** Заголовок экрана: «назад», 26 sp жирный, справа — действие. */
@Composable
fun MineHeader(title: String, onBack: () -> Unit, action: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад", tint = Mine.colors.text) }
        Text(title, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.headlineMedium, color = Mine.colors.text)
        action()
    }
}

/** Чип выбора (формат): 44 dp, скругление 12; выбранный — тон ошибки, как в макете. */
@Composable
fun MineChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, height: Dp = 48.dp) {
    val m = Mine.colors
    Surface(
        onClick = onClick, modifier = modifier.heightIn(min = height), shape = Mine.pillShape,
        color = if (selected) m.accentTone else Color.Transparent,
        contentColor = if (selected) m.link else m.text,
        border = if (selected) null else BorderStroke(1.dp, m.outline),
    ) {
        Box(Modifier.padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
            Text(text, fontSize = 15.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, maxLines = 1)
        }
    }
}

/** «−  2  +»: квадраты 48 dp. */
@Composable
fun MineStepper(value: Int, onChange: (Int) -> Unit, range: IntRange = 1..99) {
    val m = Mine.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        StepButton("−", "Менше", enabled = value > range.first) { onChange(value - 1) }
        Text(value.toString(), Modifier.width(48.dp), fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, color = m.text)
        StepButton("+", "Більше", enabled = value < range.last) { onChange(value + 1) }
    }
}

@Composable
private fun StepButton(sign: String, label: String, enabled: Boolean, onClick: () -> Unit) {
    val m = Mine.colors
    Surface(
        onClick = onClick, enabled = enabled,
        // Подпись для TalkBack: знак «−» сам по себе не читается.
        modifier = Modifier.size(48.dp).semantics { contentDescription = label },
        shape = CircleShape,
        color = Color.Transparent, contentColor = if (enabled) m.text else m.textSecondary, border = BorderStroke(1.dp, m.outline),
    ) {
        Box(contentAlignment = Alignment.Center) { Text(sign, fontSize = 24.sp, fontWeight = FontWeight.Medium) }
    }
}

/** Строка на поверхности: скругление 16, текст слева, подсказка справа. */
@Composable
fun MineRow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = Mine.fieldShape, color = Mine.colors.surface, contentColor = Mine.colors.text) {
        content()
    }
}
