package com.cloudshelf.reader.data.local

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface LibraryDao {
    @Query("SELECT * FROM books WHERE isAvailableInRemoteCatalogue = 1 ORDER BY featured DESC, title ASC")
    fun observeActiveBooks(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE id = :bookId")
    fun observeBookById(bookId: String): Flow<BookEntity?>

    @Query("SELECT * FROM categories ORDER BY name ASC")
    fun observeCategories(): Flow<List<CategoryEntity>>

    @Query("""
        SELECT * FROM books 
        WHERE isAvailableInRemoteCatalogue = 1 
        AND (title LIKE '%' || :query || '%' OR authorsJson LIKE '%' || :query || '%' OR tagsJson LIKE '%' || :query || '%')
        ORDER BY title ASC
    """)
    fun searchBooks(query: String): Flow<List<BookEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBooks(books: List<BookEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCategories(categories: List<CategoryEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCrossRefs(refs: List<BookCategoryCrossRef>)

    @Query("UPDATE books SET isAvailableInRemoteCatalogue = 0 WHERE id NOT IN (:activeIds)")
    suspend fun markRemovedBooksUnavailable(activeIds: List<String>)

    @Transaction
    suspend fun syncCatalogueTransaction(
        categories: List<CategoryEntity>,
        books: List<BookEntity>,
        crossRefs: List<BookCategoryCrossRef>
    ) {
        upsertCategories(categories)
        upsertBooks(books)
        upsertCrossRefs(crossRefs)
        if (books.isNotEmpty()) {
            markRemovedBooksUnavailable(books.map { it.id })
        }
    }
}

@Dao
interface UserActivityDao {
    @Query("SELECT * FROM reading_progress WHERE bookId = :bookId")
    fun observeProgress(bookId: String): Flow<ReadingProgressEntity?>

    @Query("SELECT * FROM reading_progress ORDER BY lastReadEpochMs DESC LIMIT 10")
    fun observeRecentlyRead(): Flow<List<ReadingProgressEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveProgress(progress: ReadingProgressEntity)

    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId ORDER BY pageNumber ASC")
    fun observeBookmarks(bookId: String): Flow<List<BookmarkEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBookmark(bookmark: BookmarkEntity)

    @Query("DELETE FROM bookmarks WHERE id = :bookmarkId")
    suspend fun deleteBookmark(bookmarkId: Long)

    @Query("SELECT * FROM annotations WHERE bookId = :bookId AND pageNumber = :pageNumber")
    fun observePageAnnotations(bookId: String, pageNumber: Int): Flow<List<AnnotationEntity>>

    @Query("SELECT * FROM annotations WHERE bookId = :bookId ORDER BY pageNumber ASC, updatedAtEpochMs DESC")
    fun observeAllBookAnnotations(bookId: String): Flow<List<AnnotationEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAnnotation(annotation: AnnotationEntity)

    @Query("DELETE FROM annotations WHERE id = :annotationId")
    suspend fun deleteAnnotation(annotationId: String)

    @Query("SELECT * FROM offline_books ORDER BY lastVerifiedEpochMs DESC")
    fun observeOfflineBooks(): Flow<List<OfflineBookEntity>>

    @Query("SELECT * FROM offline_books WHERE bookId = :bookId")
    fun observeOfflineBook(bookId: String): Flow<OfflineBookEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertOfflineBook(entity: OfflineBookEntity)

    @Query("DELETE FROM offline_books WHERE bookId = :bookId")
    suspend fun deleteOfflineBookRecord(bookId: String)
}

@Database(
    entities = [
        BookEntity::class,
        CategoryEntity::class,
        BookCategoryCrossRef::class,
        ReadingProgressEntity::class,
        BookmarkEntity::class,
        AnnotationEntity::class,
        OfflineBookEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class CloudShelfDatabase : RoomDatabase() {
    abstract fun libraryDao(): LibraryDao
    abstract fun userActivityDao(): UserActivityDao
}
