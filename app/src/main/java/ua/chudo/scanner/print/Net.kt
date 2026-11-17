package ua.chudo.scanner.print

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/** Подсети IPv4 в записи CIDR («192.0.2.0/24»). */
object Cidr {
    /** Самая большая подсеть, которую сканируем целиком: /22 = 1022 адреса. */
    const val MIN_PREFIX = 22

    /** Адреса узлов подсети без адреса сети и широковещательного. Мусор или слишком большая сеть — пусто. */
    fun hosts(cidr: String): List<String> {
        val (ip, prefix) = parse(cidr) ?: return emptyList()
        if (prefix < MIN_PREFIX) return emptyList()
        val mask = if (prefix == 0) 0L else (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
        val net = ip and mask
        val size = 1L shl (32 - prefix)
        return when (prefix) {
            32 -> listOf(toIp(net))
            31 -> listOf(toIp(net), toIp(net + 1))
            else -> (1 until size - 1).map { toIp(net + it) }
        }
    }

    /** Нормализованная запись: адрес сети + префикс («192.0.2.17/24» → «192.0.2.0/24»). */
    fun normalize(cidr: String): String? {
        val (ip, prefix) = parse(cidr) ?: return null
        val mask = if (prefix == 0) 0L else (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
        return "${toIp(ip and mask)}/$prefix"
    }

    /** /24 вокруг адреса принтера: так подсеть найденного принтера пополняет `printerSubnets`. */
    fun slash24(ip: String): String? = parseIp(ip)?.let { "${toIp(it and 0xFFFFFF00L)}/24" }

    fun contains(cidr: String, ip: String): Boolean {
        val (net, prefix) = parse(cidr) ?: return false
        val a = parseIp(ip) ?: return false
        val mask = if (prefix == 0) 0L else (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
        return (a and mask) == (net and mask)
    }

    private fun parse(cidr: String): Pair<Long, Int>? {
        val parts = cidr.trim().split('/')
        if (parts.size != 2) return null
        val ip = parseIp(parts[0]) ?: return null
        val prefix = parts[1].toIntOrNull()?.takeIf { it in 0..32 } ?: return null
        return ip to prefix
    }

    fun parseIp(s: String): Long? {
        val o = s.trim().split('.')
        if (o.size != 4) return null
        var v = 0L
        for (p in o) {
            val n = p.toIntOrNull()?.takeIf { it in 0..255 && p.isNotEmpty() && p.length <= 3 } ?: return null
            v = (v shl 8) or n.toLong()
        }
        return v
    }

    private fun toIp(v: Long) = "${v shr 24 and 255}.${v shr 16 and 255}.${v shr 8 and 255}.${v and 255}"
}

/**
 * Веб-страница принтера (http://IP/): Godex не всегда отвечает на SNMP, но на своей странице пишет «GODEX» / модель.
 * Сырой сокет, а не HttpURLConnection: так не нужно разрешать приложению незашифрованный HTTP целиком.
 */
object HttpProbe {
    /** Начало ответа (до 16 КБ) или `null`, если за [timeoutMs] нет ответа. */
    suspend fun page(ip: String, timeoutMs: Int = 1000): String? = withContext(Dispatchers.IO) {
        runCatching {
            Socket().use { s ->
                s.connect(InetSocketAddress(ip, 80), timeoutMs)
                s.soTimeout = timeoutMs
                s.getOutputStream().apply {
                    write("GET / HTTP/1.0\r\nHost: $ip\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII)); flush()
                }
                val out = ByteArrayOutputStream()
                val buf = ByteArray(4096)
                val input = s.getInputStream()
                try {
                    while (out.size() < 16 * 1024) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                    }
                } catch (_: SocketTimeoutException) {
                }
                out.toString(Charsets.ISO_8859_1.name()).ifEmpty { null }
            }
        }.getOrNull()
    }

    /** Модель Godex по странице: «G500», иначе просто «Godex». `null` — не Godex. */
    fun godexModel(page: String?): String? {
        if (page == null) return null
        val up = page.uppercase()
        if ("GODEX" !in up && "G500" !in up) return null
        val m = Regex("""\b(G5\d\d|G3\d\d|EZ-?\d{3,4}|RT\d{3}i?)\b""", RegexOption.IGNORE_CASE).find(page)?.value?.uppercase()
        return listOfNotNull("Godex", m).joinToString(" ")
    }
}
