package ua.chudo.scanner.ui.inventory

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import ua.chudo.scanner.chudoApp
import ua.chudo.scanner.data.Employee
import ua.chudo.scanner.data.InventoryDraft
import ua.chudo.scanner.data.InventoryLine
import ua.chudo.scanner.data.ShopRef
import ua.chudo.scanner.data.StockOperation
import ua.chudo.scanner.ui.common.MineButton
import ua.chudo.scanner.ui.common.MineCard
import ua.chudo.scanner.ui.common.MineHeader
import ua.chudo.scanner.ui.scan.ScannerScreen

@Composable
fun InventoryScreen(employee: Employee, mode: StockOperation, onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.chudoApp
    val scope = rememberCoroutineScope()
    val prefs = remember(context) { context.getSharedPreferences("inventory_drafts", Context.MODE_PRIVATE) }
    val key = "${employee.uid}:${employee.shopId}:${mode.name}"
    val referenceKey = "$key:reference"
    var reference by remember(key) { mutableStateOf(prefs.getString(referenceKey, "").orEmpty()) }
    var chooseShop by remember { mutableStateOf(false) }
    var destinations by remember { mutableStateOf<List<ShopRef>?>(null) }
    var destinationsFailed by remember { mutableStateOf(false) }
    var destinationQuery by remember { mutableStateOf("") }
    LaunchedEffect(chooseShop) {
        if (!chooseShop || destinations != null) return@LaunchedEffect
        destinationsFailed = false
        try {
            destinations = app.networkStock.shops().filter { it.id != employee.shopId }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            destinationsFailed = true
        }
    }
    fun saveReference(value: String) {
        prefs.edit { putString(referenceKey, value) }
        reference = value
    }
    val referenceNeeded = mode != StockOperation.INVENTORY
    val canCount = !referenceNeeded || reference.isNotBlank()
    var lines by remember(key) {
        mutableStateOf(runCatching {
            Json.decodeFromString<List<InventoryLine>>(prefs.getString(key, "[]").orEmpty())
        }.getOrDefault(emptyList()))
    }
    var manual by remember { mutableStateOf("") }
    var scanning by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var clear by remember { mutableStateOf(false) }

    fun save(next: List<InventoryLine>) {
        prefs.edit { putString(key, Json.encodeToString(next)) }
        lines = next
    }

    fun add(raw: String) {
        val barcode = raw.trim()
        if (busy || !canCount || barcode.isEmpty() || barcode.length > 64) return
        val known = lines.firstOrNull { it.barcode == barcode }
        if (known != null) {
            save(InventoryDraft.add(lines, barcode, known.name, known.expected))
            manual = ""
            message = ""
            return
        }
        busy = true
        message = "Звіряємо з 1С…"
        scope.launch {
            try {
                val availability = app.networkLookup.byBarcode(employee.shopId, barcode)
                save(InventoryDraft.add(lines, barcode, availability?.item?.name ?: barcode, availability?.own))
                manual = ""
                message = if (availability == null) "Код додано. Даних 1С для нього немає." else ""
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                save(InventoryDraft.add(lines, barcode, barcode, null))
                manual = ""
                message = "Код додано. Звірити з 1С не вдалося."
            } finally {
                busy = false
            }
        }
    }

    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) {
            val report = if (mode == StockOperation.INVENTORY) InventoryDraft.csv(lines, employee.shop)
                else InventoryDraft.movementCsv(lines, employee.shop, reference, mode)
            scope.launch {
                message = try {
                    withContext(Dispatchers.IO) {
                        checkNotNull(context.contentResolver.openOutputStream(uri)).bufferedWriter(Charsets.UTF_8).use { writer ->
                            writer.write("\uFEFF")
                            writer.write(report)
                        }
                    }
                    "Звіт збережено."
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    "Не вдалося зберегти звіт."
                }
            }
        }
    }

    BackHandler(scanning) { scanning = false }
    if (scanning) {
        ScannerScreen(onBarcode = { scanning = false; add(it) }, onClose = { scanning = false })
        return
    }

    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { MineHeader(mode.title, onBack) }
        item {
            MineCard {
                Text(employee.shop)
                Text("Чернетка на цьому пристрої · ${lines.size} товарів · ${lines.sumOf { it.counted }} шт")
                if (referenceNeeded) Text("CSV для передачі в облік. Залишки змінюються після проведення документа в 1С.")
                when (mode) {
                    StockOperation.INVENTORY -> Text("Звірка з залишками всього магазину")
                    StockOperation.RECEIVING -> OutlinedTextField(
                        reference, { saveReference(it.take(80)) }, label = { Text("Номер накладної") },
                        singleLine = true, enabled = lines.isEmpty() && !busy,
                    )
                    StockOperation.TRANSFER -> MineButton(
                        reference.ifBlank { "Вибрати магазин призначення" },
                        { destinationQuery = ""; chooseShop = true }, enabled = lines.isEmpty() && !busy,
                    )
                }
                MineButton("Сканувати", { scanning = true }, enabled = !busy && canCount)
                OutlinedTextField(manual, { manual = it }, label = { Text("Штрихкод") }, singleLine = true)
                MineButton("Додати", { add(manual) }, enabled = !busy && canCount && manual.isNotBlank() && manual.length <= 64)
            }
        }
        if (message.isNotEmpty()) item { Text(message) }
        items(lines, key = { it.barcode }) { line ->
            MineCard {
                Text(line.name)
                if (line.name != line.barcode) Text(line.barcode)
                when (mode) {
                    StockOperation.INVENTORY -> {
                        Text("1С: ${line.expected ?: "—"} · Фактично: ${line.counted}")
                        line.expected?.let { Text("Різниця: ${line.counted - it}") }
                    }
                    StockOperation.RECEIVING -> Text("Прийнято: ${line.counted} шт")
                    StockOperation.TRANSFER -> Text("До переміщення: ${line.counted} шт · залишок 1С: ${line.expected ?: "—"}")
                }
                Row {
                    TextButton(enabled = !busy && line.counted > 0, onClick = {
                        save(lines.map { if (it.barcode == line.barcode) it.copy(counted = it.counted - 1) else it })
                    }) { Text("−1") }
                    TextButton(enabled = !busy, onClick = { add(line.barcode) }) { Text("+1") }
                }
            }
        }
        if (lines.isNotEmpty()) item {
            Column {
                MineButton("Зберегти звіт CSV", { export.launch(mode.fileName) },
                    enabled = !busy && canCount && (mode == StockOperation.INVENTORY || lines.any { it.counted > 0 }))
                TextButton(enabled = !busy, onClick = { clear = true }) { Text("Очистити чернетку") }
            }
        }
    }

    if (chooseShop) AlertDialog(
        onDismissRequest = { chooseShop = false },
        title = { Text("Магазин призначення") },
        text = {
            Column {
                OutlinedTextField(destinationQuery, { destinationQuery = it }, label = { Text("Місто або адреса") }, singleLine = true)
                if (destinationsFailed) Text("Не вдалося завантажити магазини.")
                else if (destinations == null) Text("Завантаження…")
                else LazyColumn(Modifier.weight(1f, fill = false)) {
                    items(destinations.orEmpty().filter {
                        "${it.address} ${it.region}".contains(destinationQuery.trim(), ignoreCase = true)
                    }) { shop ->
                        TextButton(onClick = { saveReference(shop.address.ifBlank { shop.id }); chooseShop = false }) {
                            Text(shop.address.ifBlank { shop.id })
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { chooseShop = false }) { Text("Закрити") } },
    )

    if (clear) AlertDialog(
        onDismissRequest = { clear = false },
        title = { Text("Очистити підрахунок?") },
        text = { Text("Чернетку цього магазину буде видалено з пристрою.") },
        confirmButton = { TextButton(onClick = { save(emptyList()); saveReference(""); clear = false }) { Text("Очистити") } },
        dismissButton = { TextButton(onClick = { clear = false }) { Text("Скасувати") } },
    )
}
