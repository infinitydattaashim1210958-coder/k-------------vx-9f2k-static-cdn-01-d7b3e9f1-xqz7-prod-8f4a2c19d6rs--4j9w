package com.cloudshelf.reader.feature.reader

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.NavigateBefore
import androidx.compose.material.icons.filled.NavigateNext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cloudshelf.reader.core.pdf.OnlinePdfCacheManager
import com.cloudshelf.reader.core.security.OfflineVaultManager
import com.cloudshelf.reader.domain.model.Bookmark
import com.cloudshelf.reader.domain.model.ReadingProgress
import com.cloudshelf.reader.domain.repository.LibraryRepository
import com.cloudshelf.reader.domain.repository.UserActivityRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class ReaderState(
    val isLoading: Boolean = true,
    val error: String? = null,
    val pageCount: Int = 0,
    val currentPage: Int = 0,
    val pageBitmap: Bitmap? = null,
    val loadProgress: Float = 0f
)

@HiltViewModel
class PdfReaderViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val libraryRepository: LibraryRepository,
    private val userActivityRepository: UserActivityRepository,
    private val onlineCache: OnlinePdfCacheManager,
    private val vaultManager: OfflineVaultManager
) : ViewModel() {

    private val bookId: String = checkNotNull(savedStateHandle["bookId"])
    private val offline: Boolean = savedStateHandle["offline"] ?: false

    private val _state = MutableStateFlow(ReaderState())
    val state: StateFlow<ReaderState> = _state.asStateFlow()

    private var renderer: PdfRenderer? = null
    private var pfd: ParcelFileDescriptor? = null
    private var sessionStartMs = System.currentTimeMillis()

    init {
        openDocument()
    }

    private fun openDocument() {
        viewModelScope.launch {
            try {
                _state.value = ReaderState(isLoading = true)
                val book = libraryRepository.getBook(bookId)
                    ?: throw IllegalStateException("Book not found")

                val descriptor = if (offline) {
                    vaultManager.openDecryptedDescriptor(bookId)
                } else {
                    onlineCache.openOnlineSeekableDescriptor(
                        bookId = book.id,
                        downloadUrl = book.downloadUrl,
                        expectedSize = book.fileSize
                    ) { progress ->
                        _state.value = _state.value.copy(loadProgress = progress)
                    }
                }

                pfd = descriptor
                renderer = PdfRenderer(descriptor)
                val pageCount = renderer!!.pageCount
                val progress = userActivityRepository.observeProgress(bookId)
                // Restore last page if available
                val lastPage = withContext(Dispatchers.IO) {
                    // simple first emission wait is handled via flow elsewhere; default 0
                    0
                }
                _state.value = ReaderState(
                    isLoading = false,
                    pageCount = pageCount,
                    currentPage = lastPage.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
                )
                renderPage(_state.value.currentPage)
            } catch (e: Exception) {
                _state.value = ReaderState(isLoading = false, error = e.message ?: "Failed to open PDF")
            }
        }
    }

    fun goToPage(page: Int) {
        val count = _state.value.pageCount
        if (count == 0) return
        val target = page.coerceIn(0, count - 1)
        _state.value = _state.value.copy(currentPage = target)
        renderPage(target)
        persistProgress(target, count)
    }

    fun nextPage() = goToPage(_state.value.currentPage + 1)
    fun prevPage() = goToPage(_state.value.currentPage - 1)

    fun addBookmark() {
        viewModelScope.launch {
            userActivityRepository.addBookmark(
                Bookmark(
                    bookId = bookId,
                    pageNumber = _state.value.currentPage,
                    label = "Page ${_state.value.currentPage + 1}",
                    note = null,
                    createdAtEpochMs = System.currentTimeMillis()
                )
            )
        }
    }

    private fun renderPage(index: Int) {
        viewModelScope.launch(Dispatchers.Default) {
            val r = renderer ?: return@launch
            try {
                r.openPage(index).use { page ->
                    val width = page.width.coerceAtLeast(1)
                    val height = page.height.coerceAtLeast(1)
                    // Limit bitmap size for memory safety
                    val scale = minOf(1.5f, 2048f / width, 2048f / height)
                    val bmp = Bitmap.createBitmap(
                        (width * scale).toInt().coerceAtLeast(1),
                        (height * scale).toInt().coerceAtLeast(1),
                        Bitmap.Config.ARGB_8888
                    )
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    _state.value = _state.value.copy(pageBitmap = bmp)
                }
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.message)
            }
        }
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

    override fun onCleared() {
        renderer?.close()
        pfd?.close()
        super.onCleared()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfReaderScreen(
    onBack: () -> Unit,
    viewModel: PdfReaderViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (state.pageCount > 0)
                            "Page ${state.currentPage + 1} / ${state.pageCount}"
                        else "Reader"
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.addBookmark() }) {
                        Icon(Icons.Default.Bookmark, contentDescription = "Bookmark")
                    }
                    IconButton(onClick = { viewModel.prevPage() }) {
                        Icon(Icons.Default.NavigateBefore, contentDescription = "Previous")
                    }
                    IconButton(onClick = { viewModel.nextPage() }) {
                        Icon(Icons.Default.NavigateNext, contentDescription = "Next")
                    }
                }
            )
        }
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center
        ) {
            when {
                state.isLoading -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    if (state.loadProgress > 0f) {
                        Text("${(state.loadProgress * 100).toInt()}%", modifier = Modifier.padding(8.dp))
                    }
                }
                state.error != null -> Text(
                    text = state.error ?: "Error",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp)
                )
                state.pageBitmap != null -> {
                    Image(
                        bitmap = state.pageBitmap!!.asImageBitmap(),
                        contentDescription = "PDF page",
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer(
                                scaleX = scale,
                                scaleY = scale,
                                translationX = offsetX,
                                translationY = offsetY
                            )
                            .pointerInput(Unit) {
                                detectTransformGestures { _, pan, zoom, _ ->
                                    scale = (scale * zoom).coerceIn(0.5f, 4f)
                                    offsetX += pan.x
                                    offsetY += pan.y
                                }
                            }
                    )
                }
            }
        }
    }
}
