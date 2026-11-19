package com.cloudshelf.reader.data.remote

import com.cloudshelf.reader.core.common.CloudShelfConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI

@Serializable
data class RemoteCatalogueDto(
    val schemaVersion: Int,
    val catalogueVersion: String,
    val lastUpdated: String? = null,
    val categories: List<RemoteCategoryDto> = emptyList(),
    val books: List<RemoteBookDto> = emptyList()
)

@Serializable
data class RemoteCategoryDto(
    val id: String,
    val name: String,
    val description: String? = null,
    val coverUrl: String? = null
)

@Serializable
data class RemoteBookDto(
    val id: String,
    val title: String,
    val authors: List<String> = emptyList(),
    val description: String? = null,
    val categoryIds: List<String> = emptyList(),
    val language: String = "en",
    val coverUrl: String? = null,
    val releaseTag: String,
    val assetName: String,
    val downloadUrl: String,
    val fileSize: Long,
    val sha256: String,
    val pageCount: Int? = null,
    val publishedAt: String? = null,
    val featured: Boolean = false,
    val tags: List<String> = emptyList()
)

data class CatalogueValidationResult(
    val catalogueVersion: String,
    val validCategories: List<RemoteCategoryDto>,
    val validBooks: List<RemoteBookDto>,
    val rejectedReasons: List<String>
)

class CatalogueValidator {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = false
        coerceInputValues = true
    }

    private val sha256Regex = Regex("^[a-fA-F0-9]{64}$")
    private val safeIdRegex = Regex("^[a-zA-Z0-9._-]{1,128}$")

    fun parseAndValidate(rawJson: String): Result<CatalogueValidationResult> {
        val dto = runCatching { json.decodeFromString<RemoteCatalogueDto>(rawJson) }
            .getOrElse { return Result.failure(IllegalArgumentException("Malformed JSON: ${it.message}", it)) }

        if (dto.schemaVersion != CloudShelfConfig.SUPPORTED_CATALOGUE_SCHEMA_VERSION) {
            return Result.failure(
                UnsupportedOperationException("Unsupported schemaVersion=${dto.schemaVersion}")
            )
        }
        if (dto.catalogueVersion.isBlank()) {
            return Result.failure(IllegalArgumentException("Missing catalogueVersion"))
        }

        val rejected = mutableListOf<String>()
        val seenCategoryIds = mutableSetOf<String>()
        val validCategories = mutableListOf<RemoteCategoryDto>()

        for (cat in dto.categories) {
            if (!safeIdRegex.matches(cat.id) || cat.name.isBlank()) {
                rejected += "Category '${cat.id}': invalid ID or blank name"
                continue
            }
            if (!seenCategoryIds.add(cat.id)) {
                rejected += "Category '${cat.id}': duplicate ID"
                continue
            }
            if (cat.coverUrl != null && !isAllowedHttpsUrl(cat.coverUrl)) {
                rejected += "Category '${cat.id}': untrusted coverUrl"
                continue
            }
            validCategories += cat
        }

        val seenBookIds = mutableSetOf<String>()
        val validBooks = mutableListOf<RemoteBookDto>()

        for (book in dto.books) {
            val error = validateBook(book, seenBookIds)
            if (error != null) {
                rejected += "Book '${book.id}': $error"
            } else {
                seenBookIds.add(book.id)
                validBooks += book
            }
        }

        return Result.success(
            CatalogueValidationResult(
                catalogueVersion = dto.catalogueVersion,
                validCategories = validCategories,
                validBooks = validBooks,
                rejectedReasons = rejected
            )
        )
    }

    private fun validateBook(book: RemoteBookDto, seenIds: Set<String>): String? {
        if (!safeIdRegex.matches(book.id)) return "invalid book ID"
        if (book.id in seenIds) return "duplicate book ID"
        if (book.title.isBlank()) return "blank title"
        if (book.assetName.contains("..") || book.assetName.contains("/") || book.assetName.contains("\\")) {
            return "unsafe assetName path traversal"
        }
        if (!isAllowedHttpsUrl(book.downloadUrl)) return "untrusted or non-HTTPS downloadUrl"
        if (book.coverUrl != null && !isAllowedHttpsUrl(book.coverUrl)) return "untrusted coverUrl"
        if (book.fileSize <= 0L) return "invalid fileSize (${book.fileSize})"
        if (!sha256Regex.matches(book.sha256)) return "invalid SHA-256 format"
        return null
    }

    fun isAllowedHttpsUrl(urlString: String): Boolean {
        return try {
            val uri = URI(urlString)
            uri.scheme.equals("https", ignoreCase = true) &&
                uri.host != null &&
                uri.host.lowercase() in CloudShelfConfig.ALLOWED_HOSTS
        } catch (_: Exception) {
            false
        }
    }
}
