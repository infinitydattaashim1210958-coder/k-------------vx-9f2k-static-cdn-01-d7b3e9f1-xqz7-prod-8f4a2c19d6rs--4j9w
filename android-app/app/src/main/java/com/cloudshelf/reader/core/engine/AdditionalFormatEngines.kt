package com.cloudshelf.reader.core.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.os.ParcelFileDescriptor
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.util.zip.ZipInputStream

/**
 * CBZ comic archive backend rendering ordered image pages from ZIP containers (P5).
 */
class ComicDocumentEngine(private val context: Context) : DocumentEngine {
    override val format: DocumentFormat = DocumentFormat.CBZ
    override val capabilities: DocumentCapabilities = DocumentCapabilities(
        supportsSearch = false,
        supportsOutline = false,
        supportsTextSelection = false,
        supportsAnnotations = true,
        supportsForms = false,
        supportsPrint = true,
        supportsExport = false,
        supportsPageOperations = false,
        supportsRotation = true,
        supportsTts = false
    )

    private val cache = BoundedBitmapLruCache()
    private val pages = mutableListOf<ByteArray>()
    private var title = "Comic Archive"
    private var pfd: ParcelFileDescriptor? = null

    override val pageCount: Int get() = pages.size

    override suspend fun open(descriptor: ParcelFileDescriptor, titleHint: String) = withContext(Dispatchers.IO) {
        pfd = descriptor
        title = titleHint
        pages.clear()
        val entries = mutableListOf<Pair<String, ByteArray>>()
        ZipInputStream(FileInputStream(descriptor.fileDescriptor)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val name = entry.name.lowercase()
                if (!entry.isDirectory && (name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png") || name.endsWith(".webp"))) {
                    entries.add(entry.name to zip.readBytes())
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        entries.sortBy { it.first }
        pages.addAll(entries.map { it.second })
    }

    override suspend fun getMetadata(): DocumentMetadata =
        DocumentMetadata(title = title, format = DocumentFormat.CBZ, pageCount = pageCount)

    override suspend fun getPageInfo(pageIndex: Int): DocumentPageInfo {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val bytes = pages[pageIndex.coerceIn(0, (pageCount - 1).coerceAtLeast(0))]
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        return DocumentPageInfo(pageIndex, opts.outWidth.coerceAtLeast(1), opts.outHeight.coerceAtLeast(1))
    }

    override suspend fun renderPage(
        pageIndex: Int,
        targetWidthPx: Int,
        rotationDegrees: Int,
        isThumbnail: Boolean
    ): Bitmap = withContext(Dispatchers.Default) {
        val key = "cbz_${pageIndex}_${targetWidthPx}_${rotationDegrees}"
        cache.get(key)?.let { return@withContext it }
        val bytes = pages[pageIndex]
        val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw IllegalStateException("Failed to decode comic image at page $pageIndex")
        val rot = ((rotationDegrees % 360) + 360) % 360
        val out = if (rot == 0) raw else {
            val m = Matrix().apply { postRotate(rot.toFloat()) }
            Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true).also {
                if (it !== raw) raw.recycle()
            }
        }
        cache.put(key, out)
        out
    }

    override fun close() {
        cache.clear()
        pages.clear()
        runCatching { pfd?.close() }
        pfd = null
    }
}

/**
 * Single or multi-image document engine for JPEG / PNG / WebP files (P5).
 */
class ImageDocumentEngine : DocumentEngine {
    override val format: DocumentFormat = DocumentFormat.IMAGE
    override val capabilities: DocumentCapabilities = DocumentCapabilities(
        supportsSearch = false,
        supportsOutline = false,
        supportsTextSelection = false,
        supportsAnnotations = true,
        supportsForms = false,
        supportsPrint = true,
        supportsExport = false,
        supportsPageOperations = false,
        supportsRotation = true,
        supportsTts = false
    )

    private var imageBytes: ByteArray? = null
    private var title: String = "Image"
    private var pfd: ParcelFileDescriptor? = null

    override val pageCount: Int get() = if (imageBytes != null) 1 else 0

    override suspend fun open(descriptor: ParcelFileDescriptor, titleHint: String) = withContext(Dispatchers.IO) {
        pfd = descriptor
        title = titleHint
        FileInputStream(descriptor.fileDescriptor).use { input ->
            imageBytes = input.readBytes()
        }
    }

    override suspend fun getMetadata(): DocumentMetadata =
        DocumentMetadata(title = title, format = DocumentFormat.IMAGE, pageCount = pageCount)

    override suspend fun getPageInfo(pageIndex: Int): DocumentPageInfo {
        val bytes = imageBytes ?: return DocumentPageInfo(0, 800, 600)
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        return DocumentPageInfo(0, opts.outWidth.coerceAtLeast(1), opts.outHeight.coerceAtLeast(1))
    }

    override suspend fun renderPage(
        pageIndex: Int,
        targetWidthPx: Int,
        rotationDegrees: Int,
        isThumbnail: Boolean
    ): Bitmap = withContext(Dispatchers.Default) {
        val bytes = imageBytes ?: throw IllegalStateException("No image loaded")
        val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw IllegalStateException("Unable to decode image")
        val rot = ((rotationDegrees % 360) + 360) % 360
        if (rot == 0) raw else {
            val m = Matrix().apply { postRotate(rot.toFloat()) }
            Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true).also {
                if (it !== raw) raw.recycle()
            }
        }
    }

    override fun close() {
        imageBytes = null
        runCatching { pfd?.close() }
        pfd = null
    }
}

/**
 * Markdown paginated reader backend supporting outline, full-text search, and TTS (P5).
 */
class MarkdownDocumentEngine : DocumentEngine {
    override val format: DocumentFormat = DocumentFormat.MARKDOWN
    override val capabilities: DocumentCapabilities = DocumentCapabilities(
        supportsSearch = true,
        supportsOutline = true,
        supportsTextSelection = true,
        supportsAnnotations = true,
        supportsForms = false,
        supportsPrint = true,
        supportsExport = false,
        supportsPageOperations = false,
        supportsRotation = false,
        supportsTts = true
    )

