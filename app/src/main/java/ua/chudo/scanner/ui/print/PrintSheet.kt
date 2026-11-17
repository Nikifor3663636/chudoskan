package ua.chudo.scanner.ui.print

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import ua.chudo.scanner.chudoApp
import ua.chudo.scanner.print.LabelKind
import ua.chudo.scanner.print.PriceFormat
import ua.chudo.scanner.print.PriceTag
import ua.chudo.scanner.print.QueueItem
import ua.chudo.scanner.print.Template
import ua.chudo.scanner.print.toItem
import ua.chudo.scanner.ui.common.MineChip
import ua.chudo.scanner.ui.common.MinePrimaryButton
import ua.chudo.scanner.ui.common.MineStepper
import ua.chudo.scanner.ui.common.StatusDot
import ua.chudo.scanner.ui.theme.Mine

/** «Друкувати 2 цінники», «Друкувати 5 етикеток». */
fun printButtonText(t: Template, n: Int): String {
    val forms = if (t == Template.LABEL_40x24) Triple("етикетку", "етикетки", "етикеток") else Triple("цінник", "цінники", "цінників")
    val w = when {
        n % 100 in 11..14 -> forms.third
        n % 10 == 1 -> forms.first
        n % 10 in 2..4 -> forms.second
        else -> forms.third
    }
    return "Друкувати $n $w"
}

fun formatTitle(t: Template) = when (t) {
    Template.LABEL_40x24 -> "Етикетка 40×24"
    Template.TAG_68x38 -> "Цінник 68×38"
    Template.BIG -> "Крупногабарит"
}

/** Какой принтер возьмём: «Godex G500 · онлайн». Только статус — выбирать нечего. */
data class PrinterLine(val model: String?, val online: Boolean?)

/** Состояние листа печати (без ViewModel — для превью). */
data class PrintSheetUi(
    val subtitle: String,
    val format: Template,
    val promo: Boolean,
    /** null — у товара нет старой цены, переключатель не показываем. */
    val oldPrice: String?,
    val qty: Int,
    val printer: PrinterLine,
    val busy: Boolean = false,
    /** Принтер не нашли / ошибка печати: одно сообщение, кнопка становится «Спробувати ще». */
    val error: String? = null,
)

/** Лист «Друк цінника» с карточки товара: формат, акция, количество — и «Друк». Принтер выбирается сам. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrintSheet(tag: PriceTag, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    val svc = LocalContext.current.chudoApp.printing
    val scope = rememberCoroutineScope()
    var format by rememberSaveable { mutableStateOf(Template.TAG_68x38) }
    var promo by rememberSaveable { mutableStateOf(true) }
    var qty by rememberSaveable { mutableIntStateOf(1) }
    var line by remember { mutableStateOf(PrinterLine(null, null)) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { svc.store.lastTemplate.collect { t -> if (t != null) format = t } }
    LaunchedEffect(format.kind) {
        line = PrinterLine(null, null)
        line = svc.quickStatus(format.kind)?.let { (r, ok) -> PrinterLine(r.model, ok) } ?: PrinterLine(null, false)
    }
    val item = tag.toItem()
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Mine.colors.sheet, contentColor = Mine.colors.text) {
        PrintSheetContent(
            PrintSheetUi(
                subtitle = listOfNotNull(tag.name.ifBlank { null }, item.price?.let { "${PriceFormat.tag(it).first} ₴" }).joinToString(", "),
                format = format,
                promo = promo && item.canPromo,
                oldPrice = if (item.canPromo) PriceFormat.tag(item.oldPrice!!).first else null,
                qty = qty, printer = line, busy = busy, error = error,
            ),
            onFormat = { format = it; error = null; scope.launch { svc.store.setLastTemplate(it) } },
            onPromo = { promo = it },
            onQty = { qty = it },
            onPrint = {
                busy = true
                error = null
                scope.launch {
                    try {
                        val r = svc.print(listOf(QueueItem(tag, qty)), format, promo && item.canPromo && format.kind == LabelKind.PLAIN)
                        line = PrinterLine(r.model, true)
                        onDone("Надруковано")
                        onDismiss()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        error = e.message ?: "Не вдалося надрукувати"
                    } finally {
                        busy = false
                    }
                }
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PrintSheetContent(
    ui: PrintSheetUi,
    onFormat: (Template) -> Unit = {},
    onPromo: (Boolean) -> Unit = {},
    onQty: (Int) -> Unit = {},
    onPrint: () -> Unit = {},
) {
    val m = Mine.colors
    Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 30.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column {
            Text("Друк цінника", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = m.text)
            Text(ui.subtitle, Modifier.padding(top = 4.dp), fontSize = 14.sp, lineHeight = 20.sp, color = m.textSecondary)
        }
        Column {
            Text("Формат", Modifier.padding(bottom = 8.dp), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = m.textSecondary)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Template.entries.forEach { t -> MineChip(formatTitle(t), ui.format == t, { onFormat(t) }) }
            }
        }
        if (ui.oldPrice != null && ui.format.kind == LabelKind.PLAIN) {
            Surface(shape = RoundedCornerShape(16.dp), color = m.card, contentColor = m.text) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Акційний цінник", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        Text("Стара ціна ${ui.oldPrice} ₴ буде закреслена. Встав жовтий папір.", fontSize = 13.sp, lineHeight = 17.sp, color = m.textSecondary)
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(
                        ui.promo, onPromo,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = m.accent, checkedThumbColor = m.onAccent, checkedBorderColor = Color.Transparent,
                            uncheckedTrackColor = m.surface, uncheckedThumbColor = m.textSecondary, uncheckedBorderColor = m.outline,
                        ),
                    )
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Кількість", Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.Medium, color = m.text)
            MineStepper(ui.qty, onQty)
        }
        PrinterStatusLine(ui.printer)
        ui.error?.let { Text(it, fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium, color = m.warningText) }
        MinePrimaryButton(
            when {
                ui.busy -> "Друкую…"
                ui.error != null -> "Спробувати ще"
                else -> printButtonText(ui.format, ui.qty)
            },
            onPrint, height = 58.dp, enabled = !ui.busy,
        )
    }
}

/** Маленький статус без кнопок: точка + «Godex G500 · онлайн». */
@Composable
private fun PrinterStatusLine(p: PrinterLine) {
    val m = Mine.colors
    Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        StatusDot(
            when (p.online) {
                true -> m.success
                false -> m.warning
                null -> m.outline
            },
        )
        Spacer(Modifier.width(8.dp))
        Text(
            when {
                p.online == null -> "Шукаю принтер…"
                p.model == null -> "Принтер знайдемо під час друку"
                p.online -> "${p.model} · онлайн"
                else -> "${p.model} · не відповідає, пошукаю під час друку"
            },
            fontSize = 14.sp, color = m.textSecondary, maxLines = 1,
        )
    }
}
