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
import java.io.FileInputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
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

    init {
        cleanupStalePartials()
    }

    /**
     * Opens a seekable ParcelFileDescriptor for immediate online reading without creating
     * a permanent offline book entry. Enforces host allowlist, SHA-256 verification when
     * metadata supplies one, partial-download cleanup on failure/cancellation, and LRU eviction.
     */
    suspend fun openOnlineSeekableDescriptor(
        bookId: String,
        downloadUrl: String,
        expectedSize: Long,
        expectedSha256: String? = null,
        onProgress: (Float) -> Unit
    ): ParcelFileDescriptor = withContext(Dispatchers.IO) {
        require(validator.isAllowedHttpsUrl(downloadUrl)) {
            "Blocked untrusted PDF URL: $downloadUrl"
        }

        val safeName = bookId.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val targetFile = File(cacheRoot, "$safeName.tmp.pdf")
        val partFile = File(cacheRoot, "$safeName.tmp.part")

        if (isCacheValid(targetFile, expectedSize, expectedSha256)) {
            targetFile.setLastModified(System.currentTimeMillis())
            onProgress(1f)
            return@withContext ParcelFileDescriptor.open(targetFile, ParcelFileDescriptor.MODE_READ_ONLY)
        }

        targetFile.delete()
        partFile.delete()
        evictIfNeeded(requiredBytes = expectedSize.coerceAtLeast(1024L))

        val request = Request.Builder()
            .url(downloadUrl)
            .header("Accept-Encoding", "identity")
            .build()

        try {
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("HTTP ${response.code} while fetching document")
                }
                val finalHost = response.request.url.host.lowercase()
                if (finalHost !in CloudShelfConfig.ALLOWED_HOSTS) {
                    throw SecurityException("Redirected to disallowed host: $finalHost")
                }
                val body = response.body ?: throw IOException("Empty response body")
                val totalBytes = if (body.contentLength() > 0) body.contentLength() else expectedSize
                val digest = MessageDigest.getInstance("SHA-256")
                var downloaded = 0L

                RandomAccessFile(partFile, "rw").use { raf ->
                    raf.setLength(0L)
                    body.byteStream().use { input ->
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            coroutineContext.ensureActive()
                            val read = input.read(buffer)
                            if (read == -1) break
                            raf.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            downloaded += read
                            if (totalBytes > 0) {
                                onProgress((downloaded.toFloat() / totalBytes).coerceIn(0f, 1f))
                            }
                        }
                    }
                }

                if (expectedSize > 0 && downloaded != expectedSize) {
                    throw IOException("Size mismatch: expected $expectedSize bytes, received $downloaded")
                }

                if (!expectedSha256.isNullOrBlank()) {
                    val actualHex = digest.digest().joinToString("") { "%02x".format(it) }
                    if (!actualHex.equals(expectedSha256, ignoreCase = true)) {
                        throw SecurityException("Online cache SHA-256 verification failed")
                    }
                }
            }

            if (!partFile.renameTo(targetFile)) {
                partFile.copyTo(targetFile, overwrite = true)
                partFile.delete()
            }
            targetFile.setLastModified(System.currentTimeMillis())
            ParcelFileDescriptor.open(targetFile, ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (t: Throwable) {
            partFile.delete()
            throw t
        }
    }

    fun cleanupStalePartials() {
        cacheRoot.listFiles { _, name -> name.endsWith(".part") }?.forEach { it.delete() }
    }

    fun clearAllOnlineCache() {
        cacheRoot.listFiles()?.forEach { it.delete() }
    }

    fun getCurrentCacheUsageBytes(): Long {
        return cacheRoot.listFiles()?.sumOf { it.length() } ?: 0L
    }

    private fun isCacheValid(file: File, expectedSize: Long, expectedSha256: String?): Boolean {
        if (!file.exists()) return false
        if (expectedSize > 0 && file.length() != expectedSize) return false
        if (expectedSha256.isNullOrBlank()) return file.length() > 0
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buf = ByteArray(32 * 1024)
            while (true) {
                val read = input.read(buf)
                if (read == -1) break
                digest.update(buf, 0, read)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        return actual.equals(expectedSha256, ignoreCase = true)
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
