package ua.chudo.scanner.data

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.FilterInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Потокове читання xlsx: рядок за рядком, без читання аркуша в пам'ять (аркуш 1С буває ~100 МБ XML).
 * Частини zip порівнюються без урахування регістру: 1С пише `xl/SharedStrings.xml`.
 * Порядок частин у zip будь-який, тому [open] викликається двічі: спершу рядки (sharedStrings) і номер
 * першого аркуша, потім сам аркуш.
 */
object Xlsx {

    /** Звідки брати XmlPullParser; у JVM-тестах android.util.Xml недоступний — підставляють kXML. */
    internal var newParser: () -> XmlPullParser = { Xml.newPullParser() }

    /** [onRow] отримує номер рядка (з 1, як у Excel) і клітинки з порожніми рядками на місці пропусків. */
    fun read(open: () -> InputStream, onRow: (Int, List<String>) -> Unit) {
        var strings: List<String> = emptyList()
        var sheetName: String? = null
        var sheetNo = Int.MAX_VALUE
        eachEntry(open) { name, zip ->
            val lower = name.lowercase()
            when {
                lower == SHARED_STRINGS -> strings = readSharedStrings(parser(zip))
                lower.startsWith(SHEET_PREFIX) && lower.endsWith(".xml") -> {
                    val n = lower.removePrefix(SHEET_PREFIX).removeSuffix(".xml").toIntOrNull() ?: 999
                    if (sheetName == null || n < sheetNo) { sheetName = name; sheetNo = n }
                }
            }
        }
        val target = sheetName ?: throw StockFileException("У файлі Excel немає аркушів.")
        eachEntry(open) { name, zip -> if (name == target) readSheet(parser(zip), strings, onRow) }
    }

    fun isZip(b: ByteArray) = b.size > 4 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte()

    private inline fun eachEntry(open: () -> InputStream, block: (String, ZipInputStream) -> Unit) {
        ZipInputStream(open().buffered()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (!e.isDirectory) block(e.name, zip)
            }
        }
    }

    private fun parser(input: InputStream): XmlPullParser =
        newParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(skipBom(input), "UTF-8")
        }

    /**
     * 1С пише частини xlsx з міткою UTF-8 (EF BB BF) перед «<?xml»; kXML (і android.util.Xml) на ній падає:
     * «PI must not start with xml». Пропускаємо мітку, якщо вона є.
     */
    internal fun skipBom(input: InputStream): InputStream {
        val b = java.io.BufferedInputStream(input, 8192)
        b.mark(3)
        val head = ByteArray(3)
        var n = 0
        while (n < 3) {
            val r = b.read(head, n, 3 - n)
            if (r < 0) break
            n += r
        }
        val bom = n == 3 && head[0] == 0xEF.toByte() && head[1] == 0xBB.toByte() && head[2] == 0xBF.toByte()
        if (!bom) b.reset()
        return b
    }

    private fun readSharedStrings(p: XmlPullParser): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var inT = false
        var inRph = false // фонетичні підказки — не текст клітинки
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "si" -> sb.setLength(0)
                    "t" -> inT = true
                    "rPh" -> inRph = true
                }
                XmlPullParser.TEXT -> if (inT && !inRph) sb.append(p.text)
                XmlPullParser.END_TAG -> when (p.name) {
                    "si" -> out.add(sb.toString())
                    "t" -> inT = false
                    "rPh" -> inRph = false
                }
            }
        }
        return out
    }

    private fun readSheet(p: XmlPullParser, strings: List<String>, onRow: (Int, List<String>) -> Unit) {
        var row: ArrayList<String>? = null
        var rowNo = 0
        var col = 0
        var type: String? = null
        val value = StringBuilder()
        var inValue = false
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "row" -> {
                        rowNo = p.getAttributeValue(null, "r")?.toIntOrNull() ?: (rowNo + 1)
                        row = ArrayList()
                    }
                    "c" -> {
                        col = p.getAttributeValue(null, "r")?.let(::columnIndex) ?: (row?.size ?: 0)
                        type = p.getAttributeValue(null, "t")
                        value.setLength(0)
                    }
                    "v", "t" -> inValue = true
                }
                XmlPullParser.TEXT -> if (inValue) value.append(p.text)
                XmlPullParser.END_TAG -> when (p.name) {
                    "v", "t" -> inValue = false
                    "c" -> row?.let { r ->
                        val text = if (type == "s") strings.getOrNull(value.toString().trim().toIntOrNull() ?: -1).orEmpty() else value.toString()
                        while (r.size < col) r.add("")
                        if (r.size == col) r.add(text) else r[col] = text
                    }
                    "row" -> { row?.let { onRow(rowNo, it) }; row = null }
                }
            }
        }
    }

    /** "AB12" → 27. */
    internal fun columnIndex(ref: String): Int {
        var n = 0
        for (ch in ref) {
            if (!ch.isLetter()) break
            n = n * 26 + (ch.uppercaseChar() - 'A' + 1)
        }
        return n - 1
    }

    private const val SHARED_STRINGS = "xl/sharedstrings.xml"
    private const val SHEET_PREFIX = "xl/worksheets/sheet"
}

/** Рахує прочитані байти — для прогресу розбору великого файлу. */
class CountingInputStream(input: InputStream, private val onRead: (Long) -> Unit) : FilterInputStream(input) {
    var count = 0L
        private set

    override fun read(): Int = super.read().also { if (it >= 0) add(1) }

    override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) add(it.toLong()) }

    override fun skip(n: Long): Long = super.skip(n).also { if (it > 0) add(it) }

    private fun add(n: Long) {
        count += n
        onRead(count)
    }
}
