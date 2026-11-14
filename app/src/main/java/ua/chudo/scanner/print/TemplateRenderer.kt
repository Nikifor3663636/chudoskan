package ua.chudo.scanner.print

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withRotation
import androidx.core.graphics.withTranslation
import uniffi.chudo_core.ean13Bars
import uniffi.chudo_core.ean13Full
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min

/**
 * Ценники как в 1С: только чёрный, шрифт без засечек с кириллицей.
 * Этикетка 40×25 — 320×200 точек (203 dpi); листовые — A4 300 dpi, ячейки без промежутков.
 */
object TemplateRenderer {
    const val SHEET_DPI = 300
    const val LABEL_W = 320
    const val LABEL_H = 200

    private val SANS = Typeface.SANS_SERIF
    private val BOLD = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)

    fun mm(v: Float, dpi: Int = SHEET_DPI) = v * dpi / 25.4f
    private fun pt(v: Float, dpi: Int = SHEET_DPI) = v * dpi / 72f

    private fun ean(code: String): Pair<String, List<Boolean>>? =
        code.filter(Char::isDigit).takeIf { it.isNotEmpty() }?.let { runCatching { ean13Full(it) to ean13Bars(it) }.getOrNull() }

    private fun paint(size: Float, bold: Boolean = false) =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = size; typeface = if (bold) BOLD else SANS }

    /** Текст до [maxLines] строк с «…»; возвращает высоту. */
    private fun block(c: Canvas, s: String, p: TextPaint, x: Float, y: Float, w: Float, maxLines: Int): Float {
        if (s.isBlank() || w < 1) return 0f
        val l = StaticLayout.Builder.obtain(s, 0, s.length, p, w.toInt())
            .setMaxLines(maxLines).setEllipsize(TextUtils.TruncateAt.END).setIncludePad(false).build()
        c.withTranslation(x, y) { l.draw(this) }
        return l.height.toFloat()
    }

    private fun fit(s: String, p: TextPaint, w: Float): String =
        TextUtils.ellipsize(s, p, w, TextUtils.TruncateAt.END).toString()

    private fun capHeight(p: Paint): Float = Rect().also { p.getTextBounds("0", 0, 1, it) }.height().toFloat()

    // ---------- A) Этикетка 40×25 ----------

    fun label(item: LabelItem): Bitmap {
        val dpi = TagRenderer.DPI
        val w = LABEL_W.toFloat()
        val h = LABEL_H.toFloat()
        val bmp = createBitmap(LABEL_W, LABEL_H)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        val pad = 6f
        val inner = w - 2 * pad
        val topH = h * 0.45f

        // Верх: EAN-13 на всю ширину, нет штрихкода — крупно артикул.
        val bars = ean(item.barcode)
        if (bars != null) {
            val digits = paint(pt(6.5f, dpi))
            TagRenderer.drawEan(c, bars.first, bars.second, pad, inner, 3f, topH - 3f - digits.textSize, digits)
        } else if (item.article.isNotEmpty()) {
            val p = paint(topH * 0.6f, bold = true)
            if (p.measureText(item.article) > inner) p.textSize *= inner / p.measureText(item.article)
            p.textAlign = Paint.Align.CENTER
            c.drawText(item.article, w / 2, (topH + capHeight(p)) / 2, p)
        }

        // Низ: «2 299,00» жирно + « грн».
        val big = paint(pt(12f, dpi), bold = true)
        val unit = paint(pt(9f, dpi))
        val priceTop = h - pad - big.textSize
        item.price?.let { v ->
            val s = PriceFormat.label(v)
            val total = big.measureText(s) + unit.measureText(" грн")
            val x = (w - total) / 2
            val base = h - pad - big.fontMetrics.descent / 2
            c.drawText(s, x, base, big)
            c.drawText(" грн", x + big.measureText(s), base, unit)
        }

        // Середина: слева бренд и «арт …», справа «ТМ бренд, назва».
        val small = paint(pt(6f, dpi))
        val midTop = topH + 2f
        val lines = max(1, ((priceTop - midTop) / (small.fontSpacing)).toInt()).coerceAtMost(3)
        val leftW = inner * 0.3f
        val left = listOfNotNull(item.brand.ifEmpty { null }, item.article.ifEmpty { null }?.let { "арт $it" }).joinToString("\n")
        block(c, left, small, pad, midTop, leftW - 4f, lines)
        block(c, item.title(), small, pad + leftW, midTop, inner - leftW, lines)
        return bmp
    }

    // ---------- B, C) Листы A4 ----------

    /** Размер листа в точках 300 dpi. */
    fun pageSize(t: Template): Pair<Int, Int> {
        val l = requireNotNull(t.layout)
        return mm(l.pageWmm).toInt() to mm(l.pageHmm).toInt()
    }

    fun pages(cells: List<LabelItem>, t: Template): List<List<LabelItem>> = cells.chunked(requireNotNull(t.layout).perPage)

    /** Лист целиком; [scale] < 1 — для предпросмотра. */
    fun pageBitmap(cells: List<LabelItem>, t: Template, promo: Boolean, scale: Float = 1f): Bitmap {
        val (w, h) = pageSize(t)
        val bmp = createBitmap((w * scale).toInt(), (h * scale).toInt(), if (scale < 1f) Bitmap.Config.ARGB_8888 else Bitmap.Config.RGB_565)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        c.scale(scale, scale)
        drawPage(c, cells, t, promo)
        return bmp
    }

    /** PDF (векторный): листы A4, каждый — [Template.layout].perPage ячеек. */
    fun pdf(cells: List<LabelItem>, t: Template, promo: Boolean): Pair<ByteArray, Int> {
        val l = requireNotNull(t.layout)
        val doc = PdfDocument()
        val pw = (l.pageWmm * 72 / 25.4f).toInt()
        val ph = (l.pageHmm * 72 / 25.4f).toInt()
        val chunks = pages(cells, t)
        chunks.forEachIndexed { i, chunk ->
            val page = doc.startPage(PdfDocument.PageInfo.Builder(pw, ph, i + 1).create())
            page.canvas.scale(72f / SHEET_DPI, 72f / SHEET_DPI)
            drawPage(page.canvas, chunk, t, promo)
            doc.finishPage(page)
        }
        return ByteArrayOutputStream().also { doc.writeTo(it); doc.close() }.toByteArray() to chunks.size
    }

    fun drawPage(c: Canvas, cells: List<LabelItem>, t: Template, promo: Boolean) {
        val l = requireNotNull(t.layout)
        val cw = mm(l.cellWmm)
        val ch = mm(l.cellHmm)
        cells.take(l.perPage).forEachIndexed { i, item ->
            val cell = l.cell(i)
            val x = mm(l.marginXmm) + cell.col * cw
            val y = mm(l.marginYmm) + cell.row * ch
            c.withTranslation(x, y) {
                frame(this, cw, ch)
                val p = promo && item.canPromo
                if (t == Template.BIG) big(this, item, p, cw, ch) else tag(this, item, p, cw, ch)
            }
        }
    }

    private fun frame(c: Canvas, w: Float, h: Float) {
        val p = Paint().apply {
            color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 2f
            pathEffect = DashPathEffect(floatArrayOf(14f, 10f), 0f)
        }
        c.drawRect(1f, 1f, w - 1f, h - 1f, p)
    }

    /** Вертикальный EAN-13 (читается снизу вверх), цифры справа от штрихов. */
    private fun verticalEan(c: Canvas, bars: Pair<String, List<Boolean>>, x: Float, bottom: Float, length: Float, thick: Float, digits: TextPaint) {
        c.withTranslation(x, bottom) {
            withRotation(-90f) { TagRenderer.drawEan(this, bars.first, bars.second, 0f, length, 0f, thick - digits.textSize, digits) }
        }
    }

    /**
     * Цена: целая часть жирно, «00» индексом справа, «грн» под «00»; для акции выше — старая цена,
     * перечёркнутая косой линией. Размер — максимальный, чтобы [sample] (или сама цена) влезла в зону.
     */
    private fun price(c: Canvas, item: LabelItem, promo: Boolean, x: Float, top: Float, w: Float, bottom: Float, maxSize: Float, sample: String? = null) {
        val v = item.price ?: return
        val (whole, kop) = PriceFormat.tag(v)
        val probe = paint(100f, bold = true)
        val small = paint(42f)
        val widthAt100 = probe.measureText(sample?.takeIf { it.length > whole.length } ?: whole) + 6f + max(small.measureText("00"), small.measureText("грн"))
        val heightAt100 = capHeight(probe) * (if (promo) 1.55f else 1f) + 4f
        val size = min(maxSize, min(w / widthAt100, (bottom - top) / heightAt100) * 100f)
        val big = paint(size, bold = true)
        val sub = paint(size * 0.42f)
        val cap = capHeight(big)
        val blockW = big.measureText(whole) + size * 0.06f + max(sub.measureText(kop), sub.measureText("грн"))
        val x0 = x + (w - blockW) / 2
        val base = bottom - 2f
        c.drawText(whole, x0, base, big)
        val sx = x0 + big.measureText(whole) + size * 0.06f
        c.drawText(kop, sx, base - cap + capHeight(sub), sub)
        c.drawText("грн", sx, base, sub)
        if (promo) {
            val old = paint(size * 0.45f)
            val s = PriceFormat.label(item.oldPrice ?: return)
            val ow = old.measureText(s)
            val ob = base - cap - size * 0.12f
            val oc = capHeight(old)
            c.drawText(s, x0, ob, old)
            val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; strokeWidth = max(2f, size * 0.035f) }
            c.drawLine(x0 - 4f, ob + 4f, x0 + ow + 4f, ob - oc - 4f, line)
        }
    }

    /** B) Цінник 68×38. */
    private fun tag(c: Canvas, item: LabelItem, promo: Boolean, w: Float, h: Float) {
        val pad = mm(1.5f)
        val tiny = paint(pt(5f))

        // Слева вертикально «{code} {article}».
        val side = listOf(item.code, item.article).filter { it.isNotEmpty() }.joinToString(" ")
        var left = pad
        if (side.isNotEmpty()) {
            c.withTranslation(pad - tiny.fontMetrics.ascent, h - pad) {
                withRotation(-90f) { drawText(fit(side, tiny, h - 2 * pad), 0f, 0f, tiny) }
            }
            left += tiny.textSize * 1.3f
        }

        // Верх: «ТМ бренд, назва, арт. …» до 3 строк.
        val textBottom = pad + block(c, item.title(withArticle = true), paint(pt(9f), bold = true), left, pad, w - left - pad, 3)

        // Справа: вертикальный EAN-13, под ним подпись.
        val caption = "Ціна за одиницю товару"
        val capBase = h - pad
        c.drawText(caption, w - pad - tiny.measureText(caption), capBase, tiny)
        val zoneTop = textBottom + mm(1f)
        val zoneBottom = capBase - tiny.textSize - mm(0.5f)
        var right = w - pad
        ean(item.barcode)?.let { bars ->
            val thick = mm(9f)
            right -= thick
            verticalEan(c, bars, right, zoneBottom, zoneBottom - zoneTop, thick, paint(pt(5.5f)))
            right -= mm(1f)
        }
        price(c, item, promo, left, zoneTop, right - left, h - pad, pt(40f))
    }

    /** C) Крупногабарит 148×105. */
    private fun big(c: Canvas, item: LabelItem, promo: Boolean, w: Float, h: Float) {
        val pad = mm(3f)
        val topH = h * 0.4f
        var textRight = w - pad
        ean(item.barcode)?.let { bars ->
            val thick = mm(15f)
            textRight -= thick
            verticalEan(c, bars, textRight, topH, topH - pad, thick, paint(pt(9f)))
            textRight -= mm(2f)
        }
        block(c, item.title(), paint(pt(14f)), pad, pad, textRight - pad, 4)

        val codes = paint(pt(7f))
        val caption = paint(pt(6f))
        val base = h - pad
        val side = listOf(item.code, item.article).filter { it.isNotEmpty() }.joinToString(", ")
        c.drawText(fit(side, codes, w / 2 - pad), pad, base, codes)
        val capText = "Ціна за одиницю товару"
        c.drawText(capText, w - pad - caption.measureText(capText), base, caption)
        price(c, item, promo, pad, topH + mm(2f), w - 2 * pad, base - codes.textSize * 1.4f, Float.MAX_VALUE, sample = "99 999")
    }
}
