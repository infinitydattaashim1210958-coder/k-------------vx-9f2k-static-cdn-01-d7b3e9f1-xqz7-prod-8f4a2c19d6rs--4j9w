
package com.cloudshelf.reader.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.cloudshelf.reader.feature.common.BookCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onBookClick: (String) -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("CloudShelf") },
                actions = {
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "Refresh catalogue"
                        )
                    }
                }
            )
        }
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.syncState.isSyncing,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                state.isLoading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }

                else -> {
                    LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        state.syncState.lastError?.let { err ->
                            item {
                                Text(
                                    text = "Sync note: $err",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }

                        // Continue Reading
                        if (state.continueReading.isNotEmpty()) {
                            item {
                                Text(
                                    "Continue Reading",
                                    style = MaterialTheme.typography.titleLarge
                                )
                            }

                            items(
                                items = state.continueReading,
                                key = { "continue:${it.first.id}" }
                            ) { (book, _) ->
                                BookCard(
                                    book = book,
                                    onClick = { onBookClick(book.id) }
                                )
                            }
                        }

                        // Featured
                        if (state.featured.isNotEmpty()) {
                            item {
                                Text(
                                    "Featured",
                                    style = MaterialTheme.typography.titleLarge
                                )
                            }

                            items(
                                items = state.featured,
                                key = { "featured:${it.id}" }
                            ) { book ->
                                BookCard(
                                    book = book,
                                    onClick = { onBookClick(book.id) }
                                )
                            }
                        }

                        // Library
                        item {
                            Text(
                                "Library",
                                style = MaterialTheme.typography.titleLarge
                            )
                        }

                        if (state.recentlyAdded.isEmpty()) {
                            item {
                                Text(
                                    text = "No books yet. Upload PDFs to GitHub Releases and add entries to catalogue.json on the main branch.",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        } else {
                            items(
                                items = state.recentlyAdded,
                                key = { "library:${it.id}" }
                            ) { book ->
                                BookCard(
                                    book = book,
                                    onClick = { onBookClick(book.id) }
                                )
                            }
                        }

                        item {
                            Spacer(Modifier.height(24.dp))
                        }
                    }
                }
            }
        }
    }
}
