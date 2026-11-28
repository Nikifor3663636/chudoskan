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
import androidx.compose.foundation.shape.RoundedCornerShape
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

/** Основна кнопка M3 (filled): на всю ширину, 56 dp, пігулка. */
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
        colors = ButtonDefaults.buttonColors(containerColor = m.accent, contentColor = m.onAccent),
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(22.dp), color = m.onAccent, strokeWidth = 2.dp)
        } else {
            icon?.let { Icon(it, null, Modifier.size(22.dp)); Spacer(Modifier.width(10.dp)) }
            Text(text, style = MaterialTheme.typography.titleMedium)
        }
    }
}

/** Кнопка M3 у картці: 48 dp, пігулка. [tone] — filled, помилка (tonal) або outlined. */
enum class MineTone { ACCENT, DANGER, OUTLINE }

@Composable
fun MineButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, tone: MineTone = MineTone.OUTLINE, enabled: Boolean = true, busy: Boolean = false) {
    val m = Mine.colors
    val shape = Mine.pillShape
    val pad = PaddingValues(horizontal = 24.dp, vertical = 8.dp)
    val content: @Composable () -> Unit = {
        if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = LocalContentColorOr(tone, m))
        else Text(text, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
    }
    when (tone) {
        MineTone.OUTLINE -> OutlinedButton(
            onClick, modifier.heightIn(min = 48.dp), enabled = enabled && !busy, shape = shape, contentPadding = pad,
            border = BorderStroke(1.dp, m.outline),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = m.accent),
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
    MineTone.OUTLINE -> m.accent
}

/** Картка M3 (elevated-тон): surfaceContainerLow, 12 dp. */
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

/** Заголовок екрана як M3 Top App Bar (small): 64 dp, «Назад», назва titleLarge, дія справа. */
@Composable
fun MineHeader(title: String, onBack: () -> Unit, action: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад", tint = Mine.colors.text) }
        Text(title, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleLarge, color = Mine.colors.text, maxLines = 1)
        action()
    }
}

/** Чип вибору M3 (filter chip): 8 dp, вибраний — secondaryContainer без рамки. */
@Composable
fun MineChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, height: Dp = 40.dp) {
    val c = MaterialTheme.colorScheme
    Surface(
        onClick = onClick, modifier = modifier.heightIn(min = height), shape = RoundedCornerShape(8.dp),
        color = if (selected) c.secondaryContainer else Color.Transparent,
        contentColor = if (selected) c.onSecondaryContainer else c.onSurfaceVariant,
        border = if (selected) null else BorderStroke(1.dp, c.outlineVariant),
    ) {
        Box(Modifier.padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
            Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1)
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

/** Рядок на поверхні: 12 dp. */
@Composable
fun MineRow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = Mine.cardShape, color = Mine.colors.surface, contentColor = Mine.colors.text) {
        content()
    }
}
