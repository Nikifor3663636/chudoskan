package ua.chudo.scanner.print

import java.io.ByteArrayOutputStream

/** Одна страница/этикетка задания: готовая 1-битная картинка и сколько копий. */
data class PrintPage(val image: MonoImage, val copies: Int = 1)

/** Задание на печать: картинки уже нарисованы ([LabelRenderer]), язык только упаковывает их в команды. */
data class PrintJob(val pages: List<PrintPage>)

/** Язык команд принтера: картинки → байты. Без Android — проверяется unit-тестами. */
interface PrinterLanguage {
    fun render(job: PrintJob): ByteArray
}

/**
 * Носитель Godex магазина (самотест G500: `^W40 ^Q24,2` — этикетки 40×24 мм, зазор 2 мм, датчик зазора).
 * [darkness]/[speed] = null — команды не шлём, принтер печатает со своими значениями (как в самотесте);
 * менять их может только администратор.
 */
data class EzplSettings(
    val widthMm: Int = 40,
    val lengthMm: Int = 24,
    /** Зазор между этикетками, мм; 0 — непрерывная лента (`^Q24,0,0`). */
    val gapMm: Int = 2,
    /** `^H`: 00…19. */
    val darkness: Int? = null,
    /** `^S`: дюйм/с. */
    val speed: Int? = null,
)

/**
 * Godex EZPL: вся этикетка — одна 1-битная картинка (кириллицы в шрифтах принтера нет, Code Page 850).
 * Источник синтаксиса — «EZPL Programmer's Manual», GoDEX, ред. J от 26.02.2018:
 * ```
 * ^Q24,2        Setup Commands → «^Qx,y(,z) - Label length»: этикетки с зазором, x — длина, y — зазор (мм);
 *               непрерывная лента: y = 0, z = подача (^Q24,0,0)
 * ^W40          Setup Commands → «^Wx - Label width setting», мм
 * ^H10          Setup Commands → «^Hx - Print darkness setting», 00…19 (только если задал администратор)
 * ^S3           Setup Commands → «^Sx - Speed setting», дюйм/с (только если задал администратор)
 * ^P2           Setup Commands → «^Px - Number of pages printed», 1…32767
 * ^L            Setup Commands → «^Lx - The start sign of label format»
 * Q0,0,40,192   Label Format Commands → «Qx,y,width,height - Pattern command»: width — байты, height — точки;
 * <7680 байт>   сразу за командой width×height байт картинки, бит 1 — точка печатается
 *               (Appendix II «Command Examples», пример 13 «Pattern command setting»)
 * E             Label Format Commands → «E - Terminate label formatting mode and print label»
 * ```
 * Каждая команда заканчивается CR (Introduction → «Rules and syntax»); шлём CR LF, как в примерах мануала.
 */
class EzplGraphic(private val s: EzplSettings = EzplSettings()) : PrinterLanguage {
    override fun render(job: PrintJob): ByteArray {
        val out = ByteArrayOutputStream()
        job.pages.forEach { (img, copies) ->
            out.ascii(header(copies))
            out.ascii("Q0,0,${img.bytesPerRow},${img.height}$CRLF")
            out.write(img.data)
            out.ascii("${CRLF}E$CRLF")
        }
        return out.toByteArray()
    }

    /** Настройки носителя и начало формата — всё до команды картинки. */
    fun header(copies: Int): String = buildString {
        append(if (s.gapMm > 0) "^Q${s.lengthMm},${s.gapMm}$CRLF" else "^Q${s.lengthMm},0,0$CRLF")
        append("^W${s.widthMm}$CRLF")
        s.darkness?.let { append("^H${it.coerceIn(0, 19).toString().padStart(2, '0')}$CRLF") }
        s.speed?.let { append("^S${it.coerceIn(2, 7)}$CRLF") }
        append("^P${copies.coerceIn(1, 32767)}$CRLF")
        append("^L$CRLF")
    }

    private companion object {
        const val CRLF = "\r\n"
    }
}

/**
 * HP PCL 5 растр для офисных лазерных (Brother HL-L2360DN: эмуляция PCL6 = PCL5e + PCL XL).
 * Страница A4 300 dpi, бит 1 — точка печатается, строки сжаты TIFF PackBits (`ESC *b2M`),
 * пустые строки пропускаются (`ESC *b#Y`). Задание обёрнуто в PJL, чтобы принтер включил PCL сам.
 */
