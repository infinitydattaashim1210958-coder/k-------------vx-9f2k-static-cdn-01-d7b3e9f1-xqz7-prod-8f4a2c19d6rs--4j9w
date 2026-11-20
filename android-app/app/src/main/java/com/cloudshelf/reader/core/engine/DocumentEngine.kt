package com.cloudshelf.reader.core.engine

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable

enum class DocumentFormat {
    PDF,
    EPUB,
    CBZ,
    MARKDOWN,
    IMAGE
}

data class DocumentCapabilities(
    val supportsSearch: Boolean,
    val supportsOutline: Boolean,
    val supportsTextSelection: Boolean,
    val supportsAnnotations: Boolean,
    val supportsForms: Boolean,
    val supportsPrint: Boolean,
    val supportsExport: Boolean,
    val supportsPageOperations: Boolean,
    val supportsRotation: Boolean,
    val supportsTts: Boolean
)

data class DocumentPageInfo(
    val pageIndex: Int,
    val widthPx: Int,
    val heightPx: Int,
    val label: String = (pageIndex + 1).toString()
)

data class DocumentOutlineNode(
    val id: String,
    val title: String,
    val pageIndex: Int,
    val children: List<DocumentOutlineNode> = emptyList()
)

data class DocumentLink(
    val pageIndex: Int,
    val leftNorm: Float,
    val topNorm: Float,
    val rightNorm: Float,
    val bottomNorm: Float,
    val destinationPageIndex: Int? = null,
    val externalUrl: String? = null
)

data class DocumentMetadata(
    val title: String,
    val author: String = "",
    val format: DocumentFormat,
    val pageCount: Int,
    val creator: String? = null,
    val producer: String? = null,
    val isPasswordProtected: Boolean = false,
    val hasDigitalSignature: Boolean = false
)

data class TextSearchMatch(
    val pageIndex: Int,
    val snippet: String,
    val matchIndexInPage: Int
)

/**
 * Format-neutral contract for CloudShelf document backends (Section 6 of Okular Roadmap).
 */
interface DocumentEngine : Closeable {
    val format: DocumentFormat
    val capabilities: DocumentCapabilities
    val pageCount: Int

    suspend fun open(descriptor: ParcelFileDescriptor, titleHint: String = "Document")
    suspend fun getMetadata(): DocumentMetadata
    suspend fun getPageInfo(pageIndex: Int): DocumentPageInfo
    suspend fun renderPage(
        pageIndex: Int,
        targetWidthPx: Int,
        rotationDegrees: Int = 0,
        isThumbnail: Boolean = false
    ): Bitmap

    suspend fun extractPageText(pageIndex: Int): String = ""
    suspend fun search(query: String): List<TextSearchMatch> = emptyList()
    suspend fun getOutline(): List<DocumentOutlineNode> = emptyList()
    suspend fun getPageLinks(pageIndex: Int): List<DocumentLink> = emptyList()
    fun cancelRender(pageIndex: Int) = Unit
}

/**
 * Bounded LRU Bitmap cache with explicit memory budget in KB and safe eviction cleanup.
 */
class BoundedBitmapLruCache(
    maxMemoryKb: Int = (Runtime.getRuntime().maxMemory() / 1024L / 8L).toInt().coerceIn(8192, 32768)
) {
    private val cache = object : LruCache<String, Bitmap>(maxMemoryKb) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            return (value.byteCount / 1024).coerceAtLeast(1)
        }

        override fun entryRemoved(evicted: Boolean, key: String, oldValue: Bitmap, newValue: Bitmap?) {
            // Compose ImageBitmap holds references during draw frames; let GC reclaim safely once evicted.
        }
    }

    fun get(key: String): Bitmap? = cache.get(key)?.takeUnless { it.isRecycled }

    fun put(key: String, bitmap: Bitmap) {
        if (!bitmap.isRecycled) {
            cache.put(key, bitmap)
        }
    }

    fun clear() {
        cache.evictAll()
    }
}

class DocumentEngineRegistry(
    private val pdfFactory: () -> DocumentEngine,
    private val epubFactory: () -> DocumentEngine,
    private val comicFactory: () -> DocumentEngine,
    private val markdownFactory: () -> DocumentEngine,
    private val imageFactory: () -> DocumentEngine
) {
    fun detectFormat(assetNameOrUrl: String): DocumentFormat {
        val clean = assetNameOrUrl.substringBefore('?').lowercase()
        return when {
            clean.endsWith(".epub") -> DocumentFormat.EPUB
            clean.endsWith(".cbz") -> DocumentFormat.CBZ
            clean.endsWith(".md") || clean.endsWith(".markdown") -> DocumentFormat.MARKDOWN
            clean.endsWith(".png") || clean.endsWith(".jpg") || clean.endsWith(".jpeg") || clean.endsWith(".webp") -> DocumentFormat.IMAGE
            else -> DocumentFormat.PDF
        }
    }

    fun create(format: DocumentFormat): DocumentEngine = when (format) {
        DocumentFormat.PDF -> pdfFactory()
        DocumentFormat.EPUB -> epubFactory()
        DocumentFormat.CBZ -> comicFactory()
        DocumentFormat.MARKDOWN -> markdownFactory()
        DocumentFormat.IMAGE -> imageFactory()
    }
}

suspend inline fun <T> withEngineIo(crossinline block: suspend () -> T): T =
    withContext(Dispatchers.IO) { block() }
