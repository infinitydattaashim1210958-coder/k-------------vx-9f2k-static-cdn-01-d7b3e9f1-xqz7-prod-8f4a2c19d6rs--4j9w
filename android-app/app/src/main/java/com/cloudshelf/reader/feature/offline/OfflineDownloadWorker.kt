package com.cloudshelf.reader.feature.offline

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.cloudshelf.reader.core.security.OfflineVaultManager
import com.cloudshelf.reader.domain.model.OfflineBookStatus
import com.cloudshelf.reader.domain.model.OfflineStatus
import com.cloudshelf.reader.domain.repository.UserActivityRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class OfflineDownloadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val vaultManager: OfflineVaultManager,
    private val userActivityRepository: UserActivityRepository
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val bookId = inputData.getString(KEY_BOOK_ID) ?: return Result.failure()
        val url = inputData.getString(KEY_URL) ?: return Result.failure()
        val sha256 = inputData.getString(KEY_SHA256) ?: return Result.failure()
        val size = inputData.getLong(KEY_SIZE, 0L)

        userActivityRepository.upsertOfflineStatus(
            OfflineBookStatus(
                bookId = bookId,
                status = OfflineStatus.DOWNLOADING,
                encryptedFileName = "",
                expectedSha256 = sha256,
                verifiedBytes = 0,
                lastVerifiedEpochMs = System.currentTimeMillis()
            )
        )

        return try {
            val file = vaultManager.downloadVerifyAndEncrypt(
                bookId = bookId,
                downloadUrl = url,
                expectedSha256 = sha256,
                expectedSize = size
            ) { progress ->
                setProgressAsync(workDataOf(KEY_PROGRESS to progress))
            }
            userActivityRepository.upsertOfflineStatus(
                OfflineBookStatus(
                    bookId = bookId,
                    status = OfflineStatus.VERIFIED,
                    encryptedFileName = file.name,
                    expectedSha256 = sha256,
                    verifiedBytes = file.length(),
                    lastVerifiedEpochMs = System.currentTimeMillis()
                )
            )
            Result.success()
        } catch (e: Exception) {
            userActivityRepository.upsertOfflineStatus(
                OfflineBookStatus(
                    bookId = bookId,
                    status = OfflineStatus.FAILED,
                    encryptedFileName = "",
                    expectedSha256 = sha256,
                    verifiedBytes = 0,
                    lastVerifiedEpochMs = System.currentTimeMillis()
                )
            )
            Result.failure()
        }
    }

    companion object {
        const val KEY_BOOK_ID = "bookId"
        const val KEY_URL = "url"
        const val KEY_SHA256 = "sha256"
        const val KEY_SIZE = "size"
        const val KEY_PROGRESS = "progress"
    }
}
