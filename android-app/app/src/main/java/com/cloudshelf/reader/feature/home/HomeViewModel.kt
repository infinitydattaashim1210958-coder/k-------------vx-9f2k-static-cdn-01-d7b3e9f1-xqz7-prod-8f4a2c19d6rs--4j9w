package com.cloudshelf.reader.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cloudshelf.reader.domain.model.Book
import com.cloudshelf.reader.domain.model.Category
import com.cloudshelf.reader.domain.model.ReadingProgress
import com.cloudshelf.reader.domain.model.SyncState
import com.cloudshelf.reader.domain.repository.LibraryRepository
import com.cloudshelf.reader.domain.repository.UserActivityRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeUiState(
    val continueReading: List<Pair<Book, ReadingProgress>> = emptyList(),
    val featured: List<Book> = emptyList(),
    val recentlyAdded: List<Book> = emptyList(),
    val categories: List<Category> = emptyList(),
    val syncState: SyncState = SyncState(),
    val isLoading: Boolean = true
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val userActivityRepository: UserActivityRepository
) : ViewModel() {

    val uiState: StateFlow<HomeUiState> = combine(
        libraryRepository.observeBooks(),
        libraryRepository.observeCategories(),
        userActivityRepository.observeRecentlyRead(),
        libraryRepository.observeSyncState()
    ) { books, categories, recentProgress, sync ->
        val progressMap = recentProgress.associateBy { it.bookId }
        val continueList = recentProgress.mapNotNull { p ->
            books.find { it.id == p.bookId }?.let { it to p }
        }.take(8)
        HomeUiState(
            continueReading = continueList,
            featured = books.filter { it.featured }.take(12),
            recentlyAdded = books.take(20),
            categories = categories,
            syncState = sync,
            isLoading = false
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HomeUiState())

    init {
        viewModelScope.launch {
            libraryRepository.refreshCatalogue(force = false)
        }
    }

    fun refresh() {
        viewModelScope.launch {
            libraryRepository.refreshCatalogue(force = true)
        }
    }
}
