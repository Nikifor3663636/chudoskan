package ua.chudo.shared.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import ua.chudo.shared.auth.ProductHighlight
import ua.chudo.shared.data.LookupResult

@Composable
internal fun HighlightsScreen(nav: Navigator) {
    val graph = LocalGraph.current
    var kind by rememberSaveable { mutableStateOf("new") }
    var query by rememberSaveable { mutableStateOf("") }
    var rows by remember { mutableStateOf(emptyList<ProductHighlight>()) }
    var selected by remember { mutableStateOf<ProductHighlight?>(null) }
    var openProduct by remember { mutableStateOf<ProductHighlight?>(null) }
    var loading by remember { mutableStateOf(true) }
    var failure by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    var offset by remember { mutableIntStateOf(0) }
    var attempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(kind, query, offset, attempt) {
        if (offset == 0) {
            rows = emptyList()
            more = false
        }
        loading = true
        failure = false
        try {
            if (offset == 0 && query.isNotBlank()) delay(250)
            val page = graph.auth.highlightPage(kind, query.trim(), offset)
            rows = (rows + page).distinct()
            more = page.size == 50
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            failure = true
        } finally {
            loading = false
        }
    }

    LaunchedEffect(openProduct) {
        val row = openProduct ?: return@LaunchedEffect
        val code = row.article.ifBlank { row.barcode }
        val result = try {
            graph.repo.lookup(code)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        openProduct = null
        selected = null
        if (result is LookupResult.Single) nav.go(Screen.Product(result.urlKey, row.barcode.ifBlank { null }))
        else nav.go(Screen.Results(code))
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        TopBar("Новинки й акції", onBack = { nav.back() })
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(kind == "new", { kind = "new"; offset = 0 }, { Text("Новинки") })
                    FilterChip(kind == "promo", { kind = "promo"; offset = 0 }, { Text("Акції") })
                }
            }
            item {
                OutlinedTextField(query, { query = it; offset = 0 }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("Назва, штрихкод або артикул") })
            }
            items(rows) { row ->
                Card(onClick = { selected = row }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(row.name, style = MaterialTheme.typography.titleMedium)
                        Text(row.label)
                    }
                }
            }
            when {
                loading -> item { CircularProgressIndicator() }
                failure -> item {
                    Text("Не вдалося завантажити список.")
                    TextButton({ attempt++ }) { Text("Повторити") }
                }
                rows.isEmpty() -> item { Text(if (query.isBlank()) "Активних записів поки немає." else "Товарів не знайдено.") }
                more -> item { TextButton({ offset += 50 }) { Text("Показати ще") } }
            }
        }
    }

    selected?.let { row ->
        AlertDialog(
            onDismissRequest = { if (openProduct == null) selected = null },
            title = { Text(row.name) },
            text = { Text(listOfNotNull(
                row.label,
                row.article.takeIf { it.isNotBlank() }?.let { "Артикул $it" },
                row.barcode.takeIf { it.isNotBlank() }?.let { "Штрихкод $it" },
                row.startsOn?.let { "Початок $it" },
                row.endsOn?.let { "До $it включно" },
            ).joinToString("\n")) },
            confirmButton = {
                TextButton({ openProduct = row }, enabled = openProduct == null) {
                    Text(if (openProduct == null) "Картка товару" else "Шукаю…")
                }
            },
            dismissButton = { TextButton({ selected = null }, enabled = openProduct == null) { Text("Закрити") } },
        )
    }
}
