package com.cloudshelf.reader.core.pdf

import android.content.Context
import android.os.ParcelFileDescriptor
import com.cloudshelf.reader.core.common.CloudShelfConfig
import com.cloudshelf.reader.data.remote.CatalogueValidator
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

@Singleton
class OnlinePdfCacheManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val validator: CatalogueValidator
) {
    private val cacheRoot = File(context.cacheDir, "online_pdf_cache").apply { mkdirs() }

    /**
     * Opens a seekable ParcelFileDescriptor for immediate online reading without creating
     * a permanent offline book entry. Enforces host allowlist and bounded LRU cache size.
     */
    suspend fun openOnlineSeekableDescriptor(
        bookId: String,
        downloadUrl: String,
        expectedSize: Long,
        onProgress: (Float) -> Unit
    ): ParcelFileDescriptor = withContext(Dispatchers.IO) {
        require(validator.isAllowedHttpsUrl(downloadUrl)) {
            "Blocked untrusted PDF URL: $downloadUrl"
        }

        val safeName = bookId.replace(Regex("[^a-zA-Z0-9._-]"), "_") + ".tmp.pdf"
        val targetFile = File(cacheRoot, safeName)

        if (targetFile.exists() && targetFile.length() == expectedSize && expectedSize > 0) {
            targetFile.setLastModified(System.currentTimeMillis())
            onProgress(1f)
            return@withContext ParcelFileDescriptor.open(targetFile, ParcelFileDescriptor.MODE_READ_ONLY)
        }

        evictIfNeeded(requiredBytes = expectedSize.coerceAtLeast(1024L))

        val request = Request.Builder()
            .url(downloadUrl)
            .header("Accept-Encoding", "identity")
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code} while fetching PDF")
            }
            val finalHost = response.request.url.host.lowercase()
            if (finalHost !in CloudShelfConfig.ALLOWED_HOSTS) {
                throw SecurityException("Redirected to disallowed host: $finalHost")
            }

            val body = response.body ?: throw IOException("Empty PDF response body")
            val totalBytes = if (body.contentLength() > 0) body.contentLength() else expectedSize

            RandomAccessFile(targetFile, "rw").use { raf ->
                raf.setLength(0L)
                body.byteStream().use { input ->
                    val buffer = ByteArray(32 * 1024)
                    var downloaded = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read == -1) break
                        raf.write(buffer, 0, read)
                        downloaded += read
                        if (totalBytes > 0) {
                            onProgress((downloaded.toFloat() / totalBytes).coerceIn(0f, 1f))
                        }
                    }
                }
            }
        }

        targetFile.setLastModified(System.currentTimeMillis())
        ParcelFileDescriptor.open(targetFile, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    fun clearAllOnlineCache() {
        cacheRoot.listFiles()?.forEach { it.delete() }
    }

    fun getCurrentCacheUsageBytes(): Long {
        return cacheRoot.listFiles()?.sumOf { it.length() } ?: 0L
    }

    private fun evictIfNeeded(requiredBytes: Long) {
        val files = cacheRoot.listFiles()?.sortedBy { it.lastModified() }?.toMutableList() ?: return
        var currentSize = files.sumOf { it.length() }
        while (currentSize + requiredBytes > CloudShelfConfig.MAX_ONLINE_CACHE_BYTES && files.isNotEmpty()) {
            val oldest = files.removeAt(0)
            val len = oldest.length()
            if (oldest.delete()) {
                currentSize -= len
            }
        }
    }
}
