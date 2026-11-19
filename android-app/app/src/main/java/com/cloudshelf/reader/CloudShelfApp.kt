package com.cloudshelf.reader

import android.app.Application
import com.cloudshelf.reader.core.security.OfflineVaultManager
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class CloudShelfApp : Application() {
    @Inject lateinit var offlineVaultManager: OfflineVaultManager

    override fun onCreate() {
        super.onCreate()
        offlineVaultManager.cleanupOrphanedTempFiles()
    }
}
