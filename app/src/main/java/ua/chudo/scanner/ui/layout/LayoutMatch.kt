package ua.chudo.scanner.ui.layout

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Прямоугольник в долях 0..1 от картинки, начало слева сверху. */
data class Box(val x: Float, val y: Float, val w: Float, val h: Float) {
    val cx get() = x + w / 2
    val cy get() = y + h / 2
    fun contains(fx: Float, fy: Float) = fx in x..(x + w) && fy in y..(y + h)
}

data class MapLabel(val text: String, val box: Box)

data class ShopLayout(val shopId: String, val width: Int, val height: Int, val labels: List<MapLabel>, val sections: List<Box>)

/** Подписи на карте ↔ бренд товара. */
class BrandMatcher(synonyms: Map<String, String> = emptyMap()) {
    private val syn = synonyms.entries.associate { (k, v) -> norm(k) to norm(v) }

    private fun canon(s: String) = norm(s).let { syn[it] ?: it }

    /** Одна подпись может содержать несколько брендов: «LEGO / Duplo». */
    private fun parts(label: String) = label.split('/', ',', ';', '\n', '|').map(::canon).filter { it.isNotEmpty() }

    /** Бренд ↔ подпись: точное совпадение, вхождение (от 3 знаков) или одна опечатка (от 6 знаков). */
    fun same(brand: String, label: String): Boolean {
        val b = canon(brand)
        if (b.isEmpty()) return false
        return parts(label).any { l ->
            l == b || (min(l.length, b.length) >= 3 && (l.contains(b) || b.contains(l))) ||
                (min(l.length, b.length) >= 6 && abs(l.length - b.length) <= 1 && lev(l, b) <= 1)
        }
    }

    /** Слово названия ↔ подпись: без вхождений, только целиком (или одна опечатка). */
    private fun sameWord(word: String, label: String): Boolean {
        val w = canon(word)
        return w.length >= 3 && parts(label).any { l -> l == w || (min(l.length, w.length) >= 6 && lev(l, w) <= 1) }
    }

    /**
     * Где лежит товар: по бренду, если он есть, иначе по словам названия (и парам соседних слов: «Hot Wheels»).
     * Возвращает индексы подписей.
     */
    fun find(labels: List<MapLabel>, brand: String?, name: String?): List<Int> {
        brand?.takeIf { it.isNotBlank() }?.let { b ->
            val hits = labels.indices.filter { same(b, labels[it].text) }
            if (hits.isNotEmpty()) return hits
        }
        val words = name.orEmpty().split(Regex("[\\s,.;:()«»\"]+")).filter { it.isNotEmpty() && !it.equals("ТМ", true) && !it.equals("TM", true) }
        val probes = words + words.zipWithNext { a, b -> a + b }
        return labels.indices.filter { i -> probes.any { sameWord(it, labels[i].text) } }
    }

    companion object {
        private val LOOKALIKE = mapOf(
            'а' to 'a', 'в' to 'b', 'е' to 'e', 'ё' to 'e', 'к' to 'k', 'м' to 'm', 'н' to 'h', 'о' to 'o',
            'р' to 'p', 'с' to 'c', 'т' to 't', 'у' to 'y', 'х' to 'x', 'і' to 'i', 'ї' to 'i',
        )
        private const val DROP = " \t-‐–—'’ʼ`._"

        /** Нижний регистр, без пробелов/дефисов/апострофов/точек, кириллица-двойник → латиница. */
        fun norm(s: String): String = buildString {
            // NFKC: лигатуры из CorelDRAW («Treﬂ», «Inﬁniti») → обычные буквы.
            for (c in java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFKC).lowercase()) if (c !in DROP) append(LOOKALIKE[c] ?: c)
        }

        /** «ТМ LEGO, Конструктор…» → «LEGO». */
        fun brandOf(name: String?): String? =
            Regex("^\\s*(?:ТМ|TM)\\s+([^,]+)", RegexOption.IGNORE_CASE).find(name.orEmpty())?.groupValues?.get(1)?.trim()?.ifEmpty { null }

        private fun lev(a: String, b: String): Int {
            var prev = IntArray(b.length + 1) { it }
            for (i in 1..a.length) {
                val cur = IntArray(b.length + 1)
                cur[0] = i
                for (j in 1..b.length) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = cur
            }
            return prev[b.length]
        }
    }
}

/**
 * Камера карты: картинка [imgW]×[imgH] вписана в экран [viewW]×[viewH] и увеличена в [zoom] раз (1–6),
 * [tx]/[ty] — где на экране левый верхний угол картинки.
 */
data class MapCamera(
    val imgW: Float,
    val imgH: Float,
    val viewW: Float,
    val viewH: Float,
    val zoom: Float = 1f,
    val tx: Float = Float.NaN,
    val ty: Float = Float.NaN,
) {
    private val base get() = min(viewW / imgW, viewH / imgH)
    val contentW get() = imgW * base * zoom
    val contentH get() = imgH * base * zoom

    fun toScreen(fx: Float, fy: Float) = (tx + fx * contentW) to (ty + fy * contentH)
    fun toImage(sx: Float, sy: Float) = (sx - tx) / contentW to (sy - ty) / contentH

    /** Картинка не уезжает с экрана; меньше экрана — по центру. */
    fun clamped(): MapCamera {
        fun axis(t: Float, content: Float, view: Float) =
            if (content <= view) (view - content) / 2 else (if (t.isNaN()) (view - content) / 2 else t).coerceIn(view - content, 0f)
        return copy(tx = axis(tx, contentW, viewW), ty = axis(ty, contentH, viewH))
    }

    fun pan(dx: Float, dy: Float) = copy(tx = tx + dx, ty = ty + dy).clamped()

    /** Зум к точке экрана: точка под пальцем остаётся на месте. */
    fun zoomAt(sx: Float, sy: Float, newZoom: Float): MapCamera {
        val (fx, fy) = toImage(sx, sy)
        val z = newZoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
        val next = copy(zoom = z)
        return next.copy(tx = sx - fx * next.contentW, ty = sy - fy * next.contentH).clamped()
    }

    fun centerOn(fx: Float, fy: Float, newZoom: Float = zoom): MapCamera {
        val next = copy(zoom = newZoom.coerceIn(MIN_ZOOM, MAX_ZOOM))
        return next.copy(tx = viewW / 2 - fx * next.contentW, ty = viewH / 2 - fy * next.contentH).clamped()
    }

    /** Все рамки целиком на экране, с запасом [pad] (в долях картинки). */
    fun fit(boxes: List<Box>, pad: Float = 0.03f, maxZoom: Float = 4f): MapCamera {
        if (boxes.isEmpty()) return this
        val x0 = boxes.minOf { it.x } - pad
        val y0 = boxes.minOf { it.y } - pad
        val x1 = boxes.maxOf { it.x + it.w } + pad
        val y1 = boxes.maxOf { it.y + it.h } + pad
        val z = min(viewW / (max(x1 - x0, 1e-3f) * imgW * base), viewH / (max(y1 - y0, 1e-3f) * imgH * base))
        return centerOn((x0 + x1) / 2, (y0 + y1) / 2, min(z, maxZoom))
    }

    companion object {
        const val MIN_ZOOM = 1f
        const val MAX_ZOOM = 6f
    }
}
