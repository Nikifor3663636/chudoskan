package ua.chudo.scanner.print

/** Данные ценника. Цены — строкой как на ценнике («129,90»), чтобы их можно было править вручную. */
data class PriceTag(
    val name: String,
    val price: String,
    val oldPrice: String,
    val article: String,
    val barcode: String,
)

/** «Липкі» — термоэтикетки, «Звичайні» — лист A4. */
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

/** Как телефон достаёт до принтера. */
enum class Transport(val title: String) {
    /** Wi-Fi/LAN магазина: IP + порт (обычно 9100). */
    LAN("Мережа магазину"),

    /** Собственная Wi-Fi сеть принтера (Wireless Direct / Wi-Fi Direct / точка доступа), без роутера. */
    WIFI_DIRECT("Wi-Fi принтера"),

    /** Bluetooth Classic (SPP); принтер спарений у налаштуваннях телефона. */
    BLUETOOTH("Bluetooth"),
}

/** Язык команд принтера. Для этикеток — картинка ценника в формате этого языка. */
enum class PrintLang(val title: String, val hint: String) {
    ZPL("ZPL", "Zebra, Godex, Honeywell, Citizen"),
    TSPL("TSPL", "TSC, Xprinter, HPRT, Gprinter, більшість китайських"),
    EPL("EPL", "старі Zebra / Eltron"),
    ESCPOS("ESC/POS", "чекові та прості мобільні принтери"),
    PDF("PDF", "офісні A4: Brother, HP, Canon, Epson…"),
    ;

    companion object {
        val LABEL = listOf(ZPL, TSPL, EPL, ESCPOS)
    }
}

/**
 * Сохранённый принтер.
 * - [Transport.LAN]: [host]:[port]; [fingerprint] — чем узнать принтер, если сменился IP:
 *   `brother:<имя из NSD>`, `godex:<ответ на ~HI>`, `snmp:<модель>`, [ONLY_9100] или `ip:<адрес>`.
 * - [Transport.WIFI_DIRECT]: сеть принтера [ssid]/[pass]; [host] пустой — берём шлюз этой сети.
 * - [Transport.BLUETOOTH]: [host] — MAC-адрес.
 */
data class SavedPrinter(
    val id: String,
    val name: String,
    val role: LabelKind,
    val host: String,
    val port: Int,
    val fingerprint: String,
    val transport: Transport = Transport.LAN,
    val lang: PrintLang = defaultLang(role),
    val ssid: String = "",
    val pass: String = "",
)

fun defaultLang(role: LabelKind) = if (role == LabelKind.STICKY) PrintLang.ZPL else PrintLang.PDF

/** Отпечаток «единственный :9100 в сети, который не офисный A4». */
const val ONLY_9100 = "only9100"

/** Найденный в сети принтер. [lang] — предположение по модели (SNMP / NSD / ~HI). */
data class FoundPrinter(
    val host: String,
    val port: Int,
    val fingerprint: String,
    val name: String,
    val role: LabelKind,
    val lang: PrintLang = defaultLang(role),
)

/** Принтер недоступен; [reason] — по-людськи, що перевірити. */
class PrinterNotFoundException(val printer: SavedPrinter, val reason: String) : Exception(reason)

fun formatPrice(v: Double): String =
    if (v == Math.floor(v)) v.toLong().toString() else String.format(java.util.Locale.US, "%.2f", v).replace('.', ',')
