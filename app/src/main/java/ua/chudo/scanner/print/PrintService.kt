package ua.chudo.scanner.print

import android.app.Activity
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Печать ценников: липкие — Godex (ZPL по TCP), обычные — Brother (PrintManager или прямой TCP). */
class PrintService(context: Context) {
    val store = PrinterStore(context)
    val discovery = PrinterDiscovery(context)

    /**
     * Рабочий адрес: сохранённый IP → нет ответа → тихий пересканирование /24 по отпечатку → обновляем IP.
     * `null` — принтер в сети не найден.
     */
    suspend fun ensureHost(p: SavedPrinter): String? {
        if (RawTcp.reachable(p.host, p.port, 800)) return p.host
        val host = discovery.locate(p) ?: return null
        store.upsert(p.copy(host = host))
        return host
    }

    suspend fun printSticky(p: SavedPrinter, items: List<QueueItem>, t: LabelTemplate) {
        val host = ensureHost(p) ?: throw PrinterNotFoundException(p)
        val labels = withContext(Dispatchers.Default) { items.map { TagRenderer.mono(it.tag, t) to it.copies } }
        printerFor(host, p.port).print(labels)
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

    /** «Пряма печать»: PDF сырым TCP на IP:9100 Brother. */
    suspend fun printPlainDirect(p: SavedPrinter, items: List<QueueItem>) {
        val host = ensureHost(p) ?: throw PrinterNotFoundException(p)
        RawTcp.send(host, p.port, plainPdf(items).first)
    }

    /** «Тест»: липкий — печатает пробную этикетку, обычный — проверяет связь. */
    suspend fun test(host: String, port: Int, role: LabelKind): Boolean {
        if (!RawTcp.reachable(host, port, 1500)) return false
        if (role == LabelKind.STICKY) {
            val tag = PriceTag("ЧудоСкан · тест", "123,45", "150", "TEST", "4820000000000")
            val img = withContext(Dispatchers.Default) { TagRenderer.mono(tag, LabelTemplate.STICKY_58x40) }
            printerFor(host, port).print(listOf(img to 1))
        }
        return true
    }

    private fun printerFor(host: String, port: Int): LabelPrinter = ZplPrinter(host, port)
}
