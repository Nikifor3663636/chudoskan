package ua.chudo.scanner.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.rounded.Check
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import ua.chudo.scanner.ui.theme.Manrope
import ua.chudo.scanner.ui.theme.MineColors

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ua.chudo.scanner.ui.theme.Mine

/** Тон кнопки: головна дія, небезпечна, контурна, текстова (дизайн-система → «Кнопки»). */
enum class MineTone { ACCENT, DANGER, OUTLINE, TEXT }

/** Кольори кнопки в стані: фон, текст, рамка (null — без рамки). */
data class ButtonPaint(val container: Color, val content: Color, val border: Color?)

/** Кольори кнопки з токенів: звичайна, натиснута, неактивна. */
fun buttonPaint(tone: MineTone, pressed: Boolean, enabled: Boolean, m: MineColors): ButtonPaint = when {
    !enabled -> ButtonPaint(if (tone == MineTone.TEXT) Color.Transparent else m.field, m.textSecondary, null)
    tone == MineTone.ACCENT -> ButtonPaint(if (pressed) m.accentPressed else m.accent, m.onAccent, null)
    tone == MineTone.DANGER -> ButtonPaint(if (pressed) m.errorBg else m.danger, m.onDanger, null)
    tone == MineTone.OUTLINE -> ButtonPaint(if (pressed) m.field else Color.Transparent, m.text, m.outline)
    else -> ButtonPaint(if (pressed) m.accentTone else Color.Transparent, m.accentText, null)
}

/** Підпис кнопки: 17 sp Bold; не влазить — зменшується до 13 sp, а не обрізається. */
@Composable
private fun ButtonLabel(text: String, color: Color, max: TextUnit = 17.sp) {
    BasicText(
        text, style = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Bold, color = color, textAlign = TextAlign.Center),
        maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
        autoSize = TextAutoSize.StepBased(minFontSize = 13.sp, maxFontSize = max, stepSize = 0.5.sp),
    )
}

/** Основна кнопка: на всю ширину, 56 dp, «таблетка», акцент; натиснута — accentPressed. */
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
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val p = buttonPaint(MineTone.ACCENT, pressed, enabled, Mine.colors)
    Surface(
        onClick = onClick, enabled = enabled && !busy, interactionSource = source,
        modifier = modifier.fillMaxWidth().height(height).semantics { role = Role.Button },
        shape = RoundedCornerShape(height / 2), color = p.container, contentColor = p.content,
    ) {
        Row(Modifier.padding(horizontal = 24.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(22.dp), color = p.content, strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
            } else icon?.let { Icon(it, null, Modifier.size(22.dp)); Spacer(Modifier.width(10.dp)) }
            ButtonLabel(text, p.content, if (height > 56.dp) 18.sp else 17.sp)
        }
    }
}

/** Кнопка в картці: 48 dp, заокруглення 14. [tone] — акцент, помилка, контур або текст. */
@Composable
fun MineButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: MineTone = MineTone.OUTLINE,
    enabled: Boolean = true,
    busy: Boolean = false,
    icon: ImageVector? = null,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val p = buttonPaint(tone, pressed, enabled, Mine.colors)
    Surface(
        onClick = onClick, enabled = enabled && !busy, interactionSource = source,
        modifier = modifier.height(48.dp).semantics { role = Role.Button },
        shape = RoundedCornerShape(14.dp), color = p.container, contentColor = p.content,
        border = p.border?.let { BorderStroke(1.dp, it) },
    ) {
        Row(
            Modifier.padding(horizontal = if (tone == MineTone.TEXT) 12.dp else 16.dp),
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
        ) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = p.content)
                Spacer(Modifier.width(8.dp))
            } else icon?.let { Icon(it, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)) }
            ButtonLabel(text, p.content)
        }
    }
}

/** Карточка: фон карточки, скругление 20, отступ 18. */
@Composable
fun MineCard(modifier: Modifier = Modifier, padding: Dp = 18.dp, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = Mine.colors.card, contentColor = Mine.colors.text) {
        Column(Modifier.padding(padding), content = content)
    }
}

/** Иконка в квадрате 44 dp на тоне акцента. */
@Composable
fun MineIconBox(icon: ImageVector) {
    Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(Mine.colors.accentTone), contentAlignment = Alignment.Center) {
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
        Text(title, Modifier.weight(1f), fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Mine.colors.text)
        action()
    }
}

/** Чип вибору: 48 dp, заокруглення 14; вибраний — тон акценту, рамка акценту й галочка (не лише колір). */
@Composable
fun MineChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, height: Dp = 48.dp) {
    val m = Mine.colors
    Surface(
        onClick = onClick, modifier = modifier.height(height).semantics { this.selected = selected },
        shape = RoundedCornerShape(14.dp),
        color = if (selected) m.accentTone else Color.Transparent, contentColor = m.text,
        border = BorderStroke(1.dp, if (selected) m.accent else m.outline),
    ) {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (selected) {
                Icon(Icons.Rounded.Check, null, Modifier.size(20.dp), tint = m.accentText)
                Spacer(Modifier.width(8.dp))
            }
            Text(text, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1)
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
        shape = RoundedCornerShape(14.dp),
        color = Color.Transparent, contentColor = if (enabled) m.text else m.textSecondary, border = BorderStroke(1.dp, m.outline),
    ) {
        Box(contentAlignment = Alignment.Center) { Text(sign, fontSize = 24.sp, fontWeight = FontWeight.Medium) }
    }
}

/** Строка на поверхности: скругление 16, текст слева, подсказка справа. */
@Composable
fun MineRow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = Mine.colors.surface, contentColor = Mine.colors.text) {
        content()
    }
}

/**
 * Сегменти (дизайн-система → Segment): «таблетки» 48 dp на field; активна — інверсія (текст на фоні тексту).
 * Прокручуються, якщо не вміщаються.
 */
@Composable
fun <T> MineSegment(items: List<T>, selected: T, title: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    val m = Mine.colors
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.forEach { item ->
            val on = item == selected
            Surface(
                onClick = { onSelect(item) }, modifier = Modifier.height(48.dp).semantics { role = Role.Tab; this.selected = on },
                shape = RoundedCornerShape(999.dp), color = if (on) m.text else m.field, contentColor = if (on) m.bg else m.text,
            ) {
                Box(Modifier.padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                    Text(title(item), fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }
            }
        }
    }
}
