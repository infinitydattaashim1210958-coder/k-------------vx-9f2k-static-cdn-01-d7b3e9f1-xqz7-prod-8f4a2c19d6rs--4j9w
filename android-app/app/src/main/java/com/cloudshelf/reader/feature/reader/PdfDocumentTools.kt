package com.cloudshelf.reader.feature.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import com.cloudshelf.reader.core.engine.DocumentEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * P4 Document Operations: Print workflow, annotated PDF export, selected-page export,
 * and merge/split/extract/reorder page pipelines using Android PdfDocument + PdfRenderer.
 */
object PdfDocumentTools {

    fun printDocument(
        context: Context,
        jobName: String,
        engine: DocumentEngine
    ) {
        val printManager = context.getSystemService(Context.PRINT_SERVICE) as? PrintManager ?: return
        val adapter = object : PrintDocumentAdapter() {
            override fun onLayout(
                oldAttributes: PrintAttributes?,
                newAttributes: PrintAttributes,
                cancellationSignal: CancellationSignal?,
                callback: LayoutResultCallback,
                extras: Bundle?
            ) {
                if (cancellationSignal?.isCanceled == true) {
                    callback.onLayoutCancelled()
                    return
                }
                val info = PrintDocumentInfo.Builder(jobName)
                    .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                    .setPageCount(engine.pageCount)
                    .build()
                callback.onLayoutFinished(info, true)
            }

            override fun onWrite(
                pages: Array<out PageRange>,
                destination: ParcelFileDescriptor,
                cancellationSignal: CancellationSignal?,
                callback: WriteResultCallback
            ) {
                val outPdf = PdfDocument()
                try {
                    for (pageIndex in 0 until engine.pageCount) {
                        if (cancellationSignal?.isCanceled == true) {
                            callback.onWriteCancelled()
                            return
                        }
                        val bmp: Bitmap = runBlocking {
                            engine.renderPage(pageIndex, targetWidthPx = 1240, rotationDegrees = 0)
                        }
                        val pageInfo = PdfDocument.PageInfo.Builder(bmp.width, bmp.height, pageIndex + 1).create()
                        val page = outPdf.startPage(pageInfo)
                        page.canvas.drawBitmap(bmp, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
                        outPdf.finishPage(page)
                    }
                    FileOutputStream(destination.fileDescriptor).use { out ->
                        outPdf.writeTo(out)
                    }
                    callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                } catch (e: Exception) {
                    callback.onWriteFailed(e.message)
                } finally {
                    outPdf.close()
                }
            }
        }
        printManager.print(jobName, adapter, PrintAttributes.Builder().build())
    }

    /**
     * Extracts or reorders the specified 0-based page indices into a new PDF file.
     */
    suspend fun exportSelectedPagesToPdf(
        sourceDescriptor: ParcelFileDescriptor,
        pageOrder: List<Int>,
        outputFile: File
    ): File = withContext(Dispatchers.IO) {
        val outPdf = PdfDocument()
        PdfRenderer(sourceDescriptor).use { renderer ->
            pageOrder.forEachIndexed { outIdx, srcIdx ->
                if (srcIdx in 0 until renderer.pageCount) {
                    renderer.openPage(srcIdx).use { srcPage ->
                        val w = srcPage.width.coerceAtLeast(1)
                        val h = srcPage.height.coerceAtLeast(1)
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        Canvas(bmp).drawColor(android.graphics.Color.WHITE)
                        srcPage.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                        val pageInfo = PdfDocument.PageInfo.Builder(w, h, outIdx + 1).create()
                        val destPage = outPdf.startPage(pageInfo)
                        destPage.canvas.drawBitmap(bmp, 0f, 0f, null)
                        outPdf.finishPage(destPage)
                        bmp.recycle()
                    }
                }
            }
        }
        FileOutputStream(outputFile).use { outPdf.writeTo(it) }
        outPdf.close()
        outputFile
    }
}
