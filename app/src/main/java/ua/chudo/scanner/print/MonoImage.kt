package ua.chudo.scanner.print

import android.graphics.Bitmap
import android.graphics.Color

/** Монохромная картинка: бит 1 — чёрная точка, строки выровнены по байту (как ждут ^GF, EZPL Q и PCL). */
class MonoImage(val width: Int, val height: Int, val data: ByteArray) {
    val bytesPerRow get() = (width + 7) / 8

    fun isBlack(x: Int, y: Int): Boolean = data[y * bytesPerRow + x / 8].toInt() and (0x80 ushr (x % 8)) != 0

    fun toBitmap(): Bitmap {
        val px = IntArray(width * height)
        for (y in 0 until height) for (x in 0 until width) px[y * width + x] = if (isBlack(x, y)) Color.BLACK else Color.WHITE
        return Bitmap.createBitmap(px, width, height, Bitmap.Config.ARGB_8888)
    }

    companion object {
        /** Из функции «чёрная ли точка» — без Android (тесты, штрихкод). */
        fun of(width: Int, height: Int, black: (x: Int, y: Int) -> Boolean): MonoImage {
            val bpr = (width + 7) / 8
            val data = ByteArray(bpr * height)
            for (y in 0 until height) for (x in 0 until width) {
                if (black(x, y)) data[y * bpr + x / 8] = (data[y * bpr + x / 8].toInt() or (0x80 ushr (x % 8))).toByte()
            }
            return MonoImage(width, height, data)
        }

        /** Чёрное по прозрачному ([LabelRenderer]) → 1 бит: точка чёрная, если покрытие ≥ 50 %. */
        fun fromAlpha(bmp: Bitmap): MonoImage {
            val w = bmp.width
            val h = bmp.height
            if (bmp.config == Bitmap.Config.ALPHA_8) {
                val stride = bmp.rowBytes
                val buf = java.nio.ByteBuffer.allocate(stride * h)
                bmp.copyPixelsToBuffer(buf)
                val a = buf.array()
                return of(w, h) { x, y -> (a[y * stride + x].toInt() and 0xFF) >= 128 }
            }
            val row = IntArray(w)
            val bpr = (w + 7) / 8
            val data = ByteArray(bpr * h)
            for (y in 0 until h) {
                bmp.getPixels(row, 0, w, 0, y, w, 1)
                for (x in 0 until w) {
                    if (row[x] ushr 24 >= 128) data[y * bpr + x / 8] = (data[y * bpr + x / 8].toInt() or (0x80 ushr (x % 8))).toByte()
                }
            }
            return MonoImage(w, h, data)
        }
    }
}
