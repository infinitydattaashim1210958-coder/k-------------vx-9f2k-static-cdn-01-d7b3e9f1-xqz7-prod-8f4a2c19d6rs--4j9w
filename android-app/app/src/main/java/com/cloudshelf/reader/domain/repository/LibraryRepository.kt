package com.cloudshelf.reader.domain.repository

import com.cloudshelf.reader.domain.model.Book
import com.cloudshelf.reader.domain.model.Category
import com.cloudshelf.reader.domain.model.SyncState
import kotlinx.coroutines.flow.Flow

interface LibraryRepository {
    fun observeBooks(): Flow<List<Book>>
    fun observeBook(bookId: String): Flow<Book?>
    fun observeCategories(): Flow<List<Category>>
    fun searchBooks(query: String): Flow<List<Book>>
    fun observeSyncState(): Flow<SyncState>
    suspend fun refreshCatalogue(force: Boolean = false): Result<Unit>
    suspend fun getBook(bookId: String): Book?
}