    private val pageTexts = mutableListOf<String>()
    private val outlineNodes = mutableListOf<DocumentOutlineNode>()
    private var title = "Markdown"
    private var pfd: ParcelFileDescriptor? = null

    override val pageCount: Int get() = pageTexts.size

    override suspend fun open(descriptor: ParcelFileDescriptor, titleHint: String) = withContext(Dispatchers.IO) {
        pfd = descriptor
        title = titleHint
        val raw = FileInputStream(descriptor.fileDescriptor).bufferedReader().use { it.readText() }
        pageTexts.clear()
        outlineNodes.clear()
        val lines = raw.lines()
        val chunkSize = 42
        lines.chunked(chunkSize).forEachIndexed { idx, chunk ->
            pageTexts.add(chunk.joinToString("\n"))
            chunk.filter { it.startsWith("#") }.forEachIndexed { hIdx, heading ->
                val cleanHeading = heading.trimStart('#', ' ')
                if (cleanHeading.isNotBlank()) {
                    outlineNodes.add(DocumentOutlineNode("md_${idx}_$hIdx", cleanHeading, idx))
                }
            }
        }
        if (pageTexts.isEmpty()) pageTexts.add("")
    }

    override suspend fun getMetadata(): DocumentMetadata =
        DocumentMetadata(title = title, format = DocumentFormat.MARKDOWN, pageCount = pageCount)

    override suspend fun getPageInfo(pageIndex: Int): DocumentPageInfo =
        DocumentPageInfo(pageIndex, 1080, 1520)

    override suspend fun renderPage(
        pageIndex: Int,
        targetWidthPx: Int,
        rotationDegrees: Int,
        isThumbnail: Boolean
    ): Bitmap = withContext(Dispatchers.Default) {
        val width = targetWidthPx.coerceIn(240, 1440)
        val height = (width * 1.41f).toInt()
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1A1C1E")
            textSize = (width / 38f).coerceIn(10f, 34f)
        }
        val text = pageTexts.getOrElse(pageIndex) { "" }
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, (width - 64).coerceAtLeast(100))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .build()
        canvas.save()
        canvas.translate(32f, 40f)
        layout.draw(canvas)
        canvas.restore()
        bmp
    }

    override suspend fun extractPageText(pageIndex: Int): String = pageTexts.getOrElse(pageIndex) { "" }

    override suspend fun search(query: String): List<TextSearchMatch> {
        if (query.isBlank()) return emptyList()
        val matches = mutableListOf<TextSearchMatch>()
        pageTexts.forEachIndexed { idx, content ->
            var pos = content.indexOf(query, ignoreCase = true)
            var count = 0
            while (pos >= 0) {
                val start = (pos - 24).coerceAtLeast(0)
                val end = (pos + query.length + 36).coerceAtMost(content.length)
                matches.add(TextSearchMatch(idx, content.substring(start, end).replace('\n', ' '), count++))
                pos = content.indexOf(query, pos + query.length, ignoreCase = true)
            }
        }
        return matches
    }

    override suspend fun getOutline(): List<DocumentOutlineNode> = outlineNodes.toList()

    override fun close() {
        pageTexts.clear()
        outlineNodes.clear()
        runCatching { pfd?.close() }
        pfd = null
    }
}

