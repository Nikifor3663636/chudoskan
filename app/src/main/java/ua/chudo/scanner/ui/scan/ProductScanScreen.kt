package ua.chudo.scanner.ui.scan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import ua.chudo.scanner.chudoApp
import ua.chudo.scanner.data.Backend
import ua.chudo.scanner.data.Employee
import ua.chudo.scanner.data.HighlightKind
import ua.chudo.scanner.data.LookupResult
import ua.chudo.scanner.data.ProductHighlights
import ua.chudo.scanner.ui.home.updatedText
import ua.chudo.scanner.ui.stock.highlightPrice
import java.text.NumberFormat
import java.util.Locale

private data class ScanSummary(
    val title: String,
    val urlKey: String?,
    val price: Double?,
    val own: Double?,
    val otherStore: String?,
    val stockTime: String?,
    val flags: List<String>,
)

@Composable
fun ProductScanScreen(
    employee: Employee,
    onClose: () -> Unit,
    onSearch: (String) -> Unit,
    onProduct: (String, String) -> Unit,
    onPrint: (String, String) -> Unit,
    onCart: (String?) -> Unit,
) {
    val app = LocalContext.current.chudoApp
    var code by rememberSaveable { mutableStateOf<String?>(null) }
    var summary by remember(code) { mutableStateOf<ScanSummary?>(null) }
    var loading by remember(code) { mutableStateOf(code != null) }
    var manual by rememberSaveable { mutableStateOf(false) }
    var entered by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(code, employee.shopId) {
        val barcode = code ?: return@LaunchedEffect
        summary = run {
            val net = optional { app.networkLookup.byBarcode(employee.shopId, barcode) }
            val lookup = optional { app.repository.lookup(barcode, net) } as? LookupResult.Single
            val product = lookup?.let { optional { app.repository.details(it.urlKey) } }
            val flags = if (Backend.isSupabase) {
                optional { ProductHighlights.forProduct(barcode, product?.article ?: net?.item?.article) }.orEmpty()
            } else emptyList()
            ScanSummary(
                title = product?.name ?: net?.item?.name ?: flags.firstOrNull()?.name ?: "Товар за кодом $barcode",
                urlKey = lookup?.urlKey,
                price = flags.firstNotNullOfOrNull { it.promoPrice } ?: product?.price?.finalPrice,
                own = net?.own,
                otherStore = net?.city?.firstOrNull()?.let { store -> "${store.name} · ${formatQuantity(store.qty)} шт" },
                stockTime = updatedText(net?.updatedAt),
                flags = flags.mapNotNull { flag -> HighlightKind.entries.firstOrNull { it.key == flag.kind }?.badge },
            )
        }
        loading = false
    }

    val barcode = code
    val result: (@Composable () -> Unit)? = if (barcode == null) null else {
        {
            Surface(shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth(), tonalElevation = 3.dp) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (loading) {
                        Text("Шукаємо товар…", style = MaterialTheme.typography.titleMedium)
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    } else {
                        val found = summary
                        Text(found?.title ?: barcode, style = MaterialTheme.typography.titleMedium)
                        found?.flags?.takeIf { it.isNotEmpty() }?.let { Text(it.joinToString(" · ")) }
                        found?.price?.let { Text(highlightPrice(it), style = MaterialTheme.typography.headlineMedium) }
                        Text(found?.own?.let {
                            if (it > 0.0) "У вашому магазині ${formatQuantity(it)} шт"
                            else "У вашому магазині немає"
                        } ?: "Залишок вашого магазину невідомий")
                        if (found?.own == 0.0) found.otherStore?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        found?.stockTime?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        val key = found?.urlKey
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (key != null) {
                                Button(onClick = { onPrint(key, barcode) }) { Text("Цінник", fontWeight = FontWeight.SemiBold) }
                                TextButton(onClick = { onProduct(key, barcode) }) { Text("Картка") }
                            } else Button(onClick = { onSearch(barcode) }) { Text("Пошук") }
                            TextButton(onClick = { code = null }) { Text("Закрити") }
                        }
                    }
                }
            }
        }
    }
    ScannerScreen(
        onBarcode = { if (!manual) code = it },
        onClose = onClose,
        continuous = true,
        result = result,
        onManual = { manual = true },
        onCartMode = {
            val scanned = code
            code = null
            onCart(scanned)
        },
    )
    if (manual) AlertDialog(
        onDismissRequest = { manual = false },
        title = { Text("Ввести код вручну") },
        text = {
            OutlinedTextField(entered, { entered = it }, singleLine = true, label = { Text("Штрихкод або артикул") })
        },
        confirmButton = {
            TextButton(onClick = { onSearch(entered.trim()) }, enabled = entered.isNotBlank()) { Text("Знайти") }
        },
        dismissButton = { TextButton(onClick = { manual = false }) { Text("Скасувати") } },
    )
}

private fun formatQuantity(quantity: Double): String =
    NumberFormat.getNumberInstance(Locale.forLanguageTag("uk")).format(quantity)

private suspend fun <T> optional(read: suspend () -> T): T? = try {
    read()
} catch (error: CancellationException) {
    throw error
} catch (_: Exception) {
    null
}
