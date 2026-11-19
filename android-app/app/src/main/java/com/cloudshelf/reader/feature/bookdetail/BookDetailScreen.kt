package com.cloudshelf.reader.feature.bookdetail

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.cloudshelf.reader.core.security.OfflineVaultManager
import com.cloudshelf.reader.domain.model.Book
import com.cloudshelf.reader.domain.model.OfflineStatus
import com.cloudshelf.reader.domain.repository.LibraryRepository
import com.cloudshelf.reader.domain.repository.UserActivityRepository
import com.cloudshelf.reader.feature.offline.OfflineDownloadWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class BookDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    libraryRepository: LibraryRepository,
    private val userActivityRepository: UserActivityRepository,
    private val vaultManager: OfflineVaultManager,
    private val workManager: WorkManager
) : ViewModel() {
    private val bookId: String = checkNotNull(savedStateHandle["bookId"])

    val book: StateFlow<Book?> = libraryRepository.observeBook(bookId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun requestOffline(book: Book) {
        val request = OneTimeWorkRequestBuilder<OfflineDownloadWorker>()
            .setInputData(
                workDataOf(
                    OfflineDownloadWorker.KEY_BOOK_ID to book.id,
                    OfflineDownloadWorker.KEY_URL to book.downloadUrl,
                    OfflineDownloadWorker.KEY_SHA256 to book.sha256,
                    OfflineDownloadWorker.KEY_SIZE to book.fileSize
                )
            )
            .build()
        workManager.enqueueUniqueWork(
            "offline-${book.id}",
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    fun removeOffline(bookId: String) {
        viewModelScope.launch {
            vaultManager.deleteOfflineCopy(bookId)
            userActivityRepository.removeOfflineRecord(bookId)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookDetailScreen(
    onRead: (String, Boolean) -> Unit,
    onBack: () -> Unit,
    viewModel: BookDetailViewModel = hiltViewModel()
) {
    val book by viewModel.book.collectAsState()
    Scaffold(
        topBar = {
            TopAppBar(title = { Text(book?.title ?: "Book") })
        }
    ) { padding ->
        val b = book
        if (b == null) {
            Text("Loading…", modifier = Modifier.padding(padding).padding(16.dp))
            return@Scaffold
        }
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(b.title, style = MaterialTheme.typography.headlineSmall)
            Text(b.authors.joinToString(", "), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            b.description?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            Spacer(Modifier.height(8.dp))
            Text("Size: ${"%.1f".format(b.fileSize / (1024.0 * 1024.0))} MB")
            Text("Language: ${b.language}")
            b.readingProgressPercent?.let {
                LinearProgressIndicator(progress = { it / 100f }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { onRead(b.id, false) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Read Now (Online)") }
            Spacer(Modifier.height(8.dp))
            if (b.isOfflineAvailable) {
                Button(
                    onClick = { onRead(b.id, true) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Read Offline") }
                OutlinedButton(
                    onClick = { viewModel.removeOffline(b.id) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Remove Offline Copy") }
            } else {
                OutlinedButton(
                    onClick = { viewModel.requestOffline(b) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Available Offline") }
            }
        }
    }
}
