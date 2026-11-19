package com.cloudshelf.reader.domain.model

data class Book(
    val id: String,
    val title: String,
    val authors: List<String>,
    val description: String?,
    val categoryIds: List<String>,
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
    val tags: List<String>,
    val isAvailableInRemoteCatalogue: Boolean = true,
    val isOfflineAvailable: Boolean = false,
    val offlineStatus: String? = null,
    val readingProgressPercent: Float? = null,
    val lastPage: Int? = null
)

data class Category(
    val id: String,
    val name: String,
    val description: String?,
    val coverUrl: String?,
    val bookCount: Int = 0
)

data class ReadingProgress(
    val bookId: String,
    val lastPage: Int,
    val totalPages: Int,
    val scrollOffsetRatio: Float,
    val totalReadingTimeMs: Long,
    val lastReadEpochMs: Long
) {
    val percent: Float
        get() = if (totalPages <= 0) 0f else ((lastPage + 1).toFloat() / totalPages * 100f).coerceIn(0f, 100f)
}

data class Bookmark(
    val id: Long = 0,
    val bookId: String,
    val pageNumber: Int,
    val label: String,
    val note: String?,
    val createdAtEpochMs: Long
)

enum class AnnotationType { HIGHLIGHT, DRAWING, SHAPE, TEXT_BOX, COMMENT }

data class AnnotationItem(
    val id: String,
    val bookId: String,
    val pageNumber: Int,
    val type: AnnotationType,
    val payloadJson: String,
    val note: String?,
    val updatedAtEpochMs: Long
)

data class OfflineBookStatus(
    val bookId: String,
    val status: OfflineStatus,
    val encryptedFileName: String,
    val expectedSha256: String,
    val verifiedBytes: Long,
    val lastVerifiedEpochMs: Long
)

enum class OfflineStatus { QUEUED, DOWNLOADING, VERIFIED, FAILED }

data class SyncState(
    val isSyncing: Boolean = false,
    val lastSuccessEpochMs: Long? = null,
    val lastError: String? = null,
    val catalogueVersion: String? = null
)

sealed class UiState<out T> {
    data object Loading : UiState<Nothing>()
    data class Success<T>(val data: T) : UiState<T>()
    data class Error(val message: String, val retryable: Boolean = true) : UiState<Nothing>()
    data object Empty : UiState<Nothing>()
}
