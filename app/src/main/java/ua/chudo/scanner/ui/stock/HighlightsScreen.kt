package ua.chudo.scanner.ui.stock

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import ua.chudo.scanner.ui.theme.Mine
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ua.chudo.scanner.chudoApp
import ua.chudo.scanner.data.HighlightKind
import ua.chudo.scanner.data.HighlightReport
import ua.chudo.scanner.data.LookupResult
import ua.chudo.scanner.data.ProductHighlight
import ua.chudo.scanner.data.ProductHighlights
import ua.chudo.scanner.ui.common.MineCard
import ua.chudo.scanner.ui.common.MineHeader
import ua.chudo.scanner.ui.common.MinePrimaryButton
import java.text.NumberFormat
import java.util.Locale

@Composable
fun HighlightsScreen(
    canUpload: Boolean,
    onBack: () -> Unit,
    onProduct: (String, String?) -> Unit,
    onSearch: (String) -> Unit,
    onBrowseCatalog: (List<String>, String) -> Unit = { _, _ -> },
    initialKind: HighlightKind = HighlightKind.NEW,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val kind = initialKind
    var query by rememberSaveable { mutableStateOf("") }
    var rows by remember { mutableStateOf(emptyList<ProductHighlight>()) }
    var selected by remember { mutableStateOf<ProductHighlight?>(null) }
    var preview by remember { mutableStateOf<Pair<HighlightKind, List<ProductHighlight>>?>(null) }
    var loading by remember { mutableStateOf(true) }
    var more by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var listError by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val category = kind
            working = true
            note = "Перевіряю файл…"
            scope.launch {
                try {
                    val report = withContext(Dispatchers.IO) {
                        HighlightReport.parse(category) {
                            checkNotNull(ctx.contentResolver.openInputStream(uri)) { "Не вдалося відкрити файл." }
                        }
                    }
                    preview = category to report
                    note = ""
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    note = e.message ?: "Не вдалося прочитати звіт .xlsx."
                } finally {
                    working = false
                }
            }
        }
    }

    LaunchedEffect(kind, query, refresh) {
        rows = emptyList()
        more = false
        listError = false
        loading = true
        try {
            if (query.isNotBlank()) delay(250)
            val page = ProductHighlights.page(kind, query.trim(), 0)
            rows = page
            more = page.size == ProductHighlights.PAGE_SIZE
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            listError = true
        } finally {
            loading = false
        }
    }

    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { MineHeader(kind.title, onBack) }
        item {
            OutlinedTextField(
                query, { query = it }, Modifier.fillMaxWidth(), enabled = !working,
                label = { Text("Назва, штрихкод або артикул") }, singleLine = true, shape = Mine.fieldShape,
            )
        }
        if (canUpload) item {
            MinePrimaryButton("Завантажити звіт «${kind.title}»", {
                picker.launch(arrayOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/octet-stream"))
            }, enabled = !working)
            Text("Колонки .xlsx — назва та штрихкод або артикул. Дати й акційна ціна за наявності.")
        }
        if (note.isNotEmpty()) item { Text(note) }
        if (listError) item {
            Text("Не вдалося завантажити список.")
            TextButton({ refresh++ }) { Text("Повторити") }
        } else if (!loading && rows.isEmpty()) item {
            Text(if (query.isBlank()) "Активних записів поки немає." else "За цим запитом товарів немає.")
        }
        items(rows) { row ->
            CatalogRow(
                title = row.name,
                detail = listOf(row.article, row.barcode).filter(String::isNotBlank).joinToString(" · "),
                trailing = row.promoPrice?.let(::highlightPrice),
                onClick = { selected = row },
            )
        }
        if (loading) item { CircularProgressIndicator() }
        if (more && !loading) item {
            TextButton({
                val category = kind
                val search = query
                val offset = rows.size
                val revision = refresh
                loading = true
                scope.launch {
                    try {
                        val page = ProductHighlights.page(category, search.trim(), offset)
                        if (category == kind && search == query && revision == refresh) {
                            rows = (rows + page).distinct()
                            more = page.size == ProductHighlights.PAGE_SIZE
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        if (category == kind && search == query) note = "Не вдалося завантажити наступні товари."
                    } finally {
                        if (category == kind && search == query && revision == refresh) loading = false
                    }
                }
            }) { Text("Показати ще") }
        }
    }

    selected?.let { row ->
        AlertDialog(
            onDismissRequest = { if (!working) selected = null },
            title = { Text(row.name) },
            text = {
                val dates = listOfNotNull(
                    row.startsOn?.let { "Початок $it" }, row.endsOn?.let { "До $it включно" },
                    row.promoPrice?.let(::highlightPrice),
                )
                if (dates.isNotEmpty()) Text(dates.joinToString("\n"))
            },
            confirmButton = {
                TextButton(enabled = !working, onClick = {
                    val code = row.article.ifBlank { row.barcode }
                    working = true
                    scope.launch {
                        try {
                            val result = ctx.chudoApp.repository.lookup(code)
                            selected = null
                            onBrowseCatalog(rows.map { it.article.ifBlank { it.barcode } }.distinct(), code)
                            if (result is LookupResult.Single) onProduct(result.urlKey, row.barcode.ifBlank { null })
                            else onSearch(code)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            selected = null
                            note = "Не вдалося відкрити картку з каталогу."
                        } finally {
                            working = false
                        }
                    }
                }) { Text(if (working) "Шукаю…" else "Картка товару") }
            },
            dismissButton = { TextButton({ selected = null }, enabled = !working) { Text("Закрити") } },
        )
    }

    preview?.let { (category, report) ->
        AlertDialog(
            onDismissRequest = { preview = null },
            title = { Text("Опублікувати «${category.title}»?") },
            text = { Text("${report.size} товарів. Звіт замінить поточний список цієї категорії для всієї мережі.") },
            confirmButton = {
                TextButton({
                    preview = null
                    working = true
                    note = "Публікую…"
                    scope.launch {
                        try {
                            val count = ProductHighlights.publish(category, report)
                            note = "Опубліковано товарів — $count."
                            refresh++
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            note = "Не вдалося завершити публікацію. Онови список і перевір результат."
                        } finally {
                            working = false
                        }
                    }
                }) { Text("Опублікувати") }
            },
            dismissButton = { TextButton({ preview = null }) { Text("Скасувати") } },
        )
    }
}

internal fun highlightPrice(price: Double): String =
    NumberFormat.getCurrencyInstance(Locale.forLanguageTag("uk-UA")).format(price)
