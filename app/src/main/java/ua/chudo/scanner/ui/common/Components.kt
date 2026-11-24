package ua.chudo.scanner.ui.common

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import ua.chudo.scanner.print.PriceFormat
import ua.chudo.scanner.ui.theme.Mine
import androidx.compose.ui.unit.sp
import uniffi.chudo_core.Price
import uniffi.chudo_core.ProductSummary
import uniffi.chudo_core.StockStatus

@Composable
fun ProductImage(url: String?, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Fit) {
    Box(
        modifier
            .clip(MaterialTheme.shapes.medium)
            .background(Color.White),
        contentAlignment = Alignment.Center,
    ) {
        if (url == null) {
            Icon(Icons.Rounded.Image, null, tint = Mine.colors.stockOut)
        } else {
            SubcomposeAsyncImage(
                model = url,
                contentDescription = null,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize().padding(6.dp),
                loading = { Shimmer(Modifier.fillMaxSize()) },
                error = { Icon(Icons.Rounded.Image, null, tint = Mine.colors.stockOut) },
            )
        }
    }
}

/** Наличие на сайте: цветная точка и простые слова. */
@Composable
fun StockBadge(stock: StockStatus, modifier: Modifier = Modifier) {
    val m = Mine.colors
    val (text, color) = when (stock) {
        StockStatus.IN_STOCK -> "Є на сайті" to m.success
        StockStatus.OUT_OF_STOCK -> "Немає на сайті" to m.outline
        StockStatus.UNKNOWN -> "Наявність невідома" to m.outline
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        StatusDot(color)
        Spacer(Modifier.width(6.dp))
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = if (stock == StockStatus.IN_STOCK) m.text else m.textSecondary)
    }
}

@Composable
fun DiscountBadge(percent: Double, modifier: Modifier = Modifier) {
    val m = Mine.colors
    Text(
        "−${percent.toInt()}%",
        modifier.clip(RoundedCornerShape(8.dp)).background(m.accent).padding(horizontal = 8.dp, vertical = 2.dp),
        color = m.onAccent, fontSize = 13.sp, fontWeight = FontWeight.Bold,
    )
}

/** Цена строкой: Montserrat, как на ценнике; старая — зачёркнута рядом. */
@Composable
fun PriceLine(price: Price?, compact: Boolean = false) {
    val m = Mine.colors
    if (price == null) {
        Text("Ціна недоступна", fontSize = 15.sp, color = m.textSecondary)
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "${PriceFormat.tag(price.finalPrice).first} ₴",
            fontFamily = PriceFont, fontWeight = FontWeight.ExtraBold, fontSize = if (compact) 19.sp else 28.sp, color = m.text,
        )
        if (price.regularPrice > price.finalPrice + 0.001) {
            Text(
                "${PriceFormat.tag(price.regularPrice).first} ₴", fontSize = 14.sp, color = m.textSecondary,
                textDecoration = TextDecoration.LineThrough,
            )
            if (price.discountPercent >= 1) DiscountBadge(price.discountPercent)
        }
    }
}

/** Товар в списке результатов: фото, название, артикул, цена, наличие. Точное совпадение — с акцентной рамкой. */
@Composable
fun ProductCard(item: ProductSummary, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val m = Mine.colors
    val highlight = item.exactMatch
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = m.card, contentColor = m.text,
        border = androidx.compose.foundation.BorderStroke(if (highlight) 2.dp else 1.dp, if (highlight) m.accent else m.line),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            ProductImage(item.image, Modifier.size(88.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (highlight) Text("Точний збіг артикулу", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = m.accent)
                Text(item.name, fontSize = 16.sp, lineHeight = 21.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                item.article?.let { Text("Арт. $it", fontSize = 14.sp, color = m.textSecondary) }
                PriceLine(item.price, compact = true)
                StockBadge(item.stock)
            }
        }
    }
}

@Composable
fun Shimmer(modifier: Modifier = Modifier, shapeRadius: Dp = 12.dp) {
    val t = rememberInfiniteTransition(label = "shimmer")
    val a by t.animateFloat(0.35f, 0.8f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "a")
    Box(
        modifier
            .clip(RoundedCornerShape(shapeRadius))
            .alpha(a)
            .background(Mine.colors.field)
    )
}

/** Пустой экран или ошибка: что случилось и что сделать — кнопками. */
@Composable
fun MessageState(
    icon: ImageVector,
    title: String,
    text: String,
    modifier: Modifier = Modifier,
    primary: Pair<String, () -> Unit>? = null,
    secondary: Pair<String, () -> Unit>? = null,
) {
    val m = Mine.colors
    Column(modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(80.dp).clip(CircleShape).background(m.accentTone), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(36.dp), tint = m.accent)
        }
        Spacer(Modifier.height(20.dp))
        Text(title, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold, color = m.text, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(text, fontSize = 15.sp, lineHeight = 22.sp, color = m.textSecondary, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        primary?.let { (label, action) -> MinePrimaryButton(label, action) }
        secondary?.let { (label, action) ->
            Spacer(Modifier.height(10.dp))
            MineButton(label, action, Modifier.fillMaxWidth().height(52.dp))
        }
    }
}

@Composable
fun ErrorState(error: UiError, siteUrl: String?, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    MessageState(
        icon = Icons.Rounded.CloudOff,
        title = error.title,
        text = error.hint,
        modifier = modifier,
        primary = "Спробувати ще раз" to onRetry,
        secondary = if (error.openSite && siteUrl != null) "Відкрити сайт" to { ctx.openUrl(siteUrl) } else null,
    )
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Mine.colors.text, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}
