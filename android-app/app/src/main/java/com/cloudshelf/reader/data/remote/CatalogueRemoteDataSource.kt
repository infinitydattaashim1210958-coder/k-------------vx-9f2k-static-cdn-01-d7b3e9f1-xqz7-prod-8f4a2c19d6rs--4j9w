package com.cloudshelf.reader.data.remote

import com.cloudshelf.reader.core.common.CloudShelfConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CatalogueRemoteDataSource @Inject constructor(
    private val client: OkHttpClient,
    private val validator: CatalogueValidator
) {
    suspend fun fetchCatalogue(
        catalogueUrl: String = CloudShelfConfig.DEFAULT_CATALOGUE_URL,
        etag: String? = null,
        lastModified: String? = null
    ): CatalogueFetchResult = withContext(Dispatchers.IO) {
        if (!validator.isAllowedHttpsUrl(catalogueUrl)) {
            return@withContext CatalogueFetchResult.Error("Catalogue URL is not on the allowlist")
        }

        val requestBuilder = Request.Builder().url(catalogueUrl)
        if (!etag.isNullOrBlank()) requestBuilder.header("If-None-Match", etag)
        if (!lastModified.isNullOrBlank()) requestBuilder.header("If-Modified-Since", lastModified)

        try {
            client.newCall(requestBuilder.build()).execute().use { response ->
                when (response.code) {
                    304 -> CatalogueFetchResult.NotModified
                    in 200..299 -> {
                        val body = response.body?.string()
                            ?: return@use CatalogueFetchResult.Error("Empty catalogue body")
                        val validated = validator.parseAndValidate(body)
                        validated.fold(
                            onSuccess = {
                                CatalogueFetchResult.Success(
                                    result = it,
                                    etag = response.header("ETag"),
                                    lastModified = response.header("Last-Modified")
                                )
                            },
                            onFailure = { CatalogueFetchResult.Error(it.message ?: "Validation failed") }
                        )
                    }
                    429 -> CatalogueFetchResult.Error("GitHub rate limited (429). Try later.")
                    404 -> CatalogueFetchResult.Error("Catalogue not found (404). Upload catalogue.json to the repository.")
                    else -> CatalogueFetchResult.Error("HTTP ${response.code}: ${response.message}")
                }
            }
        } catch (e: Exception) {
            CatalogueFetchResult.Error(e.message ?: "Network error")
        }
    }
}

sealed class CatalogueFetchResult {
    data class Success(
        val result: CatalogueValidationResult,
        val etag: String?,
        val lastModified: String?
    ) : CatalogueFetchResult()

    data object NotModified : CatalogueFetchResult()
    data class Error(val message: String) : CatalogueFetchResult()
}
