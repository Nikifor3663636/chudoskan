package ua.chudo.scanner.print

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/** Принтер этикеток. Сейчас — ZPL (Godex в режиме GZPL); позже — EzplPrinter. */
interface LabelPrinter {
    suspend fun print(labels: List<Pair<MonoImage, Int>>)
}

class ZplPrinter(private val host: String, private val port: Int) : LabelPrinter {
    override suspend fun print(labels: List<Pair<MonoImage, Int>>) {
        val out = ByteArrayOutputStream()
        withContext(Dispatchers.Default) { labels.forEach { (img, n) -> out.write(Zpl.label(img, n)) } }
        RawTcp.send(host, port, out.toByteArray())
    }
}

object Zpl {
    private val HEX = "0123456789ABCDEF".toCharArray()

    /** ^XA^PW^LL^FO0,0^GFA(bitmap)^PQ{n}^XZ */
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

/** Сырой TCP (порт 9100). */
object RawTcp {
    suspend fun send(host: String, port: Int, bytes: ByteArray, timeoutMs: Int = 4000) = withContext(Dispatchers.IO) {
        Socket().use { s ->
            s.connect(InetSocketAddress(host, port), timeoutMs)
            s.soTimeout = 15_000
            s.getOutputStream().apply { write(bytes); flush() }
        }
    }

    suspend fun reachable(host: String, port: Int, timeoutMs: Int = 300): Boolean = withContext(Dispatchers.IO) {
        runCatching { Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs) } }.isSuccess
    }

    /** Отправить команду и прочитать ответ (до [timeoutMs]). `null` — нет ответа. */
    suspend fun query(host: String, port: Int, cmd: ByteArray, timeoutMs: Int = 800): String? = withContext(Dispatchers.IO) {
        runCatching {
            Socket().use { s ->
                s.connect(InetSocketAddress(host, port), timeoutMs)
                s.soTimeout = timeoutMs
                s.getOutputStream().apply { write(cmd); flush() }
                val buf = ByteArray(512)
                val n = try { s.getInputStream().read(buf) } catch (_: SocketTimeoutException) { -1 }
                if (n > 0) String(buf, 0, n, Charsets.ISO_8859_1) else null
            }
        }.getOrNull()
    }
}
