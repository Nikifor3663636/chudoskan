package ua.chudo.scanner.ui.common

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ua.chudo.scanner.ui.theme.Mine
import ua.chudo.scanner.ui.theme.MineColors

/** Мікроілюстрації станів (дизайн-система → «Стани»): прості форми лише з токенів, полотно 120×104. */
enum class Illustration { SEARCH, STOCK, ORDERS, CART, PRINTERS, ERROR, OFFLINE, UPDATE, PRINT, CHECKOUT, FILE }

private const val W = 120f
private const val H = 104f

/** Ілюстрація [kind] на крузі [bg]; [success] — зелена галочка в куті. Декоративна: TalkBack її пропускає. */
@Composable
fun MineIllustration(kind: Illustration, modifier: Modifier = Modifier, success: Boolean = false, width: Dp = 120.dp) {
    val m = Mine.colors
    Canvas(modifier.size(width, width * (H / W))) {
        scale(size.width / W, size.height / H, pivot = Offset.Zero) {
            circle(60f, 52f, 48f, if (success) m.stockInBg else m.accentTone)
            draw(kind, m)
            if (success) {
                circle(86f, 74f, 20f, m.stockIn)
                path("M77 74 l6 6 l12 -12", m.card, 4f)
            }
        }
    }
}

private fun DrawScope.rect(x: Float, y: Float, w: Float, h: Float, c: Color, r: Float = 6f) =
    drawRoundRect(c, Offset(x, y), Size(w, h), CornerRadius(r))

/** Прямокутник кольору картки з рамкою [c] (як «папір» на ілюстраціях). */
private fun DrawScope.outlined(x: Float, y: Float, w: Float, h: Float, c: Color, m: MineColors, r: Float = 6f, sw: Float = 2f) {
    rect(x, y, w, h, m.card, r)
    drawRoundRect(c, Offset(x + sw / 2, y + sw / 2), Size(w - sw, h - sw), CornerRadius(r), style = Stroke(sw))
}

private fun DrawScope.circle(x: Float, y: Float, r: Float, c: Color) = drawCircle(c, r, Offset(x, y))

private fun DrawScope.path(d: String, c: Color, width: Float) = drawPath(
    PathParser().parsePathString(d).toPath(), c,
    style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round),
)

/** Смуги штрихкоду: [n] смуг через 6, по черзі 3 і 2 завширшки. */
private fun DrawScope.bars(x: Float, y: Float, h: Float, n: Int, c: Color) =
    repeat(n) { i -> rect(x + i * 6, y, if (i % 2 == 1) 2f else 3f, h, c, 0f) }

private fun DrawScope.draw(kind: Illustration, m: MineColors) = when (kind) {
    Illustration.SEARCH -> {
        outlined(30f, 34f, 44f, 32f, m.line, m); bars(36f, 40f, 20f, 6, m.text)
        drawCircle(m.accent, 14f, Offset(69f, 57f), style = Stroke(6f))
        rotate(-45f, Offset(82f, 81f)) { rect(78f, 70f, 8f, 22f, m.accent, 4f) }
    }
    Illustration.STOCK -> {
        rect(26f, 72f, 68f, 6f, m.textSecondary, 3f); rect(30f, 46f, 26f, 26f, m.accent)
        rect(58f, 52f, 22f, 20f, m.warning); outlined(40f, 22f, 22f, 22f, m.line, m)
    }
    Illustration.ORDERS -> {
        rect(30f, 38f, 60f, 46f, m.accent, 8f); rect(30f, 38f, 60f, 12f, m.accentPressed, 8f)
        rect(56f, 38f, 8f, 46f, m.warning, 2f); rect(70f, 62f, 16f, 10f, m.card, 3f)
    }
    Illustration.CART -> {
        path("M40 44 a20 20 0 0 1 40 0", m.text, 5f); rect(28f, 42f, 64f, 34f, m.accent, 10f)
        listOf(40f, 57f, 74f).forEach { rect(it, 52f, 6f, 14f, m.accentTone, 3f) }
        circle(44f, 84f, 6f, m.text); circle(76f, 84f, 6f, m.text)
    }
    Illustration.PRINTERS -> {
        outlined(40f, 22f, 40f, 26f, m.line, m, 4f); rect(26f, 42f, 68f, 32f, m.textSecondary, 10f)
        outlined(40f, 62f, 40f, 30f, m.line, m, 4f); rect(46f, 70f, 22f, 4f, m.accent, 2f)
        rect(46f, 78f, 14f, 4f, m.line, 2f); circle(85f, 53f, 3f, m.stockIn)
    }
    Illustration.ERROR -> {
        outlined(26f, 36f, 60f, 40f, m.line, m, 8f); bars(34f, 44f, 24f, 7, m.textSecondary)
        circle(86f, 74f, 16f, m.errorBg); rect(84f, 64f, 4f, 12f, m.error, 2f); circle(86f, 81f, 2.5f, m.error)
    }
    Illustration.OFFLINE -> {
        path("M34 52 a36 36 0 0 1 52 0", m.textSecondary, 6f); path("M44 62 a22 22 0 0 1 32 0", m.textSecondary, 6f)
        circle(60f, 74f, 6f, m.textSecondary)
        rotate(-38f, Offset(62f, 55f)) { rect(30f, 52f, 64f, 6f, m.error, 3f) }
    }
    Illustration.UPDATE -> {
        rect(40f, 16f, 40f, 72f, m.text, 10f); rect(44f, 22f, 32f, 56f, m.card, 6f)
        rect(56f, 32f, 8f, 22f, m.accent, 4f); path("M50 46 L60 58 L70 46", m.accent, 6f)
    }
    Illustration.PRINT -> {
        outlined(30f, 24f, 48f, 62f, m.line, m); rect(38f, 34f, 24f, 4f, m.text, 2f)
        rect(38f, 44f, 32f, 10f, m.accent, 3f); bars(38f, 62f, 14f, 6, m.text)
    }
    Illustration.CHECKOUT -> {
        outlined(30f, 24f, 52f, 54f, m.line, m, 8f)
        rect(36f, 30f, 16f, 16f, m.text, 3f); rect(60f, 30f, 16f, 16f, m.text, 3f); rect(36f, 56f, 16f, 16f, m.text, 3f)
        rect(62f, 58f, 6f, 6f, m.text, 1f); rect(70f, 66f, 6f, 6f, m.text, 1f)
    }
    Illustration.FILE -> {
        outlined(32f, 26f, 44f, 58f, m.line, m)
        rect(40f, 38f, 28f, 4f, m.line, 2f); rect(40f, 48f, 20f, 4f, m.line, 2f); rect(40f, 58f, 24f, 4f, m.line, 2f)
        rect(40f, 68f, 14f, 4f, m.accent, 2f)
    }
}

