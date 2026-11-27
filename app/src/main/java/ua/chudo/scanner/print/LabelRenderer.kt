package ua.chudo.scanner.print

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withRotation
import androidx.core.graphics.withTranslation
import ua.chudo.scanner.R
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Ценники и этикетки по макету (design/html, файли .dc.html; 1 мм = 8 px).
 * Масштаб под принтер: Godex 203 dpi ≈ 1:1, Brother 300 dpi — ×1.476.
 */
enum class LabelFormat(val title: String, val wPx: Int, val hPx: Int, val promo: Boolean) {
    /** Godex магазина: 40×24 мм = 320×192 точек; макет Label40x25.png ужат по высоте. */
    Label40x24("Етикетка 40×24", 320, 192, false),
    Tag68x38("Цінник 68×38", 544, 304, false),
    Tag68x38Promo("Цінник 68×38, акційний", 544, 304, true),
    TagBig("Крупногабарит", 1184, 840, false),
    TagBigPromo("Крупногабарит, акційний", 1184, 840, true),

    /** 140×100 мм, як у 1С: та сама верстка, що й 148×105, від країв. */
    TagBig140("Крупногабарит 140×100", 1120, 800, false),
    TagBig140Promo("Крупногабарит 140×100, акційний", 1120, 800, true),
    ;

    companion object {
        fun of(t: Template, promo: Boolean): LabelFormat = when (t) {
            Template.LABEL_40x24 -> Label40x24
            Template.TAG_68x38 -> if (promo) Tag68x38Promo else Tag68x38
            Template.BIG -> if (promo) TagBigPromo else TagBig
            Template.BIG_140x100 -> if (promo) TagBig140Promo else TagBig140
        }
    }
}

/** Цвет акционной бумаги — только для превью, на печать фон не идёт. */
const val PROMO_PAPER = 0xFFEEF26B.toInt()

/** Точек на миллиметр в макете: 8 px = 1 мм = 203.2 dpi. */
private const val DESIGN_DPI = 203.2f

/**
 * Один рендер на превью и на печать: [render] рисует чёрным по прозрачному Bitmap,
 * превью подкладает под него бумагу (белую или жёлтую), печать переводит его в 1 бит ([MonoImage.fromAlpha]).
 */
class LabelRenderer(context: Context) {
    private val res = context.applicationContext
    private val golos500: Typeface by lazy { font(R.font.golos_text_medium) }
    private val golos600: Typeface by lazy { font(R.font.golos_text_semibold) }
    private val mont700: Typeface by lazy { font(R.font.montserrat_bold) }
    private val mont800: Typeface by lazy { font(R.font.montserrat_extrabold) }
    private val roboto500: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    private fun font(id: Int): Typeface = runCatching { ResourcesCompat.getFont(res, id) }.getOrNull() ?: Typeface.SANS_SERIF

    /** Размер в точках принтера. */
    fun sizeDots(f: LabelFormat, dpi: Float): Pair<Int, Int> {
        val s = dpi / DESIGN_DPI
        return (f.wPx * s).roundToInt() to (f.hPx * s).roundToInt()
    }

    /** Ценник целиком: прозрачный фон, чёрные штрихи и текст. */
    fun render(item: LabelItem, f: LabelFormat, dpi: Float): Bitmap {
        val (w, h) = sizeDots(f, dpi)
        val bmp = createBitmap(w, h)
        draw(Canvas(bmp), item, f, dpi)
        return bmp
    }

    /** 1-битная картинка для печати — из того же Bitmap, что и превью. */
    fun mono(item: LabelItem, f: LabelFormat, dpi: Float): MonoImage = MonoImage.fromAlpha(render(item, f, dpi))

    /** Рисует ценник в (0, 0) холста. Холст должен быть сдвинут на целое число точек — иначе поплывут штрихи. */
    fun draw(c: Canvas, item: LabelItem, f: LabelFormat, dpi: Float) {
        val k = Scale(dpi / DESIGN_DPI, moduleFor(dpi))
        when (f) {
            LabelFormat.Label40x24 -> label40x24(c, item, k)
            LabelFormat.Tag68x38, LabelFormat.Tag68x38Promo -> tag68x38(c, item, k, f.promo)
            LabelFormat.TagBig, LabelFormat.TagBigPromo, LabelFormat.TagBig140, LabelFormat.TagBig140Promo -> tagBig(c, item, k, f)
        }
    }

