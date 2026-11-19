package com.cloudshelf.reader.di

import android.content.Context
import androidx.room.Room
import com.cloudshelf.reader.core.security.AndroidKeyStoreMasterKeyWrapper
import com.cloudshelf.reader.core.security.ChunkedPdfCryptoEngine
import com.cloudshelf.reader.core.security.MasterKeyWrapper
import com.cloudshelf.reader.core.security.OfflineVaultManager
import com.cloudshelf.reader.data.local.CloudShelfDatabase
import com.cloudshelf.reader.data.local.LibraryDao
import com.cloudshelf.reader.data.local.UserActivityDao
import com.cloudshelf.reader.data.remote.CatalogueRemoteDataSource
import com.cloudshelf.reader.data.remote.CatalogueValidator
import com.cloudshelf.reader.data.repository.LibraryRepositoryImpl
import com.cloudshelf.reader.data.repository.UserActivityRepositoryImpl
import com.cloudshelf.reader.domain.repository.LibraryRepository
import com.cloudshelf.reader.domain.repository.UserActivityRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import androidx.work.WorkManager
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    @Provides
    @Singleton
    fun provideCatalogueValidator(): CatalogueValidator = CatalogueValidator()

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): CloudShelfDatabase {
        return Room.databaseBuilder(
            context,
            CloudShelfDatabase::class.java,
            "cloudshelf_db"
        )
            .fallbackToDestructiveMigrationOnDowngrade()
            .build()
    }

    @Provides
    fun provideLibraryDao(db: CloudShelfDatabase): LibraryDao = db.libraryDao()

    @Provides
    fun provideUserActivityDao(db: CloudShelfDatabase): UserActivityDao = db.userActivityDao()

    @Provides
    @Singleton
    fun provideMasterKeyWrapper(impl: AndroidKeyStoreMasterKeyWrapper): MasterKeyWrapper = impl

    @Provides
    @Singleton
    fun provideCryptoEngine(keyWrapper: MasterKeyWrapper): ChunkedPdfCryptoEngine {
        return ChunkedPdfCryptoEngine(keyWrapper)
    }

    @Provides
    @Singleton
    fun provideWorkManager(@ApplicationContext context: Context): WorkManager =
        WorkManager.getInstance(context)
}


@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds
    @Singleton
    abstract fun bindLibraryRepository(impl: LibraryRepositoryImpl): LibraryRepository

    @Binds
    @Singleton
    abstract fun bindUserActivityRepository(impl: UserActivityRepositoryImpl): UserActivityRepository
}
