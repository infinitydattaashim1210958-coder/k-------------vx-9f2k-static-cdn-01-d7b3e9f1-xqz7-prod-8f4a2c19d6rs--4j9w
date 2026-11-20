package com.cloudshelf.reader.feature.reader

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cloudshelf.reader.core.engine.DocumentEngine
import com.cloudshelf.reader.core.engine.DocumentEngineRegistry
import com.cloudshelf.reader.core.pdf.OnlinePdfCacheManager
import com.cloudshelf.reader.core.security.OfflineVaultManager
import com.cloudshelf.reader.data.local.ReaderPreferenceEntity
import com.cloudshelf.reader.domain.model.AnnotationPayload
import com.cloudshelf.reader.domain.model.AnnotationTool
import com.cloudshelf.reader.domain.model.Bookmark
import com.cloudshelf.reader.domain.model.NormalizedPoint
import com.cloudshelf.reader.domain.model.ReadingProgress
import com.cloudshelf.reader.domain.model.RenderedAnnotation
import com.cloudshelf.reader.domain.repository.LibraryRepository
import com.cloudshelf.reader.domain.repository.UserActivityRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class PdfReaderViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @ApplicationContext private val appContext: Context,
    private val libraryRepository: LibraryRepository,
    private val userActivityRepository: UserActivityRepository,
    private val onlineCache: OnlinePdfCacheManager,
    private val vaultManager: OfflineVaultManager,
    private val engineRegistry: DocumentEngineRegistry
) : ViewModel() {
    private val bookId: String = checkNotNull(savedStateHandle["bookId"])
    private val offline: Boolean = savedStateHandle["offline"] ?: false

    private val _state = MutableStateFlow(ReaderControllerState())
    val state: StateFlow<ReaderControllerState> = _state.asStateFlow()

    private val history = ReaderNavigationHistory()
    private val undoRedo = AnnotationUndoRedoManager()
    private var engine: DocumentEngine? = null
    private var sessionStartMs = System.currentTimeMillis()
    val ttsController = ReaderTtsController(appContext)

    val bookmarks: StateFlow<List<Bookmark>> = userActivityRepository
        .observeBookmarks(bookId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val annotations: StateFlow<List<RenderedAnnotation>> = userActivityRepository
        .observeAnnotations(bookId)
        .map { list -> list.mapNotNull { AnnotationEngine.fromDomainItem(it) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _activeTool = MutableStateFlow(AnnotationTool.NONE)
    val activeTool: StateFlow<AnnotationTool> = _activeTool.asStateFlow()

    private val _activeColor = MutableStateFlow(0xFF1B6EF3L)
    val activeColor: StateFlow<Long> = _activeColor.asStateFlow()

    private val _activeStrokeWidth = MutableStateFlow(5f)
    val activeStrokeWidth: StateFlow<Float> = _activeStrokeWidth.asStateFlow()

    private val _activeAlpha = MutableStateFlow(1f)
    val activeAlpha: StateFlow<Float> = _activeAlpha.asStateFlow()

    private val _selectedAnnotationId = MutableStateFlow<String?>(null)
    val selectedAnnotationId: StateFlow<String?> = _selectedAnnotationId.asStateFlow()

    init {
        openDocument()
    }

    private fun openDocument() {
        viewModelScope.launch {
            try {
                _state.value = ReaderControllerState(isLoading = true)
                val book = libraryRepository.getBook(bookId)
                    ?: throw IllegalStateException("Book not found")
                val format = engineRegistry.detectFormat(book.assetName.ifBlank { book.downloadUrl })
                val docEngine = engineRegistry.create(format)
                engine = docEngine

                val descriptor = if (offline) {
                    vaultManager.openDecryptedDescriptor(bookId)
                } else {
                    onlineCache.openOnlineSeekableDescriptor(
                        bookId = book.id,
                        downloadUrl = book.downloadUrl,
                        expectedSize = book.fileSize,
                        expectedSha256 = book.sha256
                    ) { progress ->
                        _state.value = _state.value.copy(loadProgress = progress)
                    }
                }

                docEngine.open(descriptor, book.title)
                val count = docEngine.pageCount

                // P0 fix: directly fetch persisted reading progress instead of defaulting to 0
                val savedProgress = userActivityRepository.getProgressOnce(bookId)
                    ?: userActivityRepository.observeProgress(bookId).firstOrNull()
                val restoredPage = ReaderPageRestoration.resolveInitialPage(savedProgress?.lastPage, count)
                history.reset(restoredPage)

                val savedPrefs = userActivityRepository.observeReaderPreference(bookId).firstOrNull()
                val metadata = docEngine.getMetadata()
                val outline = docEngine.getOutline()

                _state.value = ReaderControllerState(
                    isLoading = false,
                    bookTitle = book.title,
                    format = format,
                    capabilities = docEngine.capabilities,
                    pageCount = count,
                    currentPage = restoredPage,
                    layoutMode = savedPrefs?.let {
                        runCatching { ReaderLayoutMode.valueOf(it.layoutMode) }.getOrNull()
                    } ?: ReaderLayoutMode.CONTINUOUS_VERTICAL,
                    zoomMode = savedPrefs?.let {
                        runCatching { ReaderZoomMode.valueOf(it.zoomMode) }.getOrNull()
                    } ?: ReaderZoomMode.FIT_WIDTH,
                    themeMode = savedPrefs?.let {
                        runCatching { ReaderThemeMode.valueOf(it.readerTheme) }.getOrNull()
                    } ?: ReaderThemeMode.LIGHT,
                    pageSpacingDp = savedPrefs?.pageSpacingDp ?: 12,
                    rotationDegrees = savedPrefs?.rotationDegrees ?: 0,
                    metadata = metadata,
                    outline = outline
                )
            } catch (e: Exception) {
                _state.value = ReaderControllerState(
                    isLoading = false,
                    error = e.message ?: "Failed to open document"
                )
            }
        }
    }

    suspend fun renderPage(pageIndex: Int, widthPx: Int = 1080, isThumbnail: Boolean = false): Bitmap? {
        val docEngine = engine ?: return null
        return runCatching {
            docEngine.renderPage(
                pageIndex = pageIndex,
                targetWidthPx = widthPx,
                rotationDegrees = _state.value.rotationDegrees,
                isThumbnail = isThumbnail
            )
        }.getOrNull()
    }

    fun goToPage(page: Int, recordHistory: Boolean = true) {
        val count = _state.value.pageCount
        if (count == 0) return
        val target = page.coerceIn(0, count - 1)
        history.navigateTo(target, recordHistory)
        _state.value = _state.value.copy(
            currentPage = target,
            canGoBack = history.canGoBack,
            canGoForward = history.canGoForward
        )
        persistProgress(target, count)
    }

    fun nextPage() = goToPage(_state.value.currentPage + 1)
    fun prevPage() = goToPage(_state.value.currentPage - 1)

    fun navigateBack() {
        history.back()?.let { target ->
            _state.value = _state.value.copy(
                currentPage = target,
                canGoBack = history.canGoBack,
                canGoForward = history.canGoForward
            )
            persistProgress(target, _state.value.pageCount)
        }
    }

    fun navigateForward() {
        history.forward()?.let { target ->
            _state.value = _state.value.copy(
                currentPage = target,
                canGoBack = history.canGoBack,
                canGoForward = history.canGoForward
            )
            persistProgress(target, _state.value.pageCount)
        }
    }

    fun setLayoutMode(mode: ReaderLayoutMode) {
        _state.value = _state.value.copy(layoutMode = mode)
        persistPreferences()
    }

    fun setZoomMode(mode: ReaderZoomMode, zoomPercent: Int = _state.value.zoomPercent) {
        _state.value = _state.value.copy(
            zoomMode = mode,
            zoomPercent = when (mode) {
                ReaderZoomMode.ACTUAL_SIZE -> 100
                ReaderZoomMode.CUSTOM -> zoomPercent.coerceIn(25, 400)
                else -> zoomPercent
            }
        )
        persistPreferences()
    }

    fun rotate90() {
        val next = (_state.value.rotationDegrees + 90) % 360
        _state.value = _state.value.copy(rotationDegrees = next)
        persistPreferences()
    }

    fun setThemeMode(mode: ReaderThemeMode) {
        _state.value = _state.value.copy(themeMode = mode)
        persistPreferences()
    }

    fun toggleFullscreen() {
        _state.value = _state.value.copy(isFullscreen = !_state.value.isFullscreen)
    }

    fun togglePresentationMode() {
        val entering = !_state.value.isPresentationMode
        _state.value = _state.value.copy(
            isPresentationMode = entering,
            isFullscreen = entering,
            layoutMode = if (entering) ReaderLayoutMode.SINGLE_PAGE else _state.value.layoutMode
        )
    }

    fun addBookmark(label: String = "Page ${_state.value.currentPage + 1}", note: String? = null) {
        viewModelScope.launch {
            userActivityRepository.addBookmark(
                Bookmark(
                    bookId = bookId,
                    pageNumber = _state.value.currentPage,
                    label = label,
                    note = note,
                    createdAtEpochMs = System.currentTimeMillis()
                )
            )
        }
    }

    fun deleteBookmark(id: Long) {
        viewModelScope.launch { userActivityRepository.deleteBookmark(id) }
    }

    fun search(query: String) {
        viewModelScope.launch {
            val matches = engine?.search(query).orEmpty()
            _state.value = _state.value.copy(
                searchQuery = query,
                searchMatches = matches,
                activeSearchIndex = if (matches.isNotEmpty()) 0 else -1
            )
            matches.firstOrNull()?.let { goToPage(it.pageIndex) }
        }
    }

    fun nextSearchMatch() {
        val list = _state.value.searchMatches
        if (list.isEmpty()) return
        val nextIdx = (_state.value.activeSearchIndex + 1) % list.size
        _state.value = _state.value.copy(activeSearchIndex = nextIdx)
        goToPage(list[nextIdx].pageIndex)
    }

    fun prevSearchMatch() {
        val list = _state.value.searchMatches
        if (list.isEmpty()) return
        val prevIdx = (_state.value.activeSearchIndex - 1 + list.size) % list.size
        _state.value = _state.value.copy(activeSearchIndex = prevIdx)
        goToPage(list[prevIdx].pageIndex)
    }

    fun setTool(tool: AnnotationTool) { _activeTool.value = tool }
    fun setColor(colorArgb: Long) { _activeColor.value = colorArgb }
    fun setStrokeWidth(width: Float) { _activeStrokeWidth.value = width.coerceIn(1f, 24f) }
    fun setAlpha(alpha: Float) { _activeAlpha.value = alpha.coerceIn(0.15f, 1f) }
    fun selectAnnotation(id: String?) { _selectedAnnotationId.value = id }

    fun createAnnotation(pageNumber: Int, payload: AnnotationPayload) {
        viewModelScope.launch {
            undoRedo.recordBeforeMutation(annotations.value)
            val created = RenderedAnnotation(
                id = UUID.randomUUID().toString(),
                bookId = bookId,
                pageNumber = pageNumber,
                payload = payload
            )
            userActivityRepository.upsertAnnotation(AnnotationEngine.toDomainItem(created))
        }
    }

    fun moveAnnotation(id: String, dxNorm: Float, dyNorm: Float) {
        val target = annotations.value.firstOrNull { it.id == id } ?: return
        val moved = target.copy(
            payload = AnnotationEngine.translate(target.payload, dxNorm, dyNorm),
            updatedAtEpochMs = System.currentTimeMillis()
        )
        viewModelScope.launch {
            userActivityRepository.upsertAnnotation(AnnotationEngine.toDomainItem(moved))
        }
    }

    fun eraseAt(pageNumber: Int, point: NormalizedPoint) {
        val hit = annotations.value
            .filter { it.pageNumber == pageNumber }
            .lastOrNull { AnnotationEngine.hitTest(it.payload, point) } ?: return
        deleteAnnotation(hit.id)
    }

    fun deleteAnnotation(id: String) {
        viewModelScope.launch {
            undoRedo.recordBeforeMutation(annotations.value)
            userActivityRepository.deleteAnnotation(id)
            if (_selectedAnnotationId.value == id) _selectedAnnotationId.value = null
        }
    }

    fun undoAnnotation() {
        val restored = undoRedo.undo(annotations.value) ?: return
        syncAnnotationsSnapshot(restored)
    }

    fun redoAnnotation() {
        val restored = undoRedo.redo(annotations.value) ?: return
        syncAnnotationsSnapshot(restored)
    }

    private fun syncAnnotationsSnapshot(targetList: List<RenderedAnnotation>) {
        viewModelScope.launch {
            val currentIds = annotations.value.map { it.id }.toSet()
            val targetIds = targetList.map { it.id }.toSet()
            (currentIds - targetIds).forEach { userActivityRepository.deleteAnnotation(it) }
            targetList.forEach { userActivityRepository.upsertAnnotation(AnnotationEngine.toDomainItem(it)) }
        }
    }

    fun speakCurrentPage() {
        viewModelScope.launch {
            val text = engine?.extractPageText(_state.value.currentPage).orEmpty()
            if (text.isNotBlank()) ttsController.speak(text)
        }
    }

    fun printCurrentDocument(context: Context) {
        val docEngine = engine ?: return
        PdfDocumentTools.printDocument(context, _state.value.bookTitle, docEngine)
    }

    private fun persistProgress(page: Int, total: Int) {
        viewModelScope.launch {
            val elapsed = System.currentTimeMillis() - sessionStartMs
            userActivityRepository.saveProgress(
                ReadingProgress(
                    bookId = bookId,
                    lastPage = page,
                    totalPages = total,
                    scrollOffsetRatio = 0f,
                    totalReadingTimeMs = elapsed,
                    lastReadEpochMs = System.currentTimeMillis()
                )
            )
        }
    }

    private fun persistPreferences() {
        viewModelScope.launch {
            val s = _state.value
            userActivityRepository.saveReaderPreference(
                ReaderPreferenceEntity(
                    scopeKey = bookId,
                    layoutMode = s.layoutMode.name,
                    zoomMode = s.zoomMode.name,
                    readerTheme = s.themeMode.name,
                    pageSpacingDp = s.pageSpacingDp,
                    rotationDegrees = s.rotationDegrees,
                    speechRate = ttsController.state.value.speechRate,
                    updatedAtEpochMs = System.currentTimeMillis()
                )
            )
        }
    }

    override fun onCleared() {
        ttsController.release()
        engine?.close()
        super.onCleared()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfReaderScreen(
    onBack: () -> Unit,
    viewModel: PdfReaderViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()
    val bookmarks by viewModel.bookmarks.collectAsState()
    val annotations by viewModel.annotations.collectAsState()
    val activeTool by viewModel.activeTool.collectAsState()
    val activeColor by viewModel.activeColor.collectAsState()
    val activeStrokeWidth by viewModel.activeStrokeWidth.collectAsState()
    val activeAlpha by viewModel.activeAlpha.collectAsState()
    val selectedAnnotationId by viewModel.selectedAnnotationId.collectAsState()
    val ttsState by viewModel.ttsController.state.collectAsState()

    var showAnnotationBar by remember { mutableStateOf(false) }
    var showBookmarksSheet by remember { mutableStateOf(false) }
    var showSearchSheet by remember { mutableStateOf(false) }
    var showMetadataDialog by remember { mutableStateOf(false) }
    var pendingTextAnchor by remember { mutableStateOf<Pair<Int, Pair<NormalizedPoint, Boolean>>?>(null) }
    var textDialogInput by remember { mutableStateOf("") }

    var gestureScale by remember { mutableFloatStateOf(1f) }
    var gestureOffsetX by remember { mutableFloatStateOf(0f) }
    var gestureOffsetY by remember { mutableFloatStateOf(0f) }

    val pageBitmaps = remember { mutableStateMapOf<Int, Bitmap>() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    val readerBg = when (state.themeMode) {
        ReaderThemeMode.LIGHT -> Color(0xFFF1F5F9)
        ReaderThemeMode.SEPIA -> Color(0xFFF3E9D2)
        ReaderThemeMode.DARK -> Color(0xFF0F1419)
        ReaderThemeMode.HIGH_CONTRAST -> Color.Black
    }

    LaunchedEffect(state.currentPage, state.layoutMode, state.rotationDegrees, state.pageCount) {
        if (state.pageCount > 0) {
            if (state.layoutMode == ReaderLayoutMode.CONTINUOUS_VERTICAL) {
                listState.scrollToItem(state.currentPage)
            }
            val start = (state.currentPage - 1).coerceAtLeast(0)
            val end = (state.currentPage + 1).coerceAtMost(state.pageCount - 1)
            for (idx in start..end) {
                viewModel.renderPage(idx)?.let { pageBitmaps[idx] = it }
            }
        }
    }

    Scaffold(
        topBar = {
            if (!state.isFullscreen) {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = state.bookTitle,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1
                            )
                            if (state.pageCount > 0) {
                                Text(
                                    text = "Page ${state.currentPage + 1} / ${state.pageCount} · ${state.zoomPercent}%",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        if (state.canGoBack) {
                            IconButton(onClick = { viewModel.navigateBack() }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "History back")
                            }
                        }
                        if (state.canGoForward) {
                            IconButton(onClick = { viewModel.navigateForward() }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "History forward")
                            }
                        }
                        IconButton(onClick = { showAnnotationBar = !showAnnotationBar }) {
                            Icon(Icons.Default.Draw, contentDescription = "Toggle annotation tools")
                        }
                        if (state.capabilities.supportsSearch) {
                            IconButton(onClick = { showSearchSheet = true }) {
                                Icon(Icons.Default.Search, contentDescription = "Search inside document")
                            }
                        }
                        IconButton(onClick = { showBookmarksSheet = true }) {
                            Icon(Icons.Default.Bookmarks, contentDescription = "Bookmarks")
                        }
                        IconButton(onClick = { viewModel.rotate90() }) {
                            Icon(Icons.Default.RotateRight, contentDescription = "Rotate 90 degrees")
                        }
                        IconButton(onClick = { showMetadataDialog = true }) {
                            Icon(Icons.Default.Info, contentDescription = "Document details")
                        }
                        IconButton(onClick = { viewModel.toggleFullscreen() }) {
                            Icon(Icons.Default.Fullscreen, contentDescription = "Fullscreen reader")
                        }
                    }
                )
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(readerBg)
                .padding(padding)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when {
                        event.key == Key.DirectionRight || event.key == Key.J -> {
                            viewModel.nextPage(); true
                        }
                        event.key == Key.DirectionLeft || event.key == Key.K -> {
                            viewModel.prevPage(); true
                        }
                        event.key == Key.F && event.isCtrlPressed -> {
                            showSearchSheet = true; true
                        }
                        event.key == Key.Z && event.isCtrlPressed -> {
                            viewModel.undoAnnotation(); true
                        }
                        event.key == Key.Y && event.isCtrlPressed -> {
                            viewModel.redoAnnotation(); true
                        }
                        event.key == Key.R -> {
                            viewModel.rotate90(); true
                        }
                        else -> false
                    }
                }
        ) {
            if (!state.isFullscreen) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilterChip(
                        selected = state.layoutMode == ReaderLayoutMode.CONTINUOUS_VERTICAL,
                        onClick = { viewModel.setLayoutMode(ReaderLayoutMode.CONTINUOUS_VERTICAL) },
                        label = { Text("Continuous") }
                    )
                    FilterChip(
                        selected = state.layoutMode == ReaderLayoutMode.SINGLE_PAGE,
                        onClick = { viewModel.setLayoutMode(ReaderLayoutMode.SINGLE_PAGE) },
                        label = { Text("Single") }
                    )
                    FilterChip(
                        selected = state.layoutMode == ReaderLayoutMode.FACING_PAGES,
                        onClick = { viewModel.setLayoutMode(ReaderLayoutMode.FACING_PAGES) },
                        label = { Text("Facing") }
                    )
                    IconButton(onClick = {
                        val nextZoom = (state.zoomPercent - 25).coerceAtLeast(50)
                        gestureScale = nextZoom / 100f
                        viewModel.setZoomMode(ReaderZoomMode.CUSTOM, nextZoom)
                    }) {
                        Icon(Icons.Default.ZoomOut, contentDescription = "Zoom out")
                    }
                    IconButton(onClick = {
                        val nextZoom = (state.zoomPercent + 25).coerceAtMost(300)
                        gestureScale = nextZoom / 100f
                        viewModel.setZoomMode(ReaderZoomMode.CUSTOM, nextZoom)
                    }) {
                        Icon(Icons.Default.ZoomIn, contentDescription = "Zoom in")
                    }
                    IconButton(onClick = { viewModel.addBookmark() }) {
                        Icon(Icons.Default.BookmarkAdd, contentDescription = "Add bookmark")
                    }
                    if (state.capabilities.supportsPrint) {
                        IconButton(onClick = { viewModel.printCurrentDocument(context) }) {
                            Icon(Icons.Default.Print, contentDescription = "Print document")
                        }
                    }
                    if (state.capabilities.supportsTts) {
                        IconButton(onClick = {
                            if (ttsState.isSpeaking) viewModel.ttsController.stop() else viewModel.speakCurrentPage()
                        }) {
                            Icon(Icons.Default.VolumeUp, contentDescription = "Read aloud")
                        }
                    }
                }
            }

            if (showAnnotationBar && !state.isFullscreen) {
                AnnotationToolbarStrip(
                    activeTool = activeTool,
                    activeColor = activeColor,
                    activeStrokeWidth = activeStrokeWidth,
                    activeAlpha = activeAlpha,
                    selectedAnnotationId = selectedAnnotationId,
                    onToolChange = viewModel::setTool,
                    onColorChange = viewModel::setColor,
                    onWidthChange = viewModel::setStrokeWidth,
                    onAlphaChange = viewModel::setAlpha,
                    onUndo = viewModel::undoAnnotation,
                    onRedo = viewModel::redoAnnotation,
                    onDeleteSelected = {
                        selectedAnnotationId?.let { viewModel.deleteAnnotation(it) }
                    }
                )
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                when {
                    state.isLoading -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        if (state.loadProgress > 0f) {
                            Text(
                                text = "${(state.loadProgress * 100).toInt()}%",
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                    }

                    state.error != null -> Text(
                        text = state.error ?: "Error",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(16.dp)
                    )

                    state.layoutMode == ReaderLayoutMode.CONTINUOUS_VERTICAL -> {
                        LazyColumn(
                            state = listState,
                            contentPadding = PaddingValues(12.dp),
                            verticalArrangement = Arrangement.spacedBy(state.pageSpacingDp.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items((0 until state.pageCount).toList(), key = { it }) { pageIdx ->
                                LaunchedEffect(pageIdx, state.rotationDegrees) {
                                    if (!pageBitmaps.containsKey(pageIdx)) {
                                        viewModel.renderPage(pageIdx)?.let { pageBitmaps[pageIdx] = it }
                                    }
                                }
                                ReaderPageSurface(
                                    pageIndex = pageIdx,
                                    bitmap = pageBitmaps[pageIdx],
                                    annotations = annotations.filter { it.pageNumber == pageIdx },
                                    activeTool = activeTool,
                                    activeColor = activeColor,
                                    activeStrokeWidth = activeStrokeWidth,
                                    activeAlpha = activeAlpha,
                                    selectedAnnotationId = selectedAnnotationId,
                                    onSelectAnnotation = viewModel::selectAnnotation,
                                    onCreateAnnotation = { payload ->
                                        viewModel.createAnnotation(pageIdx, payload)
                                    },
                                    onMoveAnnotation = viewModel::moveAnnotation,
                                    onEraseAt = { pt -> viewModel.eraseAt(pageIdx, pt) },
                                    onRequestTextInput = { pt, isNote ->
                                        pendingTextAnchor = pageIdx to (pt to isNote)
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .graphicsLayer(scaleX = gestureScale, scaleY = gestureScale)
                                )
                            }
                        }
                    }

                    else -> {
                        val bmp = pageBitmaps[state.currentPage]
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(activeTool) {
                                    if (activeTool == AnnotationTool.NONE) {
                                        detectTransformGestures { _, pan, zoom, _ ->
                                            gestureScale = (gestureScale * zoom).coerceIn(0.5f, 4f)
                                            gestureOffsetX += pan.x
                                            gestureOffsetY += pan.y
                                        }
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            ReaderPageSurface(
                                pageIndex = state.currentPage,
                                bitmap = bmp,
                                annotations = annotations.filter { it.pageNumber == state.currentPage },
                                activeTool = activeTool,
                                activeColor = activeColor,
                                activeStrokeWidth = activeStrokeWidth,
                                activeAlpha = activeAlpha,
                                selectedAnnotationId = selectedAnnotationId,
                                onSelectAnnotation = viewModel::selectAnnotation,
                                onCreateAnnotation = { payload ->
                                    viewModel.createAnnotation(state.currentPage, payload)
                                },
                                onMoveAnnotation = viewModel::moveAnnotation,
                                onEraseAt = { pt -> viewModel.eraseAt(state.currentPage, pt) },
                                onRequestTextInput = { pt, isNote ->
                                    pendingTextAnchor = state.currentPage to (pt to isNote)
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp)
                                    .graphicsLayer(
                                        scaleX = gestureScale,
                                        scaleY = gestureScale,
                                        translationX = gestureOffsetX,
                                        translationY = gestureOffsetY
                                    )
                            )
                        }
                    }
                }

                if (state.isFullscreen) {
                    IconButton(
                        onClick = { viewModel.toggleFullscreen() },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(16.dp)
                            .background(Color.Black.copy(alpha = 0.55f), CircleShape)
                    ) {
                        Icon(
                            Icons.Default.FullscreenExit,
                            contentDescription = "Exit fullscreen",
                            tint = Color.White
                        )
                    }
                }
            }

            if (state.pageCount > 1 && !state.isFullscreen) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items((0 until state.pageCount).toList(), key = { "thumb_$it" }) { idx ->
                        val isCurrent = idx == state.currentPage
                        Surface(
                            tonalElevation = if (isCurrent) 4.dp else 1.dp,
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier
                                .border(
                                    width = if (isCurrent) 2.dp else 1.dp,
                                    color = if (isCurrent) MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.3f),
                                    shape = RoundedCornerShape(6.dp)
                                )
                                .clickable {
                                    viewModel.goToPage(idx)
                                    scope.launch {
                                        if (state.layoutMode == ReaderLayoutMode.CONTINUOUS_VERTICAL) {
                                            listState.animateScrollToItem(idx)
                                        }
                                    }
                                }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = "P${idx + 1}",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
            }
        }
    }

    pendingTextAnchor?.let { (pageIdx, pair) ->
        val (anchor, isPopupNote) = pair
        AlertDialog(
            onDismissRequest = { pendingTextAnchor = null; textDialogInput = "" },
            title = { Text(if (isPopupNote) "Add Popup Note" else "Add Text Box") },
            text = {
                OutlinedTextField(
                    value = textDialogInput,
                    onValueChange = { textDialogInput = it },
                    label = { Text("Content") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val trimmed = textDialogInput.trim()
                    if (trimmed.isNotEmpty()) {
                        val payload = if (isPopupNote) {
                            AnnotationPayload.PopupNoteItem(anchor = anchor, text = trimmed, color = activeColor)
                        } else {
                            AnnotationPayload.TextItem(topLeft = anchor, text = trimmed, color = activeColor)
                        }
                        viewModel.createAnnotation(pageIdx, payload)
                    }
                    pendingTextAnchor = null
                    textDialogInput = ""
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { pendingTextAnchor = null; textDialogInput = "" }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showBookmarksSheet) {
        ModalBottomSheet(onDismissRequest = { showBookmarksSheet = false }) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text("Bookmarks (${bookmarks.size})", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))
                if (bookmarks.isEmpty()) {
                    Text("No bookmarks added yet.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    bookmarks.forEach { bm ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.goToPage(bm.pageNumber)
                                    showBookmarksSheet = false
                                }
                                .padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(bm.label, fontWeight = FontWeight.Medium)
                                Text("Page ${bm.pageNumber + 1}", style = MaterialTheme.typography.bodySmall)
                            }
                            IconButton(onClick = { viewModel.deleteBookmark(bm.id) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete bookmark")
                            }
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }

    if (showSearchSheet) {
        ModalBottomSheet(onDismissRequest = { showSearchSheet = false }) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text("Find in Document", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = state.searchQuery,
                    onValueChange = viewModel::search,
                    label = { Text("Search text") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Text("${state.searchMatches.size} matches")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = viewModel::prevSearchMatch) { Text("Previous Match") }
                    TextButton(onClick = viewModel::nextSearchMatch) { Text("Next Match") }
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }

    if (showMetadataDialog) {
        val meta = state.metadata
        AlertDialog(
            onDismissRequest = { showMetadataDialog = false },
            title = { Text("Document Metadata") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Title: ${meta?.title ?: state.bookTitle}")
                    Text("Format: ${state.format.name}")
                    Text("Pages: ${state.pageCount}")
                    Text("Annotations: ${annotations.size}")
                }
            },
            confirmButton = {
                TextButton(onClick = { showMetadataDialog = false }) { Text("Close") }
            }
        )
    }
}

@Composable
private fun ReaderPageSurface(
    pageIndex: Int,
    bitmap: Bitmap?,
    annotations: List<RenderedAnnotation>,
    activeTool: AnnotationTool,
    activeColor: Long,
    activeStrokeWidth: Float,
    activeAlpha: Float,
    selectedAnnotationId: String?,
    onSelectAnnotation: (String?) -> Unit,
    onCreateAnnotation: (AnnotationPayload) -> Unit,
    onMoveAnnotation: (String, Float, Float) -> Unit,
    onEraseAt: (NormalizedPoint) -> Unit,
    onRequestTextInput: (NormalizedPoint, Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val ratio = if (bitmap != null && bitmap.height > 0) {
        bitmap.width.toFloat() / bitmap.height.toFloat()
    } else {
        0.707f
    }

    Surface(
        shadowElevation = 2.dp,
        shape = RoundedCornerShape(4.dp),
        color = Color.White,
        modifier = modifier
            .aspectRatio(ratio)
            .semantics { contentDescription = "Document page ${pageIndex + 1}" }
    ) {
        Box(Modifier.fillMaxSize()) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Page ${pageIndex + 1}",
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
            }

            AnnotationCanvasOverlay(
                annotations = annotations,
                activeTool = activeTool,
                activeColorArgb = activeColor,
                activeStrokeWidth = activeStrokeWidth,
                activeAlpha = activeAlpha,
                selectedAnnotationId = selectedAnnotationId,
                onSelectAnnotation = onSelectAnnotation,
                onCreateAnnotation = onCreateAnnotation,
                onMoveAnnotation = onMoveAnnotation,
                onEraseAt = onEraseAt,
                onRequestTextInput = onRequestTextInput
            )
        }
    }
}

@Composable
private fun AnnotationToolbarStrip(
    activeTool: AnnotationTool,
    activeColor: Long,
    activeStrokeWidth: Float,
    activeAlpha: Float,
    selectedAnnotationId: String?,
    onToolChange: (AnnotationTool) -> Unit,
    onColorChange: (Long) -> Unit,
    onWidthChange: (Float) -> Unit,
    onAlphaChange: (Float) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onDeleteSelected: () -> Unit
) {
    val tools = listOf(
        AnnotationTool.NONE to "Read",
        AnnotationTool.SELECT to "Select",
        AnnotationTool.PEN to "Pen",
        AnnotationTool.HIGHLIGHTER_STROKE to "Highlighter",
        AnnotationTool.HIGHLIGHT_AREA to "Area",
        AnnotationTool.RECTANGLE to "Rect",
        AnnotationTool.ELLIPSE to "Ellipse",
        AnnotationTool.LINE to "Line",
        AnnotationTool.ARROW to "Arrow",
        AnnotationTool.TEXT_BOX to "Text",
        AnnotationTool.POPUP_NOTE to "Note",
        AnnotationTool.STAMP to "Stamp",
        AnnotationTool.ERASER to "Eraser"
    )
    val palette = listOf(0xFF1B6EF3L, 0xFFDC2626L, 0xFF16A34AL, 0xFFF59E0BL, 0xFF1A1C1EL)

    Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                tools.forEach { (tool, label) ->
                    FilterChip(
                        selected = activeTool == tool,
                        onClick = { onToolChange(tool) },
                        label = { Text(label) }
                    )
                }
                IconButton(onClick = onUndo) {
                    Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo annotation")
                }
                IconButton(onClick = onRedo) {
                    Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Redo annotation")
                }
                if (selectedAnnotationId != null) {
                    IconButton(onClick = onDeleteSelected) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete selected annotation")
                    }
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(top = 6.dp)
            ) {
                palette.forEach { argb ->
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(Color(argb))
                            .border(
                                width = if (activeColor == argb) 2.dp else 0.dp,
                                color = MaterialTheme.colorScheme.onSurface,
                                shape = CircleShape
                            )
                            .clickable { onColorChange(argb) }
                    )
                }
                Text("W:${activeStrokeWidth.toInt()}", style = MaterialTheme.typography.labelSmall)
                Slider(
                    value = activeStrokeWidth,
                    onValueChange = onWidthChange,
                    valueRange = 2f..18f,
                    modifier = Modifier.width(96.dp)
                )
                Text("A:${(activeAlpha * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
                Slider(
                    value = activeAlpha,
                    onValueChange = onAlphaChange,
                    valueRange = 0.2f..1f,
                    modifier = Modifier.width(96.dp)
                )
            }
        }
    }
}
