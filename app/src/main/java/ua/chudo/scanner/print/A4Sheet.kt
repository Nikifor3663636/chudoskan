package ua.chudo.scanner.print

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import java.io.ByteArrayOutputStream
import java.io.FileOutputStream

/** Обычные ценники: лист A4, сетка 3×8 по 70×37 мм (Brother). */
object A4Sheet {
    const val COLS = 3
    const val ROWS = 8
    const val PER_PAGE = COLS * ROWS
    private const val PAGE_W = 595 // A4 в пунктах
    private const val PAGE_H = 842
    private const val PT = 72f / 25.4f

    fun pages(count: Int) = (count + PER_PAGE - 1) / PER_PAGE

    fun pdf(labels: List<Bitmap>): ByteArray {
        val doc = PdfDocument()
        val w = 70 * PT
        val h = 37 * PT
        val top = (PAGE_H - ROWS * h) / 2
        labels.chunked(PER_PAGE).forEachIndexed { i, chunk ->
            val page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, i + 1).create())
            chunk.forEachIndexed { j, b ->
                val x = (j % COLS) * w
                val y = top + (j / COLS) * h
                page.canvas.drawBitmap(b, null, RectF(x, y, x + w, y + h), null)
            }
            doc.finishPage(page)
        }
        return ByteArrayOutputStream().also { doc.writeTo(it); doc.close() }.toByteArray()
    }

    /** Печать через системный PrintManager (сетевой Brother, Mopria/плагин Brother). */
    fun systemPrint(activity: Activity, job: String, pdf: ByteArray, pageCount: Int) {
        val pm = activity.getSystemService(Context.PRINT_SERVICE) as PrintManager
        val adapter = object : PrintDocumentAdapter() {
            override fun onLayout(
                oldAttributes: PrintAttributes?,
                newAttributes: PrintAttributes?,
                cancellationSignal: CancellationSignal?,
                callback: LayoutResultCallback,
                extras: Bundle?,
            ) {
                if (cancellationSignal?.isCanceled == true) return callback.onLayoutCancelled()
                val info = PrintDocumentInfo.Builder("$job.pdf")
                    .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                    .setPageCount(pageCount)
                    .build()
                callback.onLayoutFinished(info, true)
            }

            override fun onWrite(
                pages: Array<out PageRange>?,
                destination: ParcelFileDescriptor,
                cancellationSignal: CancellationSignal?,
                callback: WriteResultCallback,
            ) {
                try {
                    FileOutputStream(destination.fileDescriptor).use { it.write(pdf) }
                    callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                } catch (e: Exception) {
                    callback.onWriteFailed(e.message)
                }
            }
        }
        pm.print(job, adapter, PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4).build())
    }
}
