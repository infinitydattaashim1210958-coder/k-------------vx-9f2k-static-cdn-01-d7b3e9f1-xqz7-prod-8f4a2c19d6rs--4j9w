package com.cloudshelf.reader

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.cloudshelf.reader.core.security.OfflineVaultManager
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class CloudShelfApp : Application(), Configuration.Provider {
    @Inject lateinit var offlineVaultManager: OfflineVaultManager
    @Inject lateinit var workerFactory: HiltWorkerFactory

    override fun onCreate() {
        super.onCreate()
        offlineVaultManager.cleanupOrphanedTempFiles()
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
