package com.cloudshelf.reader.core.security

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
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

@Singleton
class OfflineVaultManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val validator: CatalogueValidator,
    masterKeyWrapper: MasterKeyWrapper
) {
    private val cryptoEngine = ChunkedPdfCryptoEngine(masterKeyWrapper)

    // noBackupFilesDir is automatically excluded from cloud backup & device transfer
    private val vaultDir = File(context.noBackupFilesDir, "offline_books").apply { mkdirs() }
    private val tempWorkDir = File(context.cacheDir, "offline_staging").apply { mkdirs() }

    /**
     * Cleans up any leftover staging files from an unexpected process crash.
     */
    fun cleanupOrphanedTempFiles() {
        tempWorkDir.listFiles()?.forEach { it.delete() }
    }

    suspend fun downloadVerifyAndEncrypt(
        bookId: String,
        downloadUrl: String,
        expectedSha256: String,
        expectedSize: Long,
        onProgress: (Float) -> Unit
    ): File = withContext(Dispatchers.IO) {
        require(validator.isAllowedHttpsUrl(downloadUrl)) { "Untrusted download URL" }

        val safeName = sanitizeBookId(bookId)
        val tempPlainFile = File(tempWorkDir, "$safeName.download.tmp")
        val tempEncFile = File(tempWorkDir, "$safeName.enc.tmp")
        val finalVaultFile = File(vaultDir, "$safeName.cspdf")

        try {
            val request = Request.Builder().url(downloadUrl).build()
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val finalHost = response.request.url.host.lowercase()
                if (finalHost !in CloudShelfConfig.ALLOWED_HOSTS) {
                    throw SecurityException("Disallowed redirect host: $finalHost")
                }

                val body = response.body ?: throw IOException("Empty response body")
                val totalBytes = if (body.contentLength() > 0) body.contentLength() else expectedSize
                val digest = MessageDigest.getInstance("SHA-256")

                DigestInputStream(body.byteStream(), digest).use { dis ->
                    FileOutputStream(tempPlainFile).use { fos ->
                        val buf = ByteArray(32 * 1024)
                        var downloaded = 0L
                        while (true) {
                            coroutineContext.ensureActive()
                            val read = dis.read(buf)
                            if (read == -1) break
                            fos.write(buf, 0, read)
                            downloaded += read
                            if (totalBytes > 0) {
                                onProgress(((downloaded.toFloat() / totalBytes) * 0.85f).coerceIn(0f, 0.85f))
                            }
                        }
                    }
                }

                val actualSha256 = digest.digest().joinToString("") { "%02x".format(it) }
                if (!actualSha256.equals(expectedSha256, ignoreCase = true)) {
                    throw SecurityException("SHA-256 mismatch: expected $expectedSha256, got $actualSha256")
                }
            }

            // Encrypt into staging file using CSPDF1 chunked AES-256-GCM
            FileInputStream(tempPlainFile).use { input ->
                FileOutputStream(tempEncFile).use { output ->
                    cryptoEngine.encryptStream(
                        bookId = bookId,
                        plaintextLength = tempPlainFile.length(),
                        input = input,
                        output = output
                    )
                }
            }
            onProgress(0.95f)

            // Verify decryption authentication before committing
            FileInputStream(tempEncFile).use { verifyIn ->
                val verifiedCount = cryptoEngine.decryptStream(
                    bookId = bookId,
                    input = verifyIn,
                    output = DiscardingOutputStream
                )
                if (verifiedCount != tempPlainFile.length()) {
                    throw SecurityException("Post-encryption verification length mismatch")
                }
            }

            if (finalVaultFile.exists()) finalVaultFile.delete()
            if (!tempEncFile.renameTo(finalVaultFile)) {
                tempEncFile.copyTo(finalVaultFile, overwrite = true)
            }
            onProgress(1f)
            finalVaultFile
        } finally {
            tempPlainFile.delete()
            tempEncFile.delete()
        }
    }

    /**
     * Opens an authenticated offline book into an app-private, short-lived unlinked descriptor
     * so PdfRenderer can seek randomly while minimizing plaintext lifetime on disk.
     */
    suspend fun openDecryptedDescriptor(bookId: String): ParcelFileDescriptor = withContext(Dispatchers.IO) {
        val safeName = sanitizeBookId(bookId)
        val encryptedFile = File(vaultDir, "$safeName.cspdf")
        if (!encryptedFile.exists()) {
            throw IOException("Encrypted offline book missing for $bookId")
        }

        val sessionPlainFile = File(tempWorkDir, "$safeName.session.pdf")
        try {
            FileInputStream(encryptedFile).use { input ->
                FileOutputStream(sessionPlainFile).use { output ->
                    cryptoEngine.decryptStream(bookId, input, output)
                }
            }
            val pfd = ParcelFileDescriptor.open(sessionPlainFile, ParcelFileDescriptor.MODE_READ_ONLY)
            // Unlink directory entry immediately on Linux/Android VFS while open FD remains valid
            sessionPlainFile.delete()
            pfd
        } catch (e: Exception) {
            sessionPlainFile.delete()
            throw e
        }
    }

    fun deleteOfflineCopy(bookId: String): Boolean {
        val file = File(vaultDir, "${sanitizeBookId(bookId)}.cspdf")
        return !file.exists() || file.delete()
    }

    fun getTotalEncryptedStorageBytes(): Long {
        return vaultDir.listFiles()?.sumOf { it.length() } ?: 0L
    }

    private fun sanitizeBookId(bookId: String): String =
        bookId.replace(Regex("[^a-zA-Z0-9._-]"), "_")
}

/** API-26-safe replacement for OutputStream.nullOutputStream() (which needs API 33). */
private object DiscardingOutputStream : OutputStream() {
    override fun write(b: Int) = Unit
    override fun write(b: ByteArray, off: Int, len: Int) = Unit
}
