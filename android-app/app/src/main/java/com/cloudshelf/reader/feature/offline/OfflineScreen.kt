package com.cloudshelf.reader.feature.offline

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cloudshelf.reader.core.security.OfflineVaultManager
import com.cloudshelf.reader.domain.model.OfflineBookStatus
import com.cloudshelf.reader.domain.repository.UserActivityRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OfflineViewModel @Inject constructor(
    private val userActivityRepository: UserActivityRepository,
    private val vaultManager: OfflineVaultManager
) : ViewModel() {
    val offlineBooks: StateFlow<List<OfflineBookStatus>> =
        userActivityRepository.observeOfflineBooks()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun removeOffline(bookId: String) {
        viewModelScope.launch {
            vaultManager.deleteOfflineCopy(bookId)
            userActivityRepository.removeOfflineRecord(bookId)
        }
    }

    fun storageBytes(): Long = vaultManager.getTotalEncryptedStorageBytes()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfflineScreen(
    onBookClick: (String) -> Unit,
    viewModel: OfflineViewModel = hiltViewModel()
) {
    val books by viewModel.offlineBooks.collectAsState()
    Scaffold(topBar = { TopAppBar(title = { Text("Offline Library") }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Text(
                text = "Encrypted storage: ${"%.1f".format(viewModel.storageBytes() / (1024.0 * 1024.0))} MB",
                style = MaterialTheme.typography.bodyMedium
            )
            LazyColumn(
                contentPadding = PaddingValues(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (books.isEmpty()) {
                    item { Text("No offline books. Open a book detail and choose Available Offline.") }
                }
                items(books, key = { it.bookId }) { status ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(status.bookId, style = MaterialTheme.typography.titleSmall)
                            Text("Status: ${status.status}", style = MaterialTheme.typography.bodySmall)
                            Button(onClick = { onBookClick(status.bookId) }) { Text("Open") }
                            Button(onClick = { viewModel.removeOffline(status.bookId) }) {
                                Text("Remove offline copy")
                            }
                        }
                    }
                }
            }
        }
    }
}
