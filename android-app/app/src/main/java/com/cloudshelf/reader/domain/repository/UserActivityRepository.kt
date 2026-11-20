package com.cloudshelf.reader.domain.repository

import com.cloudshelf.reader.data.local.ReaderPreferenceEntity
import com.cloudshelf.reader.domain.model.AnnotationItem
import com.cloudshelf.reader.domain.model.Bookmark
import com.cloudshelf.reader.domain.model.OfflineBookStatus
import com.cloudshelf.reader.domain.model.ReadingProgress
import kotlinx.coroutines.flow.Flow

interface UserActivityRepository {
    fun observeProgress(bookId: String): Flow<ReadingProgress?>
    suspend fun getProgressOnce(bookId: String): ReadingProgress?
    fun observeRecentlyRead(): Flow<List<ReadingProgress>>
    suspend fun saveProgress(progress: ReadingProgress)

    fun observeBookmarks(bookId: String): Flow<List<Bookmark>>
    suspend fun addBookmark(bookmark: Bookmark)
    suspend fun updateBookmark(id: Long, label: String, note: String?)
    suspend fun deleteBookmark(id: Long)

    fun observeAnnotations(bookId: String): Flow<List<AnnotationItem>>
    fun observePageAnnotations(bookId: String, page: Int): Flow<List<AnnotationItem>>
    suspend fun upsertAnnotation(item: AnnotationItem)
    suspend fun deleteAnnotation(id: String)

    fun observeOfflineBooks(): Flow<List<OfflineBookStatus>>
    fun observeOfflineBook(bookId: String): Flow<OfflineBookStatus?>
    suspend fun upsertOfflineStatus(status: OfflineBookStatus)
    suspend fun removeOfflineRecord(bookId: String)

    fun observeReaderPreference(scopeKey: String): Flow<ReaderPreferenceEntity?>
    suspend fun saveReaderPreference(pref: ReaderPreferenceEntity)
}
