package com.cloudshelf.reader.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey val id: String,
    val title: String,
    val authorsJson: String,
    val description: String?,
    val language: String,
    val coverUrl: String?,
    val releaseTag: String,
    val assetName: String,
    val downloadUrl: String,
    val fileSize: Long,
    val sha256: String,
    val pageCount: Int?,
    val publishedAt: String?,
    val featured: Boolean,
    val tagsJson: String,
    val isAvailableInRemoteCatalogue: Boolean = true,
    val updatedAtEpochMs: Long
)

@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String?,
    val coverUrl: String?
)

@Entity(
    tableName = "book_category_cross_ref",
    primaryKeys = ["bookId", "categoryId"],
    indices = [Index("categoryId")]
)
data class BookCategoryCrossRef(
    val bookId: String,
    val categoryId: String
)

/**
 * User-owned tables do NOT use CASCADE delete from `books`, ensuring remote
 * catalogue removals never erase user annotations, bookmarks, or reading progress.
 */
@Entity(tableName = "reading_progress")
data class ReadingProgressEntity(
    @PrimaryKey val bookId: String,
    val lastPage: Int,
    val totalPages: Int,
    val scrollOffsetRatio: Float,
    val totalReadingTimeMs: Long,
    val lastReadEpochMs: Long
)

@Entity(tableName = "bookmarks", indices = [Index("bookId")])
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: String,
    val pageNumber: Int,
    val label: String,
    val note: String?,
    val createdAtEpochMs: Long
)

@Entity(tableName = "annotations", indices = [Index("bookId"), Index("pageNumber")])
data class AnnotationEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    val pageNumber: Int,
    val type: String,
    val payloadJson: String,
    val note: String?,
    val updatedAtEpochMs: Long
)

@Entity(tableName = "offline_books")
data class OfflineBookEntity(
    @PrimaryKey val bookId: String,
    val status: String,
    val encryptedFileName: String,
    val expectedSha256: String,
    val verifiedBytes: Long,
    val lastVerifiedEpochMs: Long
)

@Entity(tableName = "reader_preferences")
data class ReaderPreferenceEntity(
    @PrimaryKey val scopeKey: String, // "global" or specific bookId
    val layoutMode: String,
    val zoomMode: String,
    val readerTheme: String,
    val pageSpacingDp: Int,
    val rotationDegrees: Int,
    val speechRate: Float,
    val updatedAtEpochMs: Long
)