    // ---------- Листы A4 (Brother, 300 dpi) ----------

    /**
     * Страницы A4 в 1 бит: ячейки по сетке шаблона, каждая ячейка — тот же [draw].
     * Альбомний аркуш малюємо 3508×2480 і повертаємо на 90°: Brother отримує книжний 2480×3508.
     */
    fun sheetPages(cells: List<LabelItem>, t: Template, promo: Boolean, landscape: Boolean = t.defaultLandscape, dpi: Float = SHEET_DPI): List<MonoImage> {
        val l = requireNotNull(t.layout(landscape))
        val f = LabelFormat.of(t, promo)
        return cells.chunked(l.perPage).map { chunk ->
            val img = MonoImage.fromAlpha(page(chunk, l, f, dpi, Bitmap.Config.ALPHA_8))
            if (l.landscape) img.rotateClockwise() else img
        }
    }

    /** Лист для превью (уменьшенный [dpi]): белая бумага под ценниками, промо — жёлтые ячейки. */
    fun sheetPreview(cells: List<LabelItem>, t: Template, promo: Boolean, dpi: Float, landscape: Boolean = t.defaultLandscape): Bitmap {
        val f = LabelFormat.of(t, promo)
        val l = requireNotNull(t.layout(landscape))
        val ink = page(cells.take(l.perPage), l, f, dpi, Bitmap.Config.ARGB_8888)
        val out = createBitmap(ink.width, ink.height)
        val c = Canvas(out)
        c.drawColor(Color.WHITE)
        if (f.promo) {
            val paper = Paint().apply { color = PROMO_PAPER }
            val (cw, ch) = sizeDots(f, dpi)
            cells.take(l.perPage).indices.forEach { i ->
                val (x, y) = cellOrigin(l, i, dpi)
                c.drawRect(x.toFloat(), y.toFloat(), (x + cw).toFloat(), (y + ch).toFloat(), paper)
            }
        }
        val guides = Paint().apply {
            color = Color.rgb(205, 211, 219)
            style = Paint.Style.STROKE
            strokeWidth = 1f
            pathEffect = DashPathEffect(floatArrayOf(4f, 3f), 0f)
        }
        val (cellWidth, cellHeight) = sizeDots(f, dpi)
        repeat(l.perPage) { index ->
            val (x, y) = cellOrigin(l, index, dpi)
            c.drawRect(x.toFloat(), y.toFloat(), (x + cellWidth).toFloat(), (y + cellHeight).toFloat(), guides)
        }
        c.drawBitmap(ink, 0f, 0f, null)
        return out
    }

    private fun page(cells: List<LabelItem>, l: SheetLayout, f: LabelFormat, dpi: Float, config: Bitmap.Config): Bitmap {
        val pw = (l.pageWmm * dpi / 25.4f).roundToInt()
        val ph = (l.pageHmm * dpi / 25.4f).roundToInt()
        val bmp = createBitmap(pw, ph, config)
        val c = Canvas(bmp)
        cells.forEachIndexed { i, item ->
            val (x, y) = cellOrigin(l, i, dpi)
            c.withTranslation(x.toFloat(), y.toFloat()) { draw(this, item, f, dpi) }
        }
        return bmp
    }

    /** Левый верхний угол ячейки — целые точки, чтобы модули штрихкода не дробились. */
    private fun cellOrigin(l: SheetLayout, index: Int, dpi: Float): Pair<Int, Int> {
        val cell = l.cell(index)
        val x = (l.marginXmm + cell.col * l.cellWmm) * dpi / 25.4f
        val y = (l.marginYmm + cell.row * l.cellHmm) * dpi / 25.4f
        return x.roundToInt() to y.roundToInt()
    }

