package ua.chudo.scanner.print

/** Данные ценника. Цены — строкой как на ценнике («129,90»), чтобы их можно было править вручную. */
data class PriceTag(
    val name: String,
    val price: String,
    val oldPrice: String,
    val article: String,
    val barcode: String,
)

/** «Липкі» — термоэтикетки (Godex), «Звичайні» — лист A4 (Brother). */
enum class LabelKind { STICKY, PLAIN }

enum class LabelTemplate(val kind: LabelKind, val widthMm: Int, val heightMm: Int, val title: String) {
    STICKY_58x40(LabelKind.STICKY, 58, 40, "58×40"),
    STICKY_40x30(LabelKind.STICKY, 40, 30, "40×30"),
    STICKY_30x20(LabelKind.STICKY, 30, 20, "30×20"),
    A4_70x37(LabelKind.PLAIN, 70, 37, "A4 3×8 (70×37)"),
    ;

    companion object {
        fun of(kind: LabelKind) = entries.filter { it.kind == kind }
    }
}

data class QueueItem(val tag: PriceTag, val copies: Int)

/**
 * Сетевой принтер. [fingerprint] — чем узнать принтер, если сменился IP:
 * `brother:<имя из NSD>`, `godex:<ответ на ~HI>`, [ONLY_9100] или `ip:<адрес>` (только по IP).
 */
data class SavedPrinter(
    val id: String,
    val name: String,
    val role: LabelKind,
    val host: String,
    val port: Int,
    val fingerprint: String,
)

/** Отпечаток «единственный :9100 в сети, который не Brother». */
const val ONLY_9100 = "only9100"

/** Найденный в сети принтер. */
data class FoundPrinter(val host: String, val port: Int, val fingerprint: String, val name: String, val role: LabelKind)

class PrinterNotFoundException(val printer: SavedPrinter) : Exception("Printer not found: ${printer.name}")

fun formatPrice(v: Double): String =
    if (v == Math.floor(v)) v.toLong().toString() else String.format(java.util.Locale.US, "%.2f", v).replace('.', ',')
