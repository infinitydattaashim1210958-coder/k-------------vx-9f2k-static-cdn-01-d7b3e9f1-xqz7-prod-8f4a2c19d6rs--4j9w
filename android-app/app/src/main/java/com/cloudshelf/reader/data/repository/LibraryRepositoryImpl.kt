package com.cloudshelf.reader.data.repository

import com.cloudshelf.reader.core.common.CloudShelfConfig
import com.cloudshelf.reader.data.local.BookCategoryCrossRef
import com.cloudshelf.reader.data.local.LibraryDao
import com.cloudshelf.reader.data.local.OfflineBookEntity
import com.cloudshelf.reader.data.local.ReadingProgressEntity
import com.cloudshelf.reader.data.local.UserActivityDao
import com.cloudshelf.reader.data.remote.CatalogueFetchResult
import com.cloudshelf.reader.data.remote.CatalogueRemoteDataSource
import com.cloudshelf.reader.domain.model.Book
import com.cloudshelf.reader.domain.model.Category
import com.cloudshelf.reader.domain.model.SyncState
import com.cloudshelf.reader.domain.repository.LibraryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LibraryRepositoryImpl @Inject constructor(
    private val libraryDao: LibraryDao,
    private val userActivityDao: UserActivityDao,
    private val remote: CatalogueRemoteDataSource
) : LibraryRepository {

    private val syncMutex = Mutex()
    private val _syncState = MutableStateFlow(SyncState())
    private var lastEtag: String? = null
    private var lastModifiedHeader: String? = null

    override fun observeSyncState(): Flow<SyncState> = _syncState.asStateFlow()

    override fun observeBooks(): Flow<List<Book>> {
        return combine(
            libraryDao.observeActiveBooks(),
            userActivityDao.observeOfflineBooks(),
            userActivityDao.observeRecentlyRead()
        ) { books, offlineList, progressList ->
            val offlineMap = offlineList.associateBy { it.bookId }
            val progressMap = progressList.associateBy { it.bookId }
            books.map { entity ->
                entity.toDomain(
                    offline = offlineMap[entity.id],
                    progress = progressMap[entity.id]
                )
            }
        }
    }

    override fun observeBook(bookId: String): Flow<Book?> {
        return combine(
            libraryDao.observeBookById(bookId),
            userActivityDao.observeOfflineBook(bookId),
            userActivityDao.observeProgress(bookId)
        ) { book, offline, progress ->
            book?.toDomain(offline = offline, progress = progress)
        }
    }

    override fun observeCategories(): Flow<List<Category>> {
        return libraryDao.observeCategories().map { list ->
            list.map { it.toDomain() }
        }
    }

    override fun searchBooks(query: String): Flow<List<Book>> {
        val q = query.trim()
        if (q.isEmpty()) return observeBooks()
        return combine(
            libraryDao.searchBooks(q),
            userActivityDao.observeOfflineBooks(),
            userActivityDao.observeRecentlyRead()
        ) { books, offlineList, progressList ->
            val offlineMap = offlineList.associateBy { it.bookId }
            val progressMap = progressList.associateBy { it.bookId }
            books.map { entity ->
                entity.toDomain(offline = offlineMap[entity.id], progress = progressMap[entity.id])
            }
        }
    }

    override suspend fun getBook(bookId: String): Book? {
        return observeBook(bookId).first()
    }

    override suspend fun refreshCatalogue(force: Boolean): Result<Unit> = syncMutex.withLock {
        _syncState.value = _syncState.value.copy(isSyncing = true, lastError = null)
        try {
            val etag = if (force) null else lastEtag
            val lm = if (force) null else lastModifiedHeader
            when (val result = remote.fetchCatalogue(etag = etag, lastModified = lm)) {
                is CatalogueFetchResult.NotModified -> {
                    _syncState.value = _syncState.value.copy(
                        isSyncing = false,
                        lastSuccessEpochMs = System.currentTimeMillis()
                    )
                    Result.success(Unit)
                }
                is CatalogueFetchResult.Error -> {
                    _syncState.value = _syncState.value.copy(isSyncing = false, lastError = result.message)
                    Result.failure(IllegalStateException(result.message))
                }
                is CatalogueFetchResult.Success -> {
                    val now = System.currentTimeMillis()
                    val categories = result.result.validCategories.map { it.toEntity() }
                    val books = result.result.validBooks.map { it.toEntity(now) }
                    val crossRefs = result.result.validBooks.flatMap { book ->
                        book.categoryIds.map { catId -> BookCategoryCrossRef(book.id, catId) }
                    }
                    libraryDao.syncCatalogueTransaction(categories, books, crossRefs)
                    lastEtag = result.etag
                    lastModifiedHeader = result.lastModified
                    _syncState.value = SyncState(
                        isSyncing = false,
                        lastSuccessEpochMs = now,
                        lastError = if (result.result.rejectedReasons.isEmpty()) null
                        else "Rejected ${result.result.rejectedReasons.size} entries",
                        catalogueVersion = result.result.catalogueVersion
                    )
                    Result.success(Unit)
                }
            }
        } catch (e: Exception) {
            val msg = e.message ?: "Sync failed"
            _syncState.value = _syncState.value.copy(isSyncing = false, lastError = msg)
            Result.failure(e)
        }
    }
}