/**
 * EPUB spine chapter reader extracting XHTML chapters & OPF outline from EPUB ZIP containers (P5).
 */
class EpubDocumentEngine(private val context: Context) : DocumentEngine {
    override val format: DocumentFormat = DocumentFormat.EPUB
    override val capabilities: DocumentCapabilities = DocumentCapabilities(
        supportsSearch = true,
        supportsOutline = true,
        supportsTextSelection = true,
        supportsAnnotations = true,
        supportsForms = false,
        supportsPrint = true,
        supportsExport = false,
        supportsPageOperations = false,
        supportsRotation = false,
        supportsTts = true
    )

    private val chapters = mutableListOf<Pair<String, String>>() // title, plain text
    private var title = "EPUB Book"
    private var pfd: ParcelFileDescriptor? = null

    override val pageCount: Int get() = chapters.size

    override suspend fun open(descriptor: ParcelFileDescriptor, titleHint: String) = withContext(Dispatchers.IO) {
        pfd = descriptor
        title = titleHint
        chapters.clear()
        val htmlEntries = mutableListOf<Pair<String, String>>()
        ZipInputStream(FileInputStream(descriptor.fileDescriptor)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val lower = entry.name.lowercase()
                if (!entry.isDirectory && (lower.endsWith(".xhtml") || lower.endsWith(".html") || lower.endsWith(".htm"))) {
                    val rawHtml = zip.readBytes().decodeToString()
                    val plain = rawHtml
                        .replace(Regex("<script[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), "")
                        .replace(Regex("<style[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), "")
                        .replace(Regex("<[^>]+>"), " ")
                        .replace(Regex("\\s+"), " ")
                        .trim()
                    if (plain.isNotBlank()) {
                        htmlEntries.add(entry.name.substringAfterLast('/') to plain)
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        htmlEntries.sortBy { it.first }
        chapters.addAll(htmlEntries)
        if (chapters.isEmpty()) chapters.add("Cover" to "Empty EPUB document")
    }

    override suspend fun getMetadata(): DocumentMetadata =
        DocumentMetadata(title = title, format = DocumentFormat.EPUB, pageCount = pageCount)

    override suspend fun getPageInfo(pageIndex: Int): DocumentPageInfo =
        DocumentPageInfo(pageIndex, 1080, 1520, chapters.getOrNull(pageIndex)?.first ?: "${pageIndex + 1}")

    override suspend fun renderPage(
        pageIndex: Int,
        targetWidthPx: Int,
        rotationDegrees: Int,
        isThumbnail: Boolean
    ): Bitmap = withContext(Dispatchers.Default) {
        val width = targetWidthPx.coerceIn(240, 1440)
        val height = (width * 1.41f).toInt()
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1A1C1E")
            textSize = (width / 36f).coerceIn(10f, 34f)
        }
        val text = chapters.getOrNull(pageIndex)?.second ?: ""
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, (width - 72).coerceAtLeast(100))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .build()
        canvas.save()
        canvas.translate(36f, 44f)
        layout.draw(canvas)
        canvas.restore()
        bmp
    }

    override suspend fun extractPageText(pageIndex: Int): String = chapters.getOrNull(pageIndex)?.second ?: ""

    override suspend fun search(query: String): List<TextSearchMatch> {
        if (query.isBlank()) return emptyList()
        val matches = mutableListOf<TextSearchMatch>()
        chapters.forEachIndexed { idx, (_, text) ->
            var pos = text.indexOf(query, ignoreCase = true)
            var count = 0
            while (pos >= 0) {
                val start = (pos - 24).coerceAtLeast(0)
                val end = (pos + query.length + 36).coerceAtMost(text.length)
                matches.add(TextSearchMatch(idx, text.substring(start, end), count++))
                pos = text.indexOf(query, pos + query.length, ignoreCase = true)
            }
        }
        return matches
    }

    override suspend fun getOutline(): List<DocumentOutlineNode> =
        chapters.mapIndexed { idx, (name, _) -> DocumentOutlineNode("epub_$idx", name, idx) }

    override fun close() {
        chapters.clear()
        runCatching { pfd?.close() }
        pfd = null
    }
}
