package com.cloudshelf.reader.data.repository

import com.cloudshelf.reader.data.local.AnnotationEntity
import com.cloudshelf.reader.data.local.BookEntity
import com.cloudshelf.reader.data.local.BookmarkEntity
import com.cloudshelf.reader.data.local.CategoryEntity
import com.cloudshelf.reader.data.local.OfflineBookEntity
import com.cloudshelf.reader.data.local.ReadingProgressEntity
import com.cloudshelf.reader.data.remote.RemoteBookDto
import com.cloudshelf.reader.data.remote.RemoteCategoryDto
import com.cloudshelf.reader.domain.model.AnnotationItem
import com.cloudshelf.reader.domain.model.AnnotationType
import com.cloudshelf.reader.domain.model.Book
import com.cloudshelf.reader.domain.model.Bookmark
import com.cloudshelf.reader.domain.model.Category
import com.cloudshelf.reader.domain.model.OfflineBookStatus
import com.cloudshelf.reader.domain.model.OfflineStatus
import com.cloudshelf.reader.domain.model.ReadingProgress
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val json = Json { ignoreUnknownKeys = true }

fun BookEntity.toDomain(
    offline: OfflineBookEntity? = null,
    progress: ReadingProgressEntity? = null,
    categoryIds: List<String> = emptyList()
): Book {
    val authors = runCatching { json.decodeFromString<List<String>>(authorsJson) }.getOrDefault(emptyList())
    val tags = runCatching { json.decodeFromString<List<String>>(tagsJson) }.getOrDefault(emptyList())
    val offlineStatus = offline?.status
    return Book(
        id = id,
        title = title,
        authors = authors,
        description = description,
        categoryIds = categoryIds,
        language = language,
        coverUrl = coverUrl,
        releaseTag = releaseTag,
        assetName = assetName,
        downloadUrl = downloadUrl,
        fileSize = fileSize,
        sha256 = sha256,
        pageCount = pageCount,
        publishedAt = publishedAt,
        featured = featured,
        tags = tags,
        isAvailableInRemoteCatalogue = isAvailableInRemoteCatalogue,
        isOfflineAvailable = offlineStatus == OfflineStatus.VERIFIED.name,
        offlineStatus = offlineStatus,
        readingProgressPercent = progress?.let {
            if (it.totalPages <= 0) null
            else ((it.lastPage + 1).toFloat() / it.totalPages * 100f).coerceIn(0f, 100f)
        },
        lastPage = progress?.lastPage
    )
}

fun RemoteBookDto.toEntity(now: Long): BookEntity = BookEntity(
    id = id,
    title = title,
    authorsJson = json.encodeToString(authors),
    description = description,
    language = language,
    coverUrl = coverUrl,
    releaseTag = releaseTag,
    assetName = assetName,
    downloadUrl = downloadUrl,
    fileSize = fileSize,
    sha256 = sha256.lowercase(),
    pageCount = pageCount,
    publishedAt = publishedAt,
    featured = featured,
    tagsJson = json.encodeToString(tags),
    isAvailableInRemoteCatalogue = true,
    updatedAtEpochMs = now
)

fun RemoteCategoryDto.toEntity(): CategoryEntity = CategoryEntity(
    id = id,
    name = name,
    description = description,
    coverUrl = coverUrl
)

fun CategoryEntity.toDomain(bookCount: Int = 0): Category = Category(
    id = id,
    name = name,
    description = description,
    coverUrl = coverUrl,
    bookCount = bookCount
)

fun ReadingProgressEntity.toDomain(): ReadingProgress = ReadingProgress(
    bookId = bookId,
    lastPage = lastPage,
    totalPages = totalPages,
    scrollOffsetRatio = scrollOffsetRatio,
    totalReadingTimeMs = totalReadingTimeMs,
    lastReadEpochMs = lastReadEpochMs
)

fun ReadingProgress.toEntity(): ReadingProgressEntity = ReadingProgressEntity(
    bookId = bookId,
    lastPage = lastPage,
    totalPages = totalPages,
    scrollOffsetRatio = scrollOffsetRatio,
    totalReadingTimeMs = totalReadingTimeMs,
    lastReadEpochMs = lastReadEpochMs
)

fun BookmarkEntity.toDomain(): Bookmark = Bookmark(
    id = id,
    bookId = bookId,
    pageNumber = pageNumber,
    label = label,
    note = note,
    createdAtEpochMs = createdAtEpochMs
)

fun Bookmark.toEntity(): BookmarkEntity = BookmarkEntity(
    id = id,
    bookId = bookId,
    pageNumber = pageNumber,
    label = label,
    note = note,
    createdAtEpochMs = createdAtEpochMs
)

fun AnnotationEntity.toDomain(): AnnotationItem = AnnotationItem(
    id = id,
    bookId = bookId,
    pageNumber = pageNumber,
    type = runCatching { AnnotationType.valueOf(type) }.getOrDefault(AnnotationType.COMMENT),
    payloadJson = payloadJson,
    note = note,
    updatedAtEpochMs = updatedAtEpochMs
)

fun AnnotationItem.toEntity(): AnnotationEntity = AnnotationEntity(
    id = id,
    bookId = bookId,
    pageNumber = pageNumber,
    type = type.name,
    payloadJson = payloadJson,
    note = note,
    updatedAtEpochMs = updatedAtEpochMs
)

fun OfflineBookEntity.toDomain(): OfflineBookStatus = OfflineBookStatus(
    bookId = bookId,
    status = runCatching { OfflineStatus.valueOf(status) }.getOrDefault(OfflineStatus.FAILED),
    encryptedFileName = encryptedFileName,
    expectedSha256 = expectedSha256,
    verifiedBytes = verifiedBytes,
    lastVerifiedEpochMs = lastVerifiedEpochMs
)

fun OfflineBookStatus.toEntity(): OfflineBookEntity = OfflineBookEntity(
    bookId = bookId,
    status = status.name,
    encryptedFileName = encryptedFileName,
    expectedSha256 = expectedSha256,
    verifiedBytes = verifiedBytes,
    lastVerifiedEpochMs = lastVerifiedEpochMs
)