    /** Тестовая страница A4 для Brother: текст, дата, магазин, модель, EAN-13 и рамка. */
    fun testPage(lines: List<String>, barcode: String, dpi: Float = SHEET_DPI): MonoImage {
        val pw = (SheetLayout.A4_W_MM * dpi / 25.4f).roundToInt()
        val ph = (SheetLayout.A4_H_MM * dpi / 25.4f).roundToInt()
        val bmp = createBitmap(pw, ph, Bitmap.Config.ALPHA_8)
        val c = Canvas(bmp)
        val mm = dpi / 25.4f
        val frame = Paint().apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 0.5f * mm }
        c.drawRect(10 * mm, 10 * mm, pw - 10 * mm, ph - 10 * mm, frame)
        val title = text(golos600, 9 * mm)
        val body = text(golos500, 5 * mm)
        var y = 30 * mm
        c.drawText(lines.firstOrNull().orEmpty(), 20 * mm, y, title)
        y += 14 * mm
        lines.drop(1).forEach { c.drawText(it, 20 * mm, y, body); y += 8 * mm }
        val module = moduleFor(dpi)
        Ean13.normalize(barcode)?.let { code ->
            val len = Ean13.lengthDots(module)
            ean(c, code, (20 * mm).roundToInt(), (y + 6 * mm).roundToInt(), len, (25 * mm).roundToInt(), vertical = false, module = module)
        }
        return MonoImage.fromAlpha(bmp)
    }

    // ---------- Макеты ----------

    /** Перевод px макета в точки принтера и целый модуль штрихкода. */
    private class Scale(val s: Float, val module: Int) {
        fun d(px: Float) = px * s
        fun i(px: Float) = (px * s).roundToInt()
    }

    /**
     * A) Етикетка 40×24 по Label40x25.dc.html: поля 8/14/6 (в макете 10/14/8 при высоте 200), штрихкод 248×74 по центру.
     */
    private fun label40x24(c: Canvas, item: LabelItem, k: Scale) {
        val w = k.d(320f)
        val padX = k.d(14f)
        ean13Box(c, item.barcode, k, k.i(36f), k.i(8f), k.i(248f), k.i(74f), vertical = false)

        // Середина: слева бренд и «арт …» (Golos 600), справа «ТМ бренд, назва» до 3 строк (Golos 500).
        val midTop = k.d(8f + 74f + 5f)
        val small = k.d(11f)
        val left = listOf(item.brand, item.article.takeIf { it.isNotEmpty() }?.let { "арт $it" }.orEmpty())
            .filter { it.isNotEmpty() }.joinToString("\n")
        block(c, left, text(golos600, small), padX, midTop, k.d(84f), lineHeight = small * 1.25f, maxLines = 2)
        val rightX = padX + k.d(84f + 10f)
        block(c, item.title(), text(golos500, small), rightX, midTop, w - padX - rightX, lineHeight = small * 1.25f, maxLines = 3)

        // Низ: «649,00» Montserrat 800 + «грн» Golos 600, по центру, общая базовая линия.
        val v = item.price ?: return
        val price = text(mont800, k.d(26f))
        val unit = text(golos600, k.d(15f))
        val s = PriceFormat.label(v)
        val gap = k.d(8f)
        val total = price.measureText(s) + gap + unit.measureText("грн")
        val x = (w - total) / 2
        val boxBottom = k.d(192f - 6f)
        val base = baseline(price, boxBottom - k.d(26f), k.d(26f))
        c.drawText(s, x, base, price)
        c.drawText("грн", x + price.measureText(s) + gap, base, unit)
    }

    /** B) Цінник 68×38 (Tag68x38.dc.html, Tag68x38Promo.dc.html): пунктир по краю, вертикальный EAN-13 справа. */
    private fun tag68x38(c: Canvas, item: LabelItem, k: Scale, promo: Boolean) {
        val w = k.d(544f)
        val h = k.d(304f)
        val b = k.d(2f) // рамка: абсолютные отступы макета считаются от её внутреннего края
        dashedFrame(c, w, h, k.d(2f), k)

        val nameSize = k.d(if (promo) 20f else 21f)
        block(
            c, item.title(), text(golos500, nameSize), b + k.d(16f), b + k.d(if (promo) 10f else 12f),
            k.d(if (promo) 446f else 440f), lineHeight = nameSize * (if (promo) 1.25f else 1.3f), maxLines = 3,
        )

        val bw = if (promo) 56f else 58f
        val barX = (w - b - k.d(14f + bw)).roundToInt()
        val barcodeLeft = ean13Box(c, item.barcode, k, barX, (b + k.d(14f)).roundToInt(), k.i(bw), k.i(196f), vertical = true)

        footer(c, item, k, w, h, b, left = 16f, right = 14f, bottom = 9f, leftSize = 12f, rightSize = 11f)

        val priceLeft = b + k.d(if (promo) 28f else 14f)
        val priceBottom = h - b - k.d(30f)
        if (promo) {
            oldPrice(c, item, b + k.d(22f), b + k.d(118f), text(mont700, k.d(42f)), k.d(-10f), k.d(14f), k.d(20f), k.d(3f), -16f)
        }
        price(
            c, item, priceLeft, priceBottom, maxRight = (barcodeLeft ?: (w - b - k.d(14f)).roundToInt()) - k.d(8f),
            wholeSize = k.d(118f), wholeLh = 0.9f, spacingPx = k.d(-4f), kopSize = k.d(if (promo) 40f else 42f),
            unitSize = k.d(if (promo) 36f else 38f), gap = k.d(4f), padTop = k.d(4f),
        )
    }

    /** C) Крупногабарит 148×105 (TagBig.dc.html, TagBigPromo.dc.html) і 140×100 — та сама верстка від країв. */
    private fun tagBig(c: Canvas, item: LabelItem, k: Scale, f: LabelFormat) {
        val promo = f.promo
        val w = k.d(f.wPx.toFloat())
        val h = k.d(f.hPx.toFloat())
        val vy = f.hPx / 840f // 140×100 нижчий за 148×105: штрихкод і стара ціна — пропорційно вище
        val b = k.d(2f)
        dashedFrame(c, w, h, k.d(2f), k)

        val nameSize = k.d(44f)
        block(c, item.title(), text(golos500, nameSize), b + k.d(40f), b + k.d(28f), w - k.d(1184f - 1010f), lineHeight = nameSize * 1.25f, maxLines = 3)
        ean13Box(c, item.barcode, k, (w - b - k.d(40f + 72f)).roundToInt(), (b + k.d(250f * vy)).roundToInt(), k.i(72f), k.i(236f), vertical = true)
        footer(c, item, k, w, h, b, left = 44f, right = 40f, bottom = 26f, leftSize = 24f, rightSize = 22f)

        if (promo) {
            oldPrice(c, item, b + k.d(40f), b + k.d(270f * vy), text(mont700, k.d(132f)).apply { letterSpacing = -3f / 132f }, k.d(-14f), k.d(30f), k.d(62f), k.d(6f), -11f)
        }
        price(
            c, item, b + k.d(34f), h - b - k.d(84f), maxRight = w - b - k.d(40f),
            wholeSize = k.d(if (promo) 250f else 270f), wholeLh = 0.86f, spacingPx = k.d(-8f),
            kopSize = k.d(if (promo) 96f else 100f), unitSize = k.d(if (promo) 88f else 92f), gap = k.d(14f), padTop = k.d(6f),
        )
    }

    // ---------- Части ----------

    /** Пунктирная рамка по линии реза: 2 px, штрих как в браузере (≈ 3 толщины). */
    private fun dashedFrame(c: Canvas, w: Float, h: Float, stroke: Float, k: Scale) {
        val p = Paint().apply {
            color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = stroke
            pathEffect = DashPathEffect(floatArrayOf(k.d(6f), k.d(6f)), 0f)
        }
        c.drawRect(stroke / 2, stroke / 2, w - stroke / 2, h - stroke / 2, p)
    }

    /** Низ: «код, артикул» слева и «Ціна за одиницю товару» справа. */
    private fun footer(c: Canvas, item: LabelItem, k: Scale, w: Float, h: Float, b: Float, left: Float, right: Float, bottom: Float, leftSize: Float, rightSize: Float) {
        val lp = text(golos500, k.d(leftSize))
        val rp = text(golos500, k.d(rightSize))
        val caption = "Ціна за одиницю товару"
        val rightX = w - b - k.d(right) - rp.measureText(caption)
        c.drawText(caption, rightX, h - b - k.d(bottom) - rp.fontMetrics.descent, rp)
        val codes = listOf(item.code, item.article).filter { it.isNotEmpty() }.joinToString(", ")
        if (codes.isNotEmpty()) {
            val x = b + k.d(left)
            c.drawText(fit(codes, lp, rightX - x - k.d(12f)), x, h - b - k.d(bottom) - lp.fontMetrics.descent, lp)
        }
    }

    /**
     * Цена: гривны крупно (Montserrat 800), справа столбиком копейки (800) и «грн» (700).
     * Блок прижат низом к [bottom]; если не влезает до [maxRight] — уменьшаем все три размера.
     */
    private fun price(
        c: Canvas, item: LabelItem, left: Float, bottom: Float, maxRight: Float,
        wholeSize: Float, wholeLh: Float, spacingPx: Float, kopSize: Float, unitSize: Float, gap: Float, padTop: Float,
    ) {
        val v = item.price ?: return
        val (whole, kop) = PriceFormat.tag(v)
        fun measure(scale: Float): Float {
            val wp = text(mont800, wholeSize * scale).apply { letterSpacing = spacingPx / wholeSize }
            val col = max(text(mont800, kopSize * scale).measureText(kop), text(mont700, unitSize * scale).measureText("грн"))
            return wp.measureText(whole) + gap * scale + col
        }
        val scale = min(1f, (maxRight - left) / measure(1f))
        val wp = text(mont800, wholeSize * scale).apply { letterSpacing = spacingPx / wholeSize }
        val kp = text(mont800, kopSize * scale)
        val up = text(mont700, unitSize * scale)
        val lineH = wholeSize * scale * wholeLh
        val colH = padTop * scale + kopSize * scale + unitSize * scale
        val top = bottom - max(lineH, colH)
        c.drawText(whole, left, baseline(wp, top, lineH), wp)
        val colX = left + wp.measureText(whole) + gap * scale
        val colW = max(kp.measureText(kop), up.measureText("грн"))
        val kopTop = top + padTop * scale
        c.drawText(kop, colX + (colW - kp.measureText(kop)) / 2, baseline(kp, kopTop, kopSize * scale), kp)
        c.drawText("грн", colX + (colW - up.measureText("грн")) / 2, baseline(up, kopTop + kopSize * scale, unitSize * scale), up)
    }

    /** Старая цена (только гривны) и косая черта поверх неё. */
    private fun oldPrice(c: Canvas, item: LabelItem, x: Float, top: Float, p: TextPaint, lineFrom: Float, lineOver: Float, lineTop: Float, lineH: Float, angle: Float) {
        if (!item.canPromo) return
        val s = PriceFormat.tag(item.oldPrice ?: return).first
        // Цвет макета #2A2B10: непрозрачный, поэтому в 1 бит печатается чёрным.
        p.color = 0xFF2A2B10.toInt()
        c.drawText(s, x, baseline(p, top, p.textSize), p)
        val x0 = x + lineFrom
        val x1 = x + p.measureText(s) + lineOver
        val cy = top + lineTop + lineH / 2
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        c.withRotation(angle, (x0 + x1) / 2, cy) { drawRect(x0, cy - lineH / 2, x1, cy + lineH / 2, line) }
    }

    /**
     * EAN-13 в рамке макета. Модуль — целые точки (Godex 2, Brother 3); если не влезает — самый широкий целый.
     * Возвращает левый край символа (для вертикального — чтобы цена не наезжала).
     */
    private fun ean13Box(c: Canvas, raw: String, k: Scale, x: Int, y: Int, w: Int, h: Int, vertical: Boolean): Int? {
        val code = Ean13.normalize(raw) ?: return null
        val len = if (vertical) h else w
        val thick = if (vertical) w else h
        var module = k.module
        // Длиннее рамки допускаем до 15 % (ценник 68×38 на 300 dpi), дальше — уменьшаем модуль.
        if (Ean13.lengthDots(module) > len * 1.15f) module = Ean13.moduleFor(len)
        val full = Ean13.lengthDots(module)
        val along = (if (vertical) y else x) + (len - full) / 2
        if (vertical) ean(c, code, x, along, full, thick, vertical = true, module = module)
        else ean(c, code, along, y, full, thick, vertical = false, module = module)
        return x
    }

    /**
     * Штрихкод по правилам Barcode.dc.html: тихая зона 5 модулей, штрихи одной высоты,
     * 13 цифр одним рядом под штрихами, зазор 1.2 модуля, высота цифр = min(30 % толщины, 10 модулей).
     * Вертикальный — повёрнут на −90° (читается снизу вверх), цифры справа.
     * Штрихи — целые прямоугольники без сглаживания.
     */
    private fun ean(c: Canvas, code: String, x: Int, y: Int, length: Int, thick: Int, vertical: Boolean, module: Int) {
        val digitH = max(9f * module / 2f, min(thick * 0.3f, module * 10f))
        val fs = min(digitH * 1.12f, module * 95f / 13f * 1.5f)
        val barH = (thick - digitH - module * 1.2f).roundToInt()
        val ink = Paint().apply { color = Color.BLACK; isAntiAlias = false; style = Paint.Style.FILL }
        for (r in Ean13.bars(code, module)) {
            if (vertical) {
                // Ось символа идёт снизу вверх: u → y = y + length − u.
                c.drawRect(x.toFloat(), (y + length - (r.last + 1)).toFloat(), (x + barH).toFloat(), (y + length - r.first).toFloat(), ink)
            } else {
                c.drawRect((x + r.first).toFloat(), y.toFloat(), (x + r.last + 1).toFloat(), (y + barH).toFloat(), ink)
            }
        }
        val digits = text(roboto500, fs).apply { textAlign = Paint.Align.CENTER }
        val cw = Ean13.MODULES * module / 13f
        val rowTop = thick - digitH
        val base = baseline(digits, rowTop, digitH)
        for (j in 0 until 13) {
            val u = (Ean13.QUIET * module) + j * cw + cw / 2
            val ch = code.substring(j, j + 1)
            if (vertical) {
                // Повёрнутая система: (u, v) → (x + v, y + length − u).
                val px = x + 0f
                val py = y + length - u
                c.withRotation(-90f, px, py) { drawText(ch, px, py + base, digits) }
            } else {
                c.drawText(ch, x + u, y + base, digits)
            }
        }
    }

    /** Текст в рамку шириной [w] с межстрочным [lineHeight] (как CSS line-height), до [maxLines] строк с «…». */
    private fun block(c: Canvas, s: String, p: TextPaint, x: Float, y: Float, w: Float, lineHeight: Float, maxLines: Int) {
        if (s.isBlank() || w < 1) return
        val natural = p.fontMetrics.let { it.descent - it.ascent }
        val l = StaticLayout.Builder.obtain(s, 0, s.length, p, w.toInt())
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(lineHeight - natural, 1f)
            .setIncludePad(false)
            .setMaxLines(maxLines).setEllipsize(TextUtils.TruncateAt.END)
            .build()
        c.withTranslation(x, y + (lineHeight - natural) / 2) { l.draw(this) }
    }

    private fun fit(s: String, p: TextPaint, w: Float): String = TextUtils.ellipsize(s, p, max(w, 1f), TextUtils.TruncateAt.END).toString()

    /** Базовая линия текста, вписанного в строку высотой [lineH] с верхом [top] (как CSS line-height). */
    private fun baseline(p: Paint, top: Float, lineH: Float): Float {
        val fm = p.fontMetrics
        return top + (lineH - (fm.descent - fm.ascent)) / 2 - fm.ascent
    }

    private fun text(tf: Typeface, size: Float) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK; typeface = tf; textSize = size
    }

    companion object {
        const val SHEET_DPI = 300f
        const val GODEX_DPI = 203f

        /** Модуль штрихкода в точках: Godex 203 dpi — 2, Brother 300 dpi — 3; превью — пропорционально, не меньше 1. */
        fun moduleFor(dpi: Float): Int = when {
            dpi >= 290f -> 3
            dpi >= 190f -> 2
            else -> max(1, (dpi / 100f).roundToInt())
        }
    }
}
