package ua.chudo.scanner.print

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import androidx.core.graphics.scale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Состояние принтера: [ok] = null — не проверяли (Wi-Fi принтера / Bluetooth проверяются тестом). */
data class PrinterStatus(val ok: Boolean?, val text: String)

/**
 * Печать ценников на любой принтер:
 * - липкие — картинка ценника в языке принтера ([PrintLang]) по сети, Wi-Fi принтера или Bluetooth;
 * - обычные (A4) — системный друк Android (любой принтер) или PDF напрямую.
 */
class PrintService(context: Context) {
    val store = PrinterStore(context)
    val discovery = PrinterDiscovery(context)
    val wifi = WifiDirectLink(context)
    val bluetooth = BluetoothLink(context)

    /**
     * Рабочий адрес сетевого принтера: сохранённый IP → нет ответа → тихий пересканирование /24
     * по отпечатку → обновляем IP. `null` — принтер в сети не найден.
     */
    suspend fun ensureHost(p: SavedPrinter): String? {
        if (RawTcp.reachable(p.host, p.port, 800)) return p.host
        if (discovery.localIp() == null) return null
        val host = discovery.locate(p) ?: return null
        store.upsert(p.copy(host = host))
        return host
    }

    /** Доставить байты на принтер любым способом подключения. */
    private suspend fun deliver(p: SavedPrinter, bytes: ByteArray) {
        when (p.transport) {
            Transport.LAN -> {
                val host = ensureHost(p) ?: throw PrinterNotFoundException(
                    p,
                    if (discovery.localIp() == null) "Телефон не в Wi-Fi. Підключись до Wi-Fi магазину."
                    else "Принтер «${p.name}» не відповідає. Перевір, що він увімкнений і в тій самій мережі, що й телефон.",
                )
                RawTcp.send(host, p.port, bytes)
            }
            Transport.WIFI_DIRECT -> wifi.use(p.ssid, p.pass, p.host, p.port) { net, host ->
                RawTcp.send(host, p.port, bytes, factory = net.socketFactory)
            }
            Transport.BLUETOOTH -> bluetooth.send(p.host, bytes)
        }
    }

    private fun images(items: List<QueueItem>, t: LabelTemplate, lang: PrintLang): List<Pair<MonoImage, Int>> =
        items.map { q ->
            var bmp: Bitmap = TagRenderer.render(q.tag, t)
            if (lang == PrintLang.ESCPOS && bmp.width > EscPos.MAX_WIDTH) {
                bmp = bmp.scale(EscPos.MAX_WIDTH, bmp.height * EscPos.MAX_WIDTH / bmp.width)
            }
            TagRenderer.toMono(bmp) to q.copies
        }

    suspend fun printSticky(p: SavedPrinter, items: List<QueueItem>, t: LabelTemplate) {
        val lang = p.lang.takeIf { it != PrintLang.PDF } ?: PrintLang.ZPL
        val bytes = withContext(Dispatchers.Default) { LabelEncoder.encode(lang, images(items, t, lang), t) }
        deliver(p, bytes)
    }

    /** PDF A4 и число страниц. */
    suspend fun plainPdf(items: List<QueueItem>): Pair<ByteArray, Int> = withContext(Dispatchers.Default) {
        val bitmaps = items.flatMap { q ->
            val b = TagRenderer.mono(q.tag, LabelTemplate.A4_70x37).toBitmap()
            List(q.copies) { b }
        }
        A4Sheet.pdf(bitmaps) to A4Sheet.pages(bitmaps.size)
    }

    suspend fun printPlainSystem(activity: Activity, items: List<QueueItem>) {
        val (pdf, pages) = plainPdf(items)
        A4Sheet.systemPrint(activity, "Цінники", pdf, pages)
    }

    /** «Пряма печать» A4: PDF сырыми байтами (Brother, HP и большинство офисных лазерных). */
    suspend fun printPlainDirect(p: SavedPrinter, items: List<QueueItem>) {
        deliver(p, plainPdf(items).first)
    }

    /**
     * «Тест»: липкий — печатает пробную этикетку на выбранном языке (сразу видно, подходит ли язык),
     * обычный — проверяет связь (лист A4 зря не тратим).
     */
    suspend fun test(p: SavedPrinter): String {
        if (p.role == LabelKind.STICKY) {
            val tag = PriceTag("ЧудоСкан · тест ${p.lang.title}", "123,45", "150", "TEST", "4820000000000")
            printSticky(p, listOf(QueueItem(tag, 1)), LabelTemplate.STICKY_58x40)
            return "Тестову етикетку надіслано (${p.lang.title}). Якщо вийшла порожня або з «кракозябрами» — обери іншу мову принтера і натисни «Тест» ще раз."
        }
        return when (p.transport) {
            Transport.LAN -> if (ensureHost(p) != null) "Принтер відповідає." else "Немає відповіді від ${p.host}:${p.port}."
            Transport.WIFI_DIRECT -> wifi.use(p.ssid, p.pass, p.host, p.port) { _, host -> "Принтер відповідає ($host у мережі «${p.ssid}»)." }
            Transport.BLUETOOTH -> if (bluetooth.reachable(p.host)) "Принтер відповідає по Bluetooth." else "Немає зв'язку по Bluetooth."
        }
    }

    /** Быстрая проверка для списка принтеров. Wi-Fi принтера не трогаем: подключение показывает системный запрос. */
    suspend fun status(p: SavedPrinter): PrinterStatus = when (p.transport) {
        Transport.LAN -> {
            val host = ensureHost(p)
            when {
                host == null -> PrinterStatus(false, "Не в мережі")
                p.lang == PrintLang.ZPL -> zplStatus(host, p.port) ?: PrinterStatus(true, "Онлайн")
                else -> PrinterStatus(true, "Онлайн")
            }
        }
        Transport.BLUETOOTH -> when {
            !bluetooth.enabled -> PrinterStatus(false, "Bluetooth вимкнено")
            bluetooth.bonded().none { it.second == p.host } -> PrinterStatus(false, "Не спарений з телефоном")
            else -> PrinterStatus(null, "Bluetooth · перевір тестом")
        }
        Transport.WIFI_DIRECT -> PrinterStatus(null, "Wi-Fi «${p.ssid}» · перевір тестом")
    }

    /** ZPL ~HS: папір, пауза, відкрита голова. `null` — принтер не отвечает на ~HS (например, Godex). */
    private suspend fun zplStatus(host: String, port: Int): PrinterStatus? {
        val raw = RawTcp.query(host, port, "~HS\r\n".toByteArray(Charsets.US_ASCII), 800) ?: return null
        val lines = raw.split('\u0002').map { it.substringBefore('\u0003').split(',') }.filter { it.size > 3 }
        val s1 = lines.getOrNull(0) ?: return null
        val s2 = lines.getOrNull(1)
        return when {
            s1.getOrNull(1)?.trim() == "1" -> PrinterStatus(false, "Закінчились етикетки")
            s2?.getOrNull(2)?.trim() == "1" -> PrinterStatus(false, "Відкрита кришка / голова")
            s1.getOrNull(2)?.trim() == "1" -> PrinterStatus(false, "На паузі")
            else -> PrinterStatus(true, "Онлайн, готовий")
        }
    }
}
