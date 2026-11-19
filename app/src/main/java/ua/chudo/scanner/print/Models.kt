package ua.chudo.scanner.print

/** Данные ценника. Цены — строкой как на ценнике («129,90»), чтобы их можно было править вручную. */
data class PriceTag(
    val name: String,
    val price: String,
    val oldPrice: String,
    val article: String,
    val barcode: String,
    val brand: String = "",
    /** Код 1С (BOC307585). */
    val code: String = "",
)

/**
 * Вид принтера: этикетки 40×24 (Godex) или лист A4 (Brother). [wire] — значение поля `kind` в реестре.
 */
enum class LabelKind(val wire: String, val title: String) {
    STICKY("label_40x24", "Липкі етикетки"),
    PLAIN("a4", "Звичайні цінники A4"),
    ;

    companion object {
        fun ofWire(s: String?): LabelKind? = entries.firstOrNull { it.wire == s }
    }
}

/** Размер этикетки для прежних языков (TSPL берёт его в SIZE). */
enum class LabelTemplate(val kind: LabelKind, val widthMm: Int, val heightMm: Int, val title: String) {
    STICKY_58x40(LabelKind.STICKY, 58, 40, "58×40"),
    STICKY_40x24(LabelKind.STICKY, 40, 24, "40×24"),
}

data class QueueItem(val tag: PriceTag, val copies: Int)

/** Язык команд принтера. По модели: Godex — EZPL, Brother — PCL; остальные — для совместимости. */
enum class PrintLang(val title: String) {
    EZPL("EZPL"),
    ZPL("ZPL"),
    TSPL("TSPL"),
    EPL("EPL"),
    ESCPOS("ESC/POS"),
    PCL("PCL"),
}

/** Как телефон достаёт до принтера. [wire] — поле `transport` в реестре. */
enum class Link(val wire: String) {
    /** Сырой TCP 9100 в сети магазина. */
    TCP("tcp"),

    /** Принтер компьютера магазина: задание в Firestore `printJobs`, печатает ПК-агент. */
    AGENT("agent"),

    /** Кабель USB OTG к этому телефону (в общий реестр не пишется). */
    USB("usb"),
    ;

    companion object {
        fun ofWire(s: String?): Link = entries.firstOrNull { it.wire == s } ?: TCP
    }
}

/**
 * Принтер магазина — документ `shops/{shopId}/printers/{id}`.
 * [id] — MAC (ifPhysAddress), иначе модель+имя: смена IP не создаёт дубль.
 * [density]/[speed] — только у Godex, задаёт администратор; `null` — как в самотесте принтера (команды не шлём).
 */
data class PrinterRecord(
    val id: String,
    val model: String,
    val kind: LabelKind,
    val ip: String = "",
    val port: Int = 9100,
    val transport: Link = Link.TCP,
    val agentId: String = "",
    val printerName: String = "",
    val lastSeen: Long = 0,
    val lastOkBy: String = "",
    val density: Int? = null,
    val speed: Int? = null,
    /** Только для [Link.USB] на этом телефоне. */
    val usbVendor: Int = 0,
    val usbProduct: Int = 0,
    val name: String = "",
    val computerName: String = "",
) {
    val displayName: String get() = name.takeIf { it.isNotBlank() }
        ?: listOf(model, address).filter { it.isNotBlank() }.joinToString(" · ")
    /** Язык по модели: Godex — EZPL, Brother и прочие A4 — PCL. */
    val lang: PrintLang
        get() = if (kind == LabelKind.PLAIN) PrintLang.PCL else guessByModel(model)?.second?.takeIf { it != PrintLang.PCL } ?: PrintLang.EZPL

    val address: String
        get() = when (transport) {
            Link.TCP -> if (port == 9100) ip else "$ip:$port"
            Link.AGENT -> "через комп'ютер ${computerName.ifBlank { agentId }}"
            Link.USB -> "USB"
        }
}

/**
 * Устройство, найденное поиском. [kind] = null — не узнали (на 9100 что-то есть), в реестр не пишем,
 * показываем только администратору.
 */
data class Discovered(
    val ip: String,
    val port: Int = 9100,
    val model: String? = null,
    val mac: String? = null,
    val kind: LabelKind? = null,
    /** Имя из mDNS (Brother: «Brother MFC-L2700DW series»). */
    val name: String? = null,
)

fun formatPrice(v: Double): String =
    if (v == Math.floor(v)) v.toLong().toString() else String.format(java.util.Locale.US, "%.2f", v).replace('.', ',')
