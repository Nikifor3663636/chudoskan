package ua.chudo.scanner.ui.stock

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ua.chudo.scanner.R
import ua.chudo.scanner.chudoApp
import ua.chudo.scanner.data.ActivityKind
import ua.chudo.scanner.data.Employee
import ua.chudo.scanner.data.NetAvailability
import ua.chudo.scanner.print.Template
import ua.chudo.scanner.ui.print.PrintSheet
import ua.chudo.scanner.ui.print.formatTitle
import ua.chudo.scanner.ui.print.printButtonText
import ua.chudo.scanner.ui.theme.Mine

@Composable
fun LabelBatchScreen(employee: Employee, vm: StockViewModel, onBack: () -> Unit, onFindProduct: () -> Unit) {
    val app = LocalContext.current.chudoApp
    val candidates by vm.selected.collectAsStateWithLifecycle()
    val loading by vm.changedLoading.collectAsStateWithLifecycle()
    val note by vm.batchNote.collectAsStateWithLifecycle()
    val progress by vm.labelsProgress.collectAsStateWithLifecycle()
    val printTags by vm.printTags.collectAsStateWithLifecycle()
    val template by app.printing.store.lastTemplate.collectAsStateWithLifecycle(null)
    var excluded by rememberSaveable { mutableStateOf<List<String>>(arrayListOf()) }
    var openPreview by rememberSaveable { mutableStateOf(false) }
    val checked = candidates.map { it.barcode }.filterNot { it in excluded }.toSet()

    LabelBatchContent(
        candidates, checked, template ?: Template.TAG_68x38,
        onBack = onBack, onFindProduct = onFindProduct, onAddChanged = vm::addChanged,
        onToggle = { code ->
            excluded = ArrayList(if (code in excluded) excluded - code else excluded + code)
        },
        onToggleAll = {
            excluded = if (checked.size == candidates.size) ArrayList(candidates.map { it.barcode }) else arrayListOf()
        },
        onPrepare = { preview ->
            openPreview = preview
            vm.prepareLabels(checked)
        },
        loading = loading, progress = progress, note = note,
    )
    printTags?.let { tags ->
        PrintSheet(tags, onDismiss = vm::closePrint, openSheetPreview = openPreview, onDone = { message ->
            app.prefs.addActivity(employee, ActivityKind.PRINT, "Друк пакета цінників", "${tags.size} товарів")
            vm.printed(message)
        })
    }
}

@Composable
internal fun LabelBatchContent(
    candidates: List<NetAvailability>,
    checked: Set<String>,
    template: Template,
    onBack: () -> Unit = {},
    onFindProduct: () -> Unit = {},
    onAddChanged: () -> Unit = {},
    onToggle: (String) -> Unit = {},
    onToggleAll: () -> Unit = {},
    onPrepare: (Boolean) -> Unit = {},
    loading: Boolean = false,
    progress: String? = null,
    note: String? = null,
) {
    val m = Mine.colors
    val all = candidates.isNotEmpty() && checked.size == candidates.size
    val available = checked.isNotEmpty() && progress == null
    Column(Modifier.fillMaxSize().background(m.bg).statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                Icon(painterResource(R.drawable.ic_redesign_chevron_left), "Назад", Modifier.size(24.dp), tint = m.textSecondary)
            }
            Column(Modifier.padding(start = 4.dp)) {
                Text("Цінники пакетом", fontSize = 17.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, color = m.text)
                Text("Змінені з минулого завантаження", fontSize = 12.sp, lineHeight = 14.4.sp, color = m.textTertiary)
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            if (candidates.isEmpty()) item {
                Text("Додайте товари із залишків або завантажте зміни з 1С.", Modifier.padding(horizontal = 16.dp, vertical = 12.dp), fontSize = 14.sp, color = m.textSecondary)
            }
            else {
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 10.dp, top = 4.dp, end = 16.dp, bottom = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = onToggleAll, enabled = progress == null) {
                            BatchCheck(all, mixed = checked.isNotEmpty() && !all)
                            Text(if (all) "Зняти всі" else "Вибрати всі", Modifier.padding(start = 8.dp), fontSize = 14.sp, color = m.link)
                        }
                        Text(formatTitle(template), Modifier.weight(1f), fontSize = 13.sp, color = m.textTertiary, textAlign = TextAlign.End)
                    }
                    HorizontalDivider(color = m.line)
                }
                items(candidates, key = { it.barcode }) { product ->
                    val active = product.barcode in checked
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 56.dp).background(m.card)
                            .toggleable(active, enabled = progress == null, role = Role.Checkbox, onValueChange = { onToggle(product.barcode) })
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BatchCheck(active)
                        Column(Modifier.weight(1f)) {
                            Text(product.item.name.ifBlank { product.barcode }, fontSize = 15.sp, lineHeight = 19.5.sp, fontWeight = FontWeight.Medium, color = m.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOf(product.item.article, product.barcode).filter(String::isNotBlank).joinToString(" · "),
                                fontSize = 13.sp, color = m.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    HorizontalDivider(color = m.line)
                }
            }
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (candidates.isNotEmpty() && template.layout(template.defaultLandscape) != null) {
                        BatchButton("Переглянути аркуш", { onPrepare(true) }, icon = R.drawable.ic_redesign_eye, enabled = available, tonal = true)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BatchButton("Знайти товар", onFindProduct, Modifier.weight(1f), enabled = progress == null)
                        BatchButton(if (loading) "Завантаження…" else "Додати зміни з 1С", onAddChanged, Modifier.weight(1f), enabled = !loading && progress == null)
                    }
                    (progress ?: note)?.let { Text(it, fontSize = 13.sp, color = m.textSecondary) }
                }
            }
        }
        Column(Modifier.fillMaxWidth().background(m.card).navigationBarsPadding()) {
            HorizontalDivider(color = m.line)
            BatchButton(
                printButtonText(template, checked.size), { onPrepare(false) },
                Modifier.fillMaxWidth().padding(start = 16.dp, top = 10.dp, end = 16.dp, bottom = 12.dp),
                icon = R.drawable.ic_redesign_printer, enabled = available, primary = true,
            )
        }
    }
}

@Composable
private fun BatchCheck(checked: Boolean, mixed: Boolean = false) {
    val m = Mine.colors
    val selected = checked || mixed
    Surface(
        Modifier.size(20.dp), shape = RoundedCornerShape(5.dp), color = if (selected) m.text else m.card,
        contentColor = m.card, border = BorderStroke(1.5.dp, if (selected) m.text else m.outline),
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (selected) Icon(painterResource(if (mixed) R.drawable.ic_redesign_minus else R.drawable.ic_redesign_check), null, Modifier.size(16.dp))
        }
    }
}

@Composable
private fun BatchButton(
    title: String, onClick: () -> Unit, modifier: Modifier = Modifier, @DrawableRes icon: Int? = null,
    enabled: Boolean = true, primary: Boolean = false, tonal: Boolean = false,
) {
    val m = Mine.colors
    Surface(
        onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = if (primary) 52.dp else 36.dp),
        shape = RoundedCornerShape(6.dp),
        color = when {
            !enabled -> m.surface
            primary -> m.accent
            tonal -> m.accentTone
            else -> m.card
        },
        contentColor = when {
            !enabled -> m.textTertiary
            primary -> m.onAccent
            tonal -> m.link
            else -> m.text
        },
        border = if (primary) null else BorderStroke(1.dp, if (tonal) m.brandLine else m.outline),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) Icon(painterResource(icon), null, Modifier.size(if (primary) 20.dp else 16.dp))
            Text(title, fontSize = if (primary) 16.sp else 14.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
        }
    }
}
