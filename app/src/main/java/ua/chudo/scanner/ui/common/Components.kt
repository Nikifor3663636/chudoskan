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
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import ua.chudo.scanner.ui.theme.InStockGreen
import ua.chudo.scanner.ui.theme.OutOfStockGrey
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
            Icon(Icons.Rounded.Image, null, tint = OutOfStockGrey)
        } else {
            SubcomposeAsyncImage(
                model = url,
                contentDescription = null,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize().padding(6.dp),
                loading = { Shimmer(Modifier.fillMaxSize()) },
                error = { Icon(Icons.Rounded.Image, null, tint = OutOfStockGrey) },
            )
        }
    }
}

@Composable
fun StockBadge(stock: StockStatus, modifier: Modifier = Modifier) {
    val (text, color) = when (stock) {
        StockStatus.IN_STOCK -> "Є на сайті" to InStockGreen
        StockStatus.OUT_OF_STOCK -> "Немає онлайн" to OutOfStockGrey
        StockStatus.UNKNOWN -> "Невідомо" to OutOfStockGrey
    }
    Row(
        modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

@Composable
fun DiscountBadge(percent: Double, modifier: Modifier = Modifier) {
    Text(
        "−${percent.toInt()}%",
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.secondary)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        color = MaterialTheme.colorScheme.onSecondary,
        style = MaterialTheme.typography.labelLarge,
    )
}

@Composable
fun PriceLine(price: Price?, compact: Boolean = false) {
    if (price == null) {
        Text("Ціна недоступна", style = MaterialTheme.typography.bodyMedium, color = OutOfStockGrey)
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            formatPrice(price.finalPrice),
            style = if (compact) MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Black)
            else ua.chudo.scanner.ui.theme.PriceStyle,
            color = if (price.discountPercent > 0) MaterialTheme.colorScheme.secondary
            else MaterialTheme.colorScheme.onSurface,
        )
        if (price.regularPrice > price.finalPrice) {
            Text(
                formatPrice(price.regularPrice),
                style = MaterialTheme.typography.bodyMedium,
                textDecoration = TextDecoration.LineThrough,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!compact && price.discountPercent >= 1) DiscountBadge(price.discountPercent)
    }
}

@Composable
fun ProductCard(item: ProductSummary, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val highlight = item.exactMatch
    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (highlight) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.large)
                else Modifier
            ),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        tonalElevation = 1.dp,
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            ProductImage(item.image, Modifier.size(84.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (highlight) {
                    Text(
                        "Точний збіг артикулу",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(item.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                item.article?.let {
                    Text("Арт. $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PriceLine(item.price, compact = true)
                }
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
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
    )
}

@Composable
fun MessageState(
    icon: ImageVector,
    title: String,
    text: String,
    modifier: Modifier = Modifier,
    primary: Pair<String, () -> Unit>? = null,
    secondary: Pair<String, () -> Unit>? = null,
) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(88.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Spacer(Modifier.height(20.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        primary?.let { (label, action) -> Button(onClick = action, Modifier.fillMaxWidth()) { Text(label) } }
        secondary?.let { (label, action) ->
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = action, Modifier.fillMaxWidth()) { Text(label) }
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
        Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

