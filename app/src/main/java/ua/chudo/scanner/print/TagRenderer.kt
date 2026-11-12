package ua.chudo.scanner.print

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withTranslation
import uniffi.chudo_core.ean13Bars
import uniffi.chudo_core.ean13Full
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Монохромная картинка: бит 1 — чёрная точка, строки выровнены по байту (как ждёт ^GF). */
class MonoImage(val width: Int, val height: Int, val data: ByteArray) {
    val bytesPerRow get() = (width + 7) / 8

    fun toBitmap(): Bitmap {
        val px = IntArray(width * height)
        for (y in 0 until height) for (x in 0 until width) {
            val on = data[y * bytesPerRow + x / 8].toInt() and (0x80 ushr (x % 8)) != 0
            px[y * width + x] = if (on) Color.BLACK else Color.WHITE
        }
        return Bitmap.createBitmap(px, width, height, Bitmap.Config.ARGB_8888)
    }
}

/** Ценник: Canvas → Bitmap 203 dpi → 1 бит. */
object TagRenderer {
    const val DPI = 203

    fun px(mm: Float): Int = (mm * DPI / 25.4f).roundToInt()

    fun mono(tag: PriceTag, t: LabelTemplate): MonoImage = toMono(render(tag, t))

    fun render(tag: PriceTag, t: LabelTemplate): Bitmap {
        val w = px(t.widthMm.toFloat())
        val h = px(t.heightMm.toFloat())
        val bmp = createBitmap(w, h)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        val small = t.heightMm <= 20
        val pad = px(if (small) 1f else 1.5f).toFloat()
        val inner = w - 2 * pad

        // Низ: EAN-13 с цифрами.
        val code = tag.barcode.filter(Char::isDigit)
        val bars = code.takeIf { it.isNotEmpty() }?.let { runCatching { ean13Full(it) to ean13Bars(it) }.getOrNull() }
        val digitPaint = paint(h * if (small) 0.10f else 0.075f)
        val barsH = if (bars == null) 0f else h * if (small) 0.27f else 0.24f
        val bottom = if (bars == null) 0f else barsH + digitPaint.textSize
        if (bars != null) drawEan(c, bars.first, bars.second, pad, inner, h - pad - bottom, barsH, digitPaint)

        // Верх: название до 2 строк.
        val namePaint = paint(h * if (small) 0.12f else 0.095f)
        val name = tag.name.trim()
        var top = pad
        if (name.isNotEmpty()) {
            val layout = StaticLayout.Builder.obtain(name, 0, name.length, namePaint, inner.toInt())
                .setMaxLines(2).setEllipsize(TextUtils.TruncateAt.END).setIncludePad(false).build()
            c.withTranslation(pad, pad) { layout.draw(this) }
            top += layout.height
        }

        // Середина: слева старая цена (зачёркнута) и артикул, справа — крупная цена.
        val zoneTop = top + pad / 2
        val zoneBottom = h - pad - bottom - pad / 2
        val zoneH = max(zoneBottom - zoneTop, 1f)
        val old = tag.oldPrice.trim().takeIf { it.isNotEmpty() && it != tag.price.trim() }?.let { "$it ₴" }
        val article = tag.article.trim().takeIf { it.isNotEmpty() && !small }?.let { "Арт. $it" }
        val sidePaint = paint(min(zoneH * 0.32f, h * 0.1f))
        var sideW = 0f
        if (old != null || article != null) {
            val strike = paint(sidePaint.textSize).apply { isStrikeThruText = true }
            var y = zoneTop + sidePaint.textSize
            old?.let { c.drawText(it, pad, y, strike); sideW = max(sideW, strike.measureText(it)); y += sidePaint.textSize * 1.15f }
            article?.let { a ->
                val s = paint(sidePaint.textSize * 0.8f)
                c.drawText(a, pad, min(y, zoneBottom), s); sideW = max(sideW, s.measureText(a))
            }
            sideW += pad
        }
        val price = tag.price.trim().takeIf { it.isNotEmpty() }?.let { "$it ₴" }
        if (price != null) {
            val p = paint(zoneH * 0.95f).apply { typeface = Typeface.DEFAULT_BOLD }
            val avail = inner - sideW
            if (p.measureText(price) > avail) p.textSize *= avail / p.measureText(price)
            val fm = p.fontMetrics
            val baseline = zoneTop + (zoneH - (fm.descent - fm.ascent)) / 2 - fm.ascent
            c.drawText(price, pad + inner - p.measureText(price), baseline, p)
        }
        return bmp
    }

    private fun drawEan(c: Canvas, full: String, mods: List<Boolean>, pad: Float, inner: Float, top: Float, barsH: Float, digits: TextPaint) {
        val lead = digits.measureText("0") * 1.3f
        val module = max(1, ((inner - lead) / mods.size).toInt())
        val barsW = module * mods.size
        val x0 = (pad + lead + (inner - lead - barsW) / 2).roundToInt()
        val black = Paint().apply { color = Color.BLACK; isAntiAlias = false }
        val guards = setOf(0, 1, 2, 45, 46, 47, 48, 49, 92, 93, 94)
        mods.forEachIndexed { i, on ->
            if (!on) return@forEachIndexed
            val ext = if (i in guards) digits.textSize / 2 else 0f
            c.drawRect((x0 + i * module).toFloat(), top, (x0 + (i + 1) * module).toFloat(), top + barsH + ext, black)
        }
        val base = top + barsH + digits.textSize * 0.92f
        digits.textAlign = Paint.Align.CENTER
        c.drawText(full.substring(0, 1), x0 - lead / 2, base, digits)
        for (k in 0 until 6) {
            c.drawText(full.substring(1 + k, 2 + k), x0 + (3 + k * 7 + 3.5f) * module, base, digits)
            c.drawText(full.substring(7 + k, 8 + k), x0 + (50 + k * 7 + 3.5f) * module, base, digits)
        }
        digits.textAlign = Paint.Align.LEFT
    }

    private fun paint(size: Float) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = size }

    fun toMono(bmp: Bitmap): MonoImage {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val bpr = (w + 7) / 8
        val data = ByteArray(bpr * h)
        for (y in 0 until h) for (x in 0 until w) {
            val p = px[y * w + x]
            val lum = (Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114) / 1000
            if (lum < 128) data[y * bpr + x / 8] = (data[y * bpr + x / 8].toInt() or (0x80 ushr (x % 8))).toByte()
        }
        return MonoImage(w, h, data)
    }
}
