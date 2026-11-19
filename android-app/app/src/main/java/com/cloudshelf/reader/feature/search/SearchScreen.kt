package com.cloudshelf.reader.feature.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cloudshelf.reader.domain.model.Book
import com.cloudshelf.reader.domain.repository.LibraryRepository
import com.cloudshelf.reader.feature.common.BookCard
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository
) : ViewModel() {
    private val query = MutableStateFlow("")
    @OptIn(ExperimentalCoroutinesApi::class)
    val results: StateFlow<List<Book>> = query
        .flatMapLatest { libraryRepository.searchBooks(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun onQueryChange(q: String) { query.value = q }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onBookClick: (String) -> Unit,
    viewModel: SearchViewModel = hiltViewModel()
) {
    var text by remember { mutableStateOf("") }
    val results by viewModel.results.collectAsState()
    Scaffold(topBar = { TopAppBar(title = { Text("Search") }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            OutlinedTextField(
                value = text,
                onValueChange = {
                    text = it
                    viewModel.onQueryChange(it)
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Title, author, tag…") },
                singleLine = true
            )
            LazyColumn(
                contentPadding = PaddingValues(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(results, key = { it.id }) { book ->
                    BookCard(book = book, onClick = { onBookClick(book.id) })
                }
            }
        }
    }
}