/** Порожній стан: ілюстрація, що сталося (18 sp) і що зробити — одна кнопка. */
@Composable
fun EmptyState(
    illustration: Illustration,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    action: Pair<String, () -> Unit>? = null,
    tone: MineTone = MineTone.ACCENT,
) {
    StateColumn(modifier) {
        MineIllustration(illustration)
        StateTexts(title, body)
        action?.let { (label, onClick) -> MineButton(label, onClick, tone = tone) }
    }
}

/** Успіх: та сама ілюстрація на зеленому крузі з галочкою; озвучується TalkBack одразу. */
@Composable
fun SuccessState(illustration: Illustration, title: String, body: String, modifier: Modifier = Modifier) {
    StateColumn(modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        MineIllustration(illustration, success = true)
        StateTexts(title, body)
    }
}

@Composable
private fun StateColumn(modifier: Modifier, content: @Composable () -> Unit) {
    Column(
        modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) { content() }
}

@Composable
private fun StateTexts(title: String, body: String) {
    val m = Mine.colors
    Text(title, fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold, color = m.text, textAlign = TextAlign.Center)
    Text(body, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium, color = m.textSecondary, textAlign = TextAlign.Center)
}

/** Скелет завантаження: картка товару або рядок списку. */
enum class SkeletonKind { CARD, ROW }

@Composable
fun Skeleton(kind: SkeletonKind, modifier: Modifier = Modifier) {
    val m = Mine.colors
    val t = rememberInfiniteTransition(label = "skeleton")
    val a by t.animateFloat(0.55f, 1f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "a")
    @Composable
    fun block(w: Dp?, h: Dp, r: Dp = h / 2) = Box(
        (if (w == null) Modifier.fillMaxWidth() else Modifier.width(w)).height(h).clip(RoundedCornerShape(r)).background(m.field),
    )
    Surface(modifier.fillMaxWidth().alpha(a), shape = RoundedCornerShape(20.dp), color = m.card) {
        when (kind) {
            SkeletonKind.CARD -> Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    block(88.dp, 88.dp, 14.dp)
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { block(170.dp, 14.dp); block(130.dp, 14.dp); block(90.dp, 14.dp) }
                }
                block(150.dp, 40.dp, 20.dp)
                block(null, 76.dp, 14.dp)
            }
            SkeletonKind.ROW -> Row(
                Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                block(44.dp, 44.dp, 14.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) { block(170.dp, 12.dp); block(110.dp, 12.dp) }
                block(40.dp, 16.dp)
            }
        }
    }
}

/** Банер угорі екрана: оновлення, офлайн, помилка, успіх. Колір не єдиний сигнал — є іконка й текст. */
enum class BannerKind { UPDATE, OFFLINE, ERROR, SUCCESS }

/** Кольори банера: фон, іконка, текст. */
fun bannerPaint(kind: BannerKind, m: MineColors): Triple<Color, Color, Color> = when (kind) {
    BannerKind.UPDATE -> Triple(m.accentTone, m.accentText, m.text)
    BannerKind.OFFLINE -> Triple(m.stockOutBg, m.textSecondary, m.text)
    BannerKind.ERROR -> Triple(m.errorBg, m.error, m.error)
    BannerKind.SUCCESS -> Triple(m.stockInBg, m.stockIn, m.stockIn)
}

@Composable
fun Banner(kind: BannerKind, text: String, modifier: Modifier = Modifier, action: Pair<String, () -> Unit>? = null) {
    val (bg, icon, fg) = bannerPaint(kind, Mine.colors)
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(20.dp)).background(bg)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val image = when (kind) {
            BannerKind.UPDATE -> Icons.Rounded.SystemUpdate
            BannerKind.OFFLINE -> Icons.Rounded.WifiOff
            BannerKind.ERROR -> Icons.Rounded.Error
            BannerKind.SUCCESS -> Icons.Rounded.CheckCircle
        }
        Icon(image, null, tint = icon)
        Text(text, Modifier.weight(1f), fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, color = fg)
        action?.let { (label, onClick) -> MineButton(label, onClick, tone = MineTone.TEXT) }
    }
}
