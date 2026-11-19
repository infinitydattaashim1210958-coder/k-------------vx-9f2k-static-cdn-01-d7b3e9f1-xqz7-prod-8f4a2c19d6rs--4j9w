package com.cloudshelf.reader.data.repository

import com.cloudshelf.reader.data.local.UserActivityDao
import com.cloudshelf.reader.domain.model.AnnotationItem
import com.cloudshelf.reader.domain.model.Bookmark
import com.cloudshelf.reader.domain.model.OfflineBookStatus
import com.cloudshelf.reader.domain.model.ReadingProgress
import com.cloudshelf.reader.domain.repository.UserActivityRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UserActivityRepositoryImpl @Inject constructor(
    private val dao: UserActivityDao
) : UserActivityRepository {

    override fun observeProgress(bookId: String): Flow<ReadingProgress?> =
        dao.observeProgress(bookId).map { it?.toDomain() }

    override fun observeRecentlyRead(): Flow<List<ReadingProgress>> =
        dao.observeRecentlyRead().map { list -> list.map { it.toDomain() } }

    override suspend fun saveProgress(progress: ReadingProgress) {
        dao.saveProgress(progress.toEntity())
    }

    override fun observeBookmarks(bookId: String): Flow<List<Bookmark>> =
        dao.observeBookmarks(bookId).map { list -> list.map { it.toDomain() } }

    override suspend fun addBookmark(bookmark: Bookmark) {
        dao.insertBookmark(bookmark.toEntity())
    }

    override suspend fun deleteBookmark(id: Long) {
        dao.deleteBookmark(id)
    }

    override fun observeAnnotations(bookId: String): Flow<List<AnnotationItem>> =
        dao.observeAllBookAnnotations(bookId).map { list -> list.map { it.toDomain() } }

    override fun observePageAnnotations(bookId: String, page: Int): Flow<List<AnnotationItem>> =
        dao.observePageAnnotations(bookId, page).map { list -> list.map { it.toDomain() } }

    override suspend fun upsertAnnotation(item: AnnotationItem) {
        dao.upsertAnnotation(item.toEntity())
    }

    override suspend fun deleteAnnotation(id: String) {
        dao.deleteAnnotation(id)
    }

    override fun observeOfflineBooks(): Flow<List<OfflineBookStatus>> =
        dao.observeOfflineBooks().map { list -> list.map { it.toDomain() } }

    override fun observeOfflineBook(bookId: String): Flow<OfflineBookStatus?> =
        dao.observeOfflineBook(bookId).map { it?.toDomain() }

    override suspend fun upsertOfflineStatus(status: OfflineBookStatus) {
        dao.upsertOfflineBook(status.toEntity())
    }

    override suspend fun removeOfflineRecord(bookId: String) {
        dao.deleteOfflineBookRecord(bookId)
    }
}