class PclRaster(
    /** Левый край логической страницы PCL от края листа A4 portrait при 300 dpi (HP PCL 5 Technical Reference). */
    private val logicalOffsetDots: Int = A4_LOGICAL_OFFSET,
    private val logicalWidthDots: Int = A4_LOGICAL_WIDTH,
) : PrinterLanguage {
    override fun render(job: PrintJob): ByteArray {
        val out = ByteArrayOutputStream()
        out.ascii("$ESC%-12345X@PJL JOB NAME=\"ChudoScan\"\r\n@PJL ENTER LANGUAGE=PCL\r\n")
        out.ascii(
            "${ESC}E" + // сброс
                "$ESC&l26A" + // A4
                "$ESC&l0O" + // книжная
                "$ESC&l0E" + // верхнее поле 0
                "$ESC*t300R", // 300 dpi растр
        )
        job.pages.forEach { (img, copies) ->
            out.ascii("$ESC&l${copies.coerceIn(1, 99)}X")
            page(out, img)
            out.write(0x0C) // FF — лист готов
        }
        out.ascii("${ESC}E$ESC%-12345X@PJL EOJ NAME=\"ChudoScan\"\r\n$ESC%-12345X")
        return out.toByteArray()
    }

    private fun page(out: ByteArrayOutputStream, img: MonoImage) {
        // Картинка — весь лист от края; логическая страница PCL начинается на logicalOffsetDots правее.
        val from = minOf(logicalOffsetDots, img.width)
        val width = minOf(img.width - from, logicalWidthDots)
        val rowBytes = (width + 7) / 8
        out.ascii("$ESC*p0x0Y$ESC*r${width}S$ESC*r${img.height}T$ESC*b2M$ESC*r1A")
        val row = ByteArray(rowBytes)
        var skipped = 0
        for (y in 0 until img.height) {
            shiftRow(img, y, from, width, row)
            var n = rowBytes
            while (n > 0 && row[n - 1].toInt() == 0) n--
            if (n == 0) { skipped++; continue }
            if (skipped > 0) { out.ascii("$ESC*b${skipped}Y"); skipped = 0 }
            val packed = PackBits.encode(row, n)
            out.ascii("$ESC*b${packed.size}W")
            out.write(packed)
        }
        out.ascii("$ESC*rC")
    }

    /** Строка [y] со сдвигом на [from] точек влево. */
    private fun shiftRow(img: MonoImage, y: Int, from: Int, width: Int, dst: ByteArray) {
        dst.fill(0)
        if (from % 8 == 0) {
            System.arraycopy(img.data, y * img.bytesPerRow + from / 8, dst, 0, minOf(dst.size, img.bytesPerRow - from / 8))
            if (width % 8 != 0) dst[dst.size - 1] = (dst[dst.size - 1].toInt() and (0xFF shl (8 - width % 8))).toByte()
            return
        }
        for (x in 0 until width) if (img.isBlack(from + x, y)) dst[x / 8] = (dst[x / 8].toInt() or (0x80 ushr (x % 8))).toByte()
    }

    companion object {
        const val ESC = '\u001B'

        /** A4 portrait, 300 dpi: логическая страница 2338 точек, отступ 71 от края листа. */
        const val A4_LOGICAL_OFFSET = 71
        const val A4_LOGICAL_WIDTH = 2338
    }
}

/** TIFF PackBits (PCL compression method 2). */
object PackBits {
    fun encode(src: ByteArray, len: Int = src.size): ByteArray {
        val out = ByteArrayOutputStream(len + len / 128 + 1)
        var i = 0
        while (i < len) {
            // Повтор: 2…128 одинаковых байт → (1 − n), байт.
            var run = 1
            while (i + run < len && run < 128 && src[i + run] == src[i]) run++
            if (run >= 2) {
                out.write(1 - run)
                out.write(src[i].toInt())
                i += run
                continue
            }
            // Литералы: до 128 байт, пока не начнётся повтор.
            val start = i
            while (i < len && i - start < 128 && !(i + 1 < len && src[i] == src[i + 1])) i++
            out.write(i - start - 1)
            out.write(src, start, i - start)
        }
        return out.toByteArray()
    }
}

/** Прежние языки этикеток (Zebra, TSC, Eltron, ESC/POS) — для принтеров кроме Godex и Brother. */
class LegacyLabelLanguage(private val lang: PrintLang, private val template: LabelTemplate = LabelTemplate.STICKY_40x24) : PrinterLanguage {
    override fun render(job: PrintJob): ByteArray = LabelEncoder.encode(lang, job.pages.map { it.image to it.copies }, template)
}

private fun ByteArrayOutputStream.ascii(s: String) = write(s.toByteArray(Charsets.ISO_8859_1))
