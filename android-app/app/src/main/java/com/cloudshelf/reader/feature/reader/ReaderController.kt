package com.cloudshelf.reader.feature.reader

import com.cloudshelf.reader.core.engine.DocumentCapabilities
import com.cloudshelf.reader.core.engine.DocumentFormat
import com.cloudshelf.reader.core.engine.DocumentMetadata
import com.cloudshelf.reader.core.engine.DocumentOutlineNode
import com.cloudshelf.reader.core.engine.TextSearchMatch

enum class ReaderLayoutMode {
    SINGLE_PAGE,
    CONTINUOUS_VERTICAL,
    FACING_PAGES
}

enum class ReaderZoomMode {
    FIT_WIDTH,
    FIT_PAGE,
    ACTUAL_SIZE,
    CUSTOM
}

enum class ReaderThemeMode {
    LIGHT,
    SEPIA,
    DARK,
    HIGH_CONTRAST
}

/**
 * Pure back/forward page navigation history stack (P3).
 */
class ReaderNavigationHistory(initialPage: Int = 0) {
    private val backStack = ArrayDeque<Int>()
    private val forwardStack = ArrayDeque<Int>()
    var currentPage: Int = initialPage.coerceAtLeast(0)
        private set

    val canGoBack: Boolean get() = backStack.isNotEmpty()
    val canGoForward: Boolean get() = forwardStack.isNotEmpty()

    fun navigateTo(page: Int, recordHistory: Boolean = true): Int {
        val target = page.coerceAtLeast(0)
        if (target == currentPage) return currentPage
        if (recordHistory) {
            if (backStack.size >= 50) backStack.removeFirst()
            backStack.addLast(currentPage)
            forwardStack.clear()
        }
        currentPage = target
        return currentPage
    }

    fun back(): Int? {
        val prev = backStack.removeLastOrNull() ?: return null
        forwardStack.addLast(currentPage)
        currentPage = prev
        return currentPage
    }

    fun forward(): Int? {
        val next = forwardStack.removeLastOrNull() ?: return null
        backStack.addLast(currentPage)
        currentPage = next
        return currentPage
    }

    fun reset(page: Int) {
        backStack.clear()
        forwardStack.clear()
        currentPage = page.coerceAtLeast(0)
    }
}

data class ReaderControllerState(
    val isLoading: Boolean = true,
    val error: String? = null,
    val loadProgress: Float = 0f,
    val bookTitle: String = "Reader",
    val format: DocumentFormat = DocumentFormat.PDF,
    val capabilities: DocumentCapabilities = DocumentCapabilities(
        supportsSearch = false,
        supportsOutline = false,
        supportsTextSelection = false,
        supportsAnnotations = true,
        supportsForms = false,
        supportsPrint = true,
        supportsExport = true,
        supportsPageOperations = true,
        supportsRotation = true,
        supportsTts = false
    ),
    val pageCount: Int = 0,
    val currentPage: Int = 0,
    val layoutMode: ReaderLayoutMode = ReaderLayoutMode.CONTINUOUS_VERTICAL,
    val zoomMode: ReaderZoomMode = ReaderZoomMode.FIT_WIDTH,
    val zoomPercent: Int = 100,
    val rotationDegrees: Int = 0,
    val pageSpacingDp: Int = 12,
    val themeMode: ReaderThemeMode = ReaderThemeMode.LIGHT,
    val isFullscreen: Boolean = false,
    val isPresentationMode: Boolean = false,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val metadata: DocumentMetadata? = null,
    val outline: List<DocumentOutlineNode> = emptyList(),
    val searchQuery: String = "",
    val searchMatches: List<TextSearchMatch> = emptyList(),
    val activeSearchIndex: Int = -1
)

object ReaderPageRestoration {
    /**
     * Fixes P0 reading-progress restoration bug: clamps persisted lastPage safely
     * into [0, pageCount - 1] instead of resetting to 0.
     */
    fun resolveInitialPage(savedLastPage: Int?, pageCount: Int): Int {
        if (savedLastPage == null || pageCount <= 0) return 0
        return savedLastPage.coerceIn(0, pageCount - 1)
    }
}
