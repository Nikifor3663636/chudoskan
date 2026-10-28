package ua.chudo.scanner.print

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import javax.net.SocketFactory

/**
 * Картинка ценника → команды принтера. Все языки печатают одну и ту же монохромную картинку
 * (кириллица, штрихкод и цены уже нарисованы), поэтому шрифты принтера не нужны.
 */
object LabelEncoder {
    fun encode(lang: PrintLang, labels: List<Pair<MonoImage, Int>>, t: LabelTemplate): ByteArray {
        val out = ByteArrayOutputStream()
        if (lang == PrintLang.ESCPOS) out.write(byteArrayOf(0x1B, 0x40)) // ESC @ — сброс
        labels.forEach { (img, n) ->
            out.write(
                when (lang) {
                    PrintLang.ZPL -> Zpl.label(img, n)
                    PrintLang.TSPL -> Tspl.label(img, n, t)
                    PrintLang.EPL -> Epl.label(img, n)
                    PrintLang.ESCPOS -> EscPos.label(img, n)
                    PrintLang.PDF -> error("PDF — лише для A4")
                },
            )
        }
        return out.toByteArray()
    }
}

/** Zebra ZPL II (и эмуляция GZPL у Godex): ^GFA — hex, бит 1 = чёрная точка. */
object Zpl {
    private val HEX = "0123456789ABCDEF".toCharArray()

    fun label(img: MonoImage, copies: Int): ByteArray {
        val total = img.data.size
        val sb = StringBuilder(total * 2 + 96)
        sb.append("^XA^PW").append(img.width).append("^LL").append(img.height).append("^LH0,0^FO0,0")
        sb.append("^GFA,").append(total).append(',').append(total).append(',').append(img.bytesPerRow).append(',')
        for (b in img.data) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0xF])
        }
        sb.append("^FS^PQ").append(copies.coerceIn(1, 99)).append("^XZ\n")
        return sb.toString().toByteArray(Charsets.US_ASCII)
    }
}

/** TSC TSPL/TSPL2: BITMAP — двоичные данные, бит 0 = чёрная точка (инверсия). */
object Tspl {
    fun label(img: MonoImage, copies: Int, t: LabelTemplate): ByteArray {
        val out = ByteArrayOutputStream()
        val head = "SIZE ${t.widthMm} mm,${t.heightMm} mm\r\nGAP 2 mm,0 mm\r\nCLS\r\n" +
            "BITMAP 0,0,${img.bytesPerRow},${img.height},0,"
        out.write(head.toByteArray(Charsets.US_ASCII))
        out.write(inverted(img))
        out.write("\r\nPRINT 1,${copies.coerceIn(1, 99)}\r\n".toByteArray(Charsets.US_ASCII))
        return out.toByteArray()
    }
}

/** Eltron/Zebra EPL2: GW — двоичные данные, бит 0 = чёрная точка. */
object Epl {
    fun label(img: MonoImage, copies: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("\nN\nq${img.width}\nQ${img.height},24\nGW0,0,${img.bytesPerRow},${img.height},".toByteArray(Charsets.US_ASCII))
        out.write(inverted(img))
        out.write("\nP${copies.coerceIn(1, 99)}\n".toByteArray(Charsets.US_ASCII))
        return out.toByteArray()
    }
}

/** ESC/POS: GS v 0 — растровая картинка, бит 1 = чёрная точка; после неё подача бумаги. */
object EscPos {
    /** Ширина печати 58-мм принтеров (48 мм × 8 точек/мм). Шире — уменьшаем при рендере. */
    const val MAX_WIDTH = 384

    fun label(img: MonoImage, copies: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val bpr = img.bytesPerRow
        repeat(copies.coerceIn(1, 99)) {
            // Полосами по 256 строк: часть принтеров не принимает большую высоту за раз.
            var y = 0
            while (y < img.height) {
                val h = minOf(256, img.height - y)
                out.write(byteArrayOf(0x1D, 0x76, 0x30, 0, (bpr and 0xFF).toByte(), (bpr shr 8).toByte(), (h and 0xFF).toByte(), (h shr 8).toByte()))
                out.write(img.data, y * bpr, h * bpr)
                y += h
            }
            out.write(byteArrayOf(0x1B, 0x64, 4)) // ESC d 4 — подать 4 строки
        }
        return out.toByteArray()
    }
}

private fun inverted(img: MonoImage) = ByteArray(img.data.size) { (img.data[it].toInt() xor 0xFF).toByte() }

/** Сырой TCP (порт 9100). [factory] — сокеты конкретной сети (Wi-Fi принтера), иначе сеть по умолчанию. */
object RawTcp {
    suspend fun send(host: String, port: Int, bytes: ByteArray, timeoutMs: Int = 4000, factory: SocketFactory? = null) =
        withContext(Dispatchers.IO) {
            socket(factory).use { s ->
                s.connect(InetSocketAddress(host, port), timeoutMs)
                s.soTimeout = 15_000
                s.getOutputStream().apply { write(bytes); flush() }
                // Даём принтеру забрать данные до закрытия: часть принтеров обрывает задание на RST.
                runCatching { s.shutdownOutput() }
            }
        }

    suspend fun reachable(host: String, port: Int, timeoutMs: Int = 300, factory: SocketFactory? = null): Boolean =
        withContext(Dispatchers.IO) {
            runCatching { socket(factory).use { it.connect(InetSocketAddress(host, port), timeoutMs) } }.isSuccess
        }

    /** Отправить команду и прочитать ответ (до [timeoutMs]). `null` — нет ответа. */
    suspend fun query(host: String, port: Int, cmd: ByteArray, timeoutMs: Int = 800, factory: SocketFactory? = null): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                socket(factory).use { s ->
                    s.connect(InetSocketAddress(host, port), timeoutMs)
                    s.soTimeout = timeoutMs
                    s.getOutputStream().apply { write(cmd); flush() }
                    val buf = ByteArray(512)
                    val n = try { s.getInputStream().read(buf) } catch (_: SocketTimeoutException) { -1 }
                    if (n > 0) String(buf, 0, n, Charsets.ISO_8859_1) else null
                }
            }.getOrNull()
        }

    private fun socket(factory: SocketFactory?): Socket = factory?.createSocket() ?: Socket()
}
