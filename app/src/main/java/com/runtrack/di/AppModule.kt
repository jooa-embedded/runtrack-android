package com.runtrack.di

import android.content.Context
import androidx.room.Room
import com.runtrack.data.db.FatigueDao
import com.runtrack.data.datasource.BleRunDataSource
import com.runtrack.data.datasource.MockRunDataSource
import com.runtrack.data.local.RunDao
import com.runtrack.data.local.RunDatabase
import com.runtrack.data.local.UserPreferencesRepositoryImpl
import com.runtrack.data.repository.RunRepositoryImpl
import com.runtrack.domain.RunDataSource
import com.runtrack.domain.RunRepository
import com.runtrack.domain.UserPreferencesRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Single Hilt module for the app.
 *
 * ★ Phase 2 switch ★ — to move from mock data to a real BLE hub, change the
 * one @Binds line below from MockRunDataSource to a BLE-backed implementation.
 * Nothing else in the app needs to change.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {

    @Binds
    @Singleton
    abstract fun bindRunDataSource(impl: BleRunDataSource): RunDataSource //실제 기기랑 연결할 땐 Mock -> Ble로 바꾸기

    @Binds
    @Singleton
    abstract fun bindRunRepository(impl: RunRepositoryImpl): RunRepository

    @Binds
    @Singleton
    abstract fun bindUserPreferencesRepository(
        impl: UserPreferencesRepositoryImpl
    ): UserPreferencesRepository

    companion object {

        @Provides
        @Singleton
        fun provideDatabase(@ApplicationContext context: Context): RunDatabase =
            Room.databaseBuilder(context, RunDatabase::class.java, RunDatabase.NAME)
                .addMigrations(RunDatabase.MIGRATION_1_2, RunDatabase.MIGRATION_2_3)  // ← 추가
                .build()

        @Provides
        fun provideRunDao(db: RunDatabase): RunDao = db.runDao()

        @Provides
        fun provideFatigueDao(db: RunDatabase): com.runtrack.data.db.FatigueDao =
            db.fatigueDao()
    }
}
