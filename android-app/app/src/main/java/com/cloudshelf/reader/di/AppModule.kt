package com.cloudshelf.reader.di

import android.content.Context
import androidx.room.Room
import com.cloudshelf.reader.data.local.CloudShelfDatabase
import com.cloudshelf.reader.data.local.LibraryDao
import com.cloudshelf.reader.data.local.UserActivityDao
import com.cloudshelf.reader.data.remote.CatalogueValidator
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
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
            .readTimeout(45, TimeUnit.SECONDS)
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
        ).build()
    }

    @Provides
    fun provideLibraryDao(db: CloudShelfDatabase): LibraryDao = db.libraryDao()

    @Provides
    fun provideUserActivityDao(db: CloudShelfDatabase): UserActivityDao = db.userActivityDao()
}
