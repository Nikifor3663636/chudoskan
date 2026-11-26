package ua.chudo.scanner.data

import kotlinx.serialization.Serializable

@Serializable
data class InventoryLine(
    val barcode: String,
    val name: String,
    val expected: Double?,
    val counted: Int,
)

enum class StockOperation(val title: String, val fileName: String) {
    INVENTORY("Інвентаризація", "inventory.csv"),
    RECEIVING("Приймання", "receiving.csv"),
    TRANSFER("Переміщення", "transfer.csv"),
}

object InventoryDraft {
    fun add(lines: List<InventoryLine>, barcode: String, name: String, expected: Double?): List<InventoryLine> {
        if (lines.any { it.barcode == barcode }) {
            return lines.map { if (it.barcode == barcode) it.copy(counted = it.counted + 1) else it }
        }
        return lines + InventoryLine(barcode, name, expected, 1)
    }

    fun csv(lines: List<InventoryLine>, shop: String): String = buildString {
        appendLine("Магазин;Штрихкод;Товар;За даними 1С;Пораховано;Різниця")
        for (line in lines) {
            appendLine(listOf(
                quoted(shop), quoted(line.barcode), quoted(line.name), line.expected?.toString().orEmpty(),
                line.counted.toString(), line.expected?.let { (line.counted - it).toString() }.orEmpty(),
            ).joinToString(";"))
        }
    }

    fun movementCsv(lines: List<InventoryLine>, shop: String, reference: String, mode: StockOperation): String = buildString {
        val detail = if (mode == StockOperation.TRANSFER) "Магазин призначення" else "Накладна"
        appendLine("Магазин;$detail;Штрихкод;Товар;Кількість")
        for (line in lines.filter { it.counted > 0 }) {
            appendLine(listOf(quoted(shop), quoted(reference), quoted(line.barcode), quoted(line.name), line.counted).joinToString(";"))
        }
    }

    private fun quoted(text: String): String {
        val first = text.trimStart().firstOrNull()
        val safe = if (first != null && first in "=+-@") "'" + text else text
        return "\"" + safe.replace("\"", "\"\"") + "\""
    }
}
