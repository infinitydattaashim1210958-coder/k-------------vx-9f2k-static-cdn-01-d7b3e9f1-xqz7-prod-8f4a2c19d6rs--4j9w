package com.cloudshelf.reader.core.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Serializes all PdfRenderer and Page operations with a Mutex, cancels stale page jobs,
 * and enforces bounded LRU bitmap caching for large PDF stability (P0).
 */
class PdfDocumentEngine : DocumentEngine {
    override val format: DocumentFormat = DocumentFormat.PDF
    override val capabilities: DocumentCapabilities = DocumentCapabilities(
        supportsSearch = false, // Requires native text-extraction library (e.g. PdfBox/PDFium) for Android
        supportsOutline = false,
        supportsTextSelection = false,
        supportsAnnotations = true,
        supportsForms = false,
        supportsPrint = true,
        supportsExport = true,
        supportsPageOperations = true,
        supportsRotation = true,
        supportsTts = false
    )

    private val renderMutex = Mutex()
    private val bitmapCache = BoundedBitmapLruCache()
    private val activePageJobs = ConcurrentHashMap<Int, Job>()
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var descriptor: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private var documentTitle: String = "Document"

    override val pageCount: Int
        get() = renderer?.pageCount ?: 0

    override suspend fun open(descriptor: ParcelFileDescriptor, titleHint: String) {
        withContext(Dispatchers.IO) {
            renderMutex.withLock {
                closeLocked()
                this@PdfDocumentEngine.descriptor = descriptor
                this@PdfDocumentEngine.renderer = PdfRenderer(descriptor)
                this@PdfDocumentEngine.documentTitle = titleHint
            }
        }
    }

    override suspend fun getMetadata(): DocumentMetadata = DocumentMetadata(
        title = documentTitle,
        format = DocumentFormat.PDF,
        pageCount = pageCount
    )

    override suspend fun getPageInfo(pageIndex: Int): DocumentPageInfo = withContext(Dispatchers.Default) {
        renderMutex.withLock {
            val r = renderer ?: throw IllegalStateException("PdfDocumentEngine is not open")
            val safeIndex = pageIndex.coerceIn(0, (r.pageCount - 1).coerceAtLeast(0))
            r.openPage(safeIndex).use { page ->
                DocumentPageInfo(
                    pageIndex = safeIndex,
                    widthPx = page.width.coerceAtLeast(1),
                    heightPx = page.height.coerceAtLeast(1)
                )
            }
        }
    }

    override suspend fun renderPage(
        pageIndex: Int,
        targetWidthPx: Int,
        rotationDegrees: Int,
        isThumbnail: Boolean
    ): Bitmap {
        val normRotation = ((rotationDegrees % 360) + 360) % 360
        val clampedWidth = targetWidthPx.coerceIn(120, if (isThumbnail) 360 else 2048)
        val cacheKey = "${pageIndex}_${clampedWidth}_${normRotation}_${isThumbnail}"
        bitmapCache.get(cacheKey)?.let { return it }

        activePageJobs.remove(pageIndex)?.cancel(CancellationException("Superseded render request"))
        val job = currentCoroutineContext()[Job]
        if (job != null) {
            activePageJobs[pageIndex] = job
        }

        return withContext(Dispatchers.Default) {
            try {
                currentCoroutineContext().ensureActive()
                renderMutex.withLock {
                    currentCoroutineContext().ensureActive()
                    bitmapCache.get(cacheKey)?.let { return@withLock it }
                    val r = renderer ?: throw IllegalStateException("PdfDocumentEngine closed")
                    if (pageIndex < 0 || pageIndex >= r.pageCount) {
                        throw IndexOutOfBoundsException("Page $pageIndex out of range 0..${r.pageCount - 1}")
                    }
                    r.openPage(pageIndex).use { page ->
                        val pw = page.width.coerceAtLeast(1)
                        val ph = page.height.coerceAtLeast(1)
                        val scale = (clampedWidth.toFloat() / pw.toFloat()).coerceIn(0.2f, 2.5f)
                        val outW = (pw * scale).toInt().coerceIn(1, 2048)
                        val outH = (ph * scale).toInt().coerceIn(1, 2048)

                        val baseBitmap = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
                        Canvas(baseBitmap).drawColor(Color.WHITE)
                        page.render(baseBitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                        val finalBitmap = if (normRotation == 0) {
                            baseBitmap
                        } else {
                            val matrix = Matrix().apply { postRotate(normRotation.toFloat()) }
                            val rotated = Bitmap.createBitmap(baseBitmap, 0, 0, outW, outH, matrix, true)
                            if (rotated !== baseBitmap) baseBitmap.recycle()
                            rotated
                        }

                        bitmapCache.put(cacheKey, finalBitmap)
                        finalBitmap
                    }
                }
            } finally {
                if (job != null) {
                    activePageJobs.remove(pageIndex, job)
                }
            }
        }
    }

    override fun cancelRender(pageIndex: Int) {
        activePageJobs.remove(pageIndex)?.cancel(CancellationException("Cancelled"))
    }

    override fun close() {
        engineScope.cancel()
        activePageJobs.values.forEach { it.cancel() }
        activePageJobs.clear()
        bitmapCache.clear()
        closeLocked()
    }

    private fun closeLocked() {
        runCatching { renderer?.close() }
        runCatching { descriptor?.close() }
        renderer = null
        descriptor = null
    }
}
